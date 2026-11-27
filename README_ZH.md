# ttlock_flutter_android

[English](./README.md)

[`ttlock_flutter`](https://github.com/ttlock/ttlock_flutter_plus) 联邦插件的 **Android 平台实现**。宿主 App 通常只依赖 **`ttlock_flutter`**，由它在 Android 上选用本包。当你维护 Android 子模块、自定义打包，或排查原生 SDK / AAR 问题时，请以本文为准。

> 当前包版本：**0.0.3**（以 `pubspec.yaml` 为准）。  
> 本分支（**`main`**）对接 **在线 / 云端** 原生 SDK AAR（`ttlock-release`）。

## 在技术栈中的位置

```text
App  →  ttlock_flutter（Dart API）
     →  ttlock_flutter_platform_interface（Pigeon）
     →  ttlock_flutter_android（本包）  →  TTLock Android AAR
```

对外 Dart API 在父插件（`package:ttlock_flutter/ttlock.dart`）。本包负责把 Pigeon Host API 接到 TTLock Android 原生 SDK。

## 固件升级

将 `TTUpgradeHostApi` 与各升级 EventChannel 接到原生 DFU Client。在线版（`main`）支持锁 / 网关（网络 + 固件包）/ 水表 / 电表 / 键盘升级。取消订阅即中止 DFU。约定见父仓库 [README_ZH](https://github.com/ttlock/ttlock_flutter_plus/blob/main/README_ZH.md)「固件升级」。

因 flatDir AAR 不带 Maven 传递依赖，须额外引入 Nordic DFU（`no.nordicsemi.android:dfu`）。

## 原生 SDK（本分支：在线版）

| 项 | 值 |
|----|-----|
| 分支 | `main` |
| AAR | `ttlock-release.aar` |
| 声明位置 | `android/build.gradle` → `implementation(name: 'ttlock-release', ext: 'aar')` |
| 插件内路径 | `android/libs/ttlock-release.aar` |

本分支额外声明（flatDir AAR 不会带上 Maven 传递依赖）：

- `api 'no.nordicsemi.android:dfu:2.5.0'`（Nordic DFU；Release R8 需要）
- `implementation 'com.squareup.okhttp3:okhttp:3.12.10'`
- `implementation 'com.google.code.gson:gson:2.6.2'`

### 放置 AAR

1. 若自行维护本子模块，将 AAR 放在插件目录：

```text
ttlock_flutter_android/android/libs/ttlock-release.aar
```

2. **同时把同名 AAR 复制到宿主 App**（多数 Flutter 工程必须这样做；`flatDir { dirs 'libs' }` 会按宿主工程解析）：

```text
your_app/android/app/libs/ttlock-release.aar
```

若没有 `libs` 目录请先创建。文件名（去掉 `.aar`）必须与 `implementation(name: '...')` 完全一致。

若编译报错 `Could not find :ttlock-release:`，通常是宿主 `android/app/libs/` 未放置或文件名不匹配。

### 不要混用 AAR

同一构建不要同时启用 `ttlock-release` 与 `TTLockOnPremise`，除非你明确清楚双依赖的 API 重叠风险。本地化打包请改用 **`premise`** 分支。

## 与 `premise` 的差异

| | `main`（本分支） | `premise` |
|--|------------------|-----------|
| 场景 | 在线 / 云端 SDK | 本地化（On-Premise） |
| AAR | `ttlock-release.aar` | `TTLockOnPremise.aar` |
| 额外 Maven 依赖 | Nordic DFU、OkHttp、Gson | Nordic DFU、OkHttp、Gson |
| 升级能力 | 全量 | 仅锁固件包 + 网关固件包 |

## 权限（宿主 App）

在 `android/app/src/main/AndroidManifest.xml` 声明（使用 `tools:targetApi` 时根节点需 `xmlns:tools`）：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Android 9–11 BLE 扫描常依赖定位；须声明并在运行时申请 -->
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

在扫描 / 连接前动态申请 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`（以及需要时的定位权限）。人脸能力另加 `CAMERA`。

建议宿主 `minSdk` **≥ 26**（插件 `android/build.gradle` 中的 `minSdkVersion` 可能更低，仅为库兼容）。

## Release / R8

插件已通过 `android/consumer-rules.pro` 向宿主透传 keep 规则（TTLock / 插件 / Gson `TypeToken` / DFU）。请勿随意去掉这些 consumer rules，否则 minify 后可能出现 `BLE_SERVER_NOT_INIT` 等蓝牙初始化失败。

## 通过父插件接入

多数 App 应依赖父包，而不是单独依赖本包：

```yaml
dependencies:
  ttlock_flutter:
    git:
      url: https://github.com/ttlock/ttlock_flutter_plus.git
      path: ttlock_flutter
      ref: main   # 按团队发布分支 / tag 调整
```

```dart
import 'package:ttlock_flutter/ttlock.dart';
```

能力一览、BleGuard、API 总览见父仓库 [README_ZH](https://github.com/ttlock/ttlock_flutter_plus/blob/main/README_ZH.md)。

## 相关资源

- 父插件仓库：<https://github.com/ttlock/ttlock_flutter_plus>
- 本包 homepage（pubspec）：同上父仓库
- 云端 REST 接口见开放平台 `cloud/` 目录（锁初始化、更新锁数据等需与 BLE 操作配合）
