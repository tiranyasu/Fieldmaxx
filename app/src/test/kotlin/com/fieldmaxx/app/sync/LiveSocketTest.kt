package com.fieldmaxx.app.sync

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.ClientMessage
import com.fieldmaxx.core.model.ServerMessage
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule

class LiveSocketTest {
    @get:Rule val server = MockWebServer()

    @Volatile private var token: String? = "tok-1"
    private val socket by lazy { LiveSocket(OkHttpClient(), { liveSocketUrl(server.url("/").toString()) }, { token }) }

    @AfterTest fun tearDown() {
        socket.close()
    }

    /** The server's side of one connection; it answers the phone's close, so the server can shut down at once. */
    private fun upgrade(onOpen: (WebSocket) -> Unit = {}, onMessage: (String) -> Unit = {}) =
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)
            override fun onMessage(webSocket: WebSocket, text: String) = onMessage(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        })

    @Test fun connectsWithTheTokenAndCarriesFramesBothWays() = runBlocking {
        val fromPhone = Channel<String>(Channel.UNLIMITED)
        server.enqueue(upgrade(onOpen = { it.send("""{"kind":"hello","seq":7}""") }, onMessage = { fromPhone.trySend(it) }))
        val hello = async(start = CoroutineStart.UNDISPATCHED) { socket.messages.first() }

        socket.connect()

        assertEquals(ServerMessage.Hello(7), withTimeout(5_000) { hello.await() })
        assertTrue(socket.connected.value)
        assertEquals("/api/ws?token=tok-1", assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
        val position = ClientMessage.PositionUpdate(39.636130, -119.691104, 4.8, altitudeM = 1351.0)
        socket.send(position)
        assertEquals(position, FmxJson.decodeFromString(ClientMessage.serializer(), withTimeout(5_000) { fromPhone.receive() }))
    }

    @Test fun signInRequiredCloseStopsReconnecting() = runBlocking {
        server.enqueue(upgrade(onOpen = { it.close(4401, "Sign in required") }))
        val signedOut = async(start = CoroutineStart.UNDISPATCHED) { socket.signInRequired.first() }

        socket.connect()

        withTimeout(5_000) { signedOut.await() }
        assertFalse(socket.connected.value)
        delay(1_500) // longer than the first reconnect delay
        assertEquals(1, server.requestCount, "no reconnect with an ended session")
    }

    @Test fun reconnectsAfterTheConnectionDrops() = runBlocking {
        server.enqueue(upgrade(onOpen = { it.close(1001, "Server restarting") }))
        server.enqueue(upgrade(onOpen = { it.send("""{"kind":"hello","seq":9}""") }))
        val hello = async(start = CoroutineStart.UNDISPATCHED) { socket.messages.first() }

        socket.connect()

        assertEquals(ServerMessage.Hello(9), withTimeout(5_000) { hello.await() })
        assertEquals(2, server.requestCount)
    }

    @Test fun noTokenMeansSignInRequiredWithoutConnecting() = runBlocking {
        token = null
        val signedOut = async(start = CoroutineStart.UNDISPATCHED) { socket.signInRequired.first() }

        socket.connect()

        withTimeout(5_000) { signedOut.await() }
        assertEquals(0, server.requestCount)
    }

    @Test fun socketUrlIsTheServersWebSocketForm() {
        assertEquals("wss://fmx.example.com/api/ws", liveSocketUrl("https://fmx.example.com"))
        assertEquals("ws://10.0.2.2:8080/api/ws", liveSocketUrl("http://10.0.2.2:8080/"))
    }
}
