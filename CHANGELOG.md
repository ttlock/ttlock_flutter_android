## 0.0.3

* Android on-premise platform implementation wiring Pigeon Host APIs to `TTLockOnPremise.aar`.
* Implements `TTUpgradeHostApi` and upgrade EventChannels; lock/gateway package DFU plus water meter / electric meter / keypad DeviceDfu paths available on this branch (network lock/gateway DFU not built).
* Upgrade progress streams abort on subscription cancel (`onCancel`); no explicit `stop*` APIs.
* Fixes second listener on the same channel tearing down the first upgrade session.
* Documents On-Premise AAR placement and Nordic DFU / OkHttp deps required by flatDir AARs.
* Points parent-plugin docs at `ttlock_flutter_plus`.
