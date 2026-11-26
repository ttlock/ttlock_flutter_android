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

private const val UPGRADE_BUSY = "upgrade already in progress"
private const val INVALID_PARAMETER = "invalidParameter"
private const val LOCK_SYSTEM_INFO_DELAY_MS = 3000L

private val upgradeMainHandler = Handler(Looper.getMainLooper())

private fun onUpgradeMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
    } else {
        upgradeMainHandler.post(block)
    }
}

/**
 * 本路是否真正占有会话。第二路 busy 的 onCancel 不能 take 掉第一路。
 */
private class UpgradeListenGate(private val kind: String) {
    private var ownsSession = false

    fun begin(
        sink: PigeonEventSink<TTUpgradeEvent>,
        param: Any?,
        missingMessage: String,
    ): Boolean {
        ownsSession = false
        if (param == null) {
            sink.error(INVALID_PARAMETER, missingMessage, null)
            sink.endOfStream()
            return false
        }
        if (!UpgradeSession.tryBegin(kind)) {
            sink.error(TTLockUpgradeError.UPGRADE_FAIL.raw.toString(), UPGRADE_BUSY, null)
            sink.endOfStream()
            return false
        }
        UpgradeSession.bind(kind, sink)
        ownsSession = true
        return true
    }

    fun cancel(abort: () -> Unit) {
        if (!ownsSession) return
        ownsSession = false
        if (UpgradeSession.take(kind) == null) return
        abort()
    }
}

