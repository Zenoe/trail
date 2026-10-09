# 山径 · 轻量 Android 轨迹工具

2026-10-09。暂定名「山径」，面向 Android 的轻量轨迹记录与加载工具。

阶段 0 交付：产品边界、竞品启发、界面与交互规格、可点击原型、GPX 优先的数据契约、权限/耗电/地图方案及后续验收清单。

阶段 2（0.2.0）已实现：GPX 1.0/1.1 流式导入、内容预览与选择、重复文件提示、分段/航点/未知扩展保留，以及独立参考路线。保留阶段 1 的真实 GPS 记录、暂停/继续/结束与中断恢复。默认离线轨迹视图无底图，可在设置中自愿开启 OpenStreetMap 在线浏览；不含离线地图下载，GPX 导出留在阶段 3。

- [阶段 2 功能、验证与限制](docs/stage-2.md)
- [安装包](app/build/outputs/apk/debug/app-debug.apk)（本机构建产物，debug）
- [导入示例](app/src/androidTest/assets/stage2-sample.gpx)（合成路线，不能用于户外导航）
- [构建、运行与阶段 1 验证](docs/stage-1.md)
- [代码组织与设计说明](docs/architecture.md)

- [产品与界面设计](docs/product-design.md)
- [数据模型与 GPX 契约](docs/data-model.md)
- [技术决策与实施验收](docs/technical-plan.md)
- [可点击界面原型](prototype/index.html)：浏览器直接打开，无依赖、无联网；地图和运动数据均为示意，刷新重置。

现有 `../hello` 是独立记事应用；本工程与其分开，不共用应用 ID 或数据库。
