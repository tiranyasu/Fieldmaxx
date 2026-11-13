package com.fieldmaxx.importer

import com.fieldmaxx.core.geo.findCoordinatePairs
import com.fieldmaxx.core.geo.normalizeText
import com.fieldmaxx.core.model.DraftPoint
import com.fieldmaxx.core.model.ImportFlag
import com.fieldmaxx.core.model.RefImageInfo
import com.fieldmaxx.core.model.UnreadLine
import com.fieldmaxx.core.naming.pointLabel
import kotlin.math.abs

class ImportResult(
    val fileName: String,
    val kind: String,
    val layerSuggestion: String,
    val notes: String,
    val points: List<DraftPoint>,
    val unread: List<UnreadLine>,
    val images: List<ExtractedImage>,
    val refImages: List<RefImageInfo>,
)

private val TYPE_AND_NUMBER = Regex("""\b(POLE|PED|HH|MH|BU|SAI|PFP|MDU)\b\s*\d""", RegexOption.IGNORE_CASE)
private val COORD_LOOKING = Regex("""\d{1,3}\.\d{3,}""")
private val TYPED_NUMBER = Regex("""^\s*\d{1,4}\s*\.+(?!\d)""")
private const val SAME_COORD = 1e-7

private class Stray(val lat: Double, val lon: Double, val page: Int?, val flags: List<ImportFlag>, val raw: String)

private class Collector {
    val points = ArrayList<DraftPoint>()
    val strays = ArrayList<Stray>()
    val unread = ArrayList<UnreadLine>()
    val notes = LinkedHashSet<String>()

    /** A leftover line is unread when it looks like a point or a coordinate; otherwise it is a note. */
    fun leftover(line: String, page: Int?) {
        val t = line.trim()
        if (t.isEmpty()) return
        val reason = when {
            TYPE_AND_NUMBER.containsMatchIn(t) -> "type and number without readable coordinates"
            COORD_LOOKING.containsMatchIn(t) -> "number that looks like a coordinate"
            else -> null
        }
        if (reason == null) notes.add(t) else if (unread.none { it.text == t && it.sourcePage == page }) unread.add(UnreadLine(t, page, reason))
    }
}

private fun isNumbered(p: ParaBlock) = p.listLabel != null || TYPED_NUMBER.containsMatchIn(p.text)

private fun hasCoords(text: String) = findCoordinatePairs(normalizeText(text)).isNotEmpty()

/** A numbered paragraph that carries coordinates, or is followed by a coordinate paragraph, marks a query file. */
private fun isQueryGroup(blocks: List<DocBlock>): Boolean {
    val paras = blocks.filterIsInstance<ParaBlock>()
    return paras.withIndex().any { (i, p) ->
        isNumbered(p) && (hasCoords(p.text) || paras.getOrNull(i + 1)?.let { hasCoords(it.text) } == true)
    }
}

private val SPLIT_PAIR_END = Regex(""",\s*-?\s*$""")
private val NUMBER_START = Regex("""^\s*-?\s*\d""")
private val LETTER_LABEL = Regex("""^\s*([A-Za-z])\s*[-–.)]\s*(\S.*)$""")

/**
 * Repairs what a PDF's line wrapping does to a query list: a coordinate pair broken across two lines is
 * re-joined, and a bare `A- 39.3,-119.8` line gets its letter back as a list label.
 */
private fun repairBlocks(blocks: List<DocBlock>): List<DocBlock> {
    val joined = ArrayList<DocBlock>()
    for (b in blocks) {
        val prev = joined.lastOrNull()
        if (b is ParaBlock && prev is ParaBlock && b.listLabel == null && SPLIT_PAIR_END.containsMatchIn(prev.text) && NUMBER_START.containsMatchIn(b.text)) {
            joined[joined.lastIndex] = prev.copy(text = prev.text.trimEnd() + b.text.trimStart())
        } else {
            joined.add(b)
        }
    }
    return joined.map { b ->
        if (b !is ParaBlock || b.listLabel != null) return@map b
        val m = LETTER_LABEL.find(b.text) ?: return@map b
        val rest = m.groupValues[2]
        val coords = findCoordinatePairs(normalizeText(rest))
        val onlyCoords = coords.isNotEmpty() && normalizeText(rest).let { t ->
            val sb = StringBuilder(t)
            coords.sortedByDescending { it.start }.forEach { sb.delete(it.start, it.end) }
            sb.none { ch -> ch.isLetter() }
        }
        if (onlyCoords) b.copy(text = rest, listLabel = m.groupValues[1].uppercase() + "-") else b
    }
}

