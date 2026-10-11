package com.trail.app.exchange

import org.junit.Assert.*
import org.junit.Test

class GpxReaderTest {
    @Test fun elevationTotalsRespectMissingHeightAndSegments() {
        val result = read("""<trk><trkseg><trkpt lat="1" lon="103"><ele>100</ele></trkpt><trkpt lat="1" lon="103"><ele>110</ele></trkpt><trkpt lat="1" lon="103"/><trkpt lat="1" lon="103"><ele>500</ele></trkpt></trkseg><trkseg><trkpt lat="1" lon="103"><ele>0</ele></trkpt><trkpt lat="1" lon="103"><ele>-10</ele></trkpt></trkseg></trk>""")
        assertEquals(10.0, result.paths.single().ascentM!!, 0.001)
        assertEquals(10.0, result.paths.single().descentM!!, 0.001)
    }
    private fun xml(body: String, version: String = "1.1") = """<gpx xmlns="http://www.topografix.com/GPX/${version.replace('.', '/')}" xmlns:x="urn:vendor:test" version="$version" creator="tests">$body</gpx>"""
    private fun read(body: String, version: String = "1.1", events: MutableList<GpxEvent> = mutableListOf()) =
        GpxReader().read(xml(body, version).byteInputStream(), sink = { events.add(it) })
    private fun rejects(text: String, reader: GpxReader = GpxReader()) {
        try { reader.read(text.byteInputStream()); fail("Expected invalid GPX to fail") } catch (_: GpxException) { }
    }
    @Test fun multiTrackSegmentsAndRoutesRemainDistinct() {
        val events = mutableListOf<GpxEvent>()
        val result = read("""<trk><name>晨跑 🌄</name><trkseg><trkpt lat="1" lon="103"/><trkpt lat="1.001" lon="103"/></trkseg><trkseg><trkpt lat="2" lon="104"/></trkseg></trk><rte><name>计划</name><rtept lat="3" lon="105"/></rte><trk><trkseg/></trk>""", events = events)
        assertEquals(3, result.paths.size)
        assertEquals(2, result.paths[0].segments)
        assertEquals(3, result.paths[0].points)
        assertEquals(111.2, result.paths[0].distanceM, 0.5)
        assertEquals("晨跑 🌄", result.paths[0].name)
        assertEquals("ROUTE", result.paths[1].kind)
        assertNull(result.paths[0].elapsedMs)
        assertTrue(result.paths[2].warnings.contains("包含空分段"))
    }
    @Test fun optionalFieldsStayNullAndNegativeElevationIsPreserved() {
        val events = mutableListOf<GpxEvent>()
        val result = read("""<wpt lat="1" lon="103"><name>水源</name><ele>-8.5</ele><time>2026-10-09T08:00:00+08:00</time></wpt>""", events = events)
        assertEquals(1, result.waypointCount)
        assertTrue(result.paths.isEmpty())
        val point = events.filterIsInstance<GpxEvent.Position>().single().value
        assertEquals(-8.5, point.elevation!!, 0.01)
        assertEquals("水源", point.name)
        assertEquals(java.time.Instant.parse("2026-10-09T00:00:00Z").toEpochMilli(), point.time)
    }
    @Test fun prefixedNamespacesAndUnknownNestedExtensionsSurvive() {
        val input = """<g:gpx xmlns:g="http://www.topografix.com/GPX/1/1" xmlns:v="urn:vendor" version="1.1" creator="x"><g:trk><g:trkseg><g:trkpt lat="1" lon="103"><g:extensions><v:data unit="bpm"><v:value>123 &amp; 4</v:value></v:data></g:extensions></g:trkpt></g:trkseg></g:trk></g:gpx>"""
        val events = mutableListOf<GpxEvent>()
        GpxReader().read(input.byteInputStream(), sink = { events += it })
        val extra = events.filterIsInstance<GpxEvent.Position>().single().value.extraXml
        assertTrue(extra.contains("xmlns:v=\"urn:vendor\""))
        assertTrue(extra.contains("123 &amp; 4"))
        read("""<wpt lat="0" lon="0">$extra</wpt>""") // exported fragment must remain well-formed
    }
    @Test fun oneZeroMetadataAndRouteFieldsAreRetained() {
        val events = mutableListOf<GpxEvent>()
        val result = read("""<name>旧文件</name><rte><name>旧路线</name><url>https://example.invalid</url><rtept lat="0" lon="0"/></rte>""", "1.0", events)
        assertEquals("1.0", result.version)
        assertTrue(events.filterIsInstance<GpxEvent.Element>().any { it.name == "url" })
    }
    @Test fun timesGiveElapsedButReverseOrderDoesNot() {
        fun pts(a: String, b: String) = """<trk><trkseg><trkpt lat="0" lon="0"><time>$a</time></trkpt><trkpt lat="0" lon="0"><time>$b</time></trkpt></trkseg></trk>"""
        assertEquals(60_000L, read(pts("2026-10-09T00:00:00Z", "2026-10-09T00:01:00Z")).paths.single().elapsedMs)
        val reverse = read(pts("2026-10-09T00:01:00Z", "2026-10-09T00:00:00Z"))
        assertNull(reverse.paths.single().elapsedMs)
        assertTrue(reverse.paths.single().warnings.any { it.contains("时间倒序") })
    }
    @Test fun rejectsInvalidInputsAndNeverResolvesExternalEntities() {
        listOf("<gpx/>", xml("""<wpt lat="91" lon="0"/>"""), xml("""<wpt lat="NaN" lon="0"/>"""),
            xml("""<wpt lat="0" lon="0"><ele>bad</ele></wpt>"""),
            xml("""<wpt lat="0" lon="0"><time>today</time></wpt>"""), xml("<trkpt lat=\"0\" lon=\"0\"/>"),
            xml("<metadata/>"), xml("<trk>"),
            """<!DOCTYPE gpx [<!ENTITY steal SYSTEM "file:///does-not-exist">]>${xml("<wpt lat=\"0\" lon=\"0\"><name>&steal;</name></wpt>")}""").forEach { rejects(it) }
    }
    @Test fun enforcesResourceLimitsAndSupportsCancellation() {
        rejects(xml("""<wpt lat="0" lon="0"/><wpt lat="0" lon="0"/>"""), GpxReader(GpxLimits(points = 1)))
        rejects(xml("""<wpt lat="0" lon="0"/>"""), GpxReader(GpxLimits(bytes = 20)))
        rejects(xml("<extensions><x:a><x:b><x:c/></x:b></x:a></extensions>"), GpxReader(GpxLimits(depth = 3)))
        rejects(xml("<wpt lat=\"0\" lon=\"0\"><name>${"a".repeat(500)}</name></wpt>"), GpxReader(GpxLimits(elementChars = 200)))
        try { GpxReader().read(xml("<wpt lat=\"0\" lon=\"0\"/>").byteInputStream(), { throw java.util.concurrent.CancellationException() }); fail() }
        catch (_: java.util.concurrent.CancellationException) { }
    }
    @Test fun largeTrackStreamsEveryPointAndKeepsSmallPreview() {
        val body = "<trk><trkseg>" + "<trkpt lat=\"1\" lon=\"103\"/>".repeat(20_000) + "</trkseg></trk>"
        var count = 0
        val preview = GpxReader().read(xml(body).byteInputStream()) { if (it is GpxEvent.Position) count++ }
        assertEquals(20_000, count)
        assertEquals(20_000, preview.paths.single().points)
        assertEquals(1, preview.paths.size)
    }
}
