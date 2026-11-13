package com.fieldmaxx.server

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.ClientMessage
import com.fieldmaxx.core.model.ServerMessage
import com.fieldmaxx.core.model.User
import com.fieldmaxx.server.positions.listPositions
import com.fieldmaxx.server.positions.savePosition
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SyncTest {
        private val ann = User("u1", "ann", "Ann", com.fieldmaxx.core.model.Role.tech, false)

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.next(): ServerMessage = withTimeout(5000) {
        val f = incoming.receive() as Frame.Text
        FmxJson.decodeFromString(ServerMessage.serializer(), f.readText())
    }

    @Test fun seqIncreasesAndSinceFilters() = TestApp().run { app ->
        val a = app.ctx.changes.record("j1", "point", "p1")
        val b = app.ctx.changes.record("j1", "point", "p2")
        assertTrue(b.seq > a.seq)
        val (list, seq) = app.ctx.changes.since(a.seq, 1000)
        assertEquals(listOf("p2"), list.map { it.entityId })
        assertEquals(b.seq, seq)
        val token = tokenFor("ann")
        val body = Json.parseToJsonElement(client.get("/api/changes?since=0") { header("Authorization", "Bearer $token") }.bodyAsText()).jsonObject
        assertEquals(2, body.getValue("changes").jsonArray.size)
        assertEquals(b.seq, body.getValue("seq").jsonPrimitive.content.toLong())
    }

    @Test fun sinceCapsAtLimit() = TestApp().run { app ->
        repeat(5) { app.ctx.changes.record(null, "job", "j$it") }
        val (list, seq) = app.ctx.changes.since(0, 3)
        assertEquals(3, list.size)
        assertEquals(list.last().seq, seq)
        assertEquals(2, app.ctx.changes.since(seq, 3).first.size)
    }

    @Test fun wsRejectsBadToken() = TestApp(fileDb = true).run { app ->
        val c = createClient { install(WebSockets) }
        c.webSocket("/api/ws?token=nope") {
            val reason = closeReason.await()
            assertEquals(4401, reason?.code?.toInt())
        }
    }

    @Test fun wsHelloCarriesSeq() = TestApp(fileDb = true).run { app ->
        val token = tokenFor("ann")
        val change = app.ctx.changes.record("j1", "job", "j1")
        val c = createClient { install(WebSockets) }
        c.webSocket("/api/ws?token=$token") {
            assertEquals(ServerMessage.Hello(change.seq), next())
            close()
        }
    }

    @Test fun changeBroadcastToAll() = TestApp(fileDb = true).run { app ->
        val t1 = tokenFor("ann"); val t2 = tokenFor("bob")
        val c = createClient { install(WebSockets) }
        c.webSocket("/api/ws?token=$t1") {
            next()
            c.webSocket("/api/ws?token=$t2") {
                next()
                val change = app.ctx.changes.record("j1", "point", "p1")
                assertEquals(ServerMessage.ChangeMsg(change), next())
                close()
            }
            // the second socket is gone; broadcasting must still reach this one
            val p1 = assertIs<ServerMessage.ChangeMsg>(next())
            assertEquals("p1", p1.change.entityId)
            val change2 = app.ctx.changes.record("j1", "point", "p2")
            assertEquals(ServerMessage.ChangeMsg(change2), next())
            close()
        }
    }

    @Test fun positionStoredAndBroadcastWithName() = TestApp(fileDb = true).run { app ->
        val t1 = tokenFor("ann"); val t2 = tokenFor("bob")
        val c = createClient { install(WebSockets) }
        c.webSocket("/api/ws?token=$t2") {
            next()
            c.webSocket("/api/ws?token=$t1") {
                next()
                send(Frame.Text("""{"kind":"position","lat":39.6,"lon":-119.7,"accuracyM":5.0}"""))
                close()
            }
            val m = assertIs<ServerMessage.PositionMsg>(next())
            assertEquals("Ann", m.pos.displayName)
            assertEquals(39.6, m.pos.lat)
            assertTrue(!m.pos.paused)
            close()
        }
        assertEquals(1, listPositions(app.ctx).size)
        assertEquals(0, app.ctx.changes.since(0, 1000).first.size, "positions never enter the change log")
    }

    @Test fun pausedMarksPositionPaused() = TestApp().run { app ->
        tokenFor("ann")
        val user = ann.copy(id = app.ctx.db.read {
            createStatement().use { it.executeQuery("SELECT id FROM users WHERE username='ann'").use { r -> r.next(); r.getString(1) } }
        })
        savePosition(app.ctx, user, ClientMessage.PositionUpdate(39.6, -119.7, 5.0))
        app.clock.nowMs += 1000
        val p = savePosition(app.ctx, user, ClientMessage.Paused)
        assertTrue(p.paused)
        assertEquals(39.6, p.lat)
        assertEquals(app.clock.nowMs, p.at)
        assertTrue(listPositions(app.ctx).single().paused)
    }

    @Test fun positionsOlderThanHourHidden() = TestApp().run { app ->
        tokenFor("ann")
        val id = app.ctx.db.read {
            createStatement().use { it.executeQuery("SELECT id FROM users WHERE username='ann'").use { r -> r.next(); r.getString(1) } }
        }
        savePosition(app.ctx, ann.copy(id = id), ClientMessage.PositionUpdate(1.0, 2.0, 3.0))
        app.clock.nowMs += 3_599_000
        assertEquals(1, listPositions(app.ctx).size)
        app.clock.nowMs += 2_000
        assertEquals(0, listPositions(app.ctx).size)
    }

    @Test fun changeBetweenConnectAndHelloIsNotMissed() = TestApp(fileDb = true).run { app ->
        val token = tokenFor("ann")
        val c = createClient { install(WebSockets) }
        val recorder = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            repeat(30) { app.ctx.changes.record("j1", "point", "p$it"); kotlinx.coroutines.delay(1) }
        }
        c.webSocket("/api/ws?token=$token") {
            var hello = -1L
            val seen = mutableSetOf<Long>()
            while (seen.size < 30 || hello < 0) {
                when (val m = next()) {
                    is ServerMessage.Hello -> hello = m.seq
                    is ServerMessage.ChangeMsg -> seen += m.change.seq
                    else -> {}
                }
                if (hello >= 0 && (1..30L).all { it <= hello || it in seen }) break
            }
            assertTrue((1..30L).all { it <= hello || it in seen })
            close()
        }
        recorder.join()
    }

    private fun idOf(app: TestApp, name: String) = app.ctx.db.read {
        createStatement().use { it.executeQuery("SELECT id FROM users WHERE username='$name'").use { r -> r.next(); r.getString(1) } }
    }

    @Test fun disablingUserClosesTheirSocket() = TestApp(fileDb = true).run { app ->
        val token = tokenFor("ann")
        val c = createClient { install(WebSockets) }
        c.webSocket("/api/ws?token=$token") {
            next()
            com.fieldmaxx.server.users.updateUser(app.ctx, idOf(app, "ann"), null, null, true, null)
            assertEquals(4401, withTimeout(5000) { closeReason.await() }?.code?.toInt())
        }
    }

    @Test fun passwordResetClosesTheirSocket() = TestApp(fileDb = true).run { app ->
        val token = tokenFor("ann")
        val c = createClient { install(WebSockets) }
        c.webSocket("/api/ws?token=$token") {
            next()
            com.fieldmaxx.server.users.updateUser(app.ctx, idOf(app, "ann"), null, null, null, "brand-new-pass-1")
            assertEquals(4401, withTimeout(5000) { closeReason.await() }?.code?.toInt())
        }
    }
}
