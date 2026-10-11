package com.trail.app.exchange

import com.trail.app.domain.RecordingRules
import java.io.FilterInputStream
import java.io.InputStream
import java.time.OffsetDateTime
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.*
import org.xml.sax.ext.DefaultHandler2

/** SAX 只持有当前点/元数据子树，点关闭即交给 sink，不把整条路线装入内存。 */
class GpxReader(private val limits: GpxLimits = GpxLimits()) {
    fun read(input: InputStream, checkCancelled: () -> Unit = {}, sink: (GpxEvent) -> Unit = {}): GpxPreview {
        val collector = PreviewCollector()
        val handler = Handler(limits, checkCancelled) { collector.accept(it); sink(it) }
        try {
            val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            // Android 与 JVM 的 SAX 都支持 lexical-handler。配置失败即拒绝，不能悄悄降级安全边界。
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            reader.entityResolver = EntityResolver { _, _ -> throw GpxException("不允许 XML 外部实体") }
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.parse(InputSource(LimitedStream(input, limits.bytes, checkCancelled)))
            return collector.finish()
        } catch (error: SAXParseException) {
            throw GpxException("GPX XML 错误：第 ${error.lineNumber} 行，第 ${error.columnNumber} 列", error)
        } catch (error: SAXException) {
            throw GpxException("无法解析 GPX：${error.message}", error)
        }
    }

    private class LimitedStream(input: InputStream, private val limit: Long, val check: () -> Unit) : FilterInputStream(input) {
        var count = 0L
        private fun add(n: Int) { check(); if (n > 0) { count += n; if (count > limit) throw GpxException("文件超过 ${limit / 1024 / 1024} MiB 限制") } }
        override fun read(): Int = `in`.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { add(it) }
    }

    private class Node(val local: String, val uri: String, val qualified: String,
        val attrs: Map<String, String>, val namespaces: Map<String, String>) {
        val children = mutableListOf<Any>()
        fun text(): String = children.joinToString("") { if (it is Node) it.text() else it.toString() }
        fun xml(): String = buildString {
            append('<').append(qualified)
            namespaces.forEach { (prefix, value) ->
                if (prefix != "xml") append(if (prefix.isEmpty()) " xmlns=\"" else " xmlns:$prefix=\"").append(escape(value)).append('"')
            }
            attrs.forEach { (name, value) -> append(' ').append(name).append("=\"").append(escape(value)).append('"') }
            append('>')
            children.forEach { append(if (it is Node) it.xml() else escape(it.toString())) }
            append("</").append(qualified).append('>')
        }
        companion object {
            fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        }
    }

    private class Handler(val limits: GpxLimits, val check: () -> Unit, val emit: (GpxEvent) -> Unit) : DefaultHandler2() {
        data class Frame(val name: String, val uri: String, val namespaces: Map<String, String>, val node: Node?)
        val stack = mutableListOf<Frame>()
        val pendingNamespaces = mutableMapOf<String, String>()
        var namespace = ""
        var pathIndex = -1
        var segmentIndex = -1
        var pointIndex = 0
        var waypointIndex = 0
        var totalPoints = 0
        var totalSegments = 0
        var captureSize = 0
        var locator: Locator? = null

