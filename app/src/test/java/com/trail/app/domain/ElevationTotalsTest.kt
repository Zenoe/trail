package com.trail.app.domain

import org.junit.Assert.*
import org.junit.Test

class ElevationTotalsTest {
    @Test fun gradualClimbAndDescentAccumulateBeyondNoiseThreshold() {
        val total = listOf(100.0, 102.0, 104.0, 106.0, 108.0, 112.0, 110.0, 105.0)
            .fold(ElevationTotals()) { state, elevation -> state.next(elevation) }
        assertEquals(12.0, total.ascentM!!, 0.001)
        assertEquals(7.0, total.descentM!!, 0.001)
    }
    @Test fun noiseMissingValuesAndSegmentBoundariesDoNotCreateClimbs() {
        var total = listOf(100.0, 102.0, 99.0, 103.0, 100.0).fold(ElevationTotals()) { s, h -> s.next(h) }
        assertEquals(0.0, total.ascentM!!, 0.001)
        assertEquals(0.0, total.descentM!!, 0.001)
        total = total.next(null).next(500.0).copy(anchorM = null).next(-100.0)
        assertEquals(0.0, total.ascentM!!, 0.001)
        assertEquals(0.0, total.descentM!!, 0.001)
        assertNull(ElevationTotals().next(null).ascentM)
        assertNull(ElevationTotals().next(100.0).ascentM)
    }
}
