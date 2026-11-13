package com.fieldmaxx.importer

import com.fieldmaxx.core.geo.CoordMatch
import com.fieldmaxx.core.geo.findCoordinatePairs
import com.fieldmaxx.core.model.DraftPoint
import com.fieldmaxx.core.model.ImportFlag
import com.fieldmaxx.core.naming.pointLabel

data class QueryResult(val points: List<DraftPoint>, val notes: List<String>, val unattachedImageIds: List<String>)

private val QUERY_FILE = Regex("""^query[\s_]*(\d+)(?!\d)""", RegexOption.IGNORE_CASE)

/** `QUERY_1_-_A05K51V.docx` → `QUERY 1`; any other name is the file name without its extension. */
fun layerFromFileName(fileName: String): String {
    val base = fileName.substringBeforeLast('.', fileName).trim()
    val m = QUERY_FILE.find(base) ?: return base
    return "QUERY ${m.groupValues[1]}"
}

// A typed item number: digits then dots, but not the integer part of a decimal such as `39.1234`.
private val TYPED_NUMBER = Regex("""^\s*(\d{1,4})\s*\.+(?!\d)\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
private val LABEL_NUMBER = Regex("""^\s*(\d+)""")
private val LABEL_LETTERS = Regex("""^\s*([A-Za-z]+)""")
private const val SEPARATORS = " \t\r\n–—-:,"

private class Item(val num: Int, val unlabelled: Boolean, var text: String, val page: Int?) {
    val coords = ArrayList<Pair<CoordMatch, String?>>()
    val images = ArrayList<String>()
    val raw = StringBuilder()
}

/** The text of [body] with the coordinate pairs cut out and leading/trailing separators removed. */
private fun wordsOf(body: String, coords: List<CoordMatch>): String {
    val sb = StringBuilder()
    var at = 0
    for (c in coords) {
        sb.append(body, at, c.start).append(' ')
        at = c.end
    }
    sb.append(body, at, body.length)
    return sb.toString().replace(Regex("""\s+"""), " ").trim { it in SEPARATORS }
}

/**
 * Turns a query file into points: each numbered instruction is one point (several when it carries
 * several coordinate pairs), with the images that follow it as reference screenshots.
 */
fun extractQueryItems(blocks: List<DocBlock>, layer: String): QueryResult {
    val items = ArrayList<Item>()
    val notes = ArrayList<String>()
    val unattached = ArrayList<String>()
    var current: Item? = null

    fun start(number: Int?, text: String, page: Int?): Item {
        val item = Item(number ?: ((items.lastOrNull()?.num ?: 0) + 1), number == null, text, page)
        items.add(item)
        current = item
        return item
    }

    for (b in blocks) when (b) {
        is ImageBlock -> current?.images?.add(b.imageId) ?: unattached.add(b.imageId)
        is ParaBlock -> {
            val label = b.listLabel
            var body = b.text
            var number = label?.let { LABEL_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val letter = label?.let { LABEL_LETTERS.find(it)?.groupValues?.get(1)?.uppercase() }
            if (number == null && letter == null) {
                TYPED_NUMBER.find(body)?.let { number = it.groupValues[1].toInt(); body = it.groupValues[2] }
            }
            val coords = findCoordinatePairs(body)
            val words = wordsOf(body, coords)
            val hasWords = words.any { it.isLetter() }
            val cur = current
            when {
                (hasWords || coords.isNotEmpty()) && (number != null || (hasWords && coords.isNotEmpty())) -> {
                    val item = start(number, if (hasWords) words else "", b.page)
                    coords.forEach { item.coords.add(it to letter) }
                    item.raw.append(b.text)
                }
                !hasWords && coords.isNotEmpty() -> {
                    val item = cur ?: start(null, "", b.page)
                    coords.forEach { item.coords.add(it to letter) }
                    item.raw.append(if (item.raw.isEmpty()) "" else "\n").append(b.text)
                }
                hasWords && cur != null && cur.coords.isEmpty() -> {
                    cur.text = if (cur.text.isEmpty()) words else cur.text + " " + words
                    cur.raw.append("\n").append(b.text)
                }
                b.text.any { it.isLetterOrDigit() } -> notes.add(b.text.trim())
            }
        }
    }

    val points = ArrayList<DraftPoint>()
    for (item in items) {
        val n = item.coords.size
        val base = if (item.unlabelled) listOf(ImportFlag.unlabelled) else emptyList()
        if (n == 0) {
            points.add(draft(layer, item, item.num.toString(), null, base + ImportFlag.no_coordinates))
            continue
        }
        item.coords.forEachIndexed { i, (c, letter) ->
            val num = if (n == 1) item.num.toString() else item.num.toString() + (letter ?: ('A' + i).toString())
            points.add(draft(layer, item, num, c, base + if (c.swapped) listOf(ImportFlag.swapped) else emptyList()))
        }
    }
    return QueryResult(points, notes, unattached)
}

private fun draft(layer: String, item: Item, num: String, c: CoordMatch?, flags: List<ImportFlag>) = DraftPoint(
    layer = layer, type = layer, num = num, label = pointLabel(layer, layer, num),
    lat = c?.lat, lon = c?.lon, description = item.text, flags = flags,
    sourcePage = item.page, refImageIds = item.images.toList(), rawText = item.raw.toString(),
)
