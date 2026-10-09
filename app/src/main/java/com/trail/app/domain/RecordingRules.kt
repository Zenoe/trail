package com.trail.app.domain

import kotlin.math.*

enum class RecordingState { WAITING, RECORDING, PAUSED, INTERRUPTED, FINISHED }
enum class ActivityType(val label: String) { HIKE("徒步"), WALK("步行"), RUN("跑步"), BIKE("骑行") }

data class LocationSample(
    val latitude: Double, val longitude: Double, val wallTimeMs: Long,
    val elapsedMs: Long, val accuracyM: Float?, val altitudeM: Double?, val speedMps: Float?,
)

/** 纯计算层不依赖 Android：时间以单调时钟计，坐标始终是 WGS84。 */
object RecordingRules {
    const val SIGNAL_TIMEOUT_MS = 30_000L
    fun valid(sample: LocationSample) = sample.latitude.isFinite() && sample.longitude.isFinite() &&
        sample.latitude in -90.0..90.0 && sample.longitude in -180.0..180.0
    fun precise(sample: LocationSample) = valid(sample) &&
        sample.accuracyM?.let { it.isFinite() && it in 0f..50f } == true

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6_371_008.8 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    // reboot 后 elapsedRealtime 会归零，因此只能在同一服务生命周期内累加。
    fun durationDelta(previousElapsedMs: Long?, nowElapsedMs: Long): Long =
        previousElapsedMs?.let { (nowElapsedMs - it).coerceAtLeast(0) } ?: 0

    fun canPause(state: RecordingState) = state == RecordingState.WAITING || state == RecordingState.RECORDING
    fun canResume(state: RecordingState) = state == RecordingState.PAUSED || state == RecordingState.INTERRUPTED
}
