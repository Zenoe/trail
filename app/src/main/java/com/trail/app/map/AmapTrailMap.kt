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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.trail.app.BuildConfig
import com.trail.app.data.PointEntity
import com.trail.app.data.WaypointEntity
import com.trail.app.domain.LocationSample

/** 高德底图适配层：数据库与 GPX 始终保存 WGS84，仅绘制和相机定位时转换。 */
@Composable
internal fun AmapTrailMap(points: List<PointEntity>, fitRequest: Int, zoomRequest: Int, zoomIn: Boolean,
    centerLocationRequest: Int, modifier: Modifier, reference: List<PointEntity>,
    waypoints: List<WaypointEntity>, current: LocationSample?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember {
        // 必须在创建 SDK 地图视图之前声明用户已看见并同意高德隐私政策；入口在地图选择对话框。
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        MapsInitializer.setApiKey(BuildConfig.AMAP_KEY)
        MapView(context).apply { onCreate(Bundle()) }
    }
    val map = remember(view) { view.map.apply {
        uiSettings.isZoomControlsEnabled = false
        uiSettings.isCompassEnabled = false
        uiSettings.isMyLocationButtonEnabled = false
    } }
    var width by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    var markerScreen by remember { mutableStateOf<Offset?>(null) }
    var cameraBearing by remember { mutableFloatStateOf(0f) }
    val converter = remember(context) { CoordinateConverter(context).from(CoordinateConverter.CoordType.GPS) }
    fun display(lat: Double, lon: Double): LatLng {
        val raw = LatLng(lat, lon)
        return if (CoordinateConverter.isAMapDataAvailable(lat, lon)) converter.coord(raw).convert() ?: raw else raw
    }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_RESUME -> view.onResume()
            Lifecycle.Event.ON_PAUSE -> view.onPause()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose { lifecycle.removeObserver(observer); view.onPause(); view.onDestroy() }
    }
    // 轨迹线按原分段绘制，拒绝点不连线；高德覆盖物不反向写入轨迹数据。
    LaunchedEffect(map, points, reference, waypoints) {
        map.clear()
        fun drawPath(path: List<PointEntity>, color: Int, widthPx: Float) {
            MapGeometry.lines(path).forEach { line ->
                map.addPolyline(PolylineOptions().addAll(line.map { display(it.lat, it.lon) })
                    .color(color).width(widthPx))
            }
        }
        drawPath(reference, android.graphics.Color.rgb(116, 80, 162), 7f)
        drawPath(points, android.graphics.Color.WHITE, 12f)
        drawPath(points, android.graphics.Color.rgb(23, 99, 75), 7f)
        listOf(points, reference).forEach { path ->
            path.firstOrNull { it.accepted }?.let {
                map.addMarker(MarkerOptions().position(display(it.latitude, it.longitude)).title("起点"))
            }
            path.lastOrNull { it.accepted }?.let {
                map.addMarker(MarkerOptions().position(display(it.latitude, it.longitude)).title("终点"))
            }
        }
        waypoints.forEach { map.addMarker(MarkerOptions().position(display(it.latitude, it.longitude)).title(it.name ?: "标记")) }
    }
    var appliedZoom by remember { mutableIntStateOf(zoomRequest) }
    LaunchedEffect(map, zoomRequest) {
        if (zoomRequest != appliedZoom) {
            appliedZoom = zoomRequest
            map.animateCamera(if (zoomIn) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut())
        }
    }
    var appliedCenter by remember { mutableIntStateOf(centerLocationRequest) }
    LaunchedEffect(map, centerLocationRequest) {
        if (centerLocationRequest != appliedCenter && current != null) {
            appliedCenter = centerLocationRequest
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(display(current.latitude, current.longitude), 16f))
        }
    }
    var centeredOnLocation by remember { mutableStateOf(false) }
    var appliedFit by remember { mutableIntStateOf(fitRequest) }
    LaunchedEffect(map, points.firstOrNull()?.segmentId, reference.firstOrNull()?.segmentId,
        waypoints.firstOrNull()?.id, fitRequest, width, height) {
        if (width == 0 || height == 0) return@LaunchedEffect
        val coordinates = (points + reference).filter { it.accepted }.map { display(it.latitude, it.longitude) } +
            waypoints.map { display(it.latitude, it.longitude) }
        if (coordinates.isNotEmpty()) {
            val bounds = LatLngBounds.builder().apply { coordinates.forEach(::include) }.build()
            map.moveCamera(if (coordinates.size == 1) CameraUpdateFactory.newLatLngZoom(coordinates.first(), 16f)
                else CameraUpdateFactory.newLatLngBounds(bounds, width, height, 48))
            appliedFit = fitRequest
        } else if (current != null && (!centeredOnLocation || fitRequest != appliedFit)) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(display(current.latitude, current.longitude), 16f))
            centeredOnLocation = true
            appliedFit = fitRequest
        }
    }
    DisposableEffect(map, current?.latitude, current?.longitude, width, height) {
        fun project() {
            cameraBearing = map.cameraPosition.bearing
            markerScreen = if (current == null || width == 0 || height == 0) null else
                map.projection.toScreenLocation(display(current.latitude, current.longitude)).let { Offset(it.x.toFloat(), it.y.toFloat()) }
        }
        val listener = object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition) = project()
            override fun onCameraChangeFinish(position: CameraPosition) = project()
        }
        map.addOnCameraChangeListener(listener)
        project()
        onDispose { map.removeOnCameraChangeListener(listener) }
    }
    Box(modifier.onSizeChanged { width = it.width; height = it.height }) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val at = markerScreen ?: return@Canvas
            // 与空白/OSM 地图保持相同的屏幕像素箭头，避免经纬度图标随缩放忽大忽小。
            scale(0.5f, 0.5f, at) {
                val radius = 11.dp.toPx()
                drawCircle(Color.White, radius + 3.dp.toPx(), at)
                drawCircle(Color(0xFF157CB4), radius, at)
                val bearing = current?.bearingDeg
                if (bearing != null) rotate(bearing - cameraBearing, at) {
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
