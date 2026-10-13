package com.trail.app.map

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackProjectionTest {
    @Test fun nearbyPointsRemainOnCanvasAndDateLineUsesShortArc() {
        val extent = MapGeometry.extent(listOf(MapCoordinate(1.0, 179.9), MapCoordinate(2.0, -179.9)))!!
        val projection = TrackProjection(extent, 400f, 300f, 1f, Offset.Zero)
        val west = projection.project(MapCoordinate(1.5, 179.9))
        val east = projection.project(MapCoordinate(1.5, -179.9))
        assertTrue(west.x in 0f..400f)
        assertTrue(east.x in 0f..400f)
        assertTrue(east.x > west.x)
    }

    @Test fun panAndZoomOnlyChangeDisplayCoordinates() {
        val extent = MapExtent(2.0, 2.0, 0.0, 0.0)
        val center = MapCoordinate(1.0, 1.0)
        val original = TrackProjection(extent, 400f, 300f, 1f, Offset.Zero).project(center)
        val moved = TrackProjection(extent, 400f, 300f, 3f, Offset(20f, -15f)).project(center)
        assertEquals(200f, original.x, 0.01f)
        assertEquals(150f, original.y, 0.01f)
        assertEquals(220f, moved.x, 0.01f)
        assertEquals(135f, moved.y, 0.01f)
    }
}
