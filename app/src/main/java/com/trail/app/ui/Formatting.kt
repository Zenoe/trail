package com.trail.app.ui

import java.util.Locale

/** 舍入边界提前切换单位，避免出现 1000 m 或 100.0 km 撑宽记录顶栏。 */
fun compactLength(meters: Double?): String {
    if (meters == null || !meters.isFinite() || meters < 0) return "—"
    val (value, unit) = when {
        meters < 999.5 -> meters to "m"
        meters < 999_500 -> meters / 1_000 to "km"
        else -> meters / 1_000_000 to "Mm"
    }
    val format = if (unit == "m" || value >= 99.95) "%.0f %s" else "%.1f %s"
    return String.format(Locale.getDefault(), format, value, unit)
}

fun distanceText(meters: Double) = String.format(Locale.getDefault(), "%.2f", meters / 1_000)
fun durationText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
}
fun speedText(distanceM: Double, durationMs: Long) = if (durationMs <= 0) "—"
    else String.format(Locale.getDefault(), "%.1f", distanceM / durationMs * 3_600)
fun paceText(distanceM: Double, durationMs: Long): String {
    if (distanceM < 1 || durationMs <= 0) return "—"
    val secondsPerKm = (durationMs / distanceM).toLong()
    return "%d′%02d″".format(secondsPerKm / 60, secondsPerKm % 60)
}
