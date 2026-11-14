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

/**
 * Opens the Field Notes link and lists its folder (spec §6.6: "can we list the folder?"), then reports the folder's name;
 * or a readable reason it can't be used. A link that opens but whose folder can't be listed through the REST API fails
 * here, at entry, rather than as the red banner once the photos start (m2).
 */
fun checkLink(ctx: Ctx, url: String): LinkCheck =
    if (url.isBlank()) LinkCheck(ok = false, error = "Paste the Field Notes link first")
    else try {
        val dest = ctx.destinations(url.trim())
        val rootName = dest.rootName()
        dest.listFolders("")
        LinkCheck(ok = true, rootName = rootName)
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
