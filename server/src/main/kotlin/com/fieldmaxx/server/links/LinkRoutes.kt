package com.fieldmaxx.server.links

import com.fieldmaxx.core.model.LinkCheck
import com.fieldmaxx.server.Ctx
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable data class LinkCheckRequest(val url: String)

/** Opens the Field Notes link and reports the folder name, or a readable reason it can't be used. */
fun checkLink(ctx: Ctx, url: String): LinkCheck =
    if (url.isBlank()) LinkCheck(ok = false, error = "Paste the Field Notes link first")
    else try {
        LinkCheck(ok = true, rootName = ctx.destinations(url.trim()).rootName())
    } catch (e: Exception) {
        LinkCheck(ok = false, error = e.message ?: "Could not open that link")
    }

fun Route.linkRoutes(ctx: Ctx) {
    authenticate("bearer") {
        route("/links") {
            post("/check") {
                val r = call.receive<LinkCheckRequest>()
                call.respond(withContext(Dispatchers.IO) { checkLink(ctx, r.url) })
            }
        }
    }
}
