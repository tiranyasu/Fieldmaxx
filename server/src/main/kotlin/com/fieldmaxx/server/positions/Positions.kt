package com.fieldmaxx.server.positions

import com.fieldmaxx.core.model.ClientMessage
import com.fieldmaxx.core.model.CrewPosition
import com.fieldmaxx.core.model.User
import com.fieldmaxx.server.Ctx

private const val HIDE_AFTER_MS = 3_600_000L

private const val SELECT_POS =
    "SELECT p.user_id, u.display_name, p.lat, p.lon, p.accuracy_m, p.at, p.paused FROM positions p JOIN users u ON u.id = p.user_id"

private fun java.sql.ResultSet.pos() =
    CrewPosition(getString(1), getString(2), getDouble(3), getDouble(4), getDouble(5), getLong(6), getInt(7) != 0)

/** Upserts the user's latest position (never touches the change log). Paused keeps the last coordinates. */
fun savePosition(ctx: Ctx, user: User, m: ClientMessage): CrewPosition {
    val now = ctx.clock.millis()
    return ctx.db.tx {
        when (m) {
            is ClientMessage.PositionUpdate -> prepareStatement(
                "INSERT INTO positions(user_id, lat, lon, accuracy_m, altitude_m, at, paused) VALUES (?,?,?,?,?,?,0) " +
                    "ON CONFLICT(user_id) DO UPDATE SET lat=excluded.lat, lon=excluded.lon, accuracy_m=excluded.accuracy_m, " +
                    "altitude_m=excluded.altitude_m, at=excluded.at, paused=0",
            ).use {
                it.setString(1, user.id); it.setDouble(2, m.lat); it.setDouble(3, m.lon); it.setDouble(4, m.accuracyM)
                if (m.altitudeM == null) it.setNull(5, java.sql.Types.REAL) else it.setDouble(5, m.altitudeM!!)
                it.setLong(6, now); it.executeUpdate()
            }
            ClientMessage.Paused -> {
                val n = prepareStatement("UPDATE positions SET paused = 1, at = ? WHERE user_id = ?").use {
                    it.setLong(1, now); it.setString(2, user.id); it.executeUpdate()
                }
                require(n == 1) { "No position to pause yet" }
            }
        }
        prepareStatement("$SELECT_POS WHERE p.user_id = ?").use { ps ->
            ps.setString(1, user.id)
            ps.executeQuery().use { it.next(); it.pos() }
        }
    }
}

/** Latest position per crew member, omitting any older than 1 hour. */
fun listPositions(ctx: Ctx): List<CrewPosition> {
    val cutoff = ctx.clock.millis() - HIDE_AFTER_MS
    return ctx.db.read {
        prepareStatement("$SELECT_POS WHERE p.at >= ? ORDER BY p.at DESC").use { ps ->
            ps.setLong(1, cutoff)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.pos()) } }
        }
    }
}
