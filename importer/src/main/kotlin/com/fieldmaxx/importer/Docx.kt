package com.fieldmaxx.importer

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val REL = "http://schemas.openxmlformats.org/package/2006/relationships"

private class LevelDef(val start: Int?, val numFmt: String, val lvlText: String)
private class NumDef(val levels: Map<Int, LevelDef>, val startOverrides: Map<Int, Int>)
private class NumPr(val numId: Int?, val ilvl: Int?)

private fun unreadable(cause: Throwable? = null) =
    ImportException("unreadable_docx", "This Word file could not be read" + (cause?.message?.let { ": $it" } ?: ""))

private fun Element.kids(ns: String, name: String): List<Element> {
    val out = ArrayList<Element>()
    var n: Node? = firstChild
    while (n != null) {
        if (n is Element && n.namespaceURI == ns && n.localName == name) out.add(n)
        n = n.nextSibling
    }
    return out
}

private fun Element.kid(ns: String, name: String): Element? = kids(ns, name).firstOrNull()
private fun Element.wVal(): String? = getAttributeNS(W, "val").takeIf { it.isNotEmpty() }

private fun parse(bytes: ByteArray): Element {
    val f = DocumentBuilderFactory.newInstance()
    f.isNamespaceAware = true
    f.isXIncludeAware = false
    f.isExpandEntityReferences = false
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
}

private fun readNumPr(pPr: Element?): NumPr? {
    val numPr = pPr?.kid(W, "numPr") ?: return null
    return NumPr(numPr.kid(W, "numId")?.wVal()?.toIntOrNull(), numPr.kid(W, "ilvl")?.wVal()?.toIntOrNull())
}

private fun parseNumbering(root: Element?): Map<Int, NumDef> {
    if (root == null) return emptyMap()
    val abstracts = HashMap<String, Map<Int, LevelDef>>()
    for (a in root.kids(W, "abstractNum")) {
        val levels = HashMap<Int, LevelDef>()
        for (l in a.kids(W, "lvl")) {
            val ilvl = l.getAttributeNS(W, "ilvl").toIntOrNull() ?: continue
            levels[ilvl] = LevelDef(
                l.kid(W, "start")?.wVal()?.toIntOrNull(),
                l.kid(W, "numFmt")?.wVal() ?: "decimal",
                l.kid(W, "lvlText")?.wVal() ?: "",
            )
        }
        abstracts[a.getAttributeNS(W, "abstractNumId")] = levels
    }
    val nums = HashMap<Int, NumDef>()
    for (n in root.kids(W, "num")) {
        val id = n.getAttributeNS(W, "numId").toIntOrNull() ?: continue
        val levels = abstracts[n.kid(W, "abstractNumId")?.wVal()] ?: continue
        val overrides = HashMap<Int, Int>()
        for (o in n.kids(W, "lvlOverride")) {
            val ilvl = o.getAttributeNS(W, "ilvl").toIntOrNull() ?: continue
            o.kid(W, "startOverride")?.wVal()?.toIntOrNull()?.let { overrides[ilvl] = it }
        }
        nums[id] = NumDef(levels, overrides)
    }
    return nums
}

/** styleId to its own numPr, following `basedOn` chains when the style has none. */
private fun parseStyleNumbering(root: Element?): Map<String, NumPr> {
    if (root == null) return emptyMap()
    val own = HashMap<String, NumPr?>()
    val basedOn = HashMap<String, String>()
    for (s in root.kids(W, "style")) {
        val id = s.getAttributeNS(W, "styleId").takeIf { it.isNotEmpty() } ?: continue
        own[id] = readNumPr(s.kid(W, "pPr"))
        s.kid(W, "basedOn")?.wVal()?.let { basedOn[id] = it }
    }
    val out = HashMap<String, NumPr>()
    for (id in own.keys) {
        var cur: String? = id
        var hops = 0
        while (cur != null && hops++ < 20) {
            val np = own[cur]
            if (np != null) { out[id] = np; break }
            cur = basedOn[cur]
        }
    }
    return out
}

private fun resolveTarget(target: String): String =
    if (target.startsWith("/")) target.removePrefix("/") else "word/" + target

