package com.trail.app.exchange

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.trail.app.data.*
import com.trail.app.domain.ActivityType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class GpxImportRepositoryTest {
    private lateinit var db: TrailDatabase
    private lateinit var imports: GpxImportRepository
    private lateinit var root: File
    private val body = """<gpx xmlns="http://www.topografix.com/GPX/1/1" xmlns:x="urn:test" version="1.1" creator="test"><metadata><name>原始文档</name></metadata><wpt lat="1" lon="103"><name>水源</name><extensions><x:note>保留</x:note></extensions></wpt><trk><name>实测</name><trkseg><trkpt lat="1" lon="103"/><trkpt lat="1.001" lon="103"/></trkseg><trkseg><trkpt lat="2" lon="104"/></trkseg></trk><rte><name>计划</name><rtept lat="3" lon="105"/><rtept lat="3.001" lon="105"/></rte></gpx>"""
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, TrailDatabase::class.java).build()
        root = File(context.cacheDir, "gpx-test-${UUID.randomUUID()}")
        imports = GpxImportRepository(db, root)
    }
    @After fun cleanup() { db.close(); root.deleteRecursively() }
    private suspend fun draft(text: String = body) = imports.prepare("测试.gpx", { text.byteInputStream() })

    @Test fun elevationTotalsArePublishedWithImportedTrack() = runBlocking {
        val result = imports.publish(draft("""<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test"><trk><trkseg><trkpt lat="1" lon="103"><ele>100</ele></trkpt><trkpt lat="1" lon="103"><ele>120</ele></trkpt><trkpt lat="1" lon="103"><ele>110</ele></trkpt></trkseg></trk></gpx>"""), setOf(0), false)
        val stats = db.dao().observeImportStats(result.firstPathId!!).first()!!
        assertEquals(20.0, stats.ascentM!!, 0.001)
        assertEquals(10.0, stats.descentM!!, 0.001)
    }

    @Test fun mixedImportPreservesSegmentsExtensionsAndActiveRecording() = runBlocking {
        val recording = RecordingRepository(db)
        recording.start(ActivityType.RUN, 1_000, 1_000)
        val before = db.dao().active()
        val pending = draft()
        assertEquals(1, db.dao().observeTracks().first().size) // preview never creates visible import rows
        val result = imports.publish(pending, setOf(0, 1), true)
        assertEquals(before, db.dao().active())
        val tracks = db.dao().observeTracks().first().filter { it.source == "IMPORTED" }
        assertEquals(listOf("TRACK", "ROUTE"), tracks.map { it.path.kind })
        assertNull(tracks.first().activeDurationMs)
        assertNull(tracks.first().elapsedMs)
        assertEquals(2, db.dao().segmentCount(tracks.first().path.id))
        assertEquals(3, db.dao().observePoints(tracks.first().path.id).first().size)
        val wpt = db.dao().observeWaypoints(result.documentId).first().single()
        assertEquals("水源", wpt.name)
        assertTrue(wpt.extraXml.contains("urn:test"))
        val doc = db.dao().observeDocument(result.documentId).first()!!
        assertEquals(body, File(doc.originalFilePath!!).readText())
        assertEquals("READY", doc.publicationState)
        imports.recover()
        assertTrue(File(doc.originalFilePath).exists())
    }

    @Test fun selectiveImportAndDuplicateDetectionDoNotMerge() = runBlocking {
        val result = imports.publish(draft(), setOf(1), false)
        assertEquals(1, db.dao().observeTracks().first().size)
        assertTrue(db.dao().observeWaypoints(result.documentId).first().isEmpty())
        val again = draft()
        assertEquals(result.documentId, again.duplicateDocumentId)
        imports.publish(again, setOf(0), true)
        assertEquals(2, db.dao().observeTracks().first().size)
    }

    @Test fun waypointOnlyDocumentIsVisibleWithoutInventingTrack() = runBlocking {
        val result = imports.publish(draft("""<gpx xmlns="http://www.topografix.com/GPX/1/0" version="1.0"><wpt lat="-1" lon="0"><ele>-10</ele></wpt></gpx>"""), emptySet(), true)
        assertNull(result.firstPathId)
        assertTrue(db.dao().observeTracks().first().isEmpty())
        assertEquals(1, db.dao().observeWaypointDocuments().first().single().waypointCount)
    }

    @Test fun cancelledImportRemovesInvisibleBatchesAndOriginalCopy() = runBlocking {
        val many = "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" version=\"1.1\"><trk><trkseg>" +
            "<trkpt lat=\"1\" lon=\"103\"/>".repeat(2_000) + "</trkseg></trk></gpx>"
        val pending = draft(many)
        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            imports.publish(pending, setOf(0), false, progress = { if (it > 0) job.cancel() })
        }
        job.start(); job.join()
        assertTrue(db.dao().observeTracks().first().isEmpty())
        assertTrue(db.dao().stagedDocuments().isEmpty())
        assertEquals(0, db.dao().pointCount())
        assertTrue(File(root, "originals").listFiles().orEmpty().isEmpty())
    }

    @Test fun invalidFileLeavesNoDraftOrVisibleObjects() = runBlocking {
        try { draft("<not-gpx/>"); fail() } catch (_: GpxException) { }
        // Android SAX 的安全配置与桌面实现不同，设备上也必须验证 DTD 被拒绝。
        try { draft("<!DOCTYPE gpx [<!ENTITY x SYSTEM 'file:///never-read'>]>" + body); fail() } catch (_: GpxException) { }
        assertTrue(File(root, "drafts").listFiles().orEmpty().isEmpty())
        assertTrue(db.dao().observeTracks().first().isEmpty())
    }

    @Test fun recoveryRemovesUnpublishedImportButKeepsCommittedOriginal() = runBlocking {
        val saved = imports.publish(draft(), setOf(0), true)
        val original = db.dao().observeDocument(saved.documentId).first()!!.originalFilePath!!
        val abandoned = draft()
        val orphan = File(root, "originals/orphan.gpx").apply { writeText(body) }
        withContext(Dispatchers.IO) {
            db.dao().insertImported(DocumentEntity("staged", "未完成", 0, "IMPORTED", publicationState = "STAGING", originalFilePath = orphan.absolutePath))
        }
        imports.recover()
        assertTrue(db.dao().stagedDocuments().isEmpty())
        assertFalse(abandoned.file.exists())
        assertFalse(orphan.exists())
        assertEquals(body, File(original).readText())
        assertEquals(1, db.dao().observeTracks().first().size)
    }
}
