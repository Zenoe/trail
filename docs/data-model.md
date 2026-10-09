# 数据模型与 GPX 优先契约

## 原则

内部使用 WGS84、米、秒、UTC；界面再转换单位与时区。经纬度用 Double，不按屏幕显示精度截断。时间与海拔可空。文件不是一条轨迹：一个 GPX 可以包含多个 trk、rte、wpt。原始数据与计算结果分离。

依据 [GPX 1.1 官方结构](https://www.topografix.com/gpx/1/1/) 与 [开发说明](https://www.topografix.com/gpx_for_developers.asp)。导出使用 GPX 1.1，导入兼容 1.0/1.1；不认识的版本明确拒绝。

## 逻辑实体（阶段 1 Room 建表依据）

| 实体 | 核心字段 | 关系与约束 |
| --- | --- | --- |
| Document | id: UUID, name?, source: RECORDED/IMPORTED, gpxVersion?, creator?, metadataJson, originalFilePath?, originalSha256?, createdAt, updatedAt | 一个文件或一次记录的容器；原始文件复制进私有目录，路径非外部临时 URI |
| Path | id: UUID, documentId, ordinal, kind: TRACK/ROUTE, name?, description?, activityType?, standardFieldsJson | Document 1:N Path；唯一(documentId, ordinal)；保留原始顺序和类型 |
| Segment | id: UUID, pathId, ordinal, breakReason?, standardFieldsJson | Path 1:N Segment；TRACK 映射 trkseg；ROUTE 内部只有一段，导出直接为 rtept |
| Point | segmentId, ordinal, lat, lon, elevationM?, timeUtc?, timeLexical?, accuracyM?, speedMps?, standardFieldsJson | 联合主键(segmentId, ordinal)，顺序来自文件而非时间排序；经纬度必填且有限有效 |
| Waypoint | id: UUID, documentId, ordinal, lat, lon, elevationM?, timeUtc?, name?, symbol?, standardFieldsJson | GPX 顶层 wpt；不强制隶属某一条 Path |
| XmlExtension | id, ownerType, ownerKey, ordinal, xmlWithNamespaces | 支持 document/path/segment/point/waypoint/metadata；携带命名空间声明保留未知 extensions |
| RecordingSession | id, pathId, state, startedAt, endedAt?, lastCommittedAt, activeDurationMs, bootId?, lastMonotonicNs? | 状态与采样点同事务更新；同一时刻只允许一条活跃记录 |
| RecordingEvent | sessionId, ordinal, type, wallTimeUtc, monotonicNs?, bootId | 开始/暂停/恢复/失联/中断/结束；用于计算有效时长，不依赖会被校时改变的墙钟 |
| PathStats | pathId, algorithmVersion, distanceM?, elapsedMs?, activeMs?, ascentM?, pointCount, segmentCount, boundsJson?, qualityFlags | 可重算缓存；不能反向改写原始点；bounds 支持跨日期变更线 |

所有外键启用，按 Document 层级事务删除。建立 Path(documentId)、Segment(pathId,ordinal)、Point(segmentId,ordinal) 索引。大量点分页/流式读写；不在单个 JSON 或一条 Room 实体中存整条轨迹。standardFieldsJson 是版本化 DTO，不是任意业务字段垃圾桶：保存 GPX 标准的 comment、source、link、number、type、fix、sat、hdop、vdop、pdop、dgps 等非核心字段及 metadata 的 author/copyright/link/bounds；导出遵循标准子元素顺序。核心字段只存一份，以核心列为准。

## 映射与往返保证

| GPX | 内部 | 导出 |
| --- | --- | --- |
| gpx / metadata | Document + metadataJson | 标准 metadata，creator 为山径；原 creator 留在来源信息 |
| trk | kind=TRACK 的 Path | trk，保留名称、描述、顺序 |
| trkseg / trkpt | Segment / Point | 原分段、原点序；绝不跨段连接 |
| rte / rtept | kind=ROUTE 的 Path / 单段 Point | rte / rtept，不伪装成实测 trk |
| wpt | Waypoint | 顶层 wpt，保留名称和符号 |
| ele / time | 可空高程/UTC 时间 | 缺失则省略，原时区词法值可留存；新记录输出 Z 时间 |
| extensions | XmlExtension | 保留命名空间与有序 XML 子树；不保证前缀、空白或属性顺序逐字节相同 |

提供两种操作：默认「导出 GPX」生成当前语义的 1.1 文件；进口文档另有「分享原文件」，逐字节保留原文件（不包含后续重命名）。标准格式导出不等同于数据库全量备份：暂停事件、应用设置和统计缓存不属于 GPX 核心。未来全量备份采用版本化 manifest + GPX + 附件，独立于交换格式。

选择部分路线导出时默认不带文档顶层航点，提供「包含文件中的全部标记」选项；导出整个文档则包含全部航点。1.0 特有且不能直接映射到 1.1 的字段保存到带版本的应用扩展并在预览中提示；原文件保留原样。

## 导入事务

1. SAF 选择文件或 ACTION_VIEW 接收 URI；读入私有暂存文件并计算 SHA-256。不信任扩展名，检查 XML 根与命名空间；禁用 DTD/外部实体，不访问 schema URL。
2. 流式解析至 staging，预览文档中 trk/rte/wpt 的数量、名称、分段和警告。建议首版保护阈值：100 MiB、100 万点、XML 深度 64、单扩展子树 1 MiB，支持取消；超限整体停止并清理暂存。
3. 非法坐标、破损 XML 默认拒绝整次导入并指出位置；可选时间/海拔格式不合法也拒绝，不静默篡改。时间倒序、空段、重复点为警告，原样保留；只有航点的合法文档允许导入，零对象文档提示无可用内容。
4. 重复原文件哈希提示「打开已有 / 另存一份」；默认不合并。确认后单事务发布选择对象并保存原文件。失败回滚；重启清理未完成暂存。

## 导出与计算

- 导出通过 SAF 创建文档，先在私有目录完成临时 GPX 并验证可重新解析，再复制到目标 URI。外部提供者不保证原子写入，失败必须报告，尽可能删除残留文件并允许重试；成功后才显示完成。
- 距离仅对同一段内通过质量规则的相邻点累加；不移动、不删除原始点。导入轨迹默认按原点计算几何长度，同时标记可疑跳点；原始/清洗统计使用明确版本。
- 记录暂停、信号丢失或恢复中断后开新段。有效时长取持久化事件累计的单调时钟时间；导入数据仅有时间戳不能可靠推出暂停时长，显示总历时并标注，不冒充有效运动时长。
- 海拔缺失不得自动填 0；爬升需要连续有效海拔及平滑算法版本；无充分数据为 null。GPX 高程不能不加区分地混用 GNSS 椭球高与地图/气压海拔，来源不明标记未校正。
- 显示抽稀只作用于地图几何缓存，导出仍取全部原点。国内地图坐标转换只在渲染适配层进行；GPX 一律 WGS84。

## 阶段 2/3 必测矩阵

多 trk、多段、rte-only、wpt-only、混合文件、缺失 time/ele、重复点、倒序时间、负高程、中文/emoji 名称、跨日期变更线、未知嵌套扩展、1.0 转 1.1、错误 XML/DTD/越界坐标、超限取消、重复导入、写出中断。往返比较类型、坐标、点序、段数、缺失值和扩展语义；不以 XML 字符串完全相等作为默认导出标准。

## 阶段 2 实体落地说明

以上表格是逻辑契约，不代表所有未来字段已建列。v2 将文档/路径/分段非核心字段存于 `gpx_elements` 的有序 XML 子树；点与航点的非核心字段存于 `extraXml`，不是重复再填 standardFieldsJson。路径 name 子树保留来源值，后续重命名和导出必须以 Path.name 为准，避免输出重复字段。时间保留 UTC 毫秒及原始词法值；文档保留 GPX 版本、生成工具、原件路径和哈希。未实现的 updatedAt、统计算法版本、海拔校正等不声称已支持。

保护阈值实际为 100 MiB 输入、合计 100 万点、各 1 万条路径/分段/顶层航点、嵌套 64 层、单点或元数据子树最多 1,048,576 个字符（不是 UTF-8 字节数）；命名空间序列化后也检查大小。原件逐字节保存，语义 XML 不保证保留注释、空白、属性顺序或非标准容器属性。1.0 到 1.1 的导出转换和重新导入往返验收属于阶段 3，本阶段仅保留原版本内容。

## 阶段 3 导出与管理落地说明

标准导出生成 GPX 1.1：文档元数据、航点、所有计划路线、所有实测轨迹、各段原始点按规范顺序写出。跨类型的原始交错顺序因 1.1 根元素顺序约束会分组为先 rte 后 trk；每种类型内部保持原 ordinal。选定单条路径时默认不带顶层航点，界面可选择导出整个文档并包含航点。重命名的路径标题来自当前 Path.name，避免沿用旧 XML 片段产生双 name。导入原件分享仍逐字节来自私有副本，不反映改名或部分删除。

1.0 的 author/email/url/urlname 尽量映射到 1.1 的 author/link；无法映射的字段放入 `trail:legacy` 扩展并标记 `sourceVersion="1.0"`。未知扩展保留命名空间语义；不保证原 XML 字节等价。标记文件的重命名目前改变本地显示名，不改导入原件中的 metadata name。导出文件先在私有缓存生成并重解析，外部保存使用 SAF；提供者若不支持原子写入或删除，复制失败仍可能留下残片，应用会报告失败而不显示成功。
