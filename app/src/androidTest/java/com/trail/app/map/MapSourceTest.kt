package com.trail.app.map

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class MapSourceTest {
    @Test fun legacyOnlinePreferenceMigratesAndNewChoicePersists() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "map-choice-test-${UUID.randomUUID()}"
        val scoped = object : ContextWrapper(base) {
            override fun getSharedPreferences(unused: String?, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try {
            base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putBoolean("osmOnline", true).commit()
            val source = MapSource(scoped)
            assertEquals(MapProvider.OSM, source.provider.value)
            source.setProvider(MapProvider.EMPTY)
            assertEquals(MapProvider.EMPTY, MapSource(scoped).provider.value)
        } finally { base.deleteSharedPreferences(name) }
    }
}
