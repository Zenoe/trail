# 山径 · 轻量 Android 轨迹工具

2026-10-09。暂定名「山径」，面向 Android 的轻量轨迹记录与加载工具。

阶段 0 交付：产品边界、竞品启发、界面与交互规格、可点击原型、GPX 优先的数据契约、权限/耗电/地图方案及后续验收清单。

阶段 3（0.3.0）已实现：轨迹搜索、来源与路线筛选、重命名和确认删除；选择路线或整个文档导出 GPX 1.1，通过系统文件选择器保存或分享，还可单独分享导入原文件。沿用阶段 2 的 GPX 流式导入、独立参考路线与阶段 1 的 GPS 记录。默认离线轨迹视图无底图，可在设置中自愿开启 OpenStreetMap 在线浏览；不含离线地图下载。

- [阶段 3 功能、验证与限制](docs/stage-3.md)
- [阶段 2 功能、验证与限制](docs/stage-2.md)
- [安装包](app/build/outputs/apk/debug/app-debug.apk)（本机构建产物，debug）
- [导入示例](app/src/androidTest/assets/stage2-sample.gpx)（合成路线，不能用于户外导航）
- [GPX 1.1 导出样本](docs/stage3-roundtrip.gpx)
- [构建、运行与阶段 1 验证](docs/stage-1.md)
- [代码组织与设计说明](docs/architecture.md)

- [产品与界面设计](docs/product-design.md)
- [数据模型与 GPX 契约](docs/data-model.md)
- [技术决策与实施验收](docs/technical-plan.md)
- [可点击界面原型](prototype/index.html)：浏览器直接打开，无依赖、无联网；地图和运动数据均为示意，刷新重置。

现有 `../hello` 是独立记事应用；本工程与其分开，不共用应用 ID 或数据库。
