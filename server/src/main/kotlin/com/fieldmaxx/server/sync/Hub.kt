package com.fieldmaxx.server.sync

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.ServerMessage
import com.fieldmaxx.core.model.User
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Live WebSocket clients. Each client has its own unbounded outbox drained by a writer coroutine, so [broadcast] never
 * blocks, is safe from any thread, and a dead or slow socket only affects itself (it is dropped on the first failure).
 */
class Hub {
    private class Client(val user: User, val outbox: Channel<String> = Channel(Channel.UNLIMITED))

    private val clients = ConcurrentHashMap<DefaultWebSocketServerSession, Client>()

    val size: Int get() = clients.size

    fun add(session: DefaultWebSocketServerSession, user: User) {
        val client = Client(user)
        clients[session] = client
        val writer = session.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (text in client.outbox) session.outgoing.send(Frame.Text(text))
            } catch (_: Throwable) {
                // closed or failed socket: fall through and drop it
            } finally {
                drop(session)
            }
        }
        session.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { drop(session); writer.cancel() }
    }

    fun broadcast(msg: ServerMessage) {
        val text = FmxJson.encodeToString(ServerMessage.serializer(), msg)
        for (c in clients.values) c.outbox.trySend(text)
    }

    private fun drop(session: DefaultWebSocketServerSession) {
        clients.remove(session)?.outbox?.close()
    }
}
