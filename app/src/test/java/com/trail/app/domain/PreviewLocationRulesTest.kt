package com.trail.app.domain

import org.junit.Assert.*
import org.junit.Test

class PreviewLocationRulesTest {
    private fun point(time: Long, accuracy: Float) = LocationSample(1.3, 103.8, 0, time, accuracy, null, null)
    @Test fun indoorNetworkLocationCanDisplayWithoutPassingRecordingQuality() {
        val fix = point(1_000, 500f)
        assertTrue(PreviewLocationRules.shouldReplace(null, fix, 2_000))
        assertFalse(RecordingRules.precise(fix))
    }
    @Test fun cacheExpiresAndFreshGpsIsNotOverwrittenByCoarseNetwork() {
        assertFalse(PreviewLocationRules.shouldReplace(null, point(0, 20f), 300_001))
        assertFalse(PreviewLocationRules.shouldReplace(null, point(2_000, 20f), 1_000))
        assertFalse(PreviewLocationRules.shouldReplace(point(1_000, 5f), point(2_000, 500f), 3_000))
        assertTrue(PreviewLocationRules.shouldReplace(point(1_000, 5f), point(32_000, 500f), 33_000))
        assertTrue(PreviewLocationRules.shouldReplace(point(1_000, 500f), point(2_000, 5f), 3_000))
    }
}