private fun emitProgress(kind: String, percent: Int) {
    onUpgradeMain {
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

private fun failUpgrade(kind: String, code: TTLockUpgradeError, message: String?) {
    onUpgradeMain {
        val sink = UpgradeSession.take(kind) ?: return@onUpgradeMain
        sink.error(code.raw.toString(), message ?: "", null)
        sink.endOfStream()
    }
}

private fun succeedUpgrade(kind: String, lockData: String? = null) {
    onUpgradeMain {
        val sink = UpgradeSession.take(kind) ?: return@onUpgradeMain
        sink.success(
            TTUpgradeEvent(
                phase = TTUpgradePhase.SUCCESS,
                lockData = lockData,
            )
        )
        sink.endOfStream()
    }
}

private fun runUpgradeStart(kind: String, start: () -> Unit) {
    try {
        start()
    } catch (t: Throwable) {
        failUpgrade(kind, TTLockUpgradeError.UPGRADE_FAIL, t.message)
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

private fun deviceCallback(kind: String): DeviceDfuCallback {
    return object : DeviceDfuCallback {
        override fun onProgressChanged(
            deviceAddress: String?,
            percent: Int,
            speed: Float,
            avgSpeed: Float,
            currentPart: Int,
            partsTotal: Int,
        ) {
            emitProgress(kind, percent)
        }

        override fun onStatusChanged(status: Int) {}

        override fun onDfuSuccess(deviceAddress: String?) {
            succeedUpgrade(kind)
        }

        override fun onDfuError(errorCode: Int, errorContent: String?) {
            failUpgrade(kind, TTLockUpgradeError.UPGRADE_FAIL, errorContent)
        }

        override fun onDfuAborted(deviceAddress: String?) {
            failUpgrade(kind, TTLockUpgradeError.UPGRADE_FAIL, "upgrade aborted")
        }
    }
}

/**
 * 网关网络 DFU 的回调必须稳定：同 MAC 重试只调 retryEnterDfuMode*，不会再注册回调。
 */
private val gatewayNetCallback = object : GatewayDfuCallback {
    override fun onDfuSuccess(deviceAddress: String?) {
        UpgradeSession.cacheGatewayMac = ""
        succeedUpgrade(UpgradeSession.KIND_GATEWAY)
    }

    override fun onDfuAborted(deviceAddress: String?) {
        failUpgrade(UpgradeSession.KIND_GATEWAY, TTLockUpgradeError.UPGRADE_FAIL, "upgrade aborted")
    }

    override fun onProgressChanged(
        deviceAddress: String?,
        percent: Int,
        speed: Float,
        avgSpeed: Float,
        currentPart: Int,
        partsTotal: Int,
    ) {
        emitProgress(UpgradeSession.KIND_GATEWAY, percent)
    }

    override fun onError() {
        failUpgrade(UpgradeSession.KIND_GATEWAY, TTLockUpgradeError.UPGRADE_FAIL, "")
    }
}

private val gatewayPackageCallback = object : GatewayDfuCallback {
    override fun onDfuSuccess(deviceAddress: String?) {
        UpgradeSession.cacheGatewayMac = ""
        succeedUpgrade(UpgradeSession.KIND_GATEWAY)
    }

    override fun onDfuAborted(deviceAddress: String?) {
        failUpgrade(UpgradeSession.KIND_GATEWAY, TTLockUpgradeError.UPGRADE_FAIL, "upgrade aborted")
    }

    override fun onProgressChanged(
        deviceAddress: String?,
        percent: Int,
        speed: Float,
        avgSpeed: Float,
        currentPart: Int,
        partsTotal: Int,
    ) {
        emitProgress(UpgradeSession.KIND_GATEWAY, percent)
    }

    override fun onError() {
        failUpgrade(UpgradeSession.KIND_GATEWAY, TTLockUpgradeError.UPGRADE_FAIL, "")
    }
}

private fun lockCallback(lockData: String, lockMac: String): LockDfuCallback {
    return object : LockDfuCallback {
        override fun onDfuSuccess(deviceAddress: String?) {
            if (UpgradeSession.peek(UpgradeSession.KIND_LOCK) == null) return
            upgradeMainHandler.postDelayed({
                if (UpgradeSession.peek(UpgradeSession.KIND_LOCK) == null) return@postDelayed
                TTLockClient.getDefault().getLockSystemInfo(
                    lockData,
                    lockMac,
                    object : GetLockSystemInfoCallback {
                        override fun onGetLockSystemInfoSuccess(deviceInfo: DeviceInfo) {
                            succeedUpgrade(UpgradeSession.KIND_LOCK, deviceInfo.lockData)
                        }

                        override fun onFail(lockError: LockError) {
                            failUpgrade(
                                UpgradeSession.KIND_LOCK,
                                TTLockUpgradeError.GET_SPECIAL_VALUE_ERROR,
                                lockError.errorMsg,
                            )
                        }
                    },
                )
            }, LOCK_SYSTEM_INFO_DELAY_MS)
        }

        override fun onStatusChanged(status: Int) {}

        override fun onDfuAborted(deviceAddress: String?) {
            failUpgrade(UpgradeSession.KIND_LOCK, TTLockUpgradeError.UPGRADE_FAIL, "upgrade aborted")
        }

        override fun onProgressChanged(
            deviceAddress: String?,
            percent: Int,
            speed: Float,
            avgSpeed: Float,
            currentPart: Int,
            partsTotal: Int,
        ) {
            emitProgress(UpgradeSession.KIND_LOCK, percent)
        }

        override fun onError(errorCode: Int, errorContent: String?) {
            failUpgrade(UpgradeSession.KIND_LOCK, TTLockUpgradeError.UPGRADE_FAIL, errorContent)
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
        runUpgradeStart(UpgradeSession.KIND_LOCK) {
            LockDfuClient.getDefault().startDfu(
                context,
                lockParam.lockData,
                lockParam.lockMac,
                lockParam.firmwarePackage,
                lockCallback(lockParam.lockData, lockParam.lockMac),
            )
        }
    }

    override fun onCancel(p0: Any?) {
        gate.cancel { LockDfuClient.getDefault().abortDfu() }
    }
}

class UpgradeGatewayImpl : UpgradeGatewayStreamHandler {
    var context: Context
    private val gate = UpgradeListenGate(UpgradeSession.KIND_GATEWAY)

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        register(messenger, this)
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        val param = UpgradeStreamParams.gateway
        if (!gate.begin(sink, param, "请先通过 setUpgradeGatewayParam 设置参数后再订阅 upgradeGateway")) {
            return
        }
        val gatewayParam = param ?: return
        val mac = gatewayParam.gatewayMac
        runUpgradeStart(UpgradeSession.KIND_GATEWAY) {
            // 首次忽略 dfuType。同 MAC 且上次未成功清空 cache 时，按 dfuType 重试。
            if (mac != UpgradeSession.cacheGatewayMac) {
                UpgradeSession.cacheGatewayMac = mac
                try {
                    GatewayDfuClient.getDefault().startDfu(
                        context,
                        gatewayParam.clientId,
                        gatewayParam.accessToken,
                        gatewayParam.gatewayId.toInt(),
                        mac,
                        gatewayNetCallback,
                    )
                } catch (t: Throwable) {
                    UpgradeSession.cacheGatewayMac = ""
                    throw t
                }
            } else {
                when (gatewayParam.dfuType) {
                    TTDfuType.NET -> GatewayDfuClient.getDefault().retryEnterDfuModeByNet()
                    TTDfuType.BLUETOOTH -> GatewayDfuClient.getDefault().retryEnterDfuModeByBle()
                }
            }
        }
    }

    override fun onCancel(p0: Any?) {
        gate.cancel {
            GatewayDfuClient.getDefault().abortDfu()
            UpgradeSession.cacheGatewayMac = ""
        }
    }
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
        runUpgradeStart(UpgradeSession.KIND_GATEWAY) {
            // 包升级会换掉网络 DFU 回调，清掉重试标记，避免之后同 MAC 误走 retry。
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
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        val param = UpgradeStreamParams.waterMeter
        if (!gate.begin(sink, param, "请先通过 setUpgradeWaterMeterParam 设置参数后再订阅 upgradeWaterMeter")) {
            return
        }
        val meterParam = param ?: return
        runUpgradeStart(UpgradeSession.KIND_WATER) {
            DeviceDfuClient.getInstance().startDfu(
                context,
                meterParam.clientId,
                meterParam.accessToken,
                deviceModel(DeviceType.WATER_METER, meterParam.deviceId, meterParam.deviceMac, "", 0L, ""),
                deviceCallback(UpgradeSession.KIND_WATER),
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
        runUpgradeStart(UpgradeSession.KIND_WATER) {
            DeviceDfuClient.getInstance().startDfu(
                context,
                deviceModel(DeviceType.WATER_METER, packageParam.deviceId, packageParam.deviceMac, "", 0L, ""),
                packageParam.firmwarePackage,
                deviceCallback(UpgradeSession.KIND_WATER),
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
        runUpgradeStart(UpgradeSession.KIND_ELECTRIC) {
            DeviceDfuClient.getInstance().startDfu(
                context,
                meterParam.clientId,
                meterParam.accessToken,
                deviceModel(DeviceType.ELECTRIC_METER, meterParam.deviceId, meterParam.deviceMac, "", 0L, ""),
                deviceCallback(UpgradeSession.KIND_ELECTRIC),
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
        runUpgradeStart(UpgradeSession.KIND_ELECTRIC) {
            DeviceDfuClient.getInstance().startDfu(
                context,
                deviceModel(DeviceType.ELECTRIC_METER, packageParam.deviceId, packageParam.deviceMac, "", 0L, ""),
                packageParam.firmwarePackage,
                deviceCallback(UpgradeSession.KIND_ELECTRIC),
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
    }

    override fun onListen(p0: Any?, sink: PigeonEventSink<TTUpgradeEvent>) {
        val param = UpgradeStreamParams.keypad
        if (!gate.begin(sink, param, "请先通过 setUpgradeKeypadParam 设置参数后再订阅 upgradeKeypad")) {
            return
        }
        val keypadParam = param ?: return
        runUpgradeStart(UpgradeSession.KIND_KEYPAD) {
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
                deviceCallback(UpgradeSession.KIND_KEYPAD),
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
        runUpgradeStart(UpgradeSession.KIND_KEYPAD) {
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
                deviceCallback(UpgradeSession.KIND_KEYPAD),
            )
        }
    }

    override fun onCancel(p0: Any?) {
        gate.cancel { DeviceDfuClient.getInstance().abortDfu() }
    }
}
