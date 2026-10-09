package com.trail.app.ui

import java.util.Locale

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
