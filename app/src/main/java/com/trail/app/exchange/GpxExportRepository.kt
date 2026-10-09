package com.trail.app.exchange

import android.content.ContentResolver
import android.net.Uri
import com.trail.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class PreparedGpx(val file: File, val suggestedName: String)

/** 在私有缓存先生成并重新解析，外部文档提供者永远不会收到半成品 XML。 */
class GpxExportRepository(private val db: TrailDatabase, private val root: File,
    private val reader: GpxReader = GpxReader()) {
    private val dao = db.dao()
    private val outputDir get() = File(root, "gpx-export").apply { mkdirs() }

    suspend fun prepare(documentId: String, selectedPathIds: Set<String>? = null,
        includeWaypoints: Boolean = selectedPathIds == null): PreparedGpx = withContext(Dispatchers.IO) {
        val document = dao.exportDocument(documentId) ?: throw GpxException("轨迹不存在或尚未保存")
        val allPaths = dao.exportPaths(documentId)
        require(selectedPathIds == null || selectedPathIds.all { id -> allPaths.any { it.id == id } }) { "所选路线不属于该文件" }
        val paths = allPaths.filter { selectedPathIds == null || it.id in selectedPathIds }
        require(paths.isNotEmpty() || includeWaypoints && dao.waypointCount(documentId) > 0) { "没有可导出的路线或标记" }
        require(paths.none { dao.activeSlot(it.id) == 1 }) { "请先结束当前记录，再导出 GPX" }
        val filename = (if (paths.size == 1) paths.first().name else document.name)
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).ifBlank { "山径轨迹" } + ".gpx"
        val file = File(outputDir, "${UUID.randomUUID()}.gpx")
        val jobContext = coroutineContext
        try {
            OutputStreamWriter(file.outputStream().buffered(), StandardCharsets.UTF_8).use { stream ->
                val writer = GpxWriter(stream)
                writer.start()
                if (selectedPathIds == null) writer.metadata(document.name,
                    dao.exportElements(documentId, "DOCUMENT", documentId).map { it.xml })
                else writer.metadata(paths.singleOrNull()?.name ?: document.name, emptyList())
                if (includeWaypoints) {
                    var after = -1
                    while (true) {
                        jobContext.ensureActive()
                        val page = dao.exportWaypointPage(documentId, after)
                        if (page.isEmpty()) break
                        page.forEach { writer.waypoint(it.latitude, it.longitude, it.elevationM,
                            it.timeUtcMs, it.timeLexical, it.name, it.symbol, it.extraXml) }
                        after = page.last().ordinal
                    }
                }
                // GPX 1.1 要求所有 rte 排在 trk 之前；同类内部仍按原文件 ordinal。
                paths.sortedWith(compareBy<com.trail.app.data.PathEntity> { if (it.kind == "ROUTE") 0 else 1 }.thenBy { it.ordinal }).forEach { path ->
                    jobContext.ensureActive()
                    writer.startPath(path.kind, path.name, dao.exportElements(documentId, "PATH", path.id).map { it.xml })
                    dao.exportSegments(path.id).forEach { segment ->
                        writer.startSegment(path.kind)
                        var after = -1
                        while (true) {
                            jobContext.ensureActive()
                            val page = dao.exportPointPage(segment.id, after)
                            if (page.isEmpty()) break
                            page.forEach { writer.waypoint(it.latitude, it.longitude, it.elevationM,
                                it.timeUtcMs, it.timeLexical, it.name, it.symbol, it.extraXml,
                                if (path.kind == "ROUTE") "rtept" else "trkpt") }
                            after = page.last().ordinal
                        }
                        writer.endSegment(path.kind, dao.exportElements(documentId, "SEGMENT", segment.id).map { it.xml })
                    }
                    writer.endPath(path.kind)
                }
                writer.end()
            }
            // 导出和导入共用独立解析器，确认格式与资源阈值；读取失败时只删除私有临时文件。
            file.inputStream().buffered().use { reader.read(it, { jobContext.ensureActive() }) }
            PreparedGpx(file, filename)
        } catch (failure: Throwable) { file.delete(); throw failure }
    }

    suspend fun save(prepared: PreparedGpx, resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val output = resolver.openOutputStream(uri, "wt") ?: throw GpxException("无法写入所选位置")
            output.use { target -> prepared.file.inputStream().use { source -> source.copyTo(target, 64 * 1024) } }
        } catch (failure: Throwable) {
            // SAF 提供者未必支持删除或原子提交；仅尝试清理本次 CreateDocument 返回的目标。
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        } finally { prepared.file.delete() }
    }

    fun discard(prepared: PreparedGpx) { prepared.file.delete() }
    fun cleanupOld() {
        outputDir.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }
            ?.forEach { it.delete() }
    }
}
