# ttlock_flutter_android

[中文文档](./README_ZH.md)

Android platform implementation of the [`ttlock_flutter`](https://github.com/ttlock/ttlock_flutter_plus) federated plugin. Host apps normally depend on **`ttlock_flutter`**, which selects this package on Android. Use this README when you maintain the Android submodule, package a custom App, or debug native SDK / AAR issues.

> Current package version: **0.0.3** (see `pubspec.yaml`).  
> This branch (**`main`**) targets the **online / cloud** native SDK AAR (`ttlock-release`).

## Role in the stack

```text
App  →  ttlock_flutter (Dart API)
     →  ttlock_flutter_platform_interface (Pigeon)
     →  ttlock_flutter_android (this package)  →  TTLock Android AAR
```

Dart-facing APIs live in the parent plugin (`package:ttlock_flutter/ttlock.dart`). This package wires Pigeon host APIs to the native TTLock Android SDK.

## Firmware upgrade

Wires `TTUpgradeHostApi` + upgrade EventChannels to the native DFU clients. Online (`main`) supports lock / gateway (network + package) / water meter / electric meter / keypad upgrade. Progress streams abort on subscription cancel. See the parent [README](https://github.com/ttlock/ttlock_flutter_plus/blob/main/README.md) «Firmware upgrade».

Nordic DFU (`no.nordicsemi.android:dfu`) is required because flatDir AARs do not bring Maven transitive deps.

## Native SDK (this branch: online)

| Item | Value |
|------|--------|
| Branch | `main` |
| AAR | `ttlock-release.aar` |
| Declared in | `android/build.gradle` → `implementation(name: 'ttlock-release', ext: 'aar')` |
| Plugin libs path | `android/libs/ttlock-release.aar` |

Also declared on this branch (flatDir AARs do not pull Maven transitive deps):

- `api 'no.nordicsemi.android:dfu:2.5.0'` (Nordic DFU; required for release R8)
- `implementation 'com.squareup.okhttp3:okhttp:3.12.10'`
- `implementation 'com.google.code.gson:gson:2.6.2'`

### Place the AAR

1. Keep the AAR under the plugin (if you maintain this submodule):

```text
ttlock_flutter_android/android/libs/ttlock-release.aar
```

2. **Also copy the same AAR into the host app** (required for many Flutter builds; `flatDir { dirs 'libs' }` is resolved against the app project):

```text
your_app/android/app/libs/ttlock-release.aar
```

Create `libs` if needed. The file **base name** (without `.aar`) must match `implementation(name: '...')`.

If Gradle fails with `Could not find :ttlock-release:`, the host `android/app/libs/` copy is missing or misnamed.

### Do not mix AARs

Do not enable both `ttlock-release` and `TTLockOnPremise` in one build unless you intentionally dual-depend and understand API overlap. For on-premise packaging, use the **`premise`** branch instead.

## Diff vs `premise`

| | `main` (this branch) | `premise` |
|--|----------------------|-----------|
| Scenario | Online / cloud SDK | On-premise |
| AAR | `ttlock-release.aar` | `TTLockOnPremise.aar` |
| Extra Maven deps | Nordic DFU, OkHttp, Gson | Nordic DFU, OkHttp, Gson |
| Upgrade APIs | Full set | Lock package + gateway package |

## Permissions (host app)

Declare in `android/app/src/main/AndroidManifest.xml` (add `xmlns:tools` on the root `<manifest>` if you use `tools:targetApi`):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Location is often required for BLE scan on Android 9–11; request at runtime -->
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<uses-permission
    android:name="android.permission.BLUETOOTH"
    android:maxSdkVersion="30" />
<uses-permission
    android:name="android.permission.BLUETOOTH_ADMIN"
    android:maxSdkVersion="30" />

<!-- Android 12+ -->
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation"
    tools:targetApi="s" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

Request `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (and location where required) **before** scan / connect. Add `CAMERA` if you use face enrollment.

Suggested host `minSdk`: **26+** (plugin `minSdkVersion` in `android/build.gradle` may be lower for library compatibility).

## Release / R8

The plugin ships `android/consumer-rules.pro` so host release builds keep TTLock / plugin / Gson `TypeToken` / DFU classes. Do not strip these consumer rules — otherwise you may see BLE init failures such as `BLE_SERVER_NOT_INIT` after minify.

## Integration via parent plugin

Most apps should depend on the parent package, not this package alone:

```yaml
dependencies:
  ttlock_flutter:
    git:
      url: https://github.com/ttlock/ttlock_flutter_plus.git
      path: ttlock_flutter
      ref: main   # use the release branch / tag your team ships
```

```dart
import 'package:ttlock_flutter/ttlock.dart';
```

See the parent [README](https://github.com/ttlock/ttlock_flutter_plus/blob/main/README.md) for features, BleGuard, and API overview.

## Resources

- Parent plugin: <https://github.com/ttlock/ttlock_flutter_plus>
- This package homepage (pubspec): same parent repo
- Cloud REST APIs: open-platform `cloud/` docs (lock init / lockData updates must pair with BLE operations)
