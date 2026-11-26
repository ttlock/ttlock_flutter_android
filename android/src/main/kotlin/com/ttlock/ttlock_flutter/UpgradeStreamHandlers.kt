package com.ttlock.ttlock_flutter

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ttlock.bl.sdk.api.LockDfuClient
import com.ttlock.bl.sdk.api.TTLockClient
import com.ttlock.bl.sdk.callback.DfuCallback as LockDfuCallback
import com.ttlock.bl.sdk.callback.GetLockSystemInfoCallback
import com.ttlock.bl.sdk.dfu.DeviceDfuClient
import com.ttlock.bl.sdk.dfu.DeviceDfuModel
import com.ttlock.bl.sdk.dfu.DeviceType
import com.ttlock.bl.sdk.dfu.DfuCallback as DeviceDfuCallback
import com.ttlock.bl.sdk.entity.DeviceInfo
import com.ttlock.bl.sdk.entity.LockError
import com.ttlock.bl.sdk.gateway.api.GatewayDfuClient
import com.ttlock.bl.sdk.gateway.callback.DfuCallback as GatewayDfuCallback
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

private const val UPGRADE_BUSY = "upgrade already in progress"
private const val LOCK_SYSTEM_INFO_DELAY_MS = 3000L

private val upgradeMainHandler = Handler(Looper.getMainLooper())

/**
 * 锁 DFU 成功后延迟刷新系统信息。stop / cancel 走 [takeLockSession] 摘掉它，
 * 否则 3s 后的回调会落到下一次锁升级会话上。
 */
private var lockSystemInfoRefresh: Runnable? = null

/**
 * 网关包升级回调对象固定，不能在闭包里捕获代际。stop / cancel 清 0。
 * premise 不实现网络 DFU，因此没有网络代际。
 */
@Volatile
private var gatewayPackageCallbackGeneration = 0L

private fun clearLockSystemInfoRefreshLocked() {
    lockSystemInfoRefresh?.let { upgradeMainHandler.removeCallbacks(it) }
    lockSystemInfoRefresh = null
}

/**
 * 摘掉延迟刷新并结束当前锁会话，两步在同一把锁里，避免摘掉之后又被 post 上。
 */
internal fun takeLockSession(): PigeonEventSink<TTUpgradeEvent>? {
    synchronized(UpgradeSession) {
        clearLockSystemInfoRefreshLocked()
        return UpgradeSession.take(UpgradeSession.KIND_LOCK)
    }
}

private fun takeMatching(kind: String, generation: Long): PigeonEventSink<TTUpgradeEvent>? {
    synchronized(UpgradeSession) {
        if (!UpgradeSession.matches(kind, generation)) return null
        if (kind == UpgradeSession.KIND_LOCK) {
            clearLockSystemInfoRefreshLocked()
        }
        return UpgradeSession.take(kind, generation)
    }
}

internal fun clearGatewayCallbackGeneration() {
    gatewayPackageCallbackGeneration = 0L
}

private fun onUpgradeMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
    } else {
        upgradeMainHandler.post(block)
    }
}

private fun rejectNotImplemented(sink: PigeonEventSink<TTUpgradeEvent>, operation: String) {
    val error = makeNotImplementedError(operation)
    sink.error(error.code, error.message, error.details)
    sink.endOfStream()
}

/**
 * 替换 listen 的同步 onCancel 期间 > 0。这次取消只换 sink，不能拆掉已占有的会话。
 */
private var upgradeRelistenDepth = 0

/**
 * 同一个 EventChannel handler 上的多次 listen 共用这扇门。
 *
 * 忙拒绝和缺参不能清掉已经占有的会话。每张 listen 一张票据：占有的记下代际，否则为 null。
 * 真正的 cancel 先核销 null 票据，所以第二路自己的 onCancel 不会 abort 第一路。
 * 只有核销到当前代际时才 take 并 abort。
 */
private class UpgradeListenGate(private val kind: String) {
    private var ownsSession = false
    var generation: Long = 0L
        private set

    private val tickets = ArrayDeque<Long?>()
    private var cancelSerial = 0L
    private val queuedCancels = ArrayDeque<Long>()

