package com.fieldmaxx.server.users

import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.HttpError
import com.fieldmaxx.server.auth.hashPassword
import com.fieldmaxx.server.auth.verifyPassword
import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

const val MIN_PASSWORD = 8

/** R92: why the last active admin can't be disabled or made a tech. */
const val LAST_ADMIN = "Fieldmaxx needs at least one active admin. Make another user an admin first, then change this one."

fun checkPassword(pw: String) = require(pw.length >= MIN_PASSWORD) { "Password must be at least $MIN_PASSWORD characters" }

internal fun ResultSet.toUser() =
    User(getString("id"), getString("username"), getString("display_name"), Role.valueOf(getString("role")), getInt("disabled") != 0)

fun createUser(ctx: Ctx, username: String, displayName: String, password: String, role: Role): User {
    val name = username.trim()
    require(name.isNotEmpty()) { "Username is required" }
    require(displayName.isNotBlank()) { "Display name is required" }
    checkPassword(password)
    val hash = hashPassword(password)
    val id = UUID.randomUUID().toString()
    return ctx.db.tx {
        val taken = prepareStatement("SELECT 1 FROM users WHERE username = ?").use { ps ->
            ps.setString(1, name); ps.executeQuery().use { it.next() }
        }
        if (taken) throw HttpError(HttpStatusCode.Conflict, "That username is taken")
        prepareStatement("INSERT INTO users(id, username, display_name, password_hash, role, disabled, created_at) VALUES (?,?,?,?,?,0,?)").use {
            it.setString(1, id); it.setString(2, name); it.setString(3, displayName.trim())
            it.setString(4, hash); it.setString(5, role.name); it.setLong(6, ctx.clock.millis())
            it.executeUpdate()
        }
        User(id, name, displayName.trim(), role, false)
    }
}

/** Case-insensitive lookup (the column is NOCASE) returning the user and its password hash. */
internal fun Connection.findWithHash(username: String): Pair<User, String>? =
    prepareStatement("SELECT * FROM users WHERE username = ?").use { ps ->
        ps.setString(1, username)
        ps.executeQuery().use { if (it.next()) it.toUser() to it.getString("password_hash") else null }
    }

fun listUsers(ctx: Ctx): List<User> = ctx.db.read {
    createStatement().use { st ->
        st.executeQuery("SELECT * FROM users ORDER BY username COLLATE NOCASE").use { rs ->
            buildList { while (rs.next()) add(rs.toUser()) }
        }
    }
}

/**
 * Changes only the fields given. R92: refused (409, [LAST_ADMIN]) when it would disable or demote the last active admin,
 * which would leave nobody to reach the admin screens; checked inside the write, so two admins changing each other at
 * once can't both get through.
 */
fun updateUser(ctx: Ctx, id: String, displayName: String?, role: Role?, disabled: Boolean?, password: String?): User {
    password?.let(::checkPassword)
    require(displayName == null || displayName.isNotBlank()) { "Display name is required" }
    val hash = password?.let(::hashPassword)
    val updated = ctx.db.tx {
        val current = prepareStatement("SELECT * FROM users WHERE id = ?").use { ps -> ps.setString(1, id); ps.executeQuery().use { if (it.next()) it.toUser() else null } }
            ?: throw HttpError(HttpStatusCode.NotFound, "No such user")
        val stopsBeingAdmin = disabled == true || (role != null && role != Role.admin)
        if (current.role == Role.admin && !current.disabled && stopsBeingAdmin) {
            val others = prepareStatement("SELECT COUNT(*) FROM users WHERE role = 'admin' AND disabled = 0 AND id != ?").use { ps ->
                ps.setString(1, id); ps.executeQuery().use { it.next(); it.getInt(1) }
            }
            if (others == 0) throw HttpError(HttpStatusCode.Conflict, LAST_ADMIN)
        }
        fun set(col: String, bind: PreparedStatement.() -> Unit) =
            prepareStatement("UPDATE users SET $col = ? WHERE id = ?").use { it.bind(); it.setString(2, id); it.executeUpdate() }
        displayName?.let { set("display_name") { setString(1, it.trim()) } }
        role?.let { set("role") { setString(1, it.name) } }
        disabled?.let { set("disabled") { setInt(1, if (it) 1 else 0) } }
        hash?.let { set("password_hash") { setString(1, it) } }
        if (disabled == true || hash != null) {
            prepareStatement("DELETE FROM sessions WHERE user_id = ?").use { it.setString(1, id); it.executeUpdate() }
        }
        prepareStatement("SELECT * FROM users WHERE id = ?").use { ps -> ps.setString(1, id); ps.executeQuery().use { it.next(); it.toUser() } }
    }
    if (disabled == true || hash != null) ctx.hub.disconnect(id)
    return updated
}

/** Changes a password after checking the current one; the caller's own session ([keepTokenHash]) stays, others end. */
fun changePassword(ctx: Ctx, user: User, current: String, next: String, keepTokenHash: String) {
    checkPassword(next)
    val stored = ctx.db.read {
        prepareStatement("SELECT password_hash FROM users WHERE id = ?").use { ps ->
            ps.setString(1, user.id); ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
    }
    if (stored == null || !verifyPassword(stored, current)) throw HttpError(HttpStatusCode.Forbidden, "Current password is wrong")
    val hash = hashPassword(next)
    ctx.db.tx {
        prepareStatement("UPDATE users SET password_hash = ? WHERE id = ?").use { it.setString(1, hash); it.setString(2, user.id); it.executeUpdate() }
        prepareStatement("DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?").use {
            it.setString(1, user.id); it.setString(2, keepTokenHash); it.executeUpdate()
        }
    }
}
