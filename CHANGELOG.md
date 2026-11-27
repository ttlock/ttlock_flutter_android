## 0.0.3

* Android 平台实现：将 Pigeon Host API 接到 TTLock 原生 Android SDK（在线分支使用 `ttlock-release.aar`）。
* 实现 `TTUpgradeHostApi` 与升级 EventChannel；支持锁 / 网关 / 水电表 / 键盘的网络 DFU 与固件包 DFU。
* 升级进度流在 `Stream` 取消（`onCancel`）时中止 DFU；去掉显式 `stop*`，与扫描 cancel 语义对齐。
* 修复同通道二次监听拆掉首路升级会话、锁升级延迟刷新按会话代际取消等问题。
* 二维码 BLE API、R2 透传 `connectAddress`；Release 侧 Gson / Nordic DFU 依赖与 R8 规则对齐。
* `configServer` 前清除回调；键盘扫描等相关稳定性修复。