    fun begin(
        sink: PigeonEventSink<TTUpgradeEvent>,
        param: Any?,
        missingMessage: String,
    ): Boolean {
        if (upgradeRelistenDepth > 0 && queuedCancels.isNotEmpty()) {
            queuedCancels.removeLast()
        }
        if (param == null) {
            sink.error(TTLockUpgradeError.UPGRADE_FAIL.raw.toString(), missingMessage, null)
            sink.endOfStream()
            tickets.addLast(null)
            return false
        }
        val started = UpgradeSession.tryBegin(kind)
        if (started == null) {
            sink.error(TTLockUpgradeError.UPGRADE_FAIL.raw.toString(), UPGRADE_BUSY, null)
            sink.endOfStream()
            tickets.addLast(null)
            return false
        }
        generation = started
        UpgradeSession.bind(kind, started, sink)
        ownsSession = true
        tickets.addLast(started)
        return true
    }

    fun cancel(abort: () -> Unit) {
        val id = ++cancelSerial
        queuedCancels.addLast(id)
        upgradeMainHandler.post {
            val index = queuedCancels.indexOf(id)
            if (index < 0) return@post
            queuedCancels.removeAt(index)
            releaseOne(abort)
        }
    }

    private fun releaseOne(abort: () -> Unit) {
        val unowned = tickets.indexOfFirst { it == null }
        if (unowned >= 0) {
            tickets.removeAt(unowned)
            return
        }
        if (tickets.isEmpty() || !ownsSession) return
        val token = tickets.removeFirst() ?: return
        if (token != generation) return
        ownsSession = false
        val taken = takeMatching(kind, token) ?: return
        abort()
    }
}

/**
 * EventChannel 在第二次 cancel 时会因为 sink 已被清掉而不再回调。
 * 这里每次 cancel 都转给 handler，第一路稍后的 onCancel 仍能 abort。
 * 新的 listen 若替换旧 sink，会先同步 onCancel，再 begin；门闩把这次同步取消丢掉。
 */
private class UpgradeChannelBridge(
    private val messenger: BinaryMessenger,
    private val channelName: String,
    private val handler: MessagesPigeonEventChannelWrapper<TTUpgradeEvent>,
) : BinaryMessenger.BinaryMessageHandler {
    private var active: Sink? = null

    override fun onMessage(message: ByteBuffer?, reply: BinaryMessenger.BinaryReply) {
        if (message == null) {
            reply.reply(null)
            return
        }
        val call = MessagesPigeonMethodCodec.decodeMethodCall(message)
        when (call.method) {
            "listen" -> onListen(call.arguments, reply)
            "cancel" -> onCancel(reply)
            else -> reply.reply(null)
        }
    }

    private fun onListen(arguments: Any?, reply: BinaryMessenger.BinaryReply) {
        val sink = Sink()
        val replacing = active != null
        active = sink
        upgradeRelistenDepth++
        try {
            if (replacing) {
                handler.onCancel(null)
            }
            handler.onListen(arguments, sink.pigeon)
            reply.reply(MessagesPigeonMethodCodec.encodeSuccessEnvelope(null))
        } catch (e: RuntimeException) {
            active = null
            reply.reply(MessagesPigeonMethodCodec.encodeErrorEnvelope("error", e.message, null))
        } finally {
            upgradeRelistenDepth--
        }
    }

    private fun onCancel(reply: BinaryMessenger.BinaryReply) {
        active = null
        try {
            handler.onCancel(null)
            reply.reply(MessagesPigeonMethodCodec.encodeSuccessEnvelope(null))
        } catch (e: RuntimeException) {
            reply.reply(MessagesPigeonMethodCodec.encodeErrorEnvelope("error", e.message, null))
        }
    }

    private inner class Sink {
        private val ended = AtomicBoolean(false)
        val pigeon: PigeonEventSink<TTUpgradeEvent> = PigeonEventSink(
            object : EventChannel.EventSink {
                override fun success(event: Any?) {
                    if (!canSend()) return
                    messenger.send(channelName, MessagesPigeonMethodCodec.encodeSuccessEnvelope(event))
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    if (!canSend()) return
                    messenger.send(
                        channelName,
                        MessagesPigeonMethodCodec.encodeErrorEnvelope(errorCode, errorMessage, errorDetails),
                    )
                }

                override fun endOfStream() {
                    if (!ended.compareAndSet(false, true) || active !== this@Sink) return
                    messenger.send(channelName, null)
                }

                private fun canSend(): Boolean = !ended.get() && active === this@Sink
            },
        )
    }
}

private fun attachUpgradeChannel(
    messenger: BinaryMessenger,
    channelName: String,
    handler: MessagesPigeonEventChannelWrapper<TTUpgradeEvent>,
) {
    messenger.setMessageHandler(channelName, UpgradeChannelBridge(messenger, channelName, handler))
}

