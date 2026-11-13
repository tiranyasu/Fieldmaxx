package com.fieldmaxx.core.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

enum class CoordFormat { DECIMAL, DMS }

/** A coordinate pair found in text; [start] inclusive and [end] exclusive index into the searched text. */
data class CoordMatch(
    val lat: Double,
    val lon: Double,
    val start: Int,
    val end: Int,
    val swapped: Boolean,
    val format: CoordFormat,
)

private const val EARTH_RADIUS_M = 6371008.8
private const val FEET_PER_METER = 3.280839895

private val MULTI_SPACE = Regex(" {2,}")

/**
 * Cleans text extracted from PDFs/DOCX: maps U+2212 (minus sign) to `-` and non-breaking spaces
 * (U+00A0, U+202F) to spaces (the en dash U+2013 is left alone: it is a separator), unifies line breaks, turns tabs into spaces, collapses
 * runs of spaces, trims each line, and re-joins a number split across lines (a line ending in
 * `-` or `,` followed by a line starting with a digit or `-`).
 */
fun normalizeText(raw: String): String {
    val lines = raw.replace('\u2212', '-').replace('\u00A0', ' ').replace('\u202F', ' ')
        .replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ')
        .split('\n')
        .map { MULTI_SPACE.replace(it, " ").trim() }
    val out = ArrayList<String>()
    for (line in lines) {
        val prev = out.lastOrNull()
        val first = line.firstOrNull()
        if (prev != null && (prev.endsWith("-") || prev.endsWith(",")) && first != null && (first.isDigit() || first == '-')) {
            out[out.lastIndex] = prev + line
        } else {
            out.add(line)
        }
    }
    return out.joinToString("\n")
}

private const val NUM = """-?\d{1,3}\.\d{3,}"""
private val DECIMAL_PAIR = Regex("""(?<![\d.])($NUM)(?:\s*,\s*|\s+)($NUM)(?!\d)""")

private const val DEG = """(\d{1,3})\s*°\s*(\d{1,2})\s*['′]\s*(\d{1,2}(?:\.\d+)?)\s*(?:["″”]|'')"""
private val DMS_PAIR = Regex("""$DEG\s*([NSns])\s*,?\s*$DEG\s*([EWew])""")

private fun inRange(lat: Double, lon: Double) = abs(lat) <= 90.0 && abs(lon) <= 180.0

private fun dmsToDecimal(d: String, m: String, s: String, hemi: String): Double {
    val v = d.toDouble() + m.toDouble() / 60.0 + s.toDouble() / 3600.0
    return if (hemi.uppercase() in setOf("S", "W")) -v else v
}

/** Finds decimal-degree and DMS coordinate pairs, in text order. Out-of-range pairs are dropped. */
fun findCoordinatePairs(text: String): List<CoordMatch> {
    val found = ArrayList<CoordMatch>()
    for (m in DECIMAL_PAIR.findAll(text)) {
        val a = m.groupValues[1].toDouble()
        val b = m.groupValues[2].toDouble()
        val match = when {
            inRange(a, b) -> CoordMatch(a, b, m.range.first, m.range.last + 1, false, CoordFormat.DECIMAL)
            abs(a) > 90.0 && inRange(b, a) -> CoordMatch(b, a, m.range.first, m.range.last + 1, true, CoordFormat.DECIMAL)
            else -> null
        }
        if (match != null) found.add(match)
    }
    for (m in DMS_PAIR.findAll(text)) {
        val g = m.groupValues
        val lat = dmsToDecimal(g[1], g[2], g[3], g[4])
        val lon = dmsToDecimal(g[5], g[6], g[7], g[8])
        if (inRange(lat, lon)) found.add(CoordMatch(lat, lon, m.range.first, m.range.last + 1, false, CoordFormat.DMS))
    }
    return found.sortedBy { it.start }
}

/** Great-circle distance (haversine) in metres. */
fun distanceMeters(a: LatLon, b: LatLon): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
}

fun metersToFeet(m: Double): Double = m * FEET_PER_METER
