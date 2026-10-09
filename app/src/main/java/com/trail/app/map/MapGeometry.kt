package com.trail.app.map

import com.trail.app.data.PointEntity
import kotlin.math.abs

data class MapCoordinate(val lat: Double, val lon: Double)
data class MapExtent(val north: Double, val east: Double, val south: Double, val west: Double)

object MapGeometry {
    fun lines(points: List<PointEntity>): List<List<MapCoordinate>> {
        val result = mutableListOf<List<MapCoordinate>>()
        var line = mutableListOf<MapCoordinate>()
        var segment: String? = null
        fun flush() { if (line.size >= 2) result.add(line); line = mutableListOf() }
        for (point in points) {
            if (segment != point.segmentId || !point.accepted) flush()
            segment = point.segmentId
            if (!point.accepted) continue
            val next = MapCoordinate(point.latitude.coerceIn(-85.051129, 85.051129), point.longitude)
            line.lastOrNull()?.let { previous ->
                // GeoJSON 跨 ±180° 会绕地球；只在渲染层插入交界点，不改写原始坐标。
                if (abs(next.lon - previous.lon) > 180) {
                    val unwrapped = if (next.lon > previous.lon) next.lon - 360 else next.lon + 360
                    val boundary = if (unwrapped > previous.lon) 180.0 else -180.0
                    if (unwrapped == previous.lon) flush() // +180 与 -180 是同一经线，避免除以零。
                    else {
                        val t = (boundary - previous.lon) / (unwrapped - previous.lon)
                        val lat = previous.lat + (next.lat - previous.lat) * t
                        line.add(MapCoordinate(lat, boundary)); flush()
                        line.add(MapCoordinate(lat, -boundary))
                    }
                }
            }
            line.add(next)
        }
        flush(); return result
    }
    fun extent(points: List<MapCoordinate>): MapExtent? {
        if (points.isEmpty()) return null
        val longitudes = points.map { ((it.lon % 360) + 360) % 360 }.sorted()
        var gapIndex = 0; var largestGap = -1.0
        for (i in longitudes.indices) {
            val next = if (i == longitudes.lastIndex) longitudes.first() + 360 else longitudes[i + 1]
            if (next - longitudes[i] > largestGap) { largestGap = next - longitudes[i]; gapIndex = i }
        }
        var west = longitudes[(gapIndex + 1) % longitudes.size]
        var east = longitudes[gapIndex]
        if (east < west) east += 360
        if (west > 180) { west -= 360; east -= 360 }
        return MapExtent(points.maxOf { it.lat }.coerceIn(-85.051129, 85.051129), east,
            points.minOf { it.lat }.coerceIn(-85.051129, 85.051129), west)
    }
}
