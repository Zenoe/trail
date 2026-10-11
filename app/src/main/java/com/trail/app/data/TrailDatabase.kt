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
    // 只抽稀地图显示，不碰原始点。连续拒收只取首点作断线哨兵，并保留恢复后的首个有效点；
    // 否则漫长弱信号会让“抽稀”结果仍包含无上限的拒收点，或错误地跨空档连线。
    @Query("""WITH cadence AS (SELECT 1 + COUNT(*) / 10000 AS step FROM points p
        JOIN segments s ON p.segmentId = s.id WHERE s.pathId = :pathId)
        SELECT points.* FROM points JOIN segments ON points.segmentId = segments.id, cadence
        WHERE segments.pathId = :pathId AND (
            (points.accepted = 0 AND NOT EXISTS (SELECT 1 FROM points prev
                WHERE prev.segmentId = points.segmentId AND prev.ordinal = points.ordinal - 1 AND prev.accepted = 0))
            OR (points.accepted = 1 AND (points.ordinal % cadence.step = 0
                OR points.ordinal = (SELECT MIN(p.ordinal) FROM points p WHERE p.segmentId = points.segmentId)
                OR points.ordinal = (SELECT MAX(p.ordinal) FROM points p WHERE p.segmentId = points.segmentId)
                OR EXISTS (SELECT 1 FROM points prev WHERE prev.segmentId = points.segmentId
                    AND prev.ordinal = points.ordinal - 1 AND prev.accepted = 0)))
        ) ORDER BY segments.ordinal, points.ordinal""")
    fun observeMapPoints(pathId: String): Flow<List<PointEntity>>
    @Query("SELECT * FROM points WHERE segmentId = :segmentId ORDER BY ordinal DESC LIMIT 1")
    suspend fun lastPoint(segmentId: String): PointEntity?
    @Query("SELECT * FROM points WHERE segmentId = :segmentId AND accepted = 1 ORDER BY ordinal DESC LIMIT 1")
    suspend fun lastAcceptedPoint(segmentId: String): PointEntity?
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
    @Query("SELECT * FROM documents WHERE id = :id AND publicationState = 'READY'") suspend fun exportDocument(id: String): DocumentEntity?
    @Query("SELECT * FROM paths WHERE id = :id") suspend fun exportPath(id: String): PathEntity?
    @Query("SELECT * FROM paths WHERE documentId = :id ORDER BY ordinal") suspend fun exportPaths(id: String): List<PathEntity>
    @Query("SELECT * FROM segments WHERE pathId = :pathId ORDER BY ordinal") suspend fun exportSegments(pathId: String): List<SegmentEntity>
    @Query("SELECT * FROM points WHERE segmentId = :segmentId AND ordinal > :after ORDER BY ordinal LIMIT 512")
    suspend fun exportPointPage(segmentId: String, after: Int): List<PointEntity>
    @Query("SELECT * FROM waypoints WHERE documentId = :documentId AND ordinal > :after ORDER BY ordinal LIMIT 256")
    suspend fun exportWaypointPage(documentId: String, after: Int): List<WaypointEntity>
    @Query("SELECT * FROM gpx_elements WHERE documentId = :documentId AND ownerType = :type AND ownerKey = :key ORDER BY ordinal")
    suspend fun exportElements(documentId: String, type: String, key: String): List<GpxElementEntity>
    @Query("SELECT COUNT(*) FROM paths WHERE documentId = :documentId") suspend fun pathCount(documentId: String): Int
    @Query("SELECT COUNT(*) FROM waypoints WHERE documentId = :documentId") suspend fun waypointCount(documentId: String): Int
    @Query("SELECT activeSlot FROM sessions WHERE pathId = :pathId LIMIT 1") suspend fun activeSlot(pathId: String): Int?
    @Query("UPDATE paths SET name = :name WHERE id = :id") suspend fun renamePath(id: String, name: String)
    @Query("UPDATE documents SET name = :name WHERE id = :id") suspend fun renameDocument(id: String, name: String)
    @Query("DELETE FROM paths WHERE id = :id") suspend fun deletePath(id: String)
    @Query("DELETE FROM gpx_elements WHERE (ownerType = 'PATH' AND ownerKey = :pathId) OR (ownerType = 'SEGMENT' AND ownerKey IN (SELECT id FROM segments WHERE pathId = :pathId))")
    suspend fun deletePathElements(pathId: String)

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
