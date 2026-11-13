package com.fieldmaxx.core.naming

import java.util.Locale

val KNOWN_TYPES = listOf("POLE", "PED", "HH", "MH", "BU", "SAI", "PFP", "MDU")
val ADD_CATEGORIES = listOf("POLE", "PED", "HH", "MH", "BU", "SAI", "PFP", "MDU", "CULVERT", "Drainage", "CREEK IMAGE", "MAIL BOX IMAGES", "Street images", "Rear images", "VACANT LOT", "UTILITIES", "Aerial Fielding", "F3 BUILDING DATA")

data class PointKey(val layer: String, val type: String, val num: String, val label: String)

enum class Where { CATEGORY, TOP }

private val QUERY_RE = Regex("""^query\s*(\d+)$""", RegexOption.IGNORE_CASE)
private val WHITESPACE_RUN = Regex("""\s+""")

fun isQueryLayer(layer: String): Boolean = QUERY_RE.matches(layer.trim())

private fun queryNumber(layer: String): String? = QUERY_RE.matchEntire(layer.trim())?.groupValues?.get(1)

fun pointLabel(layer: String, type: String, num: String): String {
    val q = queryNumber(layer)
    return if (q != null) "Query $q-$num" else "$type $num"
}

fun defaultFolderName(label: String): String = label.trim().replace(WHITESPACE_RUN, "_")

fun normalizeFolderName(name: String): String =
    name.lowercase().filterNot { it.isWhitespace() || it == '_' }

fun categoryMatches(folderName: String, layer: String): Boolean {
    val f = normalizeFolderName(folderName)
    val l = normalizeFolderName(layer)
    return f == l || f == l + "s"
}

fun pointFolderMatches(folderName: String, p: PointKey, where: Where): Boolean {
    val f = normalizeFolderName(folderName)
    val q = queryNumber(p.layer)
    // At the top level a bare number is unambiguous only for poles (§6.6.1).
    val bare = if (where == Where.TOP && p.type != "POLE") emptyList() else listOf(p.num)
    val candidates = bare + if (q != null) listOf("query$q-${p.num}", "q$q-${p.num}") else listOf(p.type + p.num, p.type.take(1) + p.num)
    return candidates.any { normalizeFolderName(it) == f }
}

fun photoFileName(folderName: String, lat: Double, lon: Double, counter: Long): String =
    String.format(Locale.US, "%s_%.6f_%.6f_%d.jpg", folderName, lat, lon, counter)

fun markerCode(type: String): String = when {
    type == "POLE" -> "P"
    type in KNOWN_TYPES -> type
    isQueryLayer(type) -> "Q"
    else -> type.take(2).uppercase()
}

private fun numPrefix(num: String): String = num.trimEnd { it.isLetter() }

fun suggestAddedPoleNum(existingNums: List<String>, nearestNum: String): String {
    val base = numPrefix(nearestNum)
    val taken = existingNums.map { it.uppercase() }.toSet()
    return ('B'..'Z').map { "$base$it" }.firstOrNull { it !in taken } ?: "${base}B"
}

fun nextNum(existingNums: List<String>): String =
    ((existingNums.mapNotNull { numPrefix(it).toLongOrNull() }.maxOrNull() ?: 0L) + 1).toString()