        override fun setDocumentLocator(locator: Locator) { this.locator = locator }
        private fun fail(message: String): Nothing = throw GpxException("$message（第 ${locator?.lineNumber ?: 0} 行）")
        override fun startDTD(name: String?, publicId: String?, systemId: String?) { fail("不允许 DTD 或实体声明") }
        override fun skippedEntity(name: String?) { fail("不允许未解析实体") }
        override fun error(e: SAXParseException) { throw e }
        override fun fatalError(e: SAXParseException) { throw e }
        override fun startPrefixMapping(prefix: String, uri: String) { pendingNamespaces[prefix] = uri }

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            check()
            if (stack.size >= limits.depth) fail("XML 嵌套层数超过 ${limits.depth}")
            val namespaces = (stack.lastOrNull()?.namespaces.orEmpty() + pendingNamespaces)
            pendingNamespaces.clear()
            val parent = stack.lastOrNull()
            if (parent == null) {
                val version = attributes.getValue("version")
                if (localName != "gpx" || version !in listOf("1.0", "1.1") || uri != "http://www.topografix.com/GPX/${version!!.replace('.', '/')}") fail("仅支持标准命名空间的 GPX 1.0 / 1.1")
                namespace = uri
                emit(GpxEvent.Header(version, attributes.getValue("creator")))
                stack += Frame(localName, uri, namespaces, null)
                return
            }
            val core = uri == namespace
            var structural = false
            if (parent.node == null && core) when {
                parent.name == "gpx" && localName in listOf("trk", "rte") -> {
                    pathIndex++; if (pathIndex >= limits.paths) fail("路线数量超过 ${limits.paths}")
                    segmentIndex = -1; pointIndex = 0
                    emit(GpxEvent.PathStart(pathIndex, if (localName == "trk") "TRACK" else "ROUTE"))
                    if (localName == "rte") { segmentIndex = 0; segment(); emit(GpxEvent.SegmentStart(pathIndex, 0)) }
                    structural = true
                }
                parent.name == "trk" && localName == "trkseg" -> {
                    segmentIndex++; pointIndex = 0; segment()
                    emit(GpxEvent.SegmentStart(pathIndex, segmentIndex)); structural = true
                }
                localName in listOf("gpx", "trk", "rte", "trkseg") -> fail("GPX 结构错误：$localName 所在位置不正确")
                localName == "trkpt" && parent.name != "trkseg" -> fail("trkpt 必须位于 trkseg 内")
                localName == "rtept" && parent.name != "rte" -> fail("rtept 必须位于 rte 内")
                localName == "wpt" && parent.name != "gpx" -> fail("wpt 必须位于 gpx 根下")
            }
            val node = if (structural) null else {
                if (parent.node == null) captureSize = 0
                val attrs = (0 until attributes.length).associate { attributes.getQName(it).ifEmpty { attributes.getLocalName(it) } to attributes.getValue(it) }
                captureSize += qName.length + namespaces.entries.sumOf { it.key.length + it.value.length } + attrs.entries.sumOf { it.key.length + it.value.length }
                if (captureSize > limits.elementChars) fail("单个点或元数据超过大小限制")
                Node(localName, uri, qName.ifEmpty { localName }, attrs, namespaces).also { parent.node?.children?.add(it) }
            }
            stack += Frame(localName, uri, namespaces, node)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            check()
            stack.lastOrNull()?.node?.let { node ->
                captureSize += length
                if (captureSize > limits.elementChars) fail("单个点或扩展字段超过大小限制")
                val last = node.children.lastOrNull()
                if (last is StringBuilder) last.append(ch, start, length) else node.children.add(StringBuilder().append(ch, start, length))
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            check()
            val frame = stack.removeAt(stack.lastIndex)
            val node = frame.node ?: return
            if (stack.lastOrNull()?.node != null) return
            val parent = stack.lastOrNull()?.name
            if (uri == namespace && localName in listOf("trkpt", "rtept", "wpt")) {
                totalPoints++; if (totalPoints > limits.points) fail("轨迹点/航点总数超过 ${limits.points}")
                if (localName == "wpt" && waypointIndex >= limits.waypoints) fail("航点数量超过 ${limits.waypoints}")
                val owner = if (localName == "wpt") GpxOwner("WAYPOINT", point = waypointIndex++)
                    else GpxOwner("POINT", pathIndex, segmentIndex, pointIndex++)
                emit(GpxEvent.Position(owner, point(node)))
            } else {
                val owner = when (parent) {
                    "gpx" -> GpxOwner("DOCUMENT")
                    "trk", "rte" -> GpxOwner("PATH", pathIndex)
                    "trkseg" -> GpxOwner("SEGMENT", pathIndex, segmentIndex)
                    else -> fail("不支持的 GPX 结构")
                }
                val xml = node.xml()
                if (xml.length > limits.elementChars) fail("扩展字段序列化后超过大小限制")
                // name 只取核心命名空间，不能把第三方扩展中的同名元素当成轨迹标题。
                emit(GpxEvent.Element(owner, if (uri == namespace) localName else "{$uri}$localName", node.text(), xml))
            }
        }

        private fun segment() { totalSegments++; if (totalSegments > limits.segments) fail("分段数量超过 ${limits.segments}") }

