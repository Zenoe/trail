# 阶段 2：GPX 导入与参考路线

版本 0.2.0，2026-10-09。原生 Android 工程；浏览器中的 prototype 仍是阶段 0 示意，不代表此安装包。

## 使用

1. 安装 `app/build/outputs/apk/debug/app-debug.apk`，在地图或轨迹页点「导入 GPX」。系统文件选择器无需全盘存储权限；外部应用以 GPX MIME 类型打开文件也可进入导入页。
2. 预览 trk/rte 名称、点数、分段、距离、缺失字段等警告；选择需要的路线以及是否包含全部标记，再保存。
3. 详情点「加载到地图」，紫色线为参考路线，绿色线为当前记录。移除参考线不删除文件，不暂停或修改记录。
4. 同一原件再导入时，可以打开已有内容或另存所选内容，不静默合并。只有航点的文件也可导入和加载。
5. 默认仅显示本地几何；设置中可自愿开启 OSM 在线底图。查看区域会发送到 OSM，网络不可达不影响原始轨迹保存。

示例：`app/src/androidTest/assets/stage2-sample.gpx` 是合成测试数据，不是可用于导航的实地路线。

## 数据与结构

- SAX 流式解析 GPX 1.0/1.1，支持多轨迹、多分段、计划路线和顶层标记。坐标保持 WGS84，缺失时间/海拔保持 null；历时不冒充有效运动时长。
- 保留标准字段和未知扩展的命名空间 XML、文件点序及完整原件。原件保存到应用私有目录，不保留外部 URI 长期读权限，也不修改源文件。
- 分批暂存 + READY 原子发布；取消/失败撤销未完成导入，重启清理未发布数据。原有录制会话独立。
- Room schema 1 → 2 显式迁移；保留历史 schema。代码职责与中文设计注释见 [架构说明](architecture.md)。

## 验证

构建环境沿用 [阶段 1](stage-1.md)：JDK 17、Gradle 8.13、SDK 36。常规验证命令：

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug
```

模拟器测试使用本次独立启动的 `Medium_Phone_API_37.0`，安装主 APK 与测试 APK，再运行 AndroidJUnitRunner；未向手机安装。覆盖原有录制状态/定位链路、v1 真实 schema 迁移、选择导入、重复判断、只含航点、取消回滚、安全 XML、启动清理，以及导入→保存→加载→再次打开流程。参考线检查包括 MapLibre 屏幕要素查询和截图的人工像素复核。

最终结果：构建成功；14 项 JVM 测试通过；15 项 Android 模拟器测试全部通过（25.187 秒）；Lint 0 错误、9 条警告。没有完成本阶段真机或户外实测；模拟器注入定位不是实地 GPS 精度/耗电结果。没有承诺 100 万点导入的耗时或峰值内存指标。

截图：[路线详情](screenshots/stage2-detail.png)、[参考线](screenshots/stage2-reference.png)、[重复文件预览](screenshots/stage2-duplicate.png)。

阶段 0 设想使用紫色虚线；在本次 MapLibre 与模拟器图形驱动组合中，虚线图层只绘制端点，故阶段 2 改用紫色实线并保留白描边绿色实录线。该显示调整不影响 GPX 数据。

## 边界

GPX 导出、分享、搜索/重命名/删除属于阶段 3；离线区域下载、海拔/漂移优化和长时间路测属于阶段 4。当前 debug APK 包含多架构原生库，不代表最终 release 大小。

OSM 公共瓦片只供按需在线浏览，遵守[瓦片使用策略](https://operations.osmfoundation.org/policies/tiles/)：声明应用身份、显示署名、尊重 HTTP 缓存，不批量下载。该开关不是生产底图可达性或服务保障承诺；正式发布仍需确认供应商、网络及授权。默认无底图模式可独立运行。
