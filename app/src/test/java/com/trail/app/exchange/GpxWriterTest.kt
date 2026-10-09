package com.trail.app.exchange

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class GpxWriterTest {
    private fun roundTrip(block: GpxWriter.() -> Unit): Pair<String, Pair<GpxPreview, List<GpxEvent>>> {
        val text = StringWriter()
        GpxWriter(text).apply { start(); block(); end() }
        val events = mutableListOf<GpxEvent>()
        val preview = GpxReader().read(text.toString().byteInputStream(), sink = { events += it })
        return text.toString() to (preview to events)
    }
    @Test fun preservesMissingValuesOrderAndNamespacedPointFields() {
        val (xml, parsed) = roundTrip {
            metadata("测试", emptyList())
            startPath("TRACK", "改名后", listOf("<name xmlns=\"http://www.topografix.com/GPX/1/1\">旧名</name>"))
            startSegment("TRACK")
            waypoint(1.0, 103.0, null, null, null, null, null,
                "<extensions xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:x=\"urn:test\"><x:heart>88</x:heart></extensions>", "trkpt")
            waypoint(1.1, 103.1, -5.0, 1_000L, null, "弯道", null, "", "trkpt")
            endSegment("TRACK", emptyList())
            startSegment("TRACK"); endSegment("TRACK", emptyList()); endPath("TRACK")
        }
        assertEquals("1.1", parsed.first.version)
        assertEquals("改名后", parsed.first.paths.single().name)
        assertEquals(2, parsed.first.paths.single().segments)
        val points = parsed.second.filterIsInstance<GpxEvent.Position>().map { it.value }
        assertNull(points.first().elevation)
        assertNull(points.first().time)
        assertEquals(-5.0, points.last().elevation!!, 0.0)
        assertEquals("弯道", points.last().name)
        assertTrue(points.first().extraXml.contains("urn:test"))
        assertEquals(1, "<name>改名后</name>".toRegex().findAll(xml).count())
        assertFalse(xml.contains("旧名"))
    }
    @Test fun convertsOneZeroCoreAndKeepsLegacyFieldInVersionedExtension() {
        val (xml, parsed) = roundTrip {
            metadata("旧文件", listOf("<name xmlns=\"http://www.topografix.com/GPX/1/0\">旧文件</name>"))
            startPath("ROUTE", "旧路线", listOf(
                "<name xmlns=\"http://www.topografix.com/GPX/1/0\">旧路线</name>",
                "<url xmlns=\"http://www.topografix.com/GPX/1/0\">https://example.invalid</url>",
                "<mystery xmlns=\"http://www.topografix.com/GPX/1/0\">保留</mystery>"))
            startSegment("ROUTE")
            waypoint(0.0, 0.0, null, null, null, null, null, "", "rtept")
            endSegment("ROUTE", emptyList()); endPath("ROUTE")
        }
        assertEquals("ROUTE", parsed.first.paths.single().kind)
        assertEquals("旧路线", parsed.first.paths.single().name)
        assertTrue(xml.contains("sourceVersion=\"1.0\""))
        assertTrue(xml.contains("<link href=\"https://example.invalid\""))
        assertTrue(xml.contains("https://example.invalid"))
        assertFalse(xml.contains("http://www.topografix.com/GPX/1/0"))
    }
}
