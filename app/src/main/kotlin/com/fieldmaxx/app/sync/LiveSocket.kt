package com.fieldmaxx.app.sync

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.ClientMessage
import com.fieldmaxx.core.model.ServerMessage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The live WebSocket to the server (`/api/ws?token=…`). Every connection starts with [ServerMessage.Hello]; changes and
 * crew positions follow. The socket only carries frames: what they mean is the sync engine's business.
 */
interface LiveSocket {
    /** Every frame the server sends, in order. */
    val messages: SharedFlow<ServerMessage>
    val connected: StateFlow<Boolean>

    /** The server closed the socket because the session has ended (4401). It stays closed until the next [connect]. */
    val signInRequired: SharedFlow<Unit>

    /** Connects, and after a drop reconnects (1 s doubling to 30 s) until [close]. Calling it again changes nothing. */
    fun connect()
    fun close()

    /** Sent only while connected; dropped otherwise (a position is soon out of date anyway). */
    fun send(m: ClientMessage)
}

fun LiveSocket(client: OkHttpClient, url: () -> String, token: () -> String?): LiveSocket = OkHttpLiveSocket(client, url, token)

/** The WebSocket form of a server address: `https://fmx.example.com` → `wss://fmx.example.com/api/ws`. */
fun liveSocketUrl(server: String): String {
    val base = server.trimEnd('/')
    val ws = when {
        base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
        base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
        else -> base
    }
    return "$ws/api/ws"
}

/** The server's close code for a missing, bad or expired session token. */
const val WS_SIGN_IN_REQUIRED = 4401

private const val RECONNECT_FIRST_MS = 1_000L
private const val RECONNECT_MAX_MS = 30_000L
private const val PING_INTERVAL_S = 20L

/** The wait after [failures] (≥ 1) failed or dropped connections in a row: 1 s, 2 s, 4 s … then 30 s. */
internal fun reconnectDelayMs(failures: Int): Long =
    (RECONNECT_FIRST_MS shl (failures - 1).coerceIn(0, 5)).coerceAtMost(RECONNECT_MAX_MS)

/**
 * [LiveSocket] over OkHttp. [url] (the `ws://` or `wss://` endpoint) and [token] are read on every connection attempt,
 * so a new server address or session applies on the next one.
 */
class OkHttpLiveSocket(
    client: OkHttpClient,
    private val url: () -> String,
    private val token: () -> String?,
) : LiveSocket {
    // Pings find a dead connection (the phone switched networks, say) that would otherwise look open for minutes.
    private val client = client.newBuilder().pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS).build()
    private val retries = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _messages = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 256)
    override val messages: SharedFlow<ServerMessage> = _messages.asSharedFlow()
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _signInRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val signInRequired: SharedFlow<Unit> = _signInRequired.asSharedFlow()

    private val lock = Any()
    private var wanted = false // from connect() until close() or the end of the session
    private var socket: WebSocket? = null
    private var retry: Job? = null
    private var generation = 0 // the current socket's; callbacks from an older one are ignored
    private var failures = 0

    override fun connect() {
        synchronized(lock) {
            if (wanted) return
            wanted = true
            failures = 0
            open()
        }
    }

    override fun close() {
        val old = synchronized(lock) {
            wanted = false
            generation++
            retry?.cancel()
            retry = null
            _connected.value = false
            socket.also { socket = null }
        }
        old?.close(1000, null)
    }

    override fun send(m: ClientMessage) {
        val open = synchronized(lock) { socket.takeIf { _connected.value } } ?: return
        open.send(FmxJson.encodeToString(ClientMessage.serializer(), m))
    }

    /** Under [lock]. */
    private fun open() {
        retry = null
        val t = token()
        if (t == null) { // signed out: the server would only answer 4401
            wanted = false
            _signInRequired.tryEmit(Unit)
            return
        }
        val gen = ++generation
        val request = try {
            val base = Request.Builder().url(url()).build() // OkHttp takes ws:// and wss:// here
            base.newBuilder().url(base.url.newBuilder().setQueryParameter("token", t).build()).build()
        } catch (e: IllegalArgumentException) { // not a usable address (yet); it may be corrected meanwhile
            scheduleRetry()
            return
        }
        socket = client.newWebSocket(request, Listener(gen))
    }

    /** Under [lock]. */
    private fun scheduleRetry() {
        val gen = generation
        val wait = reconnectDelayMs(++failures)
        retry = retries.launch {
            delay(wait)
            synchronized(lock) { if (wanted && generation == gen) open() }
        }
    }

    /** The socket of [gen] is gone: reconnect later, or stay closed when the session has ended. */
    private fun ended(gen: Int, sessionEnded: Boolean) {
        synchronized(lock) {
            if (gen != generation) return
            generation++
            socket = null
            _connected.value = false
            if (!wanted) return
            if (!sessionEnded) {
                scheduleRetry()
                return
            }
            wanted = false
        }
        _signInRequired.tryEmit(Unit)
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) { if (gen == generation) _connected.value = true }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = try {
                FmxJson.decodeFromString(ServerMessage.serializer(), text)
            } catch (e: IllegalArgumentException) { // SerializationException is one: a frame this version doesn't know
                return
            }
            synchronized(lock) {
                if (gen != generation) return
                failures = 0 // the server is talking: the next drop retries after 1 s again
            }
            // In order, and never dropped: when the reader is busy, this waits (and the socket with it).
            runBlocking { _messages.emit(message) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            ended(gen, code == WS_SIGN_IN_REQUIRED)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(gen, code == WS_SIGN_IN_REQUIRED)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = ended(gen, response?.code == 401)
    }
}
