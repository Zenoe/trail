package com.trail.app.exchange

/** GPX 解析器只接受流，不依赖 Android URI、Room 或 UI，便于测试和复用导出。 */
data class GpxLimits(val bytes: Long = 100L * 1024 * 1024, val points: Int = 1_000_000,
    val depth: Int = 64, val elementChars: Int = 1_048_576, val paths: Int = 10_000,
    val segments: Int = 10_000, val waypoints: Int = 10_000)

data class GpxOwner(val type: String, val path: Int = -1, val segment: Int = -1, val point: Int = -1)
data class GpxPoint(val lat: Double, val lon: Double, val elevation: Double?, val time: Long?,
    val timeLexical: String?, val name: String?, val symbol: String?, val extraXml: String)

sealed interface GpxEvent {
    data class Header(val version: String, val creator: String?) : GpxEvent
    data class PathStart(val index: Int, val kind: String) : GpxEvent
    data class SegmentStart(val path: Int, val index: Int) : GpxEvent
    data class Position(val owner: GpxOwner, val value: GpxPoint) : GpxEvent
    data class Element(val owner: GpxOwner, val name: String, val value: String, val xml: String) : GpxEvent
}

data class GpxPathPreview(val index: Int, val kind: String, val name: String,
    val points: Int, val segments: Int, val distanceM: Double, val elapsedMs: Long?, val warnings: List<String>)
data class GpxPreview(val version: String, val creator: String?, val paths: List<GpxPathPreview>,
    val waypointCount: Int, val warnings: List<String>)
class GpxException(message: String, cause: Throwable? = null) : Exception(message, cause)
