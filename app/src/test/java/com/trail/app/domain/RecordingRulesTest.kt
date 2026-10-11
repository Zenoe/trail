package com.trail.app.domain

import org.junit.Assert.*
import org.junit.Test

class RecordingRulesTest {
    @Test fun distanceHandlesDateLineAndIdenticalPoints() {
        assertEquals(0.0, RecordingRules.distance(0.0, 0.0, 0.0, 0.0), 0.001)
        assertEquals(22_239.0, RecordingRules.distance(0.0, 179.9, 0.0, -179.9), 10.0)
    }
    @Test fun badCoordinatesAndMissingAccuracyCannotBeCounted() {
        val sample = LocationSample(1.0, 103.0, 0, 0, 8f, null, null)
        assertTrue(RecordingRules.precise(sample))
        assertFalse(RecordingRules.precise(sample.copy(accuracyM = null)))
        assertFalse(RecordingRules.precise(sample.copy(accuracyM = 80f)))
        assertFalse(RecordingRules.valid(sample.copy(latitude = Double.NaN)))
        assertFalse(RecordingRules.valid(sample.copy(longitude = 181.0)))
    }
    @Test fun durationNeverAddsTimeAcrossClockReset() {
        assertEquals(0L, RecordingRules.durationDelta(null, 10_000))
        assertEquals(0L, RecordingRules.durationDelta(50_000, 500))
        assertEquals(5_000L, RecordingRules.durationDelta(10_000, 15_000))
    }
    @Test fun transitionGuardsRejectRepeatedActions() {
        assertTrue(RecordingRules.canPause(RecordingState.WAITING))
        assertFalse(RecordingRules.canPause(RecordingState.PAUSED))
        assertFalse(RecordingRules.canResume(RecordingState.RECORDING))
        assertTrue(RecordingRules.canResume(RecordingState.INTERRUPTED))
        assertFalse(RecordingRules.canResume(RecordingState.FINISHED))
    }
    @Test fun coldStartNeedsNearbySecondFixAndRejectsImpossibleJump() {
        val first = LocationSample(1.3, 103.8, 0, 1_000, 5f, null, null)
        val nearby = first.copy(latitude = 1.3001, elapsedMs = 4_000)
        val jump = first.copy(latitude = 1.32, elapsedMs = 4_000)
        assertTrue(RecordingRules.stablePair(first, nearby))
        assertFalse(RecordingRules.stablePair(first, jump))
        assertFalse(RecordingRules.stablePair(first.copy(accuracyM = 40f), nearby.copy(accuracyM = 40f)))
        assertFalse(RecordingRules.plausible(nearby, jump, ActivityType.HIKE))
        assertTrue(RecordingRules.plausible(nearby, nearby.copy(latitude = 1.3002, elapsedMs = 7_000), ActivityType.HIKE))
    }
}
