package com.fieldmaxx.server.exports

import com.fieldmaxx.core.model.Attachment
import com.fieldmaxx.core.model.PointStatus
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.jobs.toPoint
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import org.dhatim.fastexcel.Workbook

val POLE_COLUMNS = listOf(
    "Point", "Original lat", "Original lon", "Moved lat", "Moved lon", "Line height (ft)", "Anchor",
    "Self-supporting/Lashed", "Tags", "Comment", "Photos", "Tech", "Closed at",
)

private val NUM = Regex("""^\s*(\d+)(.*)$""")

private fun numKey(num: String): Pair<Long, String> {
    val m = NUM.find(num) ?: return Long.MAX_VALUE to num.uppercase()
    return m.groupValues[1].toLong() to m.groupValues[2].trim().uppercase()
}

private fun fmt(d: Double): String = if (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

/**
 * One row per non-deleted POLE point, sorted by the number then its suffix (1, 2, 10, 14, 14B). Tech and Closed at come
 * from the latest close/can't-shoot event of a point that is still closed; the time is ISO-8601 local date-time in the server's zone.
 */
fun poleDataRows(ctx: Ctx, jobId: String): List<List<String>> = ctx.db.read {
    val points = prepareStatement("SELECT * FROM points WHERE job_id = ? AND deleted_at IS NULL AND type = 'POLE'").use { ps ->
        ps.setString(1, jobId)
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toPoint()) } }
    }
    val photoCounts = prepareStatement("SELECT point_id, COUNT(*) FROM photos WHERE job_id = ? AND status != 'removed' GROUP BY point_id").use { ps ->
        ps.setString(1, jobId)
        ps.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) } }
    }
    // Ascending, so a later event overwrites an earlier one.
    val closers = HashMap<String, Pair<String, Long>>()
    prepareStatement(
        "SELECT json_extract(e.data_json, '\$.pointId'), COALESCE(u.display_name, ''), e.at FROM events e LEFT JOIN users u ON u.id = e.user_id " +
            "WHERE e.job_id = ? AND e.kind IN ('point.close', 'point.cantShoot') ORDER BY e.at, e.id",
    ).use { ps ->
        ps.setString(1, jobId)
        ps.executeQuery().use { rs -> while (rs.next()) rs.getString(1)?.let { closers[it] = rs.getString(2) to rs.getLong(3) } }
    }
    val zone = ctx.clock.zone
    points.sortedWith(compareBy<Point>({ numKey(it.num).first }, { numKey(it.num).second }, { it.label }))
        .map { p ->
            val moved = p.origLat != null && p.origLon != null
            val pole = p.pole
            val closer = if (p.status != PointStatus.open) closers[p.id] else null
            listOf(
                p.label,
                (if (moved) p.origLat!! else p.lat).toString(), (if (moved) p.origLon!! else p.lon).toString(),
                if (moved) p.lat.toString() else "", if (moved) p.lon.toString() else "",
                pole?.lineHeightFt?.let(::fmt) ?: "",
                if (pole?.anchor == true) "Yes" else "No",
                when (pole?.attachment) { Attachment.self_supporting -> "Self-supporting"; Attachment.lashed -> "Lashed"; null -> "" },
                pole?.tags?.joinToString("; ") ?: "",
                p.comment,
                (photoCounts[p.id] ?: 0).toString(),
                closer?.first ?: "",
                closer?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.second), zone).toString() } ?: "",
            )
        }
}

private fun csvCell(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

/** Columns holding user text (point, tags, comment, tech); the rest are numbers or fixed words. */
private val TEXT_COLUMNS = setOf(0, 8, 9, 11)

/** Spreadsheet apps evaluate cells that start with these; a leading quote makes them plain text. */
private fun defuse(s: String) = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s

fun poleDataCsv(rows: List<List<String>>): String =
    (listOf(POLE_COLUMNS) + rows.map { r -> r.mapIndexed { i, v -> if (i in TEXT_COLUMNS) defuse(v) else v } })
        .joinToString("\r\n", postfix = "\r\n") { r -> r.joinToString(",", transform = ::csvCell) }

fun poleDataXlsx(rows: List<List<String>>): ByteArray {
    val out = ByteArrayOutputStream()
    Workbook(out, "Fieldmaxx", "1.0").use { wb ->
        val ws = wb.newWorksheet("Pole data")
        (listOf(POLE_COLUMNS) + rows).forEachIndexed { r, row -> row.forEachIndexed { c, v -> ws.value(r, c, v) } }
        for (c in POLE_COLUMNS.indices) ws.width(c, 16.0)
    }
    return out.toByteArray()
}
