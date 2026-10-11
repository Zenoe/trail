# 代码组织（阶段 2）

代码根目录：`app/src/main/java/com/trail/app`。

| 包/文件 | 职责 | 依赖约束 |
| --- | --- | --- |
| domain/RecordingRules | 状态枚举、位置样本、距离与计时计算 | 无 Android 依赖，可运行 JVM 测试 |
| data/Entities、TrailDatabase | Room 表、外键、索引、查询与 schema | 不调用定位或 UI |
| data/RecordingRepository | 记录状态转移、分段、原始点与累计量的事务 | 写操作先读数据库状态，重复命令幂等 |
| recording/LocationSource | 平台 GPS 与可替换的定位接口 | 将 Location 转换为领域样本 |
| recording/RecordingService | 前台通知、定位订阅、串行命令队列、五秒心跳 | 正常运行时唯一记录写入者 |
| tracks/TrailViewModel | 数据库 Flow 转换为页面状态 | 不负责定位生命周期 |
| map/TrailMap | MapLibre 生命周期、GeoJSON 分段渲染、全程视图 | 不改变原始点；MapSource 隔离底图配置 |
| ui/TrailScreen、TrailTheme、Formatting | 页面、状态文案、单位格式 | 所有记录操作交给 Activity/Service |
| MainActivity | 按需权限、系统设置跳转、发出记录命令 | 不直接修改数据库 |
| TrailApplication | 数据库容器、进程级恢复屏障、错误状态 | Activity 重建不重复恢复 |
| exchange/GpxReader、GpxModel | GPX 流式解析、安全限制、原始事件与预览统计 | 仅依赖 JVM；不接触 Android URI 或数据库 |
| exchange/GpxImportRepository | 私有原件、哈希、分批暂存与原子发布 | IO 线程写入，不改录制 Session |
| exchange/GpxWriter、GpxExportRepository | 标准顺序序列化、原始点分段读取、私有临时文件与 SAF 保存 | 先完整生成并重解析，再向外部目标复制 |
| data/TrackManagementRepository、tracks/TrackActionsViewModel | 改名、删除与导出操作状态 | 外键事务清理，活跃录制不可删除或导出 |
| exchange/ImportViewModel、ui/ImportDialog | 文件读取、取消、选择与重复处理 | 旋转屏幕保留导入任务，进程中断不假装继续 |
| map/MapGeometry、MapSource | 跨日期线渲染、视口计算、底图隐私开关与缓存 | 只变换显示，不改 WGS84 原点 |

## 关键设计

1. `Document → Path → Segment → Point` 保留 GPX 结构，暂停/失联后不能跨段累计距离。Room v2 显式迁移增加航点、扩展字段与导入统计，保留 v1 schema 和记录，不使用破坏性重建。
2. `sessions.activeSlot` 为 nullable UNIQUE：未结束记录占用 1，完成后置 null，保证数据库中只有一个活跃会话。
3. Service 的 Channel 串行消费命令；Repository 的事务再次检查状态。迟到定位根据单调时钟和恢复起点拒绝，不允许暂停前的缓存点污染新段。
4. 有效时长用 `elapsedRealtime`，墙钟只用于日期显示。每个点写入一次事务，五秒心跳持久化计时；恢复只承认最后提交的数据，不累计停机时间。
5. 应用进程启动先将遗留会话标记中断，UI 和 Service 等待这一步完成。正常 Service 不主动在后台重启，用户进入应用后选择继续/结束；继续开启新段。强制停止和关机不能持续采集。
6. 保存/暂停命令不要求位置权限：可见 Activity 使用普通 Service 启动完成封存；开始/继续才启动 location 类型前台服务并申请精确定位。
7. 低精度点保留且标记，不用于距离或连线。MapLibre 在低精度点和段界切断几何；不得先过滤坏点再直接连接两侧好点。
8. 数据库写失败时关闭采集、报错并保留已经提交的数据；不清库、不覆盖、不继续假报正常。数据库打不开则阻止新记录。
9. GPX 两次顺序读取：第一次有界预览，确认后第二次写入。每批最多 500 个事件或约 1 Mi 字符扩展，避免长事务阻塞录制。文档先为 STAGING，所有选择对象完成后才原子切换 READY；取消、失败和进程恢复删除未发布数据及其私有副本。
10. 参考路线的路径 ID 和当前录制路径完全分离；SavedStateHandle 保存页面选择，加载/移除参考线不写 Session。MapLibre 使用紫色参考线与白色描边的绿色实录线区分。
11. XML 禁用 DTD/外部实体；命名空间、结构、坐标及可选数值严格检查。未知子树携带命名空间保存；原文件另存私有副本，为阶段 3 的语义导出与原件分享分别提供数据。
12. GPX 1.1 导出逐段分页读原始点，避免使用地图抽稀数据；同类路线按原 ordinal 排列，但 1.1 标准要求路线整体先于轨迹。1.0 URL 转为 link，无法直接映射的旧字段写入带来源版本的应用扩展。
13. 删除路径时同一事务清理其 Path/Segment XML 片段及级联点；若同文件仍有其他路径或顶层航点，文档与原件继续保留。确认后删除最后一个对象才清理私有原件。分享通过 FileProvider 只授权对应私有文件。

这些决策在对应实现附近有注释。一般的赋值和 UI 布局不逐行加注释，避免维护噪音。

## 当前性能与功能边界

记录点按行存储、增量写入；地图查询按序号抽稀，仅保留连续拒收点的首个断线哨兵、恢复后的首个有效点与段端点，原始点单独保留。仍不是视口自适应简化算法，极多质量断点时也不是严格的一万点上限；10 万点性能目标尚未实测。录制累计量在 Session，导入统计在 import_stats；算法版本化与可重算仍待实现。0.3.1 加入冷启动与速度异常过滤；海拔校正、爬升计算、后台重启自动接续及离线地图包尚未实现。GPS 高程明确标注未校正，不伪装成可靠海拔或累计爬升。
