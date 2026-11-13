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
    private fun reasonFor(t: String) = when {
        TYPE_AND_NUMBER.containsMatchIn(t) -> "type and number without readable coordinates"
        COORD_LOOKING.containsMatchIn(t) -> "number that looks like a coordinate"
        else -> null
    }

    fun isUnreadable(line: String) = reasonFor(line.trim()) != null

    fun leftover(line: String, page: Int?) {
        val t = line.trim()
        if (t.isEmpty()) return
        val reason = reasonFor(t)
        if (reason == null) notes.add(t) else if (unread.none { it.text == t && it.sourcePage == page }) unread.add(UnreadLine(t, page, reason))
    }
}

private fun isNumbered(p: ParaBlock) = p.listLabel != null || TYPED_NUMBER.containsMatchIn(p.text)

private fun pairCount(text: String) = findCoordinatePairs(normalizeText(text)).size

/** True when [text] is nothing but coordinate pairs (hemisphere letters N/S/E/W and separators allowed). */
private fun isCoordinateOnly(text: String): Boolean {
    val t = normalizeText(text)
    val pairs = findCoordinatePairs(t)
    if (pairs.isEmpty()) return false
    val sb = StringBuilder(t)
    pairs.sortedByDescending { it.start }.forEach { sb.delete(it.start, it.end) }
    return sb.none { it.isLetter() && it.uppercaseChar() !in "NSEW" }
}

/**
 * A numbered paragraph that carries a coordinate pair, or is directly followed by a coordinate-only
 * paragraph, marks a query file. Numbered notes before an unnumbered point list do not.
 */
private fun isQueryGroup(blocks: List<DocBlock>): Boolean {
    val paras = blocks.filterIsInstance<ParaBlock>()
    return paras.withIndex().any { (i, p) ->
        isNumbered(p) && (pairCount(p.text) > 0 || paras.getOrNull(i + 1)?.let { isCoordinateOnly(it.text) } == true)
    }
}

private val NUMBER_START = Regex("""^\s*-?\s*\d""")
private val LETTER_LABEL = Regex("""^\s*([A-Za-z])\s*[-–.)]\s*(\S.*)$""")

/** Normalizes every paragraph and re-joins a coordinate pair that line wrapping broke across two paragraphs. */
private fun prepare(blocks: List<DocBlock>): List<DocBlock> {
    val joined = ArrayList<DocBlock>()
    for (raw in blocks) {
        val b = if (raw is ParaBlock) raw.copy(text = normalizeText(raw.text)) else raw
        val prev = joined.lastOrNull()
        if (b is ParaBlock && prev is ParaBlock && b.listLabel == null && NUMBER_START.containsMatchIn(b.text)) {
            val both = prev.text.trimEnd() + b.text.trimStart()
            if (pairCount(both) > pairCount(prev.text) + pairCount(b.text)) {
                joined[joined.lastIndex] = prev.copy(text = both)
                continue
            }
        }
        joined.add(b)
    }
    return joined
}

/** Turns a bare `A- 39.3,-119.8` line (a lettered sub-item) into a paragraph with list label `A-`. */
private fun restoreLetterLabels(blocks: List<DocBlock>): List<DocBlock> = blocks.map { b ->
    if (b !is ParaBlock || b.listLabel != null) return@map b
    val m = LETTER_LABEL.find(b.text) ?: return@map b
    val rest = m.groupValues[2]
    if (isCoordinateOnly(rest) && rest.none { it.isLetter() }) b.copy(text = rest, listLabel = m.groupValues[1].uppercase() + "-") else b
}

private val UNREADABLE_FIELD = Regex(""": unreadable$""")

