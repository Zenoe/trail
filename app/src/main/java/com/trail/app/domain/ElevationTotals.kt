package com.trail.app.domain

import kotlin.math.abs

/** 5 米死区抑制小幅高程噪声；缓慢爬升会相对锚点累积，不因单步不足 5 米而全部丢失。 */
data class ElevationTotals(val ascentM: Double? = null, val descentM: Double? = null, val anchorM: Double? = null) {
    fun next(elevation: Double?): ElevationTotals {
        if (elevation == null || !elevation.isFinite()) return copy(anchorM = null)
        if (anchorM == null) return copy(anchorM = elevation)
        val delta = elevation - anchorM
        if (abs(delta) < 5.0) return copy(ascentM = ascentM ?: 0.0, descentM = descentM ?: 0.0)
        return copy(ascentM = (ascentM ?: 0.0) + delta.coerceAtLeast(0.0),
            descentM = (descentM ?: 0.0) + (-delta).coerceAtLeast(0.0), anchorM = elevation)
    }
}
