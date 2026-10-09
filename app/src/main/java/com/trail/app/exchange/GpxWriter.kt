package com.trail.app.exchange

import java.io.Writer
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader

/** 仅负责 GPX 1.1 语义序列化；读取与文件保存交给 Repository。 */
class GpxWriter(private val output: Writer) {
    private val core = "http://www.topografix.com/GPX/1/1"
    private val legacy = "http://www.topografix.com/GPX/1/0"
    private val trail = "https://trail.app/ns/gpx/1"
    private val fragmentParser = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setExpandEntityReferences(false)
    }
    private fun write(value: String) { output.write(value) }
    private fun esc(value: String) = buildString(value.length) {
        value.forEach { ch -> append(when (ch) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"; else -> ch.toString() }) }
    }
    private fun tag(name: String, value: String?) { if (value != null) write("<$name>${esc(value)}</$name>") }
    fun start() { write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx xmlns=\"$core\" version=\"1.1\" creator=\"山径 Trail\">") }
    fun end() { write("</gpx>"); output.flush() }

    private fun parse(xml: String): Element {
        // 片段只来自已拒绝 DTD 的 GpxReader；Android DOM 不支持桌面 JAXP 的禁用 DTD feature。
        // 再做防御性检查，并用 resolver 阻断未来可能加入的外部实体引用。
        if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) throw GpxException("不允许 DTD 或实体声明")
        return fragmentParser.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> throw GpxException("不允许 XML 外部实体") }
    }.parse(InputSource(StringReader(xml))).documentElement
    }
    private fun name(element: Element) = element.localName ?: element.nodeName
    private fun coreElement(element: Element) = element.namespaceURI in listOf(core, legacy)

    /** 导入的 XML 片段各自声明命名空间；重写核心节点为 1.1，第三方命名空间及属性值保持原义。 */
    private fun node(node: Node) {
        when (node.nodeType) {
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> write(esc(node.nodeValue ?: ""))
            Node.ELEMENT_NODE -> {
                val element = node as Element
                val local = name(element)
                val uri = element.namespaceURI.orEmpty()
                val foreign = uri.isNotEmpty() && uri != core && uri != legacy
                val prefix = if (foreign) element.prefix?.takeIf { it.isNotBlank() } ?: "trailx" else ""
                val tagName = if (foreign) "$prefix:$local" else local
                write("<$tagName")
                if (foreign) write(" xmlns:$prefix=\"${esc(uri)}\"")
                else write(" xmlns=\"$core\"")
                val attrs = element.attributes
                for (i in 0 until attrs.length) {
                    val attr = attrs.item(i)
                    if (attr.namespaceURI == "http://www.w3.org/2000/xmlns/") continue
                    val attrPrefix = attr.prefix?.takeIf { it.isNotBlank() && it != "xml" }
                    if (attrPrefix != null) write(" xmlns:$attrPrefix=\"${esc(attr.namespaceURI.orEmpty())}\"")
                    write(" ${attr.nodeName}=\"${esc(attr.nodeValue)}\"")
                }
                write(">")
                val children = element.childNodes
                for (i in 0 until children.length) node(children.item(i))
                write("</$tagName>")
            }
        }
    }
    private fun snippets(xml: List<String>): List<Element> = xml.map(::parse)
    private fun pointSnippets(xml: String): List<Element> {
        if (xml.isBlank()) return emptyList()
        val container = parse("<root>$xml</root>")
        return (0 until container.childNodes.length).mapNotNull { container.childNodes.item(it) as? Element }
    }
    private fun sortedCore(elements: List<Element>, order: List<String>): List<Element> = elements
        .filter { coreElement(it) && name(it) in order }
        .sortedWith(compareBy { order.indexOf(name(it)) })
    private fun legacyFields(elements: List<Element>, allowed: Set<String>, converted: Set<String>): List<Element> = elements.filter {
        (coreElement(it) && name(it) !in allowed && !(it.namespaceURI == legacy && name(it) in converted) && name(it) != "extensions") || !coreElement(it)
    }
    private fun extras(elements: List<Element>, allowed: Set<String>, converted: Set<String> = emptySet()) {
        val explicit = elements.filter { coreElement(it) && name(it) == "extensions" }
        val unknown = legacyFields(elements, allowed, converted)
        if (explicit.isEmpty() && unknown.isEmpty()) return
        write("<extensions>")
        explicit.forEach { ext ->
            val children = ext.childNodes
            for (i in 0 until children.length) node(children.item(i))
        }
        unknown.forEach { element ->
            if (coreElement(element)) {
                // 1.0 特有字段不能直接写进 1.1 的固定序列；留在版本化应用扩展中。
                write("<trail:legacy xmlns:trail=\"$trail\" sourceVersion=\"1.0\" field=\"${esc(name(element))}\">")
                write(esc(element.textContent ?: "")); write("</trail:legacy>")
            } else node(element)
        }
        write("</extensions>")
    }
    private fun legacyLink(elements: List<Element>): Boolean {
        val url = elements.firstOrNull { it.namespaceURI == legacy && name(it) == "url" }?.textContent?.trim().orEmpty()
        if (url.isBlank()) return false
        val title = elements.firstOrNull { it.namespaceURI == legacy && name(it) == "urlname" }?.textContent?.trim()
        write("<link href=\"${esc(url)}\">"); tag("text", title); write("</link>")
        return true
    }
    fun metadata(documentName: String, fragments: List<String>) {
        val metadata = snippets(fragments)
        val wrapped = metadata.firstOrNull { coreElement(it) && name(it) == "metadata" }
        val fields = if (wrapped != null) (0 until wrapped.childNodes.length)
            .mapNotNull { wrapped.childNodes.item(it) as? Element } else metadata
        val order = listOf("name", "desc", "author", "copyright", "link", "time", "keywords", "bounds")
        write("<metadata>")
        if (fields.none { coreElement(it) && name(it) == "name" }) tag("name", documentName)
        val legacyAuthor = fields.firstOrNull { it.namespaceURI == legacy && name(it) == "author" }
        val legacyEmail = fields.firstOrNull { it.namespaceURI == legacy && name(it) == "email" }
        var urlConverted = false
        var emailConverted = false
        order.forEach { field ->
            when (field) {
                "author" -> if (legacyAuthor != null || legacyEmail != null) {
                    write("<author>"); tag("name", legacyAuthor?.textContent)
                    legacyEmail?.textContent?.trim()?.let { email ->
                        val split = email.lastIndexOf('@')
                        if (split > 0 && split < email.lastIndex) {
                            write("<email id=\"${esc(email.substring(0, split))}\" domain=\"${esc(email.substring(split + 1))}\"/>")
                            emailConverted = true
                        }
                    }
                    write("</author>")
                } else sortedCore(fields, listOf(field)).forEach(::node)
                "link" -> {
                    sortedCore(fields, listOf(field)).forEach(::node)
                    urlConverted = legacyLink(fields)
                }
                else -> sortedCore(fields, listOf(field)).forEach(::node)
            }
        }
        extras(fields, order.toSet(), (if (urlConverted) setOf("url", "urlname") else emptySet()) + if (emailConverted) setOf("email") else emptySet())
        write("</metadata>")
    }
    fun waypoint(lat: Double, lon: Double, elevation: Double?, time: Long?, lexical: String?,
        name: String?, symbol: String?, extraXml: String, tagName: String = "wpt") {
        write("<$tagName lat=\"$lat\" lon=\"$lon\">")
        tag("ele", elevation?.toString())
        tag("time", if (time == null) null else lexical ?: Instant.ofEpochMilli(time).toString())
        val extras = pointSnippets(extraXml)
        val orderBeforeName = listOf("magvar", "geoidheight")
        sortedCore(extras, orderBeforeName).forEach(::node)
        tag("name", name)
        val orderBeforeSym = listOf("cmt", "desc", "src", "link")
        var urlConverted = false
        orderBeforeSym.forEach { field ->
            sortedCore(extras, listOf(field)).forEach(::node)
            if (field == "link") urlConverted = legacyLink(extras)
        }
        tag("sym", symbol)
        val orderAfterSym = listOf("type", "fix", "sat", "hdop", "vdop", "pdop", "ageofdgpsdata", "dgpsid")
        sortedCore(extras, orderAfterSym).forEach(::node)
        this.extras(extras, (orderBeforeName + orderBeforeSym + orderAfterSym).toSet(),
            if (urlConverted) setOf("url", "urlname") else emptySet())
        write("</$tagName>")
    }
    fun startPath(kind: String, pathName: String, fragments: List<String>) {
        val tagName = if (kind == "ROUTE") "rte" else "trk"
        val fields = snippets(fragments)
        write("<$tagName>")
        tag("name", pathName)
        val order = listOf("cmt", "desc", "src", "link", "number", "type")
        var urlConverted = false
        order.forEach { field ->
            sortedCore(fields, listOf(field)).forEach(::node)
            if (field == "link") urlConverted = legacyLink(fields)
        }
        extras(fields, (order + "name").toSet(), if (urlConverted) setOf("url", "urlname") else emptySet())
    }
    fun endPath(kind: String) { write(if (kind == "ROUTE") "</rte>" else "</trk>") }
    fun startSegment(kind: String) { if (kind != "ROUTE") write("<trkseg>") }
    fun endSegment(kind: String, fragments: List<String>) {
        if (kind == "ROUTE") return
        val fields = snippets(fragments)
        extras(fields, emptySet())
        write("</trkseg>")
    }

}