private fun emitProgress(kind: String, generation: Long, percent: Int) {
    onUpgradeMain {
        if (!UpgradeSession.matches(kind, generation)) return@onUpgradeMain
        val sink = UpgradeSession.peek(kind) ?: return@onUpgradeMain
        sink.success(
            TTUpgradeEvent(
                phase = TTUpgradePhase.PROGRESS,
                status = TTUpgradeStatus.UPGRADING,
                progress = percent.toLong(),
            )
        )
    }
}

private fun failUpgrade(kind: String, generation: Long, code: TTLockUpgradeError, message: String?) {
    onUpgradeMain {
        val sink = takeMatching(kind, generation) ?: return@onUpgradeMain
        sink.error(code.raw.toString(), message ?: "", null)
        sink.endOfStream()
    }
}

private fun succeedUpgrade(kind: String, generation: Long, lockData: String? = null) {
    onUpgradeMain {
        val sink = takeMatching(kind, generation) ?: return@onUpgradeMain
        sink.success(
            TTUpgradeEvent(
                phase = TTUpgradePhase.SUCCESS,
                lockData = lockData,
            )
        )
        sink.endOfStream()
    }
}

private fun runUpgradeStart(kind: String, generation: Long, start: () -> Unit) {
    try {
        start()
    } catch (t: Throwable) {
        failUpgrade(kind, generation, TTLockUpgradeError.UPGRADE_FAIL, t.message)
    }
}

private fun deviceModel(
    type: DeviceType,
    deviceId: Long,
    deviceMac: String,
    lockData: String,
    slotNumber: Long,
    featureValue: String,
): DeviceDfuModel {
    val model = DeviceDfuModel()
    model.type = type
    model.deviceId = deviceId.toInt()
    model.deviceMac = deviceMac
    model.lockData = lockData
    model.slotNumber = slotNumber.toInt()
    model.featureValue = featureValue
    return model
}

private fun deviceCallback(kind: String, generation: Long): DeviceDfuCallback {
    return object : DeviceDfuCallback {
        override fun onProgressChanged(
            deviceAddress: String?,
            percent: Int,
            speed: Float,
            avgSpeed: Float,
            currentPart: Int,
            partsTotal: Int,
        ) {
            emitProgress(kind, generation, percent)
        }

        override fun onStatusChanged(status: Int) {}

        override fun onDfuSuccess(deviceAddress: String?) {
            succeedUpgrade(kind, generation)
        }

        override fun onDfuError(errorCode: Int, errorContent: String?) {
            failUpgrade(kind, generation, TTLockUpgradeError.UPGRADE_FAIL, errorContent)
        }

        override fun onDfuAborted(deviceAddress: String?) {
            failUpgrade(kind, generation, TTLockUpgradeError.UPGRADE_FAIL, "upgrade aborted")
        }
    }
}

private val gatewayPackageCallback = object : GatewayDfuCallback {
    override fun onDfuSuccess(deviceAddress: String?) {
        val generation = gatewayPackageCallbackGeneration
        UpgradeSession.cacheGatewayMac = ""
        succeedUpgrade(UpgradeSession.KIND_GATEWAY, generation)
    }

    override fun onDfuAborted(deviceAddress: String?) {
        val generation = gatewayPackageCallbackGeneration
        failUpgrade(UpgradeSession.KIND_GATEWAY, generation, TTLockUpgradeError.UPGRADE_FAIL, "upgrade aborted")
    }

    override fun onProgressChanged(
        deviceAddress: String?,
        percent: Int,
        speed: Float,
        avgSpeed: Float,
        currentPart: Int,
        partsTotal: Int,
    ) {
        emitProgress(UpgradeSession.KIND_GATEWAY, gatewayPackageCallbackGeneration, percent)
    }

    override fun onError() {
        val generation = gatewayPackageCallbackGeneration
        failUpgrade(UpgradeSession.KIND_GATEWAY, generation, TTLockUpgradeError.UPGRADE_FAIL, "")
    }
}

/**
 * premise [TTLockClient.getLockSystemInfo] 只有 `(lockData, callback)`，没有 lockMac 重载。
 */
