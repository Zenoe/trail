package com.trail.app.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trail.app.data.PointEntity
import com.trail.app.data.WaypointEntity
import com.trail.app.domain.LocationSample

/** 无底图轨迹画布：不依赖地图 SDK、不访问网络，也不修改 WGS84 原始点。 */
@Composable
fun TrailMap(points: List<PointEntity>, fitRequest: Int, zoomRequest: Int = 0, zoomIn: Boolean = true,
    centerLocationRequest: Int = 0, modifier: Modifier = Modifier,
    reference: List<PointEntity> = emptyList(), waypoints: List<WaypointEntity> = emptyList(),
    current: LocationSample? = null) {
    val trackCoordinates = remember(points, reference, waypoints) {
        (points + reference).filter { it.accepted }.map { MapCoordinate(it.latitude, it.longitude) } +
            waypoints.map { MapCoordinate(it.latitude, it.longitude) }
    }
    var idleAnchor by remember { mutableStateOf<MapCoordinate?>(null) }
    LaunchedEffect(trackCoordinates.isEmpty(), current?.latitude, current?.longitude) {
        // 尚无轨迹时只取首个位置作视图锚点；每次 GPS 更新不能把用户手动平移的视角拉回去。
        if (trackCoordinates.isNotEmpty()) idleAnchor = null
        else if (idleAnchor == null && current != null) idleAnchor = MapCoordinate(current.latitude, current.longitude)
    }
    val extent = remember(trackCoordinates, idleAnchor) {
        MapGeometry.extent(trackCoordinates.ifEmpty {
            idleAnchor?.let(::listOf).orEmpty()
        }) ?: MapExtent(0.0, 0.0, 0.0, 0.0)
    }
    var width by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var centerOverride by remember { mutableStateOf<MapCoordinate?>(null) }
    var appliedZoom by remember { mutableIntStateOf(zoomRequest) }
    var appliedCenter by remember { mutableIntStateOf(centerLocationRequest) }
    // 仅换轨迹或点击“全程”时重置视角；后续 GPS 点不能反复抢走手势视角。
    LaunchedEffect(points.firstOrNull()?.segmentId, reference.firstOrNull()?.segmentId,
        waypoints.firstOrNull()?.id, fitRequest) {
        if (trackCoordinates.isEmpty() && current != null) idleAnchor = MapCoordinate(current.latitude, current.longitude)
        zoom = 1f; pan = Offset.Zero; centerOverride = null
    }
    LaunchedEffect(zoomRequest) {
        if (zoomRequest != appliedZoom) {
            appliedZoom = zoomRequest
            zoom = (zoom * if (zoomIn) 1.5f else 1f / 1.5f).coerceIn(0.5f, 64f)
        }
    }
    LaunchedEffect(centerLocationRequest) {
        if (centerLocationRequest != appliedCenter && current != null) {
            appliedCenter = centerLocationRequest
            centerOverride = MapCoordinate(current.latitude, current.longitude)
            pan = Offset.Zero
        }
    }
    val lines = remember(points, reference) { MapGeometry.lines(points) to MapGeometry.lines(reference) }
    Canvas(modifier.fillMaxSize().onSizeChanged { width = it.width; height = it.height }
        .semantics { contentDescription = "无底图轨迹视图" }
        .pointerInput(Unit) {
            detectTransformGestures { _, gesturePan, gestureZoom, _ ->
                zoom = (zoom * gestureZoom).coerceIn(0.5f, 64f)
                pan += gesturePan
            }
        }) {
        drawRect(Color(0xFFE4EADB))
        if (width == 0 || height == 0) return@Canvas
        val projection = TrackProjection(extent, size.width, size.height, zoom, pan, centerOverride)
        fun drawLineSegments(segments: List<List<MapCoordinate>>, color: Color, stroke: Float) {
            segments.forEach { segment ->
                val path = Path()
                segment.forEachIndexed { index, coordinate ->
                    val at = projection.project(coordinate)
                    if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
                }
                drawPath(path, color, style = Stroke(width = stroke))
            }
        }
        drawLineSegments(lines.second, Color(0xFF7450A2), 4.dp.toPx())
        drawLineSegments(lines.first, Color.White, 7.dp.toPx())
        drawLineSegments(lines.first, Color(0xFF17634B), 4.dp.toPx())
        listOf(points, reference).forEach { path ->
            path.firstOrNull { it.accepted }?.let {
                val at = projection.project(MapCoordinate(it.latitude, it.longitude))
                drawCircle(Color.White, 9.dp.toPx(), at)
                drawCircle(Color(0xFF17634B), 7.dp.toPx(), at)
            }
            path.lastOrNull { it.accepted }?.let {
                val at = projection.project(MapCoordinate(it.latitude, it.longitude))
                drawCircle(Color.White, 7.dp.toPx(), at)
                drawCircle(Color(0xFFA7472D), 5.dp.toPx(), at)
            }
        }
        waypoints.forEach {
            val at = projection.project(MapCoordinate(it.latitude, it.longitude))
            drawCircle(Color.White, 8.dp.toPx(), at)
            drawCircle(Color(0xFFC77822), 6.dp.toPx(), at)
        }
        current?.let {
            val at = projection.project(MapCoordinate(it.latitude, it.longitude))
            drawCircle(Color.White, 9.dp.toPx(), at)
            drawCircle(Color(0xFF157CB4), 7.dp.toPx(), at)
            it.bearingDeg?.let { bearing -> rotate(bearing, at) {
                val arrow = Path().apply {
                    moveTo(at.x, at.y - 19.dp.toPx())
                    lineTo(at.x - 6.dp.toPx(), at.y - 7.dp.toPx())
                    lineTo(at.x + 6.dp.toPx(), at.y - 7.dp.toPx())
                    close()
                }
                drawPath(arrow, Color.White, style = Stroke(width = 2.dp.toPx()))
                drawPath(arrow, Color(0xFF157CB4))
            } }
        }
    }
}
