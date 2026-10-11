package com.trail.app.domain

import kotlin.math.*

enum class RecordingState { WAITING, RECORDING, PAUSED, INTERRUPTED, FINISHED }
enum class ActivityType(val label: String) { HIKE("徒步"), WALK("步行"), RUN("跑步"), BIKE("骑行") }

data class LocationSample(
    val latitude: Double, val longitude: Double, val wallTimeMs: Long,
    val elapsedMs: Long, val accuracyM: Float?, val altitudeM: Double?, val speedMps: Float?,
    val bearingDeg: Float? = null,
)

/** 纯计算层不依赖 Android：时间以单调时钟计，坐标始终是 WGS84。 */
object RecordingRules {
    const val SIGNAL_TIMEOUT_MS = 30_000L
    fun valid(sample: LocationSample) = sample.latitude.isFinite() && sample.longitude.isFinite() &&
        sample.latitude in -90.0..90.0 && sample.longitude in -180.0..180.0
    fun precise(sample: LocationSample) = valid(sample) &&
        sample.accuracyM?.let { it.isFinite() && it in 0f..50f } == true

    /** 起点先等两次邻近定位；单个“自称 5 米精度”的冷启动跳点仍不可信。 */
    fun stablePair(previous: LocationSample, next: LocationSample): Boolean {
        if (!precise(previous) || !precise(next)) return false
        if (previous.accuracyM!! > 25f || next.accuracyM!! > 25f) return false
        val dt = next.elapsedMs - previous.elapsedMs
        if (dt !in 1..15_000) return false
        val limit = max(15.0, 1.5 * max(previous.accuracyM.toDouble(), next.accuracyM.toDouble()))
        return distance(previous.latitude, previous.longitude, next.latitude, next.longitude) <= limit
    }

    /** 拒绝远超活动速度的瞬移；精度半径仅作为容差，不把滤波后的坐标写回原始数据。 */
    fun plausible(previous: LocationSample, next: LocationSample, activity: ActivityType): Boolean {
        if (!precise(next)) return false
        val dt = (next.elapsedMs - previous.elapsedMs).coerceAtLeast(0) / 1_000.0
        val maxSpeed = when (activity) { ActivityType.BIKE -> 25.0; ActivityType.RUN -> 12.0; else -> 8.0 }
        val tolerance = max(previous.accuracyM?.toDouble() ?: 0.0, next.accuracyM!!.toDouble()).coerceAtMost(20.0)
        return distance(previous.latitude, previous.longitude, next.latitude, next.longitude) <= max(20.0, maxSpeed * dt + tolerance)
    }

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