private fun postLockSystemInfoRefresh(lockData: String, generation: Long) {
    val runnable = object : Runnable {
        override fun run() {
            synchronized(UpgradeSession) {
                if (lockSystemInfoRefresh === this) {
                    lockSystemInfoRefresh = null
                }
            }
            if (!UpgradeSession.matches(UpgradeSession.KIND_LOCK, generation)) return
            TTLockClient.getDefault().getLockSystemInfo(
                lockData,
                object : GetLockSystemInfoCallback {
                    override fun onGetLockSystemInfoSuccess(deviceInfo: DeviceInfo) {
                        succeedUpgrade(UpgradeSession.KIND_LOCK, generation, deviceInfo.lockData)
                    }

                    override fun onFail(lockError: LockError) {
                        failUpgrade(
                            UpgradeSession.KIND_LOCK,
                            generation,
                            TTLockUpgradeError.GET_SPECIAL_VALUE_ERROR,
                            lockError.errorMsg,
                        )
                    }
                },
            )
        }
    }
    synchronized(UpgradeSession) {
        if (!UpgradeSession.matches(UpgradeSession.KIND_LOCK, generation)) return
        lockSystemInfoRefresh?.let { upgradeMainHandler.removeCallbacks(it) }
        lockSystemInfoRefresh = runnable
        upgradeMainHandler.postDelayed(runnable, LOCK_SYSTEM_INFO_DELAY_MS)
    }
}

private fun lockCallback(lockData: String, generation: Long): LockDfuCallback {
    return object : LockDfuCallback {
        override fun onDfuSuccess(deviceAddress: String?) {
            postLockSystemInfoRefresh(lockData, generation)
        }

        override fun onStatusChanged(status: Int) {}

        override fun onDfuAborted(deviceAddress: String?) {
            failUpgrade(UpgradeSession.KIND_LOCK, generation, TTLockUpgradeError.UPGRADE_FAIL, "upgrade aborted")
        }

        override fun onProgressChanged(
            deviceAddress: String?,
            percent: Int,
            speed: Float,
            avgSpeed: Float,
            currentPart: Int,
            partsTotal: Int,
        ) {
            emitProgress(UpgradeSession.KIND_LOCK, generation, percent)
        }

        override fun onError(errorCode: Int, errorContent: String?) {
            failUpgrade(UpgradeSession.KIND_LOCK, generation, TTLockUpgradeError.UPGRADE_FAIL, errorContent)
        }
    }
}

class UpgradeLockImpl : UpgradeLockStreamHandler {
    var context: Context

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
        attachUpgradeChannel(
            messenger,
            "dev.flutter.pigeon.ttlock_flutter.TTEventChannelApi.upgradeLock",
            this,
        )
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        rejectNotImplemented(sink, "upgradeLock")
    }

    override fun onCancel(p0: Any?) {}
}

class UpgradeLockByPackageImpl : UpgradeLockByPackageStreamHandler {
    var context: Context
    private val gate = UpgradeListenGate(UpgradeSession.KIND_LOCK)

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
        attachUpgradeChannel(
            messenger,
            "dev.flutter.pigeon.ttlock_flutter.TTEventChannelApi.upgradeLockByPackage",
            this,
        )
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        val param = UpgradeStreamParams.lockByPackage
        if (!gate.begin(
                sink,
                param,
                "请先通过 setUpgradeLockByPackageParam 设置参数后再订阅 upgradeLockByPackage",
            )
        ) {
            return
        }
        val lockParam = param ?: return
        runUpgradeStart(UpgradeSession.KIND_LOCK, gate.generation) {
            LockDfuClient.getDefault().startDfu(
                context,
                lockParam.lockData,
                lockParam.lockMac,
                lockParam.firmwarePackage,
                lockCallback(lockParam.lockData, gate.generation),
            )
        }
    }

    override fun onCancel(p0: Any?) {
        gate.cancel { LockDfuClient.getDefault().abortDfu() }
    }
}

class UpgradeGatewayImpl : UpgradeGatewayStreamHandler {
    var context: Context

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
        attachUpgradeChannel(
            messenger,
            "dev.flutter.pigeon.ttlock_flutter.TTEventChannelApi.upgradeGateway",
            this,
        )
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        rejectNotImplemented(sink, "upgradeGateway")
    }

    override fun onCancel(p0: Any?) {}
}

