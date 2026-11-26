package com.ttlock.ttlock_flutter

import android.content.Context
import com.ttlock.bl.sdk.api.LockDfuClient
import com.ttlock.bl.sdk.gateway.api.GatewayDfuClient
import io.flutter.plugin.common.BinaryMessenger

/**
 * 与 OHOS [makeNotImplementedError] 对齐：code 为 [TTLockError.FAIL] 数字串，message 为 notImplemented。
 */
internal fun makeNotImplementedError(operation: String): FlutterError {
    return FlutterError(
        TTLockError.FAIL.raw.toString(),
        "notImplemented",
        "$operation is not implemented on premise",
    )
}

class UpgradeApi : TTUpgradeHostApi {
    var context: Context

    constructor(context: Context, messenger: BinaryMessenger) {
        this.context = context
        TTUpgradeHostApi.setUp(messenger, this)
    }

    override fun setUpgradeLockParam(param: TTUpgradeLockParam) {
        UpgradeStreamParams.lock = param
    }

    override fun setUpgradeGatewayParam(param: TTUpgradeGatewayParam) {
        throw makeNotImplementedError("setUpgradeGatewayParam")
    }

    override fun setUpgradeGatewayByPackageParam(param: TTUpgradeGatewayByPackageParam) {
        UpgradeStreamParams.gatewayByPackage = param
    }

    override fun setUpgradeWaterMeterParam(param: TTUpgradeWaterMeterParam) {
        throw makeNotImplementedError("setUpgradeWaterMeterParam")
    }

    override fun setUpgradeWaterMeterByPackageParam(param: TTUpgradeWaterMeterByPackageParam) {
        throw makeNotImplementedError("setUpgradeWaterMeterByPackageParam")
    }

    override fun setUpgradeElectricMeterParam(param: TTUpgradeElectricMeterParam) {
        throw makeNotImplementedError("setUpgradeElectricMeterParam")
    }

    override fun setUpgradeElectricMeterByPackageParam(param: TTUpgradeElectricMeterByPackageParam) {
        throw makeNotImplementedError("setUpgradeElectricMeterByPackageParam")
    }

    override fun setUpgradeKeypadParam(param: TTUpgradeKeypadParam) {
        throw makeNotImplementedError("setUpgradeKeypadParam")
    }

    override fun setUpgradeKeypadByPackageParam(param: TTUpgradeKeypadByPackageParam) {
        throw makeNotImplementedError("setUpgradeKeypadByPackageParam")
    }

    override fun stopUpgradeLock() {
        val sink = takeLockSession()
        LockDfuClient.getDefault().abortDfu()
        sink?.endOfStream()
    }

    override fun stopUpgradeGateway() {
        clearGatewayCallbackGeneration()
        val sink = UpgradeSession.take(UpgradeSession.KIND_GATEWAY)
        GatewayDfuClient.getDefault().abortDfu()
        if (sink != null) {
            UpgradeSession.cacheGatewayMac = ""
            sink.endOfStream()
        }
    }

    override fun stopUpgradeWaterMeter() {
        throw makeNotImplementedError("stopUpgradeWaterMeter")
    }

    override fun stopUpgradeElectricMeter() {
        throw makeNotImplementedError("stopUpgradeElectricMeter")
    }

    override fun stopUpgradeKeypad() {
        throw makeNotImplementedError("stopUpgradeKeypad")
    }
}
