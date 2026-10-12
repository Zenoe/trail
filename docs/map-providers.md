# 地图选择（0.4.3-beta）

地图右上角的图层按钮在空白、OpenStreetMap 和高德之间切换；地图页面、录制中的全屏地图与轨迹详情共用所选来源。设置页也能切换。空白不请求底图网络；轨迹、标记和当前位置仍显示。OpenStreetMap 使用公共在线瓦片，显示署名，不做离线预取或批量下载。高德地图使用官方 Android 地图 SDK，首次启用前展示隐私说明和高德政策链接，明确同意后才创建地图视图。若安装包未配置 Key，选择高德只显示配置提示，不会空白打开。

## 高德 Key 配置

现有应用 `applicationId` 是 `com.trail.app`，不要把 Kotlin 源码包名与控制台绑定包名混淆。用户最初提供的 Key 绑定 `com.trail.sdk`，与现有应用不匹配；用户选择保留现有包名，避免旧版本无法升级或读到原有轨迹，并确认已在高德控制台把原 Key 改绑 `com.trail.app`。Key 已放进 Git 忽略的本机 `local.properties`。调试 SHA-1 为 `F0:05:3A:F3:8D:F4:B2:7D:4B:71:F5:71:F8:D8:61:AD:EB:AE:AC:40`，与本机调试证书一致。发布版需另绑定实际发布签名 SHA-1，并确认与 APK 签名证书一致。其他开发机可在本机、不提交 Git 的 `local.properties` 加一行 `amap.key=新Key`，或者用 Gradle 属性 `-PAMAP_KEY=新Key` 构建。Key 会进入 APK；生产发布应在高德控制台限制包名和签名。[高德官方 Key 配置说明](https://lbs.amap.com/api/maps-sdk-for-android/guide/create-project/get-key)。

本版固定依赖 `com.amap.api:3dmap:10.0.600`（当前工程仓库可解析的地图专用 SDK），不引入高德定位 SDK。GPS 仍用系统定位。数据层、GPX 导入导出保留 WGS84；仅高德地图相机、线和标记显示时通过[官方 GPS → 高德坐标转换](https://lbs.amap.com/api/android-sdk/guide/computing-equipment/coordinate-transformation)，境外坐标保持原值。不能拿高德显示坐标覆盖原始轨迹。[官方隐私合规说明](https://lbs.amap.com/api/maps-sdk-for-android/guide/create-project/dev-attention)要求在 SDK 初始化前调用同意接口，本版只在用户明确同意后创建高德视图。

## 验证边界

本机已完成编译、24 项 JVM 测试和 Lint（0 错误、13 警告）。2 项定向模拟器测试分别验证旧 OSM 偏好迁移，以及地图选择、首次隐私提示和高德视图创建；高德真实瓦片是否加载、GCJ-02 叠加精度和真机流程仍未验证。本版 Maven 包只包含 ARM 原生库；没有 ARM ABI 或转译能力的模拟器会提示不支持而不创建高德视图。debug 通用 APK 含两种地图引擎及多个 ABI，约 93 MB，尚未达到轻量包体目标。用户要求不安装到手机；模拟器验证也不能代替大陆网络和户外实测。