class UpgradeGatewayByPackageImpl : UpgradeGatewayByPackageStreamHandler {
    var context: Context
    private val gate = UpgradeListenGate(UpgradeSession.KIND_GATEWAY)

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
        attachUpgradeChannel(
            messenger,
            "dev.flutter.pigeon.ttlock_flutter.TTEventChannelApi.upgradeGatewayByPackage",
            this,
        )
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        val param = UpgradeStreamParams.gatewayByPackage
        if (!gate.begin(
                sink,
                param,
                "请先通过 setUpgradeGatewayByPackageParam 设置参数后再订阅 upgradeGatewayByPackage",
            )
        ) {
            return
        }
        val packageParam = param ?: return
        gatewayPackageCallbackGeneration = gate.generation
        runUpgradeStart(UpgradeSession.KIND_GATEWAY, gate.generation) {
            UpgradeSession.cacheGatewayMac = ""
            GatewayDfuClient.getDefault().startDfu(
                context,
                packageParam.gatewayMac,
                packageParam.firmwarePackage,
                gatewayPackageCallback,
            )
        }
    }

    override fun onCancel(p0: Any?) {
        gate.cancel {
            clearGatewayCallbackGeneration()
            GatewayDfuClient.getDefault().abortDfu()
            UpgradeSession.cacheGatewayMac = ""
        }
    }
}

class UpgradeWaterMeterImpl : UpgradeWaterMeterStreamHandler {
    var context: Context
    private val gate = UpgradeListenGate(UpgradeSession.KIND_WATER)

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
        attachUpgradeChannel(
            messenger,
            "dev.flutter.pigeon.ttlock_flutter.TTEventChannelApi.upgradeWaterMeter",
            this,
        )
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        val param = UpgradeStreamParams.waterMeter
        if (!gate.begin(sink, param, "请先通过 setUpgradeWaterMeterParam 设置参数后再订阅 upgradeWaterMeter")) {
            return
        }
        val meterParam = param ?: return
        runUpgradeStart(UpgradeSession.KIND_WATER, gate.generation) {
            DeviceDfuClient.getInstance().startDfu(
                context,
                meterParam.clientId,
                meterParam.accessToken,
                deviceModel(DeviceType.WATER_METER, meterParam.deviceId, meterParam.deviceMac, "", 0L, ""),
                deviceCallback(UpgradeSession.KIND_WATER, gate.generation),
            )
        }
    }

    override fun onCancel(p0: Any?) {
        gate.cancel { DeviceDfuClient.getInstance().abortDfu() }
    }
}

class UpgradeWaterMeterByPackageImpl : UpgradeWaterMeterByPackageStreamHandler {
    var context: Context
    private val gate = UpgradeListenGate(UpgradeSession.KIND_WATER)

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
        attachUpgradeChannel(
            messenger,
            "dev.flutter.pigeon.ttlock_flutter.TTEventChannelApi.upgradeWaterMeterByPackage",
            this,
        )
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        val param = UpgradeStreamParams.waterMeterByPackage
        if (!gate.begin(
                sink,
                param,
                "请先通过 setUpgradeWaterMeterByPackageParam 设置参数后再订阅 upgradeWaterMeterByPackage",
            )
        ) {
            return
        }
        val packageParam = param ?: return
        runUpgradeStart(UpgradeSession.KIND_WATER, gate.generation) {
            DeviceDfuClient.getInstance().startDfu(
                context,
                deviceModel(DeviceType.WATER_METER, packageParam.deviceId, packageParam.deviceMac, "", 0L, ""),
                packageParam.firmwarePackage,
                deviceCallback(UpgradeSession.KIND_WATER, gate.generation),
            )
        }
    }

    override fun onCancel(p0: Any?) {
        gate.cancel { DeviceDfuClient.getInstance().abortDfu() }
    }
}

class UpgradeElectricMeterImpl : UpgradeElectricMeterStreamHandler {
    var context: Context
    private val gate = UpgradeListenGate(UpgradeSession.KIND_ELECTRIC)

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
        attachUpgradeChannel(
            messenger,
            "dev.flutter.pigeon.ttlock_flutter.TTEventChannelApi.upgradeElectricMeter",
            this,
        )
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        val param = UpgradeStreamParams.electricMeter
        if (!gate.begin(
                sink,
                param,
                "请先通过 setUpgradeElectricMeterParam 设置参数后再订阅 upgradeElectricMeter",
            )
        ) {
            return
        }
        val meterParam = param ?: return
        runUpgradeStart(UpgradeSession.KIND_ELECTRIC, gate.generation) {
            DeviceDfuClient.getInstance().startDfu(
                context,
                meterParam.clientId,
                meterParam.accessToken,
                deviceModel(DeviceType.ELECTRIC_METER, meterParam.deviceId, meterParam.deviceMac, "", 0L, ""),
                deviceCallback(UpgradeSession.KIND_ELECTRIC, gate.generation),
            )
        }
    }

    override fun onCancel(p0: Any?) {
        gate.cancel { DeviceDfuClient.getInstance().abortDfu() }
    }
}

