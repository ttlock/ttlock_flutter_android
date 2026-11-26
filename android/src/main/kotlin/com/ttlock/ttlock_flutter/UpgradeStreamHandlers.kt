package com.ttlock.ttlock_flutter

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ttlock.bl.sdk.api.LockDfuClient
import com.ttlock.bl.sdk.api.TTLockClient
import com.ttlock.bl.sdk.callback.DfuCallback as LockDfuCallback
import com.ttlock.bl.sdk.callback.GetLockSystemInfoCallback
import com.ttlock.bl.sdk.entity.DeviceInfo
import com.ttlock.bl.sdk.entity.LockError
import com.ttlock.bl.sdk.gateway.api.GatewayDfuClient
import com.ttlock.bl.sdk.gateway.callback.DfuCallback as GatewayDfuCallback
import io.flutter.plugin.common.BinaryMessenger

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
 * 本路是否真正占有会话。第二路 busy 的 onCancel 不能 take 掉第一路。
 */
private class UpgradeListenGate(private val kind: String) {
    private var ownsSession = false
    var generation: Long = 0L
        private set

    fun begin(
        sink: PigeonEventSink<TTUpgradeEvent>,
        param: Any?,
        missingMessage: String,
    ): Boolean {
        ownsSession = false
        if (param == null) {
            sink.error(TTLockUpgradeError.UPGRADE_FAIL.raw.toString(), missingMessage, null)
            sink.endOfStream()
            return false
        }
        val generation = UpgradeSession.tryBegin(kind)
        if (generation == null) {
            sink.error(TTLockUpgradeError.UPGRADE_FAIL.raw.toString(), UPGRADE_BUSY, null)
            sink.endOfStream()
            return false
        }
        this.generation = generation
        UpgradeSession.bind(kind, generation, sink)
        ownsSession = true
        return true
    }

    fun cancel(abort: () -> Unit) {
        if (!ownsSession) return
        ownsSession = false
        val sink = if (kind == UpgradeSession.KIND_LOCK) {
            takeLockSession()
        } else {
            UpgradeSession.take(kind)
        }
        if (sink == null) return
        abort()
    }
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
    private val gate = UpgradeListenGate(UpgradeSession.KIND_LOCK)

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        val param = UpgradeStreamParams.lock
        if (!gate.begin(sink, param, "请先通过 setUpgradeLockParam 设置参数后再订阅 upgradeLock")) {
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

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        rejectNotImplemented(sink, "upgradeWaterMeter")
    }

    override fun onCancel(p0: Any?) {}
}

class UpgradeWaterMeterByPackageImpl : UpgradeWaterMeterByPackageStreamHandler {
    var context: Context

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        rejectNotImplemented(sink, "upgradeWaterMeterByPackage")
    }

    override fun onCancel(p0: Any?) {}
}

class UpgradeElectricMeterImpl : UpgradeElectricMeterStreamHandler {
    var context: Context

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        rejectNotImplemented(sink, "upgradeElectricMeter")
    }

    override fun onCancel(p0: Any?) {}
}

class UpgradeElectricMeterByPackageImpl : UpgradeElectricMeterByPackageStreamHandler {
    var context: Context

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        rejectNotImplemented(sink, "upgradeElectricMeterByPackage")
    }

    override fun onCancel(p0: Any?) {}
}

class UpgradeKeypadImpl : UpgradeKeypadStreamHandler {
    var context: Context

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        rejectNotImplemented(sink, "upgradeKeypad")
    }

    override fun onCancel(p0: Any?) {}
}

class UpgradeKeypadByPackageImpl : UpgradeKeypadByPackageStreamHandler {
    var context: Context

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        rejectNotImplemented(sink, "upgradeKeypadByPackage")
    }

    override fun onCancel(p0: Any?) {}
}
