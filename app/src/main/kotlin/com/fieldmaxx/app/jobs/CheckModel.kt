package com.fieldmaxx.app.jobs

import com.fieldmaxx.core.geo.CoordMatch
import com.fieldmaxx.core.geo.findCoordinatePairs
import com.fieldmaxx.core.geo.normalizeText
import com.fieldmaxx.core.model.DraftPoint
import com.fieldmaxx.core.model.ImportFlag
import com.fieldmaxx.core.model.ImportPreview
import com.fieldmaxx.core.model.JobImportInput
import com.fieldmaxx.core.model.UnreadLine
import com.fieldmaxx.core.naming.ADD_CATEGORIES
import com.fieldmaxx.core.naming.KNOWN_TYPES
import com.fieldmaxx.core.naming.isQueryLayer
import com.fieldmaxx.core.naming.pointLabel
import java.math.BigDecimal

/**
 * The check screen's work on the files the server read: the points of each import as the tech left them ([edits], by
 * import id), the "couldn't read" lines they chose to ignore ([ignoredUnread], keys from [unreadKey]) and each import's
 * layer ([layers]). A line that is fixed leaves its preview's `unread` list and becomes a point; every other line stays
 * listed until it is ignored, so nothing the reader missed goes unseen.
 */
data class CheckState(
    val previews: List<ImportPreview>,
    val edits: Map<String, List<DraftPoint>>,
    val ignoredUnread: Set<String>,
    val layers: Map<String, String>,
)

const val COORDINATES_NOT_RECOGNISED = "Coordinates not recognised"
const val LABEL_NOT_RECOGNISED = "Enter a label like POLE 9"

/** Every file as read: all its points, nothing ignored, the layer the reader suggests. */
fun checkStateOf(previews: List<ImportPreview>): CheckState = CheckState(emptyList(), emptyMap(), emptySet(), emptyMap()).withPreviews(previews)

/** Adds more read files (a retry, say) and keeps the work done on the others. */
fun CheckState.withPreviews(more: List<ImportPreview>): CheckState {
    val added = more.filter { p -> previews.none { it.importId == p.importId } }
    return copy(
        previews = previews + added,
        edits = edits + added.associate { it.importId to it.points },
        layers = layers + added.associate { it.importId to it.layerSuggestion },
    )
}

/** Drops an import, once it has been added to its job. */
fun CheckState.without(importId: String): CheckState = copy(
    previews = previews.filter { it.importId != importId },
    edits = edits - importId,
    ignoredUnread = ignoredUnread.filterTo(HashSet()) { !it.startsWith(importId + KEY_SEPARATOR) },
    layers = layers - importId,
)

/** "Found 7 POLE points" per type, in the order the types first appear, then "1 needs coordinates" when any do. */
fun summary(points: List<DraftPoint>): List<String> {
    val found = points.groupingBy { it.type }.eachCount().map { (type, n) -> "Found $n $type point${if (n == 1) "" else "s"}" }
    val missing = points.count { it.lat == null || it.lon == null }
    return if (missing == 0) found else found + "$missing ${if (missing == 1) "needs" else "need"} coordinates"
}

/** Every point has coordinates (fixed or removed) and every line that couldn't be read is fixed or ignored. */
fun canSave(s: CheckState): Boolean = s.previews.isNotEmpty() && pointsWithoutCoordinates(s) == 0 && unreadLeft(s) == 0

fun pointsWithoutCoordinates(s: CheckState): Int = s.previews.sumOf { p -> pointsOf(s, p.importId).count { it.lat == null || it.lon == null } }

/** Lines that are neither fixed nor ignored. */
fun unreadLeft(s: CheckState): Int = s.previews.sumOf { p -> p.unread.count { !isIgnored(s, p.importId, it) } }

/**
 * Turns a line the reader couldn't read into a point labelled [label] at [coords] (decimal or DMS, read by the same
 * parser as the files), linked to the PDF page the line came from. Throws [IllegalArgumentException] with
 * [COORDINATES_NOT_RECOGNISED] or [LABEL_NOT_RECOGNISED].
 */
