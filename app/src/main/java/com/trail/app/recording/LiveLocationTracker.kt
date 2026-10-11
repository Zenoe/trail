package com.trail.app.recording

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.Bundle
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.view.Surface
import android.view.WindowManager
import android.os.SystemClock
import com.trail.app.domain.LocationSample
import com.trail.app.domain.PreviewLocationRules
import kotlinx.coroutines.flow.MutableStateFlow

/** 只供前台地图观察当前位置；不创建录制会话，也不写数据库。 */
class LiveLocationTracker(private val context: Context, private val source: LocationSource) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val locations = context.getSystemService(LocationManager::class.java)
    private var networkListener: LocationListener? = null
    private val windows = context.getSystemService(WindowManager::class.java)
    val current = MutableStateFlow<LocationSample?>(null)
    val heading = MutableStateFlow<Float?>(null)
    val status = MutableStateFlow<String?>(null)
    private var running = false
    private var sensorListener: SensorEventListener? = null
    private var lastHeadingPublishedMs = 0L

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        current.value = null
        status.value = "正在确定当前位置…"
        // 朝向来自传感器，与卫星是否定位成功无关。
        startCompass()
        try {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                source.start(::accept, { status.value = it })
            // 地图预览可用网络位置和近期缓存；这些样本绝不进入录制服务。
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                if (locations.allProviders.contains(provider)) runCatching { locations.getLastKnownLocation(provider) }.getOrNull()?.let(::acceptPreview)
            }
            if (locations.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) = acceptPreview(location)
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                    @Deprecated("Older Android callback")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                networkListener = listener
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5_000L, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            status.value = "需要精确定位权限才能显示当前位置"
        }
    }

    fun stop() {
        running = false
        source.stop()
        networkListener?.let(locations::removeUpdates)
        networkListener = null
        sensorListener?.let(sensors::unregisterListener)
        sensorListener = null
        heading.value = null
        current.value = null
    }

    @Suppress("DEPRECATION")
    private fun startCompass() {
        val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) ?: return
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                if (!running) return
                val raw = FloatArray(9)
                val remapped = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(raw, event.values)
                val axes = when (windows.defaultDisplay.rotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(raw, axes.first, axes.second, remapped)
                val magnetic = Math.toDegrees(SensorManager.getOrientation(remapped, FloatArray(3))[0].toDouble()).toFloat()
                // 罗盘基于磁北，叠加当地磁偏角后才与地图/GPS 的真北航向一致。
                val fix = current.value
                val declination = if (fix == null) 0f else GeomagneticField(fix.latitude.toFloat(), fix.longitude.toFloat(),
                    fix.altitudeM?.toFloat() ?: 0f, System.currentTimeMillis()).declination
                val trueNorth = (magnetic + declination + 360f) % 360f
                val now = SystemClock.elapsedRealtime()
                if (now - lastHeadingPublishedMs >= 200) {
                    // 角度在 0/360 度处也走最短弧，降低罗盘噪声带来的箭头抖动。
                    val old = heading.value
                    val delta = if (old == null) 0f else (trueNorth - old + 540f) % 360f - 180f
                    heading.value = if (old == null) trueNorth else (old + delta * 0.35f + 360f) % 360f
                    lastHeadingPublishedMs = now
                }
            }
        }
        sensorListener = listener
        sensors.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
    }

    private fun acceptPreview(location: Location) = accept(LocationSample(location.latitude, location.longitude,
        location.time, location.elapsedRealtimeNanos / 1_000_000,
        if (location.hasAccuracy()) location.accuracy else null,
        if (location.hasAltitude()) location.altitude else null, null))

    private fun accept(sample: LocationSample) {
        if (!running || !PreviewLocationRules.shouldReplace(current.value, sample, SystemClock.elapsedRealtime())) return
        val age = SystemClock.elapsedRealtime() - sample.elapsedMs
        current.value = sample
        status.value = if (age > 30_000) "最近位置 · 等待更新" else if (sample.accuracyM!! > 50f) "大致位置 · 精度约 ${sample.accuracyM.toInt()} 米" else null
    }
}