private fun processGroup(source: GroupSource, page: Int?, rawBlocks: List<DocBlock>, queryLayer: String, out: Collector) {
    val blocks = repairBlocks(rawBlocks)
    val text = normalizeText(blocks.filterIsInstance<ParaBlock>().joinToString("\n") { it.text })
    if (findCoordinatePairs(text).isEmpty()) {
        text.lines().forEach { out.leftover(it, page) }
        return
    }
    if (isQueryGroup(blocks)) {
        val q = extractQueryItems(blocks, queryLayer)
        out.points += q.points
        val pageOf = blocks.filterIsInstance<ParaBlock>().associate { it.text.trim() to it.page }
        q.notes.forEach { out.leftover(it, pageOf[it] ?: page) }
        return
    }
    val r = extractLabelledPoints(TextGroup(source, page, text))
    out.points += r.points
    for (c in r.unlabelled) {
        out.strays += Stray(c.lat, c.lon, page, if (c.swapped) listOf(ImportFlag.unlabelled, ImportFlag.swapped) else listOf(ImportFlag.unlabelled), text.substring(c.start, c.end))
    }
    r.leftoverText.lines().forEach { out.leftover(it, page) }
}

private fun same(a: Double?, b: Double?) = (a == null && b == null) || (a != null && b != null && abs(a - b) < SAME_COORD)

private fun dedupe(points: List<DraftPoint>): List<DraftPoint> {
    val out = ArrayList<DraftPoint>()
    for (p in points) {
        val i = out.indexOfFirst { it.label == p.label && same(it.lat, p.lat) && same(it.lon, p.lon) }
        if (i < 0) out.add(p)
        else out[i] = out[i].copy(refImageIds = (out[i].refImageIds + p.refImageIds).distinct())
    }
    return out
}

fun importFile(fileName: String, bytes: ByteArray): ImportResult {
    val kind = when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "pdf"
        "docx" -> "docx"
        else -> throw ImportException("unsupported_type", "Only PDF and Word (.docx) files")
    }
    val queryLayer = layerFromFileName(fileName)
    val out = Collector()
    val images = ArrayList<ExtractedImage>()
    val refs = ArrayList<RefImageInfo>()

    if (kind == "pdf") {
        val x = pdfToGroups(bytes)
        // A query list runs on across pages (an item and its coordinates can be split by a page break), so read it as one.
        val pageText = x.groups.filter { it.source == GroupSource.PAGE_TEXT }
        val merged = pageText.flatMap { it.blocks }
        val oneQuery = pageText.size > 1 && isQueryGroup(repairBlocks(merged))
        if (oneQuery) processGroup(GroupSource.PAGE_TEXT, pageText.first().page, merged, queryLayer, out)
        for (g in x.groups) if (!(oneQuery && g.source == GroupSource.PAGE_TEXT)) processGroup(g.source, g.page, g.blocks, queryLayer, out)
        x.pageRenders.forEach { images += it; refs += RefImageInfo(it.id, "page", it.id.removePrefix("page-").toIntOrNull()) }
        x.images.forEach { images += it; refs += RefImageInfo(it.id, "item", it.id.split('-').getOrNull(1)?.toIntOrNull()) }
    } else {
        val x = docxToBlocks(bytes)
        processGroup(GroupSource.DOCX, null, x.blocks, queryLayer, out)
        x.images.forEach { images += it; refs += RefImageInfo(it.id, "item", null) }
    }

    var points = out.points.toList()
    if (kind == "pdf") {
        points = points.map { p ->
            val page = p.sourcePage ?: return@map p
            p.copy(refImageIds = (p.refImageIds + "page-$page").distinct())
        }
    }
    // Stray coordinates (no label) keep their place as flagged points; one already covered by a labelled point is a repeat.
    val strayPoints = ArrayList<DraftPoint>()
    for (s in out.strays) {
        if (points.any { same(it.lat, s.lat) && same(it.lon, s.lon) } || strayPoints.any { same(it.lat, s.lat) && same(it.lon, s.lon) }) continue
        val num = (strayPoints.size + 1).toString()
        strayPoints += DraftPoint(
            layer = STRAY_LAYER, type = STRAY_LAYER, num = num, label = pointLabel(STRAY_LAYER, STRAY_LAYER, num),
            lat = s.lat, lon = s.lon, description = "", flags = s.flags, sourcePage = s.page,
            refImageIds = if (kind == "pdf" && s.page != null) listOf("page-${s.page}") else emptyList(), rawText = s.raw,
        )
    }
    val all = addSanityFlags(dedupe(points) + strayPoints)
    val layer = all.filter { ImportFlag.unlabelled !in it.flags }.groupingBy { it.layer }.eachCount().maxByOrNull { it.value }?.key ?: queryLayer
    return ImportResult(fileName, kind, layer, out.notes.joinToString("\n"), all, out.unread, images, refs)
}

private const val STRAY_LAYER = "Unlabelled"
