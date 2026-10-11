package com.trail.app.domain

/** 预览允许低精度与近期缓存，记录仍使用 RecordingRules 的严格判定。 */
object PreviewLocationRules {
    const val MAX_AGE_MS = 300_000L
    fun shouldReplace(old: LocationSample?, sample: LocationSample, now: Long): Boolean {
        if (!RecordingRules.valid(sample) || now - sample.elapsedMs !in 0..MAX_AGE_MS) return false
        val accuracy = sample.accuracyM ?: return false
        if (!accuracy.isFinite() || accuracy < 0) return false
        if (old == null) return true
        if (sample.elapsedMs <= old.elapsedMs) return false
        return now - old.elapsedMs >= 30_000 || accuracy <= (old.accuracyM ?: Float.MAX_VALUE) * 2
    }
}
