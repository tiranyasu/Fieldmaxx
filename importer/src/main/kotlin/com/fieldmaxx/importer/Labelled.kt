package com.fieldmaxx.importer

import com.fieldmaxx.core.geo.CoordMatch
import com.fieldmaxx.core.geo.findCoordinatePairs
import com.fieldmaxx.core.geo.normalizeText
import com.fieldmaxx.core.model.DraftPoint
import com.fieldmaxx.core.model.ImportFlag
import com.fieldmaxx.core.naming.KNOWN_TYPES
import com.fieldmaxx.core.naming.pointLabel

enum class GroupSource { ANNOTATION, FIELD, PAGE_TEXT, DOCX }

data class TextGroup(val source: GroupSource, val page: Int?, val text: String)

/** [leftoverText] is the normalized text not accounted for by labelled points or [unlabelled] coordinates. */
data class LabelledResult(val points: List<DraftPoint>, val unlabelled: List<CoordMatch>, val leftoverText: String)

private val LABEL_RE = Regex(
    """(?:^|[^A-Za-z])((?i:${KNOWN_TYPES.joinToString("|")})|[A-Z]{2,8})\s*#?\s*(\d{1,4}[A-Za-z]?)\s*[=:\-–]?\s*$""",
)

private class Hit(val coord: CoordMatch, val type: String?, val num: String?, val labelStart: Int)

/**
 * Finds `TYPE NUM = coordinates` points. The text before each coordinate pair (since the previous pair)
 * is tested for a trailing label; the text after a point's coordinates, up to the next label, is its description.
 */
fun extractLabelledPoints(group: TextGroup): LabelledResult {
    val text = normalizeText(group.text)
    val pairs = findCoordinatePairs(text)
    val hits = ArrayList<Hit>()
    var prevEnd = 0
    for (c in pairs) {
        val slice = text.substring(prevEnd, c.start)
        val m = LABEL_RE.find(slice)
        hits.add(
            if (m == null) Hit(c, null, null, c.start)
            else Hit(c, m.groupValues[1].uppercase(), m.groupValues[2].uppercase(), prevEnd + m.groups[1]!!.range.first),
        )
        prevEnd = c.end
    }

    val points = ArrayList<DraftPoint>()
    val unlabelled = ArrayList<CoordMatch>()
    val leftover = StringBuilder()
    var consumedTo = 0
    for ((i, h) in hits.withIndex()) {
        // Text between the previous coordinates' end and this label belongs to the previous point or is leftover.
        val gapStart = if (i == 0) 0 else hits[i - 1].coord.end
        val gap = text.substring(gapStart, h.labelStart)
        val prev = points.lastOrNull().takeIf { i > 0 && hits[i - 1].type != null }
        if (prev != null) {
            // Up to the next label when it has one, else just the rest of the line.
            val owned = if (h.type != null) gap else gap.substringBefore('\n')
            points[points.lastIndex] = prev.copy(description = owned.trim(), rawText = prev.rawText + owned)
            leftover.append(gap.removePrefix(owned)).append('\n')
        } else {
            leftover.append(gap).append('\n')
        }
        consumedTo = h.coord.end
        if (h.type == null) {
            unlabelled.add(h.coord)
            continue
        }
        val label = pointLabel(h.type, h.type, h.num!!)
        points.add(
            DraftPoint(
                layer = h.type, type = h.type, num = h.num, label = label,
                lat = h.coord.lat, lon = h.coord.lon, description = "",
                flags = if (h.coord.swapped) listOf(ImportFlag.swapped) else emptyList(),
                sourcePage = group.page, refImageIds = emptyList(),
                rawText = text.substring(h.labelStart, h.coord.end),
            ),
        )
    }
    // Text after the last pair: first line is the last point's description when it was labelled.
    val tail = text.substring(consumedTo)
    val lastLabelled = hits.lastOrNull()?.type != null
    if (lastLabelled) {
        val desc = tail.substringBefore('\n').trim()
        val last = points.last()
        points[points.lastIndex] = last.copy(description = desc, rawText = last.rawText + tail.substringBefore('\n'))
        leftover.append(tail.substringAfter('\n', ""))
    } else {
        leftover.append(tail)
    }
    return LabelledResult(points, unlabelled, leftover.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
}
