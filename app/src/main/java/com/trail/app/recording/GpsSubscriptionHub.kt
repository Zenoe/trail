package com.trail.app.recording

import com.trail.app.domain.LocationSample

/** 同一进程只维持一份 GPS 订阅；调用方均在主线程，录制服务和前台位置预览独立消费。 */
class GpsSubscriptionHub(private val source: LocationSource) {
    private data class Observer(val fix: (LocationSample) -> Unit, val unavailable: (String) -> Unit)
    private val observers = mutableMapOf<Any, Observer>()

    fun handle(): LocationSource = object : LocationSource {
        private val key = Any()
        override fun start(onSample: (LocationSample) -> Unit, onUnavailable: (String) -> Unit) {
            stop()
            val first = observers.isEmpty()
            observers[key] = Observer(onSample, onUnavailable)
            if (first) try {
                source.start({ sample -> observers.values.toList().forEach { it.fix(sample) } },
                    { message -> observers.values.toList().forEach { it.unavailable(message) } })
            } catch (failure: Exception) {
                observers.remove(key)
                source.stop()
                throw failure
            }
        }
        override fun stop() {
            if (observers.remove(key) != null && observers.isEmpty()) source.stop()
        }
    }
}