        private fun point(node: Node): GpxPoint {
            fun number(value: String?, field: String): Double = value?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: fail("$field 不是有效数字")
            val lat = number(node.attrs["lat"], "纬度"); val lon = number(node.attrs["lon"], "经度")
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) fail("坐标超出 WGS84 范围")
            val children = node.children.filterIsInstance<Node>()
            fun child(name: String): String? {
                val found = children.filter { it.uri == namespace && it.local == name }
                if (found.size > 1) fail("重复的 $name 字段")
                return found.firstOrNull()?.text()?.trim()
            }
            val timeLexical = child("time")
            val time = timeLexical?.let { try { OffsetDateTime.parse(it).toInstant().toEpochMilli() } catch (_: Exception) { fail("time 必须是带时区的 ISO 8601 时间") } }
            val elevation = child("ele")?.let { number(it, "海拔") }
            val extra = children.filterNot { it.uri == namespace && it.local in listOf("time", "ele", "name", "sym") }.joinToString("") { it.xml() }
            if (extra.length > limits.elementChars) fail("点扩展字段超过大小限制")
            return GpxPoint(lat, lon, elevation, time, timeLexical, child("name"), child("sym"), extra)
        }
    }

    private class PreviewCollector {
        data class MutablePath(val index: Int, val kind: String, var name: String = "", var points: Int = 0,
            var segments: Int = 0, var distance: Double = 0.0, var firstTime: Long? = null,
            var lastTime: Long? = null, var completeTime: Boolean = true, var previous: GpxPoint? = null,
            var pointsInSegment: Int = 0, val warnings: MutableSet<String> = linkedSetOf(),
            var elevation: com.trail.app.domain.ElevationTotals = com.trail.app.domain.ElevationTotals())
        var version = ""; var creator: String? = null; var waypoints = 0
        val paths = mutableListOf<MutablePath>()
        fun accept(event: GpxEvent) {
            when (event) {
                is GpxEvent.Header -> { version = event.version; creator = event.creator }
                is GpxEvent.PathStart -> paths.add(MutablePath(event.index, event.kind))
                is GpxEvent.SegmentStart -> paths[event.path].apply {
                    if (segments > 0 && pointsInSegment == 0) warnings.add("包含空分段")
                    segments++; previous = null; pointsInSegment = 0
                    elevation = elevation.copy(anchorM = null)
                }
                is GpxEvent.Element -> if (event.owner.type == "PATH" && event.name == "name") paths[event.owner.path].name = event.value.trim()
                is GpxEvent.Position -> {
                    if (event.owner.type == "WAYPOINT") { waypoints++; return }
                    val p = paths[event.owner.path]; val point = event.value
                    p.elevation = p.elevation.next(point.elevation)
                    p.previous?.let {
                        val distance = RecordingRules.distance(it.lat, it.lon, point.lat, point.lon)
                        p.distance += distance
                        if (distance == 0.0) p.warnings.add("包含重复位置，原样保留")
                        if (distance > 1_000) p.warnings.add("包含超过 1 公里的相邻点间隔，请检查路线")
                    }
                    if (point.time == null) { p.completeTime = false; p.warnings.add("缺少时间，历时显示 —") }
                    else {
                        if (p.lastTime != null && point.time < p.lastTime!!) { p.completeTime = false; p.warnings.add("时间倒序，保留点序且不计算历时") }
                        if (p.firstTime == null) p.firstTime = point.time
                        p.lastTime = point.time
                    }
                    if (point.elevation == null) p.warnings.add("缺少海拔")
                    p.previous = point; p.points++; p.pointsInSegment++
                }
            }
        }
        fun finish(): GpxPreview {
            if (paths.isEmpty() && waypoints == 0) throw GpxException("文件中没有轨迹、路线或航点")
            return GpxPreview(version, creator, paths.map {
                if (it.pointsInSegment == 0 && it.segments > 0) it.warnings.add("包含空分段")
                GpxPathPreview(it.index, it.kind, it.name.ifBlank { "${if (it.kind == "TRACK") "轨迹" else "路线"} ${it.index + 1}" },
                    it.points, it.segments, it.distance, if (it.completeTime && it.points >= 2) it.lastTime!! - it.firstTime!! else null, it.warnings.toList(),
                    it.elevation.ascentM, it.elevation.descentM)
            }, waypoints, buildList { if (version == "1.0") add("GPX 1.0：保留原始字段，后续导出转换为 1.1")
                if (creator == null) add("未声明生成工具") })
        }
    }
}