fun fixUnread(s: CheckState, importId: String, line: UnreadLine, label: String, coords: String): CheckState {
    val key = parseLabel(label)
    val c = parseCoordinates(coords)
    val preview = previewOf(s, importId)
    val at = preview.unread.indexOf(line)
    require(at >= 0) { "That line is not in ${preview.fileName}" }
    val pages = preview.refImages.filter { it.kind == "page" && it.page != null && it.page == line.sourcePage }.map { it.id }
    val point = DraftPoint(
        layer = key.layer, type = key.type, num = key.num, label = key.label, lat = c.lat, lon = c.lon, description = "",
        flags = if (c.swapped) listOf(ImportFlag.swapped) else emptyList(), sourcePage = line.sourcePage, refImageIds = pages,
        rawText = line.text,
    )
    val unread = preview.unread.filterIndexed { i, _ -> i != at }
    return s.copy(
        previews = s.previews.map { if (it.importId == importId) it.copy(unread = unread) else it },
        edits = s.edits + (importId to stored(s, importId) + point),
        ignoredUnread = if (line in unread) s.ignoredUnread else s.ignoredUnread - unreadKey(importId, line),
    )
}

/** What each file adds to the job: its layer, its notes and its points as checked. */
fun toJobImports(s: CheckState): List<JobImportInput> =
    s.previews.map { JobImportInput(it.importId, layerOf(s, it), it.notes, pointsOf(s, it.importId)) }

fun flagText(f: ImportFlag): String = when (f) {
    ImportFlag.swapped -> "Possibly swapped"
    ImportFlag.sign_mismatch -> "Sign differs"
    ImportFlag.far_from_job -> "Far from the rest"
    ImportFlag.duplicate_label -> "Duplicate label"
    ImportFlag.unlabelled -> "No label"
    ImportFlag.no_coordinates -> "No coordinates"
}

/**
 * The import's points as they will be saved. When a query file's layer is renamed (`QUERY 1` → `QUERY 2`), its items
 * follow: `Query 1-14` becomes `Query 2-14`, so the label, the layer and the photos' folder agree. Other files keep
 * their labels; the layer only names them.
 */
fun pointsOf(s: CheckState, importId: String): List<DraftPoint> {
    val preview = previewOf(s, importId)
    val from = preview.layerSuggestion
    val to = layerOf(s, preview)
    val points = stored(s, importId)
    if (to.isEmpty() || to == from || !(isQueryLayer(from) || isQueryLayer(to))) return points
    return points.map { p -> if (p.layer == from && p.type == from) p.copy(layer = to, type = to, label = pointLabel(to, to, p.num)) else p }
}

/**
 * Changes a point from the tech's form. [coords] may be blank (the point stays without coordinates); otherwise it must
 * read as one pair. Flags about the old position go when it moves, "No label" when the label changes. Throws
 * [IllegalArgumentException] with [COORDINATES_NOT_RECOGNISED] or [LABEL_NOT_RECOGNISED].
 */
fun editPoint(s: CheckState, importId: String, index: Int, label: String, coords: String, description: String): CheckState {
    val points = stored(s, importId)
    val old = points[index]
    val key = parseLabel(label)
    val c = if (coords.isBlank()) null else parseCoordinates(coords)
    val moved = c?.lat != old.lat || c?.lon != old.lon
    var flags = old.flags
    if (moved) {
        flags = flags - POSITION_FLAGS
        if (c == null) flags = flags + ImportFlag.no_coordinates else if (c.swapped) flags = flags + ImportFlag.swapped
    }
    if (key.label != old.label) flags = flags - ImportFlag.unlabelled
    val edited = old.copy(
        layer = key.layer, type = key.type, num = key.num, label = key.label, lat = c?.lat, lon = c?.lon,
        description = description.trim(), flags = flags,
    )
    return s.copy(edits = s.edits + (importId to points.mapIndexed { i, p -> if (i == index) edited else p }))
}

fun removePoint(s: CheckState, importId: String, index: Int): CheckState =
    s.copy(edits = s.edits + (importId to stored(s, importId).filterIndexed { i, _ -> i != index }))

