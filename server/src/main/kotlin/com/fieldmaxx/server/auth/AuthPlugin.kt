package com.fieldmaxx.server.auth

import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.HttpError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.application.Application

/** Principal: the signed-in user plus the token they used (needed to keep it on password change). */
data class UserPrincipal(val user: User, val token: String)

fun Application.installAuth(ctx: Ctx) {
    install(Authentication) {
        bearer("bearer") {
            authenticate { cred -> sessionUser(ctx, cred.token)?.let { UserPrincipal(it, cred.token) } }
        }
    }
}

fun ApplicationCall.principalOrFail(): UserPrincipal =
    principal<UserPrincipal>() ?: throw HttpError(HttpStatusCode.Unauthorized, "Sign in required")

fun ApplicationCall.user(): User = principalOrFail().user

fun ApplicationCall.requireAdmin(): User =
    user().also { if (it.role != Role.admin) throw HttpError(HttpStatusCode.Forbidden, "Admins only") }
