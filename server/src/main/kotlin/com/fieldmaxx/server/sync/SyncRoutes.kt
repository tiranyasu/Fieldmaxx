package com.fieldmaxx.server.sync

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.ClientMessage
import com.fieldmaxx.core.model.Change
import com.fieldmaxx.core.model.CrewPosition
import com.fieldmaxx.core.model.ServerMessage
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.auth.sessionUser
import com.fieldmaxx.server.auth.user
import com.fieldmaxx.server.positions.listPositions
import com.fieldmaxx.server.positions.savePosition
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable data class ChangesBody(val changes: List<Change>, val seq: Long)
@Serializable data class PositionsBody(val positions: List<CrewPosition>)

const val WS_UNAUTHORIZED = 4401

fun Route.syncRoutes(ctx: Ctx) {
    authenticate("bearer") {
        get("/changes") {
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val (changes, seq) = ctx.changes.since(since)
            call.respond(ChangesBody(changes, seq))
        }
        get("/positions") {
            call.user()
            call.respond(PositionsBody(listPositions(ctx)))
        }
    }
    webSocket("/ws") {
        val user = call.request.queryParameters["token"]?.let { sessionUser(ctx, it) }
        if (user == null) {
            close(CloseReason(WS_UNAUTHORIZED.toShort(), "Sign in required"))
            return@webSocket
        }
        // Register before reading the head: a change is then covered by the hello seq or delivered by broadcast.
        ctx.hub.add(this, user)
        ctx.hub.sendTo(this, ServerMessage.Hello(ctx.changes.headSeq()))
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            try {
                val msg = FmxJson.decodeFromString(ClientMessage.serializer(), frame.readText())
                ctx.hub.broadcast(ServerMessage.PositionMsg(savePosition(ctx, user, msg)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // a malformed or out-of-order client message must not end the connection
            }
        }
    }
}