/** [ignored] false undoes an ignore. */
fun ignoreUnread(s: CheckState, importId: String, line: UnreadLine, ignored: Boolean = true): CheckState {
    val key = unreadKey(importId, line)
    return s.copy(ignoredUnread = if (ignored) s.ignoredUnread + key else s.ignoredUnread - key)
}

fun isIgnored(s: CheckState, importId: String, line: UnreadLine): Boolean = unreadKey(importId, line) in s.ignoredUnread

/** Identifies a line within its import by what it says and where. */
fun unreadKey(importId: String, line: UnreadLine): String = importId + KEY_SEPARATOR + line.sourcePage + KEY_SEPARATOR + line.text

fun setLayer(s: CheckState, importId: String, layer: String): CheckState = s.copy(layers = s.layers + (importId to layer))

/** The layer field's value; a blank one leaves each point its own layer. */
fun layerOf(s: CheckState, preview: ImportPreview): String = (s.layers[preview.importId] ?: preview.layerSuggestion).trim()

/** A label to start the fix form with: the type and number in the line, if it has them. */
fun suggestedLabel(line: UnreadLine): String =
    TYPE_IN_TEXT.find(line.text)?.let { "${it.groupValues[1].uppercase()} ${it.groupValues[2].uppercase()}" }.orEmpty()

/** `A05J9XE.pdf` → `A05J9XE`. */
fun jobNameFor(fileName: String): String = fileName.trim().substringBeforeLast('.').trim()

/** The coordinates as the forms show them: exact, and always readable back by the parser (≥ 3 decimals). */
fun coordinatesText(p: DraftPoint): String {
    val lat = p.lat ?: return ""
    val lon = p.lon ?: return ""
    return "${decimal(lat)}, ${decimal(lon)}"
}

private const val KEY_SEPARATOR = "\u0000"
private val POSITION_FLAGS = setOf(ImportFlag.swapped, ImportFlag.sign_mismatch, ImportFlag.far_from_job, ImportFlag.no_coordinates)
private val TYPE_IN_TEXT = Regex("""\b(${KNOWN_TYPES.joinToString("|")})\s*#?\s*(\d{1,4}[A-Za-z]?)\b""", RegexOption.IGNORE_CASE)
private val QUERY_LABEL = Regex("""^query\s*(\d+)\s*-\s*(\d{1,4}[A-Za-z]?)$""", RegexOption.IGNORE_CASE)
private val TYPE_LABEL = Regex("""^(.*?[A-Za-z].*?)\s*#?\s*(\d{1,4}[A-Za-z]?)$""")

private class LabelKey(val layer: String, val type: String, val num: String) {
    val label: String get() = pointLabel(layer, type, num)
}

/** `POLE 14B`, `mh 10`, `Query 1-25A`; a type the app knows takes its usual spelling. */
private fun parseLabel(text: String): LabelKey {
    val t = text.trim().replace(Regex("""\s+"""), " ")
    QUERY_LABEL.matchEntire(t)?.let { m ->
        val layer = "QUERY ${m.groupValues[1]}"
        return LabelKey(layer, layer, m.groupValues[2].uppercase())
    }
    val m = TYPE_LABEL.matchEntire(t) ?: throw IllegalArgumentException(LABEL_NOT_RECOGNISED)
    val typed = m.groupValues[1].trim()
    val type = (KNOWN_TYPES + ADD_CATEGORIES).firstOrNull { it.equals(typed, ignoreCase = true) } ?: typed
    return LabelKey(type, type, m.groupValues[2].uppercase())
}

/** Exactly one coordinate pair. */
private fun parseCoordinates(text: String): CoordMatch =
    findCoordinatePairs(normalizeText(text)).singleOrNull() ?: throw IllegalArgumentException(COORDINATES_NOT_RECOGNISED)

private fun decimal(v: Double): String = BigDecimal.valueOf(v).let { if (it.scale() < 3) it.setScale(3) else it }.toPlainString()

private fun previewOf(s: CheckState, importId: String): ImportPreview =
    s.previews.firstOrNull { it.importId == importId } ?: throw IllegalArgumentException("No file with import $importId")

private fun stored(s: CheckState, importId: String): List<DraftPoint> = s.edits[importId] ?: previewOf(s, importId).points
