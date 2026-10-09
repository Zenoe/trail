package com.trail.app.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.File
import java.io.IOException

/** 无账号的在线预览源；区域离线下载必须另选明确授权的服务，不能复用 OSM 公共瓦片。 */
class MapSource(context: Context) {
    private val prefs = context.getSharedPreferences("maps", Context.MODE_PRIVATE)
    val online = MutableStateFlow(prefs.getBoolean("osmOnline", false))
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
    fun setOnline(enabled: Boolean) { prefs.edit().putBoolean("osmOnline", enabled).apply(); online.value = enabled }
    companion object {
        const val LOCAL_STYLE = """{"version":8,"name":"Trail local","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#E4EADB"}}]}"""
        const val OSM_STYLE = """{"version":8,"name":"OpenStreetMap","sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#E4EADB"}},{"id":"osm","type":"raster","source":"osm"}]}"""
    }
}
