# ttlock_flutter_android

[中文文档](./README_ZH.md)

Android platform implementation of the [`ttlock_flutter`](https://github.com/ttlock/ttlock_flutter) federated plugin. Host apps normally depend on **`ttlock_flutter`**, which selects this package on Android. Use this README when you maintain the Android submodule, package a custom App, or debug native SDK / AAR issues.

> Current package version: **0.0.3** (see `pubspec.yaml`).  
> This branch (**`premise`**) targets the **on-premise** native SDK AAR (`TTLockOnPremise`).

## Role in the stack

```text
App  →  ttlock_flutter (Dart API)
     →  ttlock_flutter_platform_interface (Pigeon)
     →  ttlock_flutter_android (this package)  →  TTLock Android AAR
```

Dart-facing APIs live in the parent plugin (`package:ttlock_flutter/ttlock.dart`). This package wires Pigeon host APIs to the native TTLock Android SDK.

## Native SDK (this branch: on-premise)

| Item | Value |
|------|--------|
| Branch | `premise` |
| AAR | `TTLockOnPremise.aar` |
| Declared in | `android/build.gradle` → `implementation(name: 'TTLockOnPremise', ext: 'aar')` |
| Plugin libs path | `android/libs/TTLockOnPremise.aar` |
| Typical source | On-premise delivery package from TTLock |

Also declared:

- `implementation 'com.google.code.gson:gson:2.6.2'`

If a specific accessory / meter API is missing from `TTLockOnPremise`, check the comment in `android/build.gradle` about optionally adding `ttlock-release.aar` — only do so when you understand the dual-AAR risk.

### Place the AAR

1. Keep the AAR under the plugin (if you maintain this submodule):

```text
ttlock_flutter_android/android/libs/TTLockOnPremise.aar
```

2. **Also copy the same AAR into the host app** (required for many Flutter builds; `flatDir { dirs 'libs' }` is resolved against the app project):

```text
your_app/android/app/libs/TTLockOnPremise.aar
```

Create `libs` if needed. The file **base name** (without `.aar`) must match `implementation(name: '...')`.

If Gradle fails with `Could not find :TTLockOnPremise:`, the host `android/app/libs/` copy is missing or misnamed.

### Do not mix AARs

Do not enable both `TTLockOnPremise` and `ttlock-release` in one build unless you intentionally dual-depend and understand API overlap. For online / cloud packaging, use the **`main`** branch instead.

## Diff vs `main`

| | `premise` (this branch) | `main` |
|--|-------------------------|--------|
| Scenario | On-premise | Online / cloud SDK |
| AAR | `TTLockOnPremise.aar` | `ttlock-release.aar` |
| Extra Maven deps | Usually not required by this packaging | Nordic DFU, OkHttp |

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

The plugin ships `android/consumer-rules.pro` so host release builds keep TTLock / plugin / Gson `TypeToken` classes. Do not strip these consumer rules — otherwise you may see BLE init failures such as `BLE_SERVER_NOT_INIT` after minify.

## Integration via parent plugin

Most apps should depend on the parent package, not this package alone:

```yaml
dependencies:
  ttlock_flutter:
    git:
      url: https://github.com/ttlock/ttlock_flutter.git
      path: ttlock_flutter
      ref: mix   # use the release branch / tag your team ships
```

Ensure the parent checkout / submodule pins this package’s **`premise`** branch when you ship on-premise.

```dart
import 'package:ttlock_flutter/ttlock.dart';
```

See the parent [README](https://github.com/ttlock/ttlock_flutter/blob/mix/README.md) for features, BleGuard, and API overview.

## Resources

- Parent plugin: <https://github.com/ttlock/ttlock_flutter>
- This package homepage (pubspec): same parent repo
- On-premise native SDK: obtain `TTLockOnPremise.aar` from your TTLock on-premise delivery package
