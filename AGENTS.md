# 山径开发约定

- 保持单 app 模块、按职责分包：domain（纯逻辑）、data（Room/Repository）、recording（定位与服务）、tracks（页面状态）、map（渲染适配）、ui（界面）。新功能按职责落位，不把业务逻辑塞进 Activity 或 Composable。
- 疑难点和设计点必须在代码附近用中文注释说明“为什么”：尤其是状态机、单调计时、进程恢复、坐标系、分段、数据库事务、权限/系统版本差异和 GPX 往返规则。普通语法不写重复说明。
- Android 工程仅在此目录；不要改动邻接的 `hello` 记事应用或其用户变更。
- 遵循 `docs/data-model.md`。WGS84 原始坐标、分段和缺失值不可因显示或统计而改写。数据库结构变更使用显式迁移并保留导出的 schema，不使用 destructive migration。
- 记录写入通过 RecordingService → RecordingRepository；UI 观察 Flow。启动恢复是进程级操作，不在 Activity 每次重建时执行。
- 为状态/时间/数据持久化的实质性改动补充有意义的测试。交付说明区分编译、自动测试、模拟器、真机和户外实测；未运行的验证不能宣称通过。
- 阶段边界及限制同步到 README 与对应阶段文档。依赖升级有明确理由再做，保留可复现版本。