private fun processGroup(source: GroupSource, page: Int?, rawBlocks: List<DocBlock>, queryLayer: String, out: Collector) {
    var blocks = prepare(rawBlocks)
    if (source == GroupSource.FIELD) {
        val (bad, good) = blocks.partition { it is ParaBlock && UNREADABLE_FIELD.containsMatchIn(it.text) }
        bad.filterIsInstance<ParaBlock>().forEach { out.unread += UnreadLine(it.text, it.page ?: page, "form field could not be read") }
        blocks = good
    }
    val text = blocks.filterIsInstance<ParaBlock>().joinToString("\n") { it.text }
    if (findCoordinatePairs(text).isEmpty()) {
        text.lines().forEach { out.leftover(it, page) }
        return
    }
    if (isQueryGroup(blocks)) {
        val labelled = restoreLetterLabels(blocks)
        val q = extractQueryItems(labelled, queryLayer)
        out.points += q.points
        val pageOf = labelled.filterIsInstance<ParaBlock>().associate { it.text.trim() to it.page }
        q.notes.forEach { out.leftover(it, pageOf[it] ?: page) }
        return
    }
    val r = extractLabelledPoints(TextGroup(source, page, text))
    // A line a point swallowed as its description that looks like an unreadable point goes to unread instead.
    for (p in r.points) {
        val keep = ArrayList<String>()
        for (line in p.description.lines()) if (out.isUnreadable(line)) out.leftover(line, page) else keep.add(line)
        out.points += p.copy(description = keep.joinToString("\n").trim())
    }
    for (c in r.unlabelled) {
        out.strays += Stray(c.lat, c.lon, page, if (c.swapped) listOf(ImportFlag.unlabelled, ImportFlag.swapped) else listOf(ImportFlag.unlabelled), text.substring(c.start, c.end))
    }
    r.leftoverText.lines().forEach { out.leftover(it, page) }
}

/** Page-text groups that are each a query list and are consecutive are read as one list (an item can span a page break). */
private fun processPdfGroups(groups: List<PdfGroup>, queryLayer: String, out: Collector) {
    val runOf = HashMap<PdfGroup, List<PdfGroup>>()
    var run = ArrayList<PdfGroup>()
    fun close() { if (run.size > 1) run.forEach { runOf[it] = run }; run = ArrayList() }
    for (g in groups.filter { it.source == GroupSource.PAGE_TEXT }) {
        if (isQueryGroup(prepare(g.blocks))) run.add(g) else close()
    }
    close()
    val done = HashSet<PdfGroup>()
    for (g in groups) {
        val r = runOf[g]
        when {
            r == null -> processGroup(g.source, g.page, g.blocks, queryLayer, out)
            done.add(r.first()) -> processGroup(GroupSource.PAGE_TEXT, r.first().page, r.flatMap { it.blocks }, queryLayer, out)
        }
    }
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

fun importFile(fileName: String, bytes: ByteArray): ImportResult =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> importPdfExtract(fileName, pdfToGroups(bytes))
        "docx" -> importDocxExtract(fileName, docxToBlocks(bytes))
        else -> throw ImportException("unsupported_type", "Only PDF and Word (.docx) files")
    }

internal fun importPdfExtract(fileName: String, x: PdfExtract): ImportResult {
    val out = Collector()
    processPdfGroups(x.groups, layerFromFileName(fileName), out)
    val refs = x.pageRenders.map { RefImageInfo(it.id, "page", it.id.removePrefix("page-").toIntOrNull()) } +
        x.images.map { RefImageInfo(it.id, "item", it.id.split('-').getOrNull(1)?.toIntOrNull()) }
    return finish(fileName, "pdf", out, x.pageRenders + x.images, refs)
}

private fun importDocxExtract(fileName: String, x: DocxExtract): ImportResult {
    val out = Collector()
    processGroup(GroupSource.DOCX, null, x.blocks, layerFromFileName(fileName), out)
    return finish(fileName, "docx", out, x.images, x.images.map { RefImageInfo(it.id, "item", null) })
}

private fun finish(fileName: String, kind: String, out: Collector, images: List<ExtractedImage>, refs: List<RefImageInfo>): ImportResult {
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
    val layer = all.filter { ImportFlag.unlabelled !in it.flags }.groupingBy { it.layer }.eachCount().maxByOrNull { it.value }?.key ?: layerFromFileName(fileName)
    return ImportResult(fileName, kind, layer, out.notes.joinToString("\n"), all, out.unread, images, refs)
}

private const val STRAY_LAYER = "Unlabelled"
