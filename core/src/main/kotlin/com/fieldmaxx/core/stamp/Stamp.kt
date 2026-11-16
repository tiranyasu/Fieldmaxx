package com.fieldmaxx.core.stamp

import com.fieldmaxx.core.geo.metersToFeet
import com.fieldmaxx.core.model.PoleData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

data class StampMeta(
    val jobName: String,
    val pointLabel: String,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val headingDeg: Double?,
    val takenAtMs: Long,
    val zone: ZoneId,
    val gpsStale: Boolean,
    val fromGallery: Boolean,
    val extra: String? = null,
)

data class StampLayout(
    val fontPx: Int,
    val x: Int,
    val firstBaselineY: Int,
    val lineHeightPx: Int,
    val shadowBlurPx: Int,
)

private val COMPASS_POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss", Locale.US)

/** Nearest of the eight compass points; each spans 45 degrees centred on its heading. */
fun compass8(deg: Double): String {
    val normalized = ((deg % 360.0) + 360.0) % 360.0
    return COMPASS_POINTS[(floor((normalized + 22.5) / 45.0).toInt()) % 8]
}

fun formatStampTime(ms: Long, zone: ZoneId): String =
    TIME_FORMAT.format(Instant.ofEpochMilli(ms).atZone(zone))

fun stampLines(m: StampMeta): List<String> {
    val lines = mutableListOf("${m.jobName} · ${m.pointLabel}")

    if (m.lat != null && m.lon != null) {
        val coords = String.format(Locale.US, "%.6f, %.6f", m.lat, m.lon)
        lines += if (m.gpsStale) "$coords (GPS stale)" else coords
        if (m.accuracyM != null) {
            val accuracy = "±${metersToFeet(m.accuracyM).roundToInt()} ft"
            lines += if (m.altitudeM != null) {
                "$accuracy · alt ${metersToFeet(m.altitudeM).roundToInt()} ft"
            } else {
                accuracy
            }
        }
    } else {
        lines += "No GPS"
    }

    lines += if (m.headingDeg != null) {
        val degrees = ((m.headingDeg.roundToInt() % 360) + 360) % 360
        "${compass8(m.headingDeg)} $degrees°"
    } else {
        "Compass n/a"
    }

    val time = formatStampTime(m.takenAtMs, m.zone)
    lines += if (m.fromGallery) "$time (from gallery)" else time
    if (!m.extra.isNullOrBlank()) lines += wrapStampExtra(m.extra)
    return lines
}

const val STAMP_LINE_MAX = 48

/** Tags, then height above ground (poles only), then the comment, joined with " · "; null when empty. */
fun stampExtraLine(type: String, pole: PoleData?, comment: String): String? {
    val parts = mutableListOf<String>()
    if (pole != null) parts += pole.tags
    if (type == "POLE" && pole?.lineHeightFt != null) {
        val h = pole.lineHeightFt
        val text = if (h == Math.floor(h)) h.toLong().toString() else h.toString()
        parts += "HOA $text"
    }
    if (comment.isNotBlank()) parts += comment.trim()
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

private fun wrapStampExtra(extra: String): List<String> {
    val out = mutableListOf<String>()
    var cur = ""
    for (seg in extra.split(" · ")) {
        if (seg.length > STAMP_LINE_MAX) {
            if (cur.isNotEmpty()) out += cur
            val pieces = wrapWords(seg)
            out += pieces.dropLast(1)
            cur = pieces.last()
        } else if (cur.isEmpty()) {
            cur = seg
        } else if (cur.length + 3 + seg.length <= STAMP_LINE_MAX) {
            cur += " · $seg"
        } else {
            out += cur
            cur = seg
        }
    }
    if (cur.isNotEmpty()) out += cur
    return out
}

private fun wrapWords(text: String): List<String> {
    val out = mutableListOf<String>()
    var cur = ""
    for (word in text.split(" ").filter { it.isNotEmpty() }) {
        var w = word
        while (w.length > STAMP_LINE_MAX) {
            if (cur.isNotEmpty()) { out += cur; cur = "" }
            out += w.take(STAMP_LINE_MAX)
            w = w.drop(STAMP_LINE_MAX)
        }
        cur = when {
            cur.isEmpty() -> w
            cur.length + 1 + w.length <= STAMP_LINE_MAX -> "$cur $w"
            else -> { out += cur; w }
        }
    }
    if (cur.isNotEmpty()) out += cur
    return if (out.isEmpty()) listOf("") else out
}

fun stampLayout(width: Int, height: Int, lineCount: Int): StampLayout {
    val fontPx = (min(width, height) * 0.028).roundToInt()
    val lineHeightPx = (fontPx * 1.25).roundToInt()
    return StampLayout(
        fontPx = fontPx,
        x = fontPx,
        firstBaselineY = height - fontPx - lineHeightPx * (lineCount - 1),
        lineHeightPx = lineHeightPx,
        shadowBlurPx = (fontPx * 0.25).roundToInt(),
    )
}
