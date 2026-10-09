package com.trail.app.map

import com.trail.app.data.PointEntity
import org.junit.Assert.*
import org.junit.Test

class MapGeometryTest {
    private fun point(segment: String, ordinal: Int, lon: Double, accepted: Boolean = true) = PointEntity(segment, ordinal, 1.0, lon, null, null, null, null, 0, accepted)
    @Test fun neverConnectsSegmentsOrRejectedPoints() {
        val lines = MapGeometry.lines(listOf(point("a", 0, 1.0), point("a", 1, 2.0), point("a", 2, 3.0, false),
            point("a", 3, 4.0), point("b", 0, 5.0), point("b", 1, 6.0)))
        assertEquals(listOf(listOf(1.0, 2.0), listOf(5.0, 6.0)), lines.map { it.map { p -> p.lon } })
    }
    @Test fun dateLineIsSplitAndViewportUsesShortArc() {
        val lines = MapGeometry.lines(listOf(point("a", 0, 179.9), point("a", 1, -179.9)))
        assertEquals(2, lines.size)
        assertEquals(180.0, lines[0].last().lon, 0.001)
        assertEquals(-180.0, lines[1].first().lon, 0.001)
        val bounds = MapGeometry.extent(listOf(MapCoordinate(1.0, 179.9), MapCoordinate(2.0, -179.9)))!!
        assertEquals(0.2, bounds.east - bounds.west, 0.001)
    }
}
