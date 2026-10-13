package com.trail.app.map

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.min

/** 小范围轨迹采用等距近似；经度按中心展开，跨日期线时不会横跨整个画布。 */
internal class TrackProjection(extent: MapExtent, width: Float, height: Float, zoom: Float,
    private val pan: Offset, centerOverride: MapCoordinate? = null) {
    private val centerLat = centerOverride?.lat ?: (extent.north + extent.south) / 2
    private val centerLon = centerOverride?.lon ?: (extent.west + extent.east) / 2
    private val longitudeFactor = cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)
    private val latitudeSpan = (extent.north - extent.south).coerceAtLeast(0.002)
    private val longitudeSpan = ((extent.east - extent.west) * longitudeFactor).coerceAtLeast(0.002)
    private val scale = min((width - 48f).coerceAtLeast(1f) / longitudeSpan,
        (height - 48f).coerceAtLeast(1f) / latitudeSpan) * zoom
    private val centerX = width / 2f
    private val centerY = height / 2f

    fun project(coordinate: MapCoordinate): Offset {
        val deltaLon = ((coordinate.lon - centerLon + 540) % 360 + 360) % 360 - 180
        return Offset(centerX + pan.x + (deltaLon * longitudeFactor * scale).toFloat(),
            centerY + pan.y - ((coordinate.lat - centerLat) * scale).toFloat())
    }
}
