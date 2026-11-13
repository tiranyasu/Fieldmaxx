package com.fieldmaxx.server.auth

import com.fieldmaxx.core.model.User
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.HttpError
import com.fieldmaxx.server.users.changePassword
import com.fieldmaxx.server.users.findWithHash
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

private const val MAX_FAILURES = 10
private const val WINDOW_MS = 15 * 60 * 1000L

@Serializable data class LoginRequest(val username: String, val password: String)
@Serializable data class LoginResponse(val token: String, val user: User)
@Serializable data class PasswordRequest(val current: String, val next: String)
@Serializable data class UserBody(val user: User)

private const val WRONG = "Wrong username or password"

fun Route.authRoutes(ctx: Ctx) {
    post("/auth/login") {
        val req = call.receive<LoginRequest>()
        val username = req.username.trim()
        val now = ctx.clock.millis()
        val key = username.lowercase()
        // Reserve an attempt row before the slow verify, so parallel logins cannot all slip under the limit.
        val locked = ctx.db.tx {
            val recent = prepareStatement("SELECT COUNT(*) FROM login_failures WHERE username = ? AND at > ?").use { ps ->
                ps.setString(1, key); ps.setLong(2, now - WINDOW_MS)
                ps.executeQuery().use { it.next(); it.getInt(1) }
            }
            if (recent >= MAX_FAILURES) true else {
                prepareStatement("INSERT INTO login_failures(username, at) VALUES (?, ?)").use {
                    it.setString(1, key); it.setLong(2, now); it.executeUpdate()
                }
                false
            }
        }
        if (locked) throw HttpError(HttpStatusCode.TooManyRequests, "Too many attempts. Try again in 15 minutes.")
        val found = ctx.db.read { findWithHash(username) }
        if (found == null || !verifyPassword(found.second, req.password)) {
            throw HttpError(HttpStatusCode.Unauthorized, WRONG)
        }
        val user = found.first
        if (user.disabled) throw HttpError(HttpStatusCode.Forbidden, "This account is disabled")
        ctx.db.tx {
            prepareStatement("DELETE FROM login_failures WHERE username = ?").use { it.setString(1, username.lowercase()); it.executeUpdate() }
        }
        call.respond(LoginResponse(createSession(ctx, user.id), user))
    }
    authenticate("bearer") {
        post("/auth/logout") {
            deleteSession(ctx, call.principalOrFail().token)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/auth/me") { call.respond(UserBody(call.user())) }
        post("/auth/password") {
            val req = call.receive<PasswordRequest>()
            val p = call.principalOrFail()
            changePassword(ctx, p.user, req.current, req.next, tokenHash(p.token))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
