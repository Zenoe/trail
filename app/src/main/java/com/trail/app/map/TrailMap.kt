package com.trail.app.map

import android.os.Bundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.trail.app.domain.LocationSample
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trail.app.data.PointEntity
import com.trail.app.data.WaypointEntity
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*

@Composable
fun TrailMap(points: List<PointEntity>, fitRequest: Int, zoomRequest: Int = 0, zoomIn: Boolean = true,
    centerLocationRequest: Int = 0, modifier: Modifier = Modifier,
    reference: List<PointEntity> = emptyList(), waypoints: List<WaypointEntity> = emptyList(), online: Boolean = false,
    current: LocationSample? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember { MapLibre.getInstance(context); MapView(context).apply { onCreate(Bundle()) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loaded by remember { mutableIntStateOf(0) }
    var appliedZoomRequest by remember { mutableIntStateOf(zoomRequest) }
    var markerScreen by remember { mutableStateOf<Offset?>(null) }
    var cameraBearing by remember { mutableFloatStateOf(0f) }
    var mapWidth by remember { mutableIntStateOf(0) }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> view.onStart(); Lifecycle.Event.ON_RESUME -> view.onResume()
            Lifecycle.Event.ON_PAUSE -> view.onPause(); Lifecycle.Event.ON_STOP -> view.onStop(); else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        view.getMapAsync { map = it }
        onDispose { lifecycle.removeObserver(observer); view.onPause(); view.onStop(); view.onDestroy() }
    }
    LaunchedEffect(map, online) {
        loaded = 0
        map?.let { ready ->
            // MapLibre 默认署名点会弹出 SDK 信息；本应用在地图外直接展示底图来源，避免误触。
            ready.uiSettings.isAttributionEnabled = false
            ready.uiSettings.isLogoEnabled = false
            ready.uiSettings.isCompassEnabled = false
            ready.setStyle(Style.Builder().fromJson(if (online) MapSource.OSM_STYLE else MapSource.LOCAL_STYLE)) { style ->
                listOf("track", "reference", "markers", "starts", "ends").forEach { style.addSource(GeoJsonSource(it, FeatureCollection.fromFeatures(emptyArray<Feature>()))) }
                // 当前 MapLibre/SwiftShader 组合在空 GeoJSON 源后追加虚线时仅绘制端点；实线保证参考几何可见。
                style.addLayer(LineLayer("reference-line", "reference").withProperties(lineColor("#7450A2"), lineWidth(4f)))
                style.addLayer(LineLayer("track-halo", "track").withProperties(lineColor("#FFFFFF"), lineWidth(7f)))
                style.addLayer(LineLayer("track-line", "track").withProperties(lineColor("#17634B"), lineWidth(4f)))
                style.addLayer(CircleLayer("markers-dot", "markers").withProperties(circleColor("#C77822"), circleRadius(6f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
                style.addLayer(CircleLayer("start-dot", "starts").withProperties(circleColor("#17634B"), circleRadius(7f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
                style.addLayer(CircleLayer("end-dot", "ends").withProperties(circleColor("#A7472D"), circleRadius(5f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
                loaded++
            }
        }
    }
    fun features(input: List<PointEntity>) = FeatureCollection.fromFeatures(MapGeometry.lines(input).map { line ->
        Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it.lon, it.lat) }))
    })
    LaunchedEffect(points, reference, waypoints, loaded) {
        if (loaded == 0) return@LaunchedEffect
        map?.style?.getSourceAs<GeoJsonSource>("track")?.setGeoJson(features(points))
        map?.style?.getSourceAs<GeoJsonSource>("reference")?.setGeoJson(features(reference))
        val markers = mutableListOf<Feature>()
        val starts = mutableListOf<Feature>()
        val ends = mutableListOf<Feature>()
        listOf(points, reference).forEach { path ->
            path.firstOrNull { it.accepted }?.let { starts.add(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude.coerceIn(-85.051129, 85.051129)))) }
            path.lastOrNull { it.accepted }?.let { ends.add(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude.coerceIn(-85.051129, 85.051129)))) }
        }
        waypoints.forEach { markers.add(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude.coerceIn(-85.051129, 85.051129)))) }
        map?.style?.getSourceAs<GeoJsonSource>("markers")?.setGeoJson(FeatureCollection.fromFeatures(markers))
        map?.style?.getSourceAs<GeoJsonSource>("starts")?.setGeoJson(FeatureCollection.fromFeatures(starts))
        map?.style?.getSourceAs<GeoJsonSource>("ends")?.setGeoJson(FeatureCollection.fromFeatures(ends))
    }
    // 用数据身份触发初次全程视图；每个新 GPS 点不会打断用户平移缩放。
    LaunchedEffect(points.firstOrNull()?.segmentId, reference.firstOrNull()?.segmentId, waypoints.firstOrNull()?.id, fitRequest, loaded) {
        if (loaded == 0) return@LaunchedEffect
        val coordinates = (points + reference).filter { it.accepted }.map { MapCoordinate(it.latitude, it.longitude) } +
            waypoints.map { MapCoordinate(it.latitude, it.longitude) }
        val extent = MapGeometry.extent(coordinates) ?: return@LaunchedEffect
        if (extent.north == extent.south && extent.east == extent.west) map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(extent.north, extent.west), 16.0))
        else map?.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(extent.north, extent.east, extent.south, extent.west), 50))
    }
    LaunchedEffect(zoomRequest, loaded) {
        if (loaded > 0 && zoomRequest != appliedZoomRequest) {
            appliedZoomRequest = zoomRequest
            map?.animateCamera(if (zoomIn) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut())
        }
    }
    var appliedCenterRequest by remember { mutableIntStateOf(centerLocationRequest) }
    LaunchedEffect(centerLocationRequest, current?.elapsedMs, loaded) {
        if (loaded > 0 && centerLocationRequest != appliedCenterRequest && current != null) {
            appliedCenterRequest = centerLocationRequest
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(current.latitude, current.longitude), 16.0))
        }
    }
    var centeredOnLocation by remember { mutableStateOf(false) }
    var appliedLocationFit by remember { mutableIntStateOf(fitRequest) }
    LaunchedEffect(current?.elapsedMs, loaded, fitRequest) {
        if (loaded == 0 || current == null || points.any { it.accepted } || reference.any { it.accepted } || waypoints.isNotEmpty()) return@LaunchedEffect
        if (!centeredOnLocation || fitRequest != appliedLocationFit) {
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(current.latitude, current.longitude), 16.0))
            centeredOnLocation = true
            appliedLocationFit = fitRequest
        }
    }
    DisposableEffect(map, current?.elapsedMs, mapWidth) {
        val ready = map
        fun project() {
            cameraBearing = ready?.cameraPosition?.bearing?.toFloat() ?: 0f
            markerScreen = if (ready == null || current == null || mapWidth == 0) null else
                ready.projection.toScreenLocation(LatLng(current.latitude.coerceIn(-85.051129, 85.051129), current.longitude))
                    .let { Offset(it.x, it.y) }
        }
        val listener = MapLibreMap.OnCameraMoveListener { project() }
        ready?.addOnCameraMoveListener(listener)
        project()
        onDispose { ready?.removeOnCameraMoveListener(listener) }
    }
    Box(modifier.onSizeChanged { mapWidth = it.width }) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val at = markerScreen ?: return@Canvas
            // 屏幕像素叠层保持定位箭头恒定可读；投影只用于显示，不改动 WGS84 轨迹点。
            // 以定位坐标为中心整体缩放，圆点、白边和箭头同比例缩小，避免偏离真实位置。
            scale(0.5f, 0.5f, at) {
                val radius = 11.dp.toPx()
                drawCircle(Color.White, radius + 3.dp.toPx(), at)
                drawCircle(Color(0xFF157CB4), radius, at)
                val bearing = current?.bearingDeg
                if (bearing != null) rotate(bearing - cameraBearing, at) {
                    // 箭头尖端离开圆点，保持朝向明确；白边在深色卫星底图上仍可辨认。
                    val arrow = Path().apply {
                        moveTo(at.x, at.y - 31.dp.toPx())
                        lineTo(at.x - 9.dp.toPx(), at.y - 10.dp.toPx())
                        lineTo(at.x + 9.dp.toPx(), at.y - 10.dp.toPx())
                        close()
                    }
                    drawPath(arrow, Color.White, style = Stroke(width = 2.dp.toPx()))
                    drawPath(arrow, Color(0xFF157CB4))
                } else drawCircle(Color.White, 3.dp.toPx(), at)
            }
        }
    }
}
