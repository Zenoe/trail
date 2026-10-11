package com.trail.app.ui

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CompactLengthTest {
    @Test fun switchesUnitsBeforeRoundingOverflowsTheCompactDisplay() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("—", compactLength(null))
            assertEquals("0 m", compactLength(0.0))
            assertEquals("999 m", compactLength(999.0))
            assertEquals("1.0 km", compactLength(999.5))
            assertEquals("12.3 km", compactLength(12345.0))
            assertEquals("100 km", compactLength(99950.0))
        } finally { Locale.setDefault(old) }
    }
}
