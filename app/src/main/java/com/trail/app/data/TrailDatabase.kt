package com.trail.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TrailDao {
    @Insert suspend fun insert(document: DocumentEntity)
    @Insert suspend fun insert(path: PathEntity)
    @Insert suspend fun insert(segment: SegmentEntity)
    @Insert suspend fun insert(point: PointEntity)
    @Insert suspend fun insert(session: SessionEntity)
    @Insert suspend fun insert(event: RecordingEventEntity)
    @Update suspend fun update(session: SessionEntity)
    @Query("SELECT * FROM sessions WHERE activeSlot = 1 LIMIT 1") suspend fun active(): SessionEntity?
    @Query("SELECT * FROM sessions WHERE activeSlot = 1 LIMIT 1") fun observeActive(): Flow<SessionEntity?>
    @Query("SELECT paths.*, COALESCE(sessions.distanceM, import_stats.distanceM, 0.0) AS distanceM, sessions.activeDurationMs, sessions.state, documents.source, import_stats.elapsedMs FROM paths JOIN documents ON paths.documentId = documents.id LEFT JOIN sessions ON paths.id = sessions.pathId LEFT JOIN import_stats ON paths.id = import_stats.pathId WHERE documents.publicationState = 'READY' ORDER BY paths.createdAt DESC, paths.ordinal")
    fun observeTracks(): Flow<List<TrackSummary>>
    @Query("SELECT * FROM sessions WHERE pathId = :pathId LIMIT 1") fun observeSession(pathId: String): Flow<SessionEntity?>
    @Query("SELECT * FROM paths WHERE id = :id") fun observePath(id: String): Flow<PathEntity?>
    @Query("SELECT points.* FROM points JOIN segments ON points.segmentId = segments.id WHERE segments.pathId = :pathId ORDER BY segments.ordinal, points.ordinal")
    fun observePoints(pathId: String): Flow<List<PointEntity>>
    // 只抽稀显示，不修改原始数据。每段首尾与拒收点始终保留，避免跨断点连线。
    @Query("SELECT points.* FROM points JOIN segments ON points.segmentId = segments.id WHERE segments.pathId = :pathId AND (points.ordinal % (1 + (SELECT COUNT(*) FROM points p JOIN segments s ON p.segmentId = s.id WHERE s.pathId = :pathId) / 10000) = 0 OR points.ordinal = (SELECT MAX(p.ordinal) FROM points p WHERE p.segmentId = points.segmentId) OR points.accepted = 0) ORDER BY segments.ordinal, points.ordinal")
    fun observeMapPoints(pathId: String): Flow<List<PointEntity>>
    @Query("SELECT * FROM points WHERE segmentId = :segmentId ORDER BY ordinal DESC LIMIT 1")
    suspend fun lastPoint(segmentId: String): PointEntity?
    @Query("SELECT COUNT(*) FROM segments WHERE pathId = :pathId") suspend fun segmentCount(pathId: String): Int
    @Query("SELECT COUNT(*) FROM points") suspend fun pointCount(): Int
    @Query("SELECT * FROM recording_events WHERE sessionId = :id ORDER BY wallTimeMs") suspend fun events(id: String): List<RecordingEventEntity>
    @Query("SELECT * FROM import_stats WHERE pathId = :id") fun observeImportStats(id: String): Flow<ImportStatsEntity?>
    @Query("SELECT * FROM waypoints WHERE documentId = :documentId ORDER BY ordinal") fun observeWaypoints(documentId: String): Flow<List<WaypointEntity>>
    @Query("SELECT documents.*, COUNT(waypoints.id) AS waypointCount FROM documents JOIN waypoints ON waypoints.documentId = documents.id WHERE documents.publicationState = 'READY' AND NOT EXISTS (SELECT 1 FROM paths WHERE paths.documentId = documents.id) GROUP BY documents.id ORDER BY documents.createdAt DESC")
    fun observeWaypointDocuments(): Flow<List<WaypointDocument>>
    @Query("SELECT * FROM documents WHERE id = :id") fun observeDocument(id: String): Flow<DocumentEntity?>
    @Query("SELECT * FROM documents WHERE publicationState = 'READY' AND originalSha256 = :hash ORDER BY createdAt DESC LIMIT 1") suspend fun duplicate(hash: String): DocumentEntity?
    @Query("SELECT id FROM paths WHERE documentId = :id ORDER BY ordinal LIMIT 1") suspend fun firstPath(id: String): String?
    @Query("SELECT * FROM documents WHERE publicationState = 'STAGING'") suspend fun stagedDocuments(): List<DocumentEntity>
    @Query("SELECT originalFilePath FROM documents WHERE originalFilePath IS NOT NULL") suspend fun originalFiles(): List<String>
    @Query("DELETE FROM documents WHERE id = :id") suspend fun deleteDocument(id: String)
    @Query("UPDATE documents SET publicationState = 'READY' WHERE id = :id") suspend fun publish(id: String)

    // SAX 回调在 IO 线程同步写入有界批次；不能 runBlocking 每个点或在主线程执行。
    @Insert fun insertImported(document: DocumentEntity)
    @Insert fun insertImported(path: PathEntity)
    @Insert fun insertImported(segment: SegmentEntity)
    @Insert fun insertImported(points: List<PointEntity>)
    @Insert fun insertImported(waypoint: WaypointEntity)
    @Insert fun insertImported(stats: ImportStatsEntity)
    @Insert fun insertImported(element: GpxElementEntity)
}

@Database(entities = [DocumentEntity::class, PathEntity::class, SegmentEntity::class,
    PointEntity::class, SessionEntity::class, RecordingEventEntity::class, WaypointEntity::class,
    ImportStatsEntity::class, GpxElementEntity::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class TrailDatabase : RoomDatabase() { abstract fun dao(): TrailDao }
