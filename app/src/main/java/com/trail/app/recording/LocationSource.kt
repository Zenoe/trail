package com.trail.app.recording

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.trail.app.domain.LocationSample

interface LocationSource {
    fun start(onSample: (LocationSample) -> Unit, onUnavailable: (String) -> Unit)
    fun stop()
}

/** 平台 GPS 不依赖 Google Play 服务；权限由 Activity 按需申请，Service 仍须处理撤销。 */
class AndroidLocationSource(context: Context) : LocationSource {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission")
    override fun start(onSample: (LocationSample) -> Unit, onUnavailable: (String) -> Unit) {
        stop()
        if (!manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            onUnavailable("设备没有 GPS 定位提供器")
            return
        }
        val callbacks = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onSample(LocationSample(location.latitude, location.longitude, location.time,
                    location.elapsedRealtimeNanos / 1_000_000,
                    if (location.hasAccuracy()) location.accuracy else null,
                    if (location.hasAltitude()) location.altitude else null,
                    if (location.hasSpeed()) location.speed else null))
            }
            override fun onProviderDisabled(provider: String) { onUnavailable("GPS 已关闭，请在系统设置中开启") }
            override fun onProviderEnabled(provider: String) { onUnavailable("等待 GPS 定位，请到室外开阔处") }
            @Deprecated("Required for older Android location callbacks")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = callbacks
        // 必须持续收到静止时的精度与时间；5m 门槛会把“站着不动”误判为 GPS 失联。
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3_000L, 0f, callbacks, Looper.getMainLooper())
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) onUnavailable("GPS 已关闭，请在系统设置中开启")
    }

    override fun stop() {
        listener?.let { manager.removeUpdates(it) }
        listener = null
    }
}