private class State(
    val nums: Map<Int, NumDef>,
    val styleNum: Map<String, NumPr>,
    val rels: Map<String, String>,
    val parts: Map<String, ByteArray>,
) {
    val blocks = ArrayList<DocBlock>()
    val images = ArrayList<ExtractedImage>()
    val counters = HashMap<Int, IntArray>()
    val set = HashMap<Int, BooleanArray>()

    fun label(p: Element): String? {
        val pPr = p.kid(W, "pPr")
        val direct = readNumPr(pPr)
        val styleId = pPr?.kid(W, "pStyle")?.wVal()
        val fromStyle = styleId?.let { styleNum[it] }
        val numId = direct?.numId ?: fromStyle?.numId
        val ilvl = direct?.ilvl ?: fromStyle?.ilvl ?: 0
        if (numId == null || numId == 0 || ilvl !in 0..8) return null
        val num = nums[numId] ?: return null
        val lvl = num.levels[ilvl] ?: return null
        if (lvl.numFmt == "bullet" || lvl.numFmt == "none") return null

        val values = counters.getOrPut(numId) { IntArray(9) }
        val isSet = set.getOrPut(numId) { BooleanArray(9) }
        fun startOf(i: Int) = num.startOverrides[i] ?: num.levels[i]?.start ?: 1
        values[ilvl] = if (isSet[ilvl]) values[ilvl] + 1 else startOf(ilvl)
        isSet[ilvl] = true
        for (d in ilvl + 1..8) isSet[d] = false
        val levels = (0..ilvl).map { i ->
            (if (isSet[i]) values[i] else startOf(i)) to (num.levels[i]?.numFmt ?: "decimal")
        }
        return renderListLabel(lvl.lvlText, levels)
    }

    fun paragraph(p: Element) {
        val label = label(p)
        val text = StringBuilder()
        val embeds = ArrayList<String>()
        fun walk(e: Element) {
            var n: Node? = e.firstChild
            while (n != null) {
                if (n is Element) {
                    when {
                        n.namespaceURI == W && n.localName == "t" -> text.append(n.textContent)
                        n.namespaceURI == W && n.localName == "tab" -> text.append('\t')
                        n.namespaceURI == W && (n.localName == "br" || n.localName == "cr") -> text.append('\n')
                        n.localName == "blip" -> n.getAttributeNS(R, "embed").takeIf { it.isNotEmpty() }?.let { embeds.add(it) }
                        n.localName == "imagedata" -> n.getAttributeNS(R, "id").takeIf { it.isNotEmpty() }?.let { embeds.add(it) }
                        else -> walk(n)
                    }
                }
                n = n.nextSibling
            }
        }
        walk(p)
        val t = text.toString()
        if (t.isNotBlank()) blocks.add(ParaBlock(t, label, null))
        for (rid in embeds) {
            val path = rels[rid]?.let(::resolveTarget) ?: continue
            val bytes = parts[path] ?: continue
            val lower = path.lowercase()
            val mime = when {
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                else -> continue
            }
            val id = "img-${images.size + 1}"
            images.add(ExtractedImage(id, mime, bytes))
            blocks.add(ImageBlock(id, null))
        }
    }

    fun container(e: Element) {
        var n: Node? = e.firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == W) {
                when (n.localName) {
                    "p" -> paragraph(n)
                    "tbl", "tr", "tc", "sdt", "sdtContent" -> container(n)
                }
            }
            n = n.nextSibling
        }
    }
}

fun docxToBlocks(bytes: ByteArray): DocxExtract {
    try {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory) parts[e.name] = zin.readBytes()
            }
        }
        val docBytes = parts["word/document.xml"] ?: throw unreadable()
        val doc = parse(docBytes)
        val body = doc.kid(W, "body") ?: throw unreadable()
        val rels = HashMap<String, String>()
        parts["word/_rels/document.xml.rels"]?.let { rb ->
            for (r in parse(rb).kids(REL, "Relationship")) rels[r.getAttribute("Id")] = r.getAttribute("Target")
        }
        val st = State(
            parseNumbering(parts["word/numbering.xml"]?.let(::parse)),
            parseStyleNumbering(parts["word/styles.xml"]?.let(::parse)),
            rels,
            parts,
        )
        st.container(body)
        return DocxExtract(st.blocks, st.images)
    } catch (e: ImportException) {
        throw e
    } catch (e: Exception) {
        throw unreadable(e)
    }
}

private fun roman(n: Int): String {
    val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
    var rest = n
    val sb = StringBuilder()
    for (i in vals.indices) while (rest >= vals[i]) { sb.append(syms[i]); rest -= vals[i] }
    return sb.toString()
}

/** A, B, ... Z, AA, BB ... is Word's "upperLetter" scheme: the letter repeats (27 is AA, 28 is BB). */
private fun letters(n: Int): String {
    if (n < 1) return n.toString()
    return ('A' + (n - 1) % 26).toString().repeat((n - 1) / 26 + 1)
}

private fun formatNumber(value: Int, numFmt: String): String = when (numFmt) {
    "upperLetter" -> letters(value)
    "lowerLetter" -> letters(value).lowercase()
    "upperRoman" -> if (value > 0) roman(value) else value.toString()
    "lowerRoman" -> if (value > 0) roman(value).lowercase() else value.toString()
    "decimalZero" -> value.toString().padStart(2, '0')
    else -> value.toString()
}

/** Renders a Word `lvlText` such as `%1.` or `%1.%2` given the (counter value, numFmt) of levels 0..n. */
fun renderListLabel(lvlText: String, levels: List<Pair<Int, String>>): String =
    Regex("%([1-9])").replace(lvlText) { m ->
        val (value, fmt) = levels.getOrNull(m.groupValues[1].toInt() - 1) ?: return@replace m.value
        formatNumber(value, fmt)
    }
