package com.trail.app.map

import android.content.Context
import android.os.Build
import com.trail.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.File
import java.io.IOException

enum class MapProvider(val label: String) { EMPTY("空白"), OSM("OpenStreetMap"), AMAP("高德地图") }

/** 地图选择只改变渲染源；轨迹仍以 WGS84 存储。 */
class MapSource(context: Context) {
    private val prefs = context.getSharedPreferences("maps", Context.MODE_PRIVATE)
    val amapConsent = MutableStateFlow(prefs.getBoolean("amapConsent", false))
    val amapKeyConfigured = BuildConfig.AMAP_KEY.isNotBlank()
    // 当前高德地图 SDK 只提供 ARM 原生库；x86 模拟器不能创建高德 MapView。
    val amapDeviceSupported = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" }
    val provider = MutableStateFlow(runCatching { MapProvider.valueOf(prefs.getString("provider", "") ?: "") }
        .getOrElse { if (prefs.getBoolean("osmOnline", false)) MapProvider.OSM else MapProvider.EMPTY }
        .let { if (it == MapProvider.AMAP && (!amapConsent.value || !amapKeyConfigured || !amapDeviceSupported)) MapProvider.EMPTY else it })
    val status = MutableStateFlow("在线底图等待加载")
    init {
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "map-http"), 64L * 1024 * 1024))
            .addInterceptor { chain ->
                val request = chain.request().newBuilder().header("User-Agent", "Trail-Android/0.2 (com.trail.app)")
                    .header("X-Requested-With", "com.trail.app").build()
                try {
                    chain.proceed(request).also { if (request.url.host == "tile.openstreetmap.org")
                        status.value = if (it.isSuccessful) "OpenStreetMap 在线底图" else "部分底图不可用 · 轨迹仍可查看" }
                } catch (failure: IOException) { status.value = "底图加载失败 · 轨迹仍可查看"; throw failure }
            }.build())
    }
    fun setProvider(value: MapProvider) { prefs.edit().putString("provider", value.name).apply(); provider.value = value }
    fun approveAmap() { prefs.edit().putBoolean("amapConsent", true).apply(); amapConsent.value = true }
    companion object {
        const val LOCAL_STYLE = """{"version":8,"name":"Trail local","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#E4EADB"}}]}"""
        const val OSM_STYLE = """{"version":8,"name":"OpenStreetMap","sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#E4EADB"}},{"id":"osm","type":"raster","source":"osm"}]}"""
    }
}
