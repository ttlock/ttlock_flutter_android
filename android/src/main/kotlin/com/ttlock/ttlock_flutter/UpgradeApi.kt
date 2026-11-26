package com.ttlock.ttlock_flutter

import android.content.Context
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
}