class UpgradeElectricMeterByPackageImpl : UpgradeElectricMeterByPackageStreamHandler {
    var context: Context
    private val gate = UpgradeListenGate(UpgradeSession.KIND_ELECTRIC)

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
        attachUpgradeChannel(
            messenger,
            "dev.flutter.pigeon.ttlock_flutter.TTEventChannelApi.upgradeElectricMeterByPackage",
            this,
        )
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        val param = UpgradeStreamParams.electricMeterByPackage
        if (!gate.begin(
                sink,
                param,
                "请先通过 setUpgradeElectricMeterByPackageParam 设置参数后再订阅 upgradeElectricMeterByPackage",
            )
        ) {
            return
        }
        val packageParam = param ?: return
        runUpgradeStart(UpgradeSession.KIND_ELECTRIC, gate.generation) {
            DeviceDfuClient.getInstance().startDfu(
                context,
                deviceModel(DeviceType.ELECTRIC_METER, packageParam.deviceId, packageParam.deviceMac, "", 0L, ""),
                packageParam.firmwarePackage,
                deviceCallback(UpgradeSession.KIND_ELECTRIC, gate.generation),
            )
        }
    }

    override fun onCancel(p0: Any?) {
        gate.cancel { DeviceDfuClient.getInstance().abortDfu() }
    }
}

class UpgradeKeypadImpl : UpgradeKeypadStreamHandler {
    var context: Context
    private val gate = UpgradeListenGate(UpgradeSession.KIND_KEYPAD)

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
        attachUpgradeChannel(
            messenger,
            "dev.flutter.pigeon.ttlock_flutter.TTEventChannelApi.upgradeKeypad",
            this,
        )
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        val param = UpgradeStreamParams.keypad
        if (!gate.begin(sink, param, "请先通过 setUpgradeKeypadParam 设置参数后再订阅 upgradeKeypad")) {
            return
        }
        val keypadParam = param ?: return
        runUpgradeStart(UpgradeSession.KIND_KEYPAD, gate.generation) {
            DeviceDfuClient.getInstance().startDfu(
                context,
                keypadParam.clientId,
                keypadParam.accessToken,
                deviceModel(
                    DeviceType.KEYPAD,
                    keypadParam.deviceId,
                    keypadParam.deviceMac,
                    keypadParam.lockData ?: "",
                    keypadParam.slotNumber ?: 0L,
                    keypadParam.featureValue ?: "",
                ),
                deviceCallback(UpgradeSession.KIND_KEYPAD, gate.generation),
            )
        }
    }

    override fun onCancel(p0: Any?) {
        gate.cancel { DeviceDfuClient.getInstance().abortDfu() }
    }
}

class UpgradeKeypadByPackageImpl : UpgradeKeypadByPackageStreamHandler {
    var context: Context
    private val gate = UpgradeListenGate(UpgradeSession.KIND_KEYPAD)

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
        attachUpgradeChannel(
            messenger,
            "dev.flutter.pigeon.ttlock_flutter.TTEventChannelApi.upgradeKeypadByPackage",
            this,
        )
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        val param = UpgradeStreamParams.keypadByPackage
        if (!gate.begin(
                sink,
                param,
                "请先通过 setUpgradeKeypadByPackageParam 设置参数后再订阅 upgradeKeypadByPackage",
            )
        ) {
            return
        }
        val packageParam = param ?: return
        runUpgradeStart(UpgradeSession.KIND_KEYPAD, gate.generation) {
            DeviceDfuClient.getInstance().startDfu(
                context,
                deviceModel(
                    DeviceType.KEYPAD,
                    packageParam.deviceId,
                    packageParam.deviceMac,
                    packageParam.lockData ?: "",
                    packageParam.slotNumber ?: 0L,
                    packageParam.featureValue ?: "",
                ),
                packageParam.firmwarePackage,
                deviceCallback(UpgradeSession.KIND_KEYPAD, gate.generation),
            )
        }
    }

    override fun onCancel(p0: Any?) {
        gate.cancel { DeviceDfuClient.getInstance().abortDfu() }
    }
}
