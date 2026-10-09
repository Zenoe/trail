package com.trail.app.map

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
fun TrailMap(points: List<PointEntity>, fitRequest: Int, modifier: Modifier = Modifier,
    reference: List<PointEntity> = emptyList(), waypoints: List<WaypointEntity> = emptyList(), online: Boolean = false) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember { MapLibre.getInstance(context); MapView(context).apply { onCreate(Bundle()) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loaded by remember { mutableIntStateOf(0) }
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
            ready.uiSettings.isAttributionEnabled = online
            ready.uiSettings.isLogoEnabled = false
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
        val coordinates = (points + reference).filter { it.accepted }.map { MapCoordinate(it.latitude, it.longitude) } + waypoints.map { MapCoordinate(it.latitude, it.longitude) }
        val extent = MapGeometry.extent(coordinates) ?: return@LaunchedEffect
        if (extent.north == extent.south && extent.east == extent.west) map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(extent.north, extent.west), 16.0))
        else map?.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(extent.north, extent.east, extent.south, extent.west), 50))
    }
    AndroidView(factory = { view }, modifier = modifier)
}
