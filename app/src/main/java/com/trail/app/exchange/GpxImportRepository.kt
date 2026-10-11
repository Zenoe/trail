package com.trail.app.exchange

import androidx.room.withTransaction
import com.trail.app.data.*
import com.trail.app.domain.ActivityType
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class ImportDraft(val id: String, val file: File, val displayName: String, val hash: String,
    val preview: GpxPreview, val duplicateDocumentId: String?)
data class ImportResult(val documentId: String, val firstPathId: String?)

class GpxImportRepository(private val db: TrailDatabase, private val root: File,
    private val reader: GpxReader = GpxReader()) {
    private val dao = db.dao()
    private val drafts get() = File(root, "drafts").apply { mkdirs() }
    private val originals get() = File(root, "originals").apply { mkdirs() }

    /** 启动屏障期间清理未发布导入，只处理本仓库生成的 UUID 文件，绝不触碰用户源 URI。 */
    suspend fun recover() = withContext(Dispatchers.IO) {
        dao.stagedDocuments().forEach { dao.deleteDocument(it.id) }
        drafts.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        val referenced = dao.originalFiles().toSet()
        originals.listFiles()?.filter { it.isFile && it.absolutePath !in referenced }?.forEach { it.delete() }
    }

    suspend fun prepare(name: String, open: () -> InputStream, progress: (Long) -> Unit = {}): ImportDraft = withContext(Dispatchers.IO) {
        val context = coroutineContext
        val id = UUID.randomUUID().toString()
        val file = File(drafts, "$id.gpx")
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            open().use { input -> file.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024); var count = 0L
                while (true) {
                    context.ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    count += n; if (count > 100L * 1024 * 1024) throw GpxException("文件超过 100 MiB 限制")
                    output.write(buffer, 0, n); hash.update(buffer, 0, n); progress(count)
                }
            } }
            val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val preview = file.inputStream().buffered().use { reader.read(it, { context.ensureActive() }) }
            ImportDraft(id, file, name.take(200).ifBlank { "导入轨迹.gpx" }, digest, preview, dao.duplicate(digest)?.id)
        } catch (failure: Throwable) { file.delete(); throw failure }
    }

    suspend fun discard(draft: ImportDraft) = withContext(Dispatchers.IO) { draft.file.delete(); Unit }

    suspend fun publish(draft: ImportDraft, selected: Set<Int>, includeWaypoints: Boolean,
        progress: (Int) -> Unit = {}, committed: (ImportResult) -> Unit = {}): ImportResult = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty() || includeWaypoints && draft.preview.waypointCount > 0) { "请选择轨迹或航点" }
        require(selected.all { id -> draft.preview.paths.any { it.index == id } })
        val context = coroutineContext
        val documentId = UUID.randomUUID().toString()
        val original = File(originals, "$documentId.gpx")
        val pathIds = selected.associateWith { UUID.randomUUID().toString() }
        val segmentIds = mutableMapOf<Pair<Int, Int>, String>()
        val now = System.currentTimeMillis()
        var published = false
        try {
            // 原文件先完整复制，再发布数据库；崩溃留下的无引用副本在下次启动清理。
            draft.file.inputStream().use { input -> original.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) { context.ensureActive(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
            } }
            dao.insertImported(DocumentEntity(documentId, draft.displayName, now, "IMPORTED", publicationState = "STAGING",
                originalFilePath = original.absolutePath, originalSha256 = draft.hash, gpxVersion = draft.preview.version, creator = draft.preview.creator))
            val buffer = mutableListOf<GpxEvent>()
            var bufferedChars = 0
            var importedPoints = 0
            var elementOrdinal = 0
            fun flush() {
                if (buffer.isEmpty()) return
                context.ensureActive()
                // 有界批次让前台记录事务能穿插执行。整个导入的可见性由 READY 提交一次切换。
                db.runInTransaction {
                    val points = mutableListOf<PointEntity>()
                    for (event in buffer) when (event) {
                        is GpxEvent.PathStart -> pathIds[event.index]?.let { pathId ->
                            val item = draft.preview.paths.first { it.index == event.index }
                            dao.insertImported(PathEntity(pathId, documentId, item.name, ActivityType.HIKE, now, event.index, item.kind))
                            dao.insertImported(ImportStatsEntity(pathId, item.distanceM, item.elapsedMs, item.points, item.segments, item.warnings.joinToString("\n"), item.ascentM, item.descentM))
                        }
                        is GpxEvent.SegmentStart -> pathIds[event.path]?.let { pathId ->
                            val segmentId = UUID.randomUUID().toString()
                            segmentIds[event.path to event.index] = segmentId
                            dao.insertImported(SegmentEntity(segmentId, pathId, event.index, "GPX_IMPORT"))
                        }
                        is GpxEvent.Position -> {
                            val p = event.value; val owner = event.owner
                            if (owner.type == "WAYPOINT" && includeWaypoints) dao.insertImported(WaypointEntity(UUID.randomUUID().toString(), documentId, owner.point,
                                p.lat, p.lon, p.elevation, p.time, p.timeLexical, p.name, p.symbol, p.extraXml))
                            else if (owner.type == "POINT" && owner.path in selected) {
                                points.add(PointEntity(segmentIds.getValue(owner.path to owner.segment), owner.point, p.lat, p.lon,
                                    p.time, p.elevation, null, null, 0, true, p.timeLexical, p.name, p.symbol, p.extraXml))
                            }
                            importedPoints++
                        }
                        is GpxEvent.Element -> {
                            val ownerKey = when (event.owner.type) {
                                "DOCUMENT" -> documentId
                                "PATH" -> pathIds[event.owner.path]
                                "SEGMENT" -> segmentIds[event.owner.path to event.owner.segment]
                                else -> null
                            }
                            if (ownerKey != null) dao.insertImported(GpxElementEntity(UUID.randomUUID().toString(), documentId,
                                event.owner.type, ownerKey, elementOrdinal++, event.xml))
                        }
                        is GpxEvent.Header -> Unit
                    }
                    dao.insertImported(points)
                }
                buffer.clear(); bufferedChars = 0; progress(importedPoints)
            }
            original.inputStream().buffered().use { input -> reader.read(input, { context.ensureActive() }) { event ->
                buffer += event
                bufferedChars += when (event) { is GpxEvent.Position -> event.value.extraXml.length; is GpxEvent.Element -> event.xml.length; else -> 0 }
                if (buffer.size >= 500 || bufferedChars >= 1_048_576) flush()
            } }
            flush(); context.ensureActive()
            // 发布后到返回之间不再响应取消，避免“成功写入但 UI 误报取消”的竞态。
            val result = ImportResult(documentId, pathIds.toSortedMap().values.firstOrNull())
            withContext(NonCancellable) {
                db.withTransaction { dao.publish(documentId) }; published = true
                committed(result); draft.file.delete()
            }
            result
        } finally {
            if (!published) withContext(NonCancellable) { try { dao.deleteDocument(documentId) } finally { original.delete() } }
        }
    }
}
