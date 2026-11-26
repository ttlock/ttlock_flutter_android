package com.ttlock.ttlock_flutter

import android.content.Context
import com.ttlock.bl.sdk.api.LockDfuClient
import com.ttlock.bl.sdk.dfu.DeviceDfuClient
import com.ttlock.bl.sdk.gateway.api.GatewayDfuClient
import io.flutter.plugin.common.BinaryMessenger

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
        UpgradeStreamParams.gateway = param
    }

    override fun setUpgradeGatewayByPackageParam(param: TTUpgradeGatewayByPackageParam) {
        UpgradeStreamParams.gatewayByPackage = param
    }

    override fun setUpgradeWaterMeterParam(param: TTUpgradeWaterMeterParam) {
        UpgradeStreamParams.waterMeter = param
    }

    override fun setUpgradeWaterMeterByPackageParam(param: TTUpgradeWaterMeterByPackageParam) {
        UpgradeStreamParams.waterMeterByPackage = param
    }

    override fun setUpgradeElectricMeterParam(param: TTUpgradeElectricMeterParam) {
        UpgradeStreamParams.electricMeter = param
    }

    override fun setUpgradeElectricMeterByPackageParam(param: TTUpgradeElectricMeterByPackageParam) {
        UpgradeStreamParams.electricMeterByPackage = param
    }

    override fun setUpgradeKeypadParam(param: TTUpgradeKeypadParam) {
        UpgradeStreamParams.keypad = param
    }

    override fun setUpgradeKeypadByPackageParam(param: TTUpgradeKeypadByPackageParam) {
        UpgradeStreamParams.keypadByPackage = param
    }

    override fun stopUpgradeLock() {
        val sink = UpgradeSession.take(UpgradeSession.KIND_LOCK)
        LockDfuClient.getDefault().abortDfu()
        sink?.endOfStream()
    }

    override fun stopUpgradeGateway() {
        val sink = UpgradeSession.take(UpgradeSession.KIND_GATEWAY)
        GatewayDfuClient.getDefault().abortDfu()
        if (sink != null) {
            // 主动停止后客户端已 abort，下次同 MAC 应重新 startDfu，而不是 retry。
            UpgradeSession.cacheGatewayMac = ""
            sink.endOfStream()
        }
    }

    override fun stopUpgradeWaterMeter() {
        stopDevice(UpgradeSession.KIND_WATER)
    }

    override fun stopUpgradeElectricMeter() {
        stopDevice(UpgradeSession.KIND_ELECTRIC)
    }

    override fun stopUpgradeKeypad() {
        stopDevice(UpgradeSession.KIND_KEYPAD)
    }

    /**
     * 只 abort [DeviceDfuClient]。另一台设备正在升级时不调用，避免误停那一路。
     * 锁 / 网关客户端在这里不会被 abort。
     */
    private fun stopDevice(kind: String) {
        val active = UpgradeSession.activeKind
        if (active != null && active != kind && UpgradeSession.isDeviceKind(active)) {
            return
        }
        val sink = UpgradeSession.take(kind)
        DeviceDfuClient.getInstance().abortDfu()
        sink?.endOfStream()
    }
}
