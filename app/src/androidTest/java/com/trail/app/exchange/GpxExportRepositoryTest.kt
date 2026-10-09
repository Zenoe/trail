package com.trail.app.exchange

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.trail.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import android.net.Uri
import androidx.core.content.FileProvider

class GpxExportRepositoryTest {
    private lateinit var db: TrailDatabase
    private lateinit var root: File
    private lateinit var imports: GpxImportRepository
    private lateinit var exports: GpxExportRepository
    private lateinit var management: TrackManagementRepository
    private val source = """<gpx xmlns="http://www.topografix.com/GPX/1/1" xmlns:x="urn:test" version="1.1" creator="test"><metadata><name>文档</name></metadata><wpt lat="1" lon="103"><name>标记</name></wpt><trk><name>实测</name><trkseg><trkpt lat="1" lon="103"><extensions><x:note>保留</x:note></extensions></trkpt><trkpt lat="1.001" lon="103"><ele>-2</ele></trkpt></trkseg><trkseg/></trk><rte><name>计划</name><rtept lat="2" lon="104"/></rte></gpx>"""
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, TrailDatabase::class.java).build()
        root = File(context.cacheDir, "stage3-${UUID.randomUUID()}")
        imports = GpxImportRepository(db, File(root, "imports"))
        exports = GpxExportRepository(db, root)
        management = TrackManagementRepository(db, File(root, "imports/originals"))
    }
    @After fun cleanup() { db.close(); root.deleteRecursively() }

    @Test fun importExportReimportPreservesStructureAndRawSource() = runBlocking {
        val imported = imports.publish(imports.prepare("输入.gpx", { source.byteInputStream() }), setOf(0, 1), true)
        val original = db.dao().exportDocument(imported.documentId)!!.originalFilePath!!
        val track = db.dao().exportPaths(imported.documentId).first { it.kind == "TRACK" }
        management.renamePath(track.id, "改名后")
        val prepared = exports.prepare(imported.documentId)
        val resultXml = prepared.file.readText()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prepared.file.copyTo(File(context.getExternalFilesDir(null), "stage3-roundtrip.gpx"), overwrite = true)
        assertTrue(resultXml.contains("version=\"1.1\""))
        assertFalse(resultXml.contains("<name>实测</name>"))
        assertEquals(source, File(original).readText()) // 重命名与语义导出都不改原件
        val events = mutableListOf<GpxEvent>()
        val preview = GpxReader().read(prepared.file.inputStream(), sink = { events += it })
        assertEquals(listOf("ROUTE", "TRACK"), preview.paths.map { it.kind }) // 1.1 标准的类型分组
        assertEquals(listOf(1, 2), preview.paths.map { it.segments })
        assertEquals(1, preview.waypointCount)
        assertEquals("改名后", preview.paths.last().name)
        assertTrue(events.filterIsInstance<GpxEvent.Position>().any { it.value.extraXml.contains("urn:test") })
        assertTrue(events.filterIsInstance<GpxEvent.Position>().any { it.value.elevation == -2.0 })
        assertTrue(events.filterIsInstance<GpxEvent.Position>().any { it.value.time == null })
        val again = imports.publish(imports.prepare("再导入.gpx", { prepared.file.inputStream() }), setOf(0, 1), true)
        assertEquals(3, db.dao().exportPaths(again.documentId).sumOf { db.dao().exportSegments(it.id).size })
        assertEquals(1, db.dao().waypointCount(again.documentId))
    }

    @Test fun subsetExportAndDeleteKeepOtherPathsAndWaypointDocument() = runBlocking {
        val imported = imports.publish(imports.prepare("输入.gpx", { source.byteInputStream() }), setOf(0, 1), true)
        val paths = db.dao().exportPaths(imported.documentId)
        val route = paths.first { it.kind == "ROUTE" }
        val subset = exports.prepare(imported.documentId, setOf(route.id), false)
        val subsetPreview = GpxReader().read(subset.file.inputStream())
        assertEquals(1, subsetPreview.paths.size)
        assertEquals("ROUTE", subsetPreview.paths.single().kind)
        assertEquals(0, subsetPreview.waypointCount)
        val original = db.dao().exportDocument(imported.documentId)!!.originalFilePath!!
        management.deletePath(route.id)
        assertEquals(1, db.dao().exportPaths(imported.documentId).size)
        assertTrue(db.dao().exportElements(imported.documentId, "PATH", route.id).isEmpty())
        assertTrue(File(original).exists())
        management.deletePath(paths.first { it.kind == "TRACK" }.id)
        assertEquals(1, db.dao().observeWaypointDocuments().first().size)
        management.deleteWaypointDocument(imported.documentId)
        assertNull(db.dao().exportDocument(imported.documentId))
        assertFalse(File(original).exists())
    }

    @Test fun saveReportsFailureAndShareProviderGrantsOnlyListedFiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val imported = imports.publish(imports.prepare("输入.gpx", { source.byteInputStream() }), setOf(0), true)
        val original = File(context.filesDir, "gpx/originals/test-${UUID.randomUUID()}.gpx").apply {
            parentFile!!.mkdirs(); writeText(source)
        }
        // Android 分享时实际通过 FileProvider 读取私有原件，不暴露 file:// 路径。
        val shared = FileProvider.getUriForFile(context, "${context.packageName}.files", original)
        assertEquals("content", shared.scheme)
        assertEquals(source, context.contentResolver.openInputStream(shared)!!.bufferedReader().use { it.readText() })
        original.delete()
        val prepared = exports.prepare(imported.documentId)
        val destination = File(root, "saved.gpx")
        exports.save(prepared, context.contentResolver, Uri.fromFile(destination))
        assertFalse(prepared.file.exists())
        assertEquals(1, GpxReader().read(destination.inputStream()).paths.size)
        val failed = exports.prepare(imported.documentId)
        try { exports.save(failed, context.contentResolver, Uri.parse("content://invalid.trail.provider/nowhere")); fail("expected save failure") }
        catch (_: Exception) { }
        assertFalse(failed.file.exists())
    }

    @Test fun cannotDeleteOrExportActiveRecording() = runBlocking {
        val session = com.trail.app.data.RecordingRepository(db)
        session.start(com.trail.app.domain.ActivityType.HIKE, 1000, 1000)
        val active = db.dao().active()!!
        val path = db.dao().exportPath(active.pathId)!!
        try { exports.prepare(path.documentId); fail("expected active export rejection") } catch (_: IllegalArgumentException) { }
        try { management.deletePath(path.id); fail("expected active deletion rejection") } catch (_: IllegalArgumentException) { }
        assertNotNull(db.dao().active())
        session.finish(2000, 2000)
        assertTrue(exports.prepare(path.documentId).file.exists())
    }

    @Test fun oneZeroLegacyMetadataAndLinksConvertToOneOne() = runBlocking {
        val input = """<gpx xmlns="http://www.topografix.com/GPX/1/0" version="1.0" creator="legacy"><name>旧文件</name><author>旧作者</author><email>ada@example.com</email><url>https://example.com</url><urlname>网站</urlname><wpt lat="1" lon="103"><name>起点</name><url>https://example.com/wpt</url><urlname>标记网页</urlname></wpt><rte><name>旧路线</name><url>https://example.com/route</url><rtept lat="1" lon="103"/></rte></gpx>"""
        val imported = imports.publish(imports.prepare("旧版.gpx", { input.byteInputStream() }), setOf(0), true)
        val prepared = exports.prepare(imported.documentId)
        val xml = prepared.file.readText()
        assertTrue(xml.contains("<author><name>旧作者</name><email id=\"ada\" domain=\"example.com\"/></author>"))
        assertTrue(xml.contains("<link href=\"https://example.com/route\""))
        assertTrue(xml.contains("<link href=\"https://example.com/wpt\""))
        assertEquals("1.1", GpxReader().read(prepared.file.inputStream()).version)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prepared.file.copyTo(File(context.getExternalFilesDir(null), "stage3-one-zero-converted.gpx"), overwrite = true)
        Unit
    }
}
