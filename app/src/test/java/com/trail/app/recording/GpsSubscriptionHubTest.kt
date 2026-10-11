package com.trail.app.recording

import com.trail.app.domain.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsSubscriptionHubTest {
    private class FakeSource : LocationSource {
        var starts = 0
        var stops = 0
        var sample: ((LocationSample) -> Unit)? = null
        override fun start(onSample: (LocationSample) -> Unit, onUnavailable: (String) -> Unit) {
            starts++
            sample = onSample
        }
        override fun stop() { stops++; sample = null }
    }

    @Test fun foregroundAndRecorderShareOneSubscription() {
        val fake = FakeSource()
        val hub = GpsSubscriptionHub(fake)
        val preview = hub.handle()
        val recorder = hub.handle()
        var displayed = 0
        var saved = 0
        preview.start({ displayed++ }, {})
        recorder.start({ saved++ }, {})
        assertEquals(1, fake.starts)
        fake.sample!!(LocationSample(1.0, 2.0, 1, 1, 5f, null, null))
        assertEquals(1, displayed)
        assertEquals(1, saved)
        preview.stop()
        assertEquals(0, fake.stops)
        fake.sample!!(LocationSample(1.0, 2.0, 2, 2, 5f, null, null))
        assertEquals(1, displayed)
        assertEquals(2, saved)
        recorder.stop()
        assertEquals(1, fake.stops)
    }
}
