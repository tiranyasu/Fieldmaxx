package com.fieldmaxx.server

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.server.auth.authRoutes
import com.fieldmaxx.server.auth.installAuth
import com.fieldmaxx.server.users.userRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.Serializable

@Serializable data class ErrorBody(val error: String)
@Serializable data class VersionBody(val build: String)

/** Thrown by routes to produce a specific HTTP status with an `{error}` body. */
class HttpError(val status: HttpStatusCode, message: String) : RuntimeException(message)

fun Application.fieldmaxx(ctx: Ctx) {
    install(ContentNegotiation) { json(FmxJson) }
    install(WebSockets)
    install(StatusPages) {
        exception<HttpError> { call, e -> call.respond(e.status, ErrorBody(e.message ?: e.status.description)) }
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(e.message ?: "Bad request"))
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("Internal server error"))
        }
    }
    installAuth(ctx)
    routing {
        route("/api") {
            authRoutes(ctx)
            userRoutes(ctx)
            get("/version") { call.respond(VersionBody(ctx.config.buildId)) }
        }
    }
}
