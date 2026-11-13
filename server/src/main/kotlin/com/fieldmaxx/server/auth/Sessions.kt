package com.fieldmaxx.server.auth

import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import com.fieldmaxx.server.Ctx
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private val random = SecureRandom()
private const val DAY_MS = 86_400_000L

fun tokenHash(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

/** Returns the raw token; only its sha256 is stored. */
fun createSession(ctx: Ctx, userId: String): String {
    val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    val now = ctx.clock.millis()
    ctx.db.tx {
        prepareStatement("INSERT INTO sessions(token_hash, user_id, created_at, expires_at) VALUES (?,?,?,?)").use {
            it.setString(1, tokenHash(token)); it.setString(2, userId)
            it.setLong(3, now); it.setLong(4, now + ctx.config.sessionDays * DAY_MS)
            it.executeUpdate()
        }
    }
    return token
}

/** The user behind a live token (and slides its expiry), or null for unknown, expired or disabled. */
fun sessionUser(ctx: Ctx, token: String): User? {
    val now = ctx.clock.millis()
    val hash = tokenHash(token)
    return ctx.db.tx {
        val user = prepareStatement(
            "SELECT u.id, u.username, u.display_name, u.role, u.disabled FROM sessions s JOIN users u ON u.id = s.user_id " +
                "WHERE s.token_hash = ? AND s.expires_at > ?",
        ).use { ps ->
            ps.setString(1, hash); ps.setLong(2, now)
            ps.executeQuery().use { rs ->
                if (rs.next()) User(rs.getString(1), rs.getString(2), rs.getString(3), Role.valueOf(rs.getString(4)), rs.getInt(5) != 0) else null
            }
        }
        if (user == null || user.disabled) null else {
            prepareStatement("UPDATE sessions SET expires_at = ? WHERE token_hash = ?").use {
                it.setLong(1, now + ctx.config.sessionDays * DAY_MS); it.setString(2, hash); it.executeUpdate()
            }
            user
        }
    }
}

fun deleteSession(ctx: Ctx, token: String) {
    ctx.db.tx {
        prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use { it.setString(1, tokenHash(token)); it.executeUpdate() }
    }
}
