package com.ttlock.ttlock_flutter

/**
 * 各升级 EventChannel 的参数槽；由对应 [TTUpgradeHostApi.set*Param] 写入。
 */
object UpgradeStreamParams {
    @Volatile
    var lock: TTUpgradeLockParam? = null

    @Volatile
    var lockByPackage: TTUpgradeLockByPackageParam? = null

    @Volatile
    var gateway: TTUpgradeGatewayParam? = null

    @Volatile
    var gatewayByPackage: TTUpgradeGatewayByPackageParam? = null

    @Volatile
    var waterMeter: TTUpgradeWaterMeterParam? = null

    @Volatile
    var waterMeterByPackage: TTUpgradeWaterMeterByPackageParam? = null

    @Volatile
    var electricMeter: TTUpgradeElectricMeterParam? = null

    @Volatile
    var electricMeterByPackage: TTUpgradeElectricMeterByPackageParam? = null

    @Volatile
    var keypad: TTUpgradeKeypadParam? = null

    @Volatile
    var keypadByPackage: TTUpgradeKeypadByPackageParam? = null
}
