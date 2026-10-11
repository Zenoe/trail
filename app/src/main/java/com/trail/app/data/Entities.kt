package com.trail.app.data

import androidx.room.*
import com.trail.app.domain.ActivityType
import com.trail.app.domain.RecordingState

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String, val name: String, val createdAt: Long,
    val source: String = "RECORDED", val metadataJson: String = "{}",
    @ColumnInfo(defaultValue = "'READY'") val publicationState: String = "READY",
    val originalFilePath: String? = null, val originalSha256: String? = null,
    val gpxVersion: String? = null, val creator: String? = null,
)

@Entity(tableName = "paths", foreignKeys = [ForeignKey(entity = DocumentEntity::class,
    parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["documentId", "ordinal"], unique = true)])
data class PathEntity(
    @PrimaryKey val id: String, val documentId: String, val name: String,
    val activity: ActivityType, val createdAt: Long, val ordinal: Int = 0,
    val kind: String = "TRACK", val standardFieldsJson: String = "{}",
)

@Entity(tableName = "segments", foreignKeys = [ForeignKey(entity = PathEntity::class,
    parentColumns = ["id"], childColumns = ["pathId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["pathId", "ordinal"], unique = true)])
data class SegmentEntity(@PrimaryKey val id: String, val pathId: String, val ordinal: Int, val breakReason: String)

@Entity(tableName = "points", primaryKeys = ["segmentId", "ordinal"],
    foreignKeys = [ForeignKey(entity = SegmentEntity::class, parentColumns = ["id"],
        childColumns = ["segmentId"], onDelete = ForeignKey.CASCADE)])
data class PointEntity(
    val segmentId: String, val ordinal: Int, val latitude: Double, val longitude: Double,
    val timeUtcMs: Long?, val elevationM: Double?, val accuracyM: Float?, val speedMps: Float?,
    val elapsedMs: Long, val accepted: Boolean,
    val timeLexical: String? = null, val name: String? = null, val symbol: String? = null,
    @ColumnInfo(defaultValue = "''") val extraXml: String = "",
)

@Entity(tableName = "sessions", foreignKeys = [ForeignKey(entity = PathEntity::class,
    parentColumns = ["id"], childColumns = ["pathId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["pathId"], unique = true), Index(value = ["activeSlot"], unique = true)])
data class SessionEntity(
    @PrimaryKey val id: String, val pathId: String, val state: RecordingState,
    // SQLite UNIQUE 允许多个 NULL，却只允许一个 1；数据库层保证只有一条未结束会话。
    val activeSlot: Int? = 1,
    val startedAt: Long, val lastCommittedAt: Long, val endedAt: Long? = null,
    val activeDurationMs: Long = 0, val lastElapsedMs: Long? = null,
    val acceptingSinceMs: Long = 0, val lastFixElapsedMs: Long? = null,
    val segmentId: String? = null, val distanceM: Double = 0.0,
    val accuracyM: Float? = null, val elevationM: Double? = null,
    val ascentM: Double? = null, val descentM: Double? = null, val elevationAnchorM: Double? = null,
)

@Entity(tableName = "recording_events", foreignKeys = [ForeignKey(entity = SessionEntity::class,
    parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")])
data class RecordingEventEntity(@PrimaryKey val id: String, val sessionId: String,
    val type: String, val wallTimeMs: Long, val elapsedMs: Long?)

data class TrackSummary(@Embedded val path: PathEntity,
    val distanceM: Double, val activeDurationMs: Long?, val state: RecordingState?,
    val source: String, val elapsedMs: Long?)

@Entity(tableName = "waypoints", foreignKeys = [ForeignKey(entity = DocumentEntity::class,
    parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["documentId", "ordinal"], unique = true)])
data class WaypointEntity(@PrimaryKey val id: String, val documentId: String, val ordinal: Int,
    val latitude: Double, val longitude: Double, val elevationM: Double?, val timeUtcMs: Long?,
    val timeLexical: String?, val name: String?, val symbol: String?, val extraXml: String)

@Entity(tableName = "import_stats", foreignKeys = [ForeignKey(entity = PathEntity::class,
    parentColumns = ["id"], childColumns = ["pathId"], onDelete = ForeignKey.CASCADE)])
data class ImportStatsEntity(@PrimaryKey val pathId: String, val distanceM: Double, val elapsedMs: Long?,
    val pointCount: Int, val segmentCount: Int, val warnings: String,
    val ascentM: Double? = null, val descentM: Double? = null)

/** 元数据/未知扩展以带命名空间的 XML 子树保存；ownerKey 不含用户文件路径。 */
@Entity(tableName = "gpx_elements", foreignKeys = [ForeignKey(entity = DocumentEntity::class,
    parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("documentId")])
data class GpxElementEntity(@PrimaryKey val id: String, val documentId: String, val ownerType: String,
    val ownerKey: String, val ordinal: Int, val xml: String)

data class WaypointDocument(@Embedded val document: DocumentEntity, val waypointCount: Int)

class Converters {
    // 导入路径没有录制会话，LEFT JOIN 的空值表示“不适用”，不能伪造为已完成录制。
    @TypeConverter fun state(value: String?): RecordingState? = value?.let(RecordingState::valueOf)
    @TypeConverter fun state(value: RecordingState?): String? = value?.name
    @TypeConverter fun activity(value: String): ActivityType = ActivityType.valueOf(value)
    @TypeConverter fun activity(value: ActivityType): String = value.name
}
