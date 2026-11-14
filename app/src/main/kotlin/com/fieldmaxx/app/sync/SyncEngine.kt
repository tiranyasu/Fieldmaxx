package com.fieldmaxx.app.sync

import androidx.room.withTransaction
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.MetaEntity
import com.fieldmaxx.app.data.OutboxEntity
import com.fieldmaxx.app.data.toJob
import com.fieldmaxx.app.data.toOp
import com.fieldmaxx.app.data.toOutbox
import com.fieldmaxx.app.data.toPhoto
import com.fieldmaxx.app.data.toPoint
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.Change
import com.fieldmaxx.core.model.CrewMember
import com.fieldmaxx.core.model.CrewPosition
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.JobFileInfo
import com.fieldmaxx.core.model.JobSnapshot
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.OpResult
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.RefImageInfo
import com.fieldmaxx.core.model.ServerMessage
import com.fieldmaxx.core.model.User
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

enum class SyncStatus { ONLINE, OFFLINE, SYNCING }

/** What a job's snapshot carries besides its rows, kept on the phone for work without signal. */
@Serializable
data class JobExtras(val refImages: List<RefImageInfo>, val files: List<JobFileInfo>, val crew: List<CrewMember>)

/** How [SyncEngine.flushOutbox] ended. */
enum class FlushResult {
    /** Everything was sent. */
    EMPTY,

    /** The server couldn't be reached or didn't take the batch (5xx, 429, anything but 401): the ops are kept. */
    RETRY,

    /** The session has ended: the ops are kept until the next sign-in. */
    PAUSED,
}

/**
 * A reference image's address, `<server>/api/jobs/<jobId>/ref/<refId>`. Prefetching and showing both build it here, so
 * Coil's cache keys always match.
 */
fun refImageUrl(server: String, jobId: String, refId: String): String =
    server.trimEnd('/') + "/api/jobs/" + pathSegment(jobId) + "/ref/" + pathSegment(refId)

private fun pathSegment(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

private const val BATCH_SIZE = 50
private const val BACKOFF_FIRST_MS = 2_000L
private const val BACKOFF_MAX_MS = 60_000L
private const val CATCH_UP_DELAY_MS = 500L
private const val UNKNOWN = -1

private enum class Fetched { CAUGHT_UP, RETRY, PAUSED }

/**
 * The phone's copy of the server's jobs, points and photos, kept in step both ways.
 *
 * - [enqueue] shows an op at once and queues it. The outbox goes out in order, in batches of ≤ 50. A batch the server
 *   doesn't take (no signal, 5xx, 429, any other failure but 401) is kept and sent again after 2 s, doubling to 60 s; an
 *   op it answers is removed, and a refused one is reported on [rejected]. [OutboxWorker] flushes the same outbox in the
 *   background.
 * - Server changes come over the [live] socket. On every connection the engine fetches everything after `lastSeq`, the
 *   last change applied with none missing before it, page by page until a page comes back empty.
 * - A change replaces only a row it is newer than; ops not yet sent are applied again on top.
 * - A job's points and photos arrive with its snapshot ([openJob]), never partially.
 * - A 401 anywhere (or the socket's 4401) pauses everything: socket closed, ops kept, [authRequired]. [start] resumes.
 */
class SyncEngine(
    private val api: Api,
    private val db: AppDatabase,
    private val live: LiveSocket,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val server: () -> String = { "" },
    private val prefetch: suspend (urls: List<String>) -> Unit = {},
) {
    private val _status = MutableStateFlow(SyncStatus.OFFLINE)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * The session has ended. A screen that wasn't listening then hears it on its own next [start]: a paused engine tries
     * the server again and finds the session still ended.
     */
    private val _authRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authRequired: SharedFlow<Unit> = _authRequired.asSharedFlow()

    /** Ops the server refused; each was dropped and its job's server state restored. */
    private val _rejected = MutableSharedFlow<OpResult>(extraBufferCapacity = 64)
    val rejected: SharedFlow<OpResult> = _rejected.asSharedFlow()

    /** Everyone else's latest position. */
    private val _crew = MutableStateFlow<List<CrewPosition>>(emptyList())
    val crew: StateFlow<List<CrewPosition>> = _crew.asStateFlow()

    private val applier = OpApplier(db, now)
    private val applyLock = Mutex() // server state and lastSeq change one write at a time
    private val flushLock = Mutex()

    private val lock = Any()
    private var session: kotlinx.coroutines.Job? = null // guarded by lock
    private var user: User? = null // guarded by lock
    @Volatile private var paused = false

    private val fetchAsked = Channel<Unit>(Channel.CONFLATED)
    private val flushAsked = Channel<Unit>(Channel.CONFLATED)
    private val reconnected = Channel<Unit>(Channel.CONFLATED)
    private val catchUpPending = AtomicBoolean(false)
    private val staleJobs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val busy = AtomicInteger()
    @Volatile private var waiting = UNKNOWN // ops in the outbox, while a session runs
    @Volatile private var flushDelayMs = BACKOFF_FIRST_MS
    private var lastSeqRead: Long? = null // guarded by applyLock

    /** Starts syncing as [user] (also kept for the next launch, [savedUser]); resumes after [authRequired]. */
    fun start(user: User) {
        synchronized(lock) {
            if (session?.isActive == true && this.user == user) return
            session?.let {
                it.cancel()
                live.close() // another user: the next connection is theirs, and starts with its own Hello
            }
            this.user = user
            paused = false
            session = scope.launch(start = CoroutineStart.UNDISPATCHED) { run(user) }
        }
        refreshStatus()
    }

    /** Stops syncing. Everything stored stays, the outbox too. */
    fun stop() {
        val old = synchronized(lock) {
            user = null
            session.also { session = null }
        }
        old?.cancel()
        live.close()
        _crew.value = emptyList()
        refreshStatus()
    }

    /** The user of the last [start], so a launch without signal can start again. */
    suspend fun savedUser(): User? = db.meta().get(MetaKeys.USER)?.let { FmxJson.decodeFromString(User.serializer(), it) }

    /**
     * Fetches and stores the job's snapshot (points, photos, extras) and prefetches its reference images. Throws when the
     * server can't be reached ([IOException]) or refuses ([ApiException]); what is stored stays usable.
     */
    suspend fun openJob(jobId: String) {
        val snapshot = try {
            api.snapshot(jobId)
        } catch (e: ApiException) {
            if (e.status == 401) pauseForAuth()
            throw e
        }
        store(snapshot)
        val urls = snapshot.refImages.map { refImageUrl(server(), jobId, it.id) }
        if (urls.isEmpty()) return
        try {
            prefetch(urls)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: the screen loads them itself when it can.
        }
    }

    /** Shows [op] on the phone at once and queues it for the server. */
    suspend fun enqueue(op: Op) {
        val userId = synchronized(lock) { user?.id } ?: savedUser()?.id.orEmpty()
        db.withTransaction {
            db.outbox().insert(op.toOutbox())
            applier.applyLocally(op, userId, now())
        }
        flushAsked.trySend(Unit)
    }

    /** Sends the outbox until it is empty, the server can't be reached, or the session has ended. */
    suspend fun flushOutbox(): FlushResult = flushLock.withLock {
        busy.incrementAndGet()
        refreshStatus()
        try {
            flushAll()
        } finally {
            busy.decrementAndGet()
            refreshStatus()
        }
    }

    /** The job's points, deleted ones left out. */
    fun points(jobId: String): Flow<List<Point>> = db.points().observeForJob(jobId)
        .map { rows -> rows.map { it.toPoint() }.filter { it.deletedAt == null } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    fun job(jobId: String): Flow<Job?> = db.jobs().observe(jobId).map { it?.toJob() }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /** The point's photos, removed ones left out. */
    fun photos(pointId: String): Flow<List<PhotoInfo>> = db.photos().observeForPoint(pointId)
        .map { rows -> rows.map { it.toPhoto() }.filter { it.status != PhotoStatus.removed } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /** All the job's photos, removed ones left out. */
    fun jobPhotos(jobId: String): Flow<List<PhotoInfo>> = db.photos().observeForJob(jobId)
        .map { rows -> rows.map { it.toPhoto() }.filter { it.status != PhotoStatus.removed } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /** Null until the job has been opened on this phone. */
    fun extras(jobId: String): Flow<JobExtras?> = db.meta().observe(MetaKeys.extras(jobId))
        .map { json -> json?.let { FmxJson.decodeFromString(JobExtras.serializer(), it) } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    // ---- session ----

    private suspend fun run(user: User) = coroutineScope {
        waiting = UNKNOWN // until the outbox is counted, the status can't say everything is sent
        // Listening first: the first frame of a connection must not be missed.
        launch(start = CoroutineStart.UNDISPATCHED) { live.messages.collect { onMessage(it, user, this@coroutineScope) } }
        launch(start = CoroutineStart.UNDISPATCHED) { live.signInRequired.collect { pauseForAuth() } }
        launch(start = CoroutineStart.UNDISPATCHED) { live.connected.collect { refreshStatus() } }
        launch {
            db.outbox().observeCount().collect {
                waiting = it
                refreshStatus()
            }
        }
        launch { flushLoop() }
        launch { fetchLoop() }
        db.meta().put(MetaEntity(MetaKeys.USER, FmxJson.encodeToString(User.serializer(), user)))
        flushAsked.trySend(Unit)
        live.connect()
    }

    private suspend fun onMessage(m: ServerMessage, me: User, session: CoroutineScope) {
        when (m) {
            is ServerMessage.Hello -> {
                // A new connection: catch up from lastSeq (never from the Hello's seq), send what waits, refresh the crew.
                fetchAsked.trySend(Unit)
                reconnected.trySend(Unit)
                flushAsked.trySend(Unit)
                session.launch { seedCrew(me.id) }
                session.launch { staleJobs.toList().forEach { refetch(it) } }
            }
            is ServerMessage.ChangeMsg -> applyLive(m.change)
            is ServerMessage.PositionMsg -> if (m.pos.userId != me.id) _crew.update { it.withPosition(m.pos) }
        }
    }

    private fun pauseForAuth() {
        val old = synchronized(lock) {
            if (paused) return
            paused = true
            session.also { session = null }
        }
        live.close()
        _authRequired.tryEmit(Unit)
        refreshStatus()
        old?.cancel() // may be the caller's own session: last
    }

    private fun refreshStatus() {
        synchronized(lock) {
            _status.value = when {
                session == null || paused || !live.connected.value -> SyncStatus.OFFLINE
                busy.get() > 0 || waiting != 0 -> SyncStatus.SYNCING
                else -> SyncStatus.ONLINE
            }
        }
    }

    // ---- server → phone ----

    private suspend fun store(s: JobSnapshot) {
        val rewound = applyLock.withLock {
            applier.applySnapshot(s)
            // Changes after the snapshot may have been skipped while the job wasn't stored here: fetch them again.
            (s.seq < lastSeq()).also { if (it) setLastSeq(s.seq) }
        }
        if (rewound) fetchAsked.trySend(Unit)
    }

    private suspend fun applyLive(c: Change) {
        val gap = applyLock.withLock {
            applier.applyChange(c)
            val last = lastSeq()
            if (c.seq == last + 1) setLastSeq(c.seq)
            c.seq > last + 1
        }
        if (gap && catchUpPending.compareAndSet(false, true)) {
            // A change is missing before this one; it usually turns up within moments, else the fetch brings it.
            scope.launch {
                delay(CATCH_UP_DELAY_MS)
                catchUpPending.set(false)
                fetchAsked.trySend(Unit)
            }
        }
    }

    private suspend fun fetchLoop() {
        while (true) {
            fetchAsked.receive()
            var wait = BACKOFF_FIRST_MS
            while (fetchSince() == Fetched.RETRY) {
                // Asked again meanwhile (a new connection, say): try at once instead of waiting the backoff out.
                val asked = withTimeoutOrNull(wait) { fetchAsked.receive() } != null
                wait = if (asked) BACKOFF_FIRST_MS else (wait * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
        }
    }

    /** Fetches every change after lastSeq, page by page until a page comes back empty. */
    private suspend fun fetchSince(): Fetched {
        busy.incrementAndGet()
        refreshStatus()
        try {
            var cursor = applyLock.withLock { lastSeq() }
            while (true) {
                val (changes, seq) = api.changes(cursor)
                applyLock.withLock {
                    applier.applyChanges(changes)
                    // The page covers (cursor, seq]: it extends lastSeq only if nothing before it is missing.
                    val last = lastSeq()
                    if (cursor <= last && seq > last) setLastSeq(seq)
                }
                if (changes.isEmpty() || seq <= cursor) return Fetched.CAUGHT_UP
                cursor = seq
            }
        } catch (e: IOException) {
            return Fetched.RETRY
        } catch (e: ApiException) {
            if (e.status != 401) return Fetched.RETRY
            pauseForAuth()
            return Fetched.PAUSED
        } finally {
            busy.decrementAndGet()
            refreshStatus()
        }
    }

    /** Under [applyLock]. */
    private suspend fun lastSeq(): Long =
        lastSeqRead ?: (db.meta().get(MetaKeys.LAST_SEQ)?.toLongOrNull() ?: 0L).also { lastSeqRead = it }

    /** Under [applyLock]. */
    private suspend fun setLastSeq(seq: Long) {
        db.meta().put(MetaEntity(MetaKeys.LAST_SEQ, seq.toString()))
        lastSeqRead = seq
    }

    private suspend fun seedCrew(me: String) {
        val seed = try {
            api.positions()
        } catch (e: IOException) {
            return
        } catch (e: ApiException) {
            if (e.status == 401) pauseForAuth()
            return
        }
        _crew.update { current ->
            val known = current.associateBy { it.userId }
            seed.filter { it.userId != me }.map { s -> known[s.userId]?.takeIf { it.at > s.at } ?: s }
        }
    }

    /** Restores a job's server state after a refused op; tried again on the next connection when that fails. */
    private suspend fun refetch(jobId: String) {
        try {
            store(api.snapshot(jobId))
            staleJobs -= jobId
        } catch (e: IOException) {
            staleJobs += jobId
        } catch (e: ApiException) {
            when {
                e.status == 401 -> {
                    staleJobs += jobId
                    pauseForAuth()
                }
                e.status >= 500 || e.status == 429 -> staleJobs += jobId
                else -> staleJobs -= jobId // gone, or no longer ours to read
            }
        }
    }

    // ---- phone → server ----

    private suspend fun flushLoop() {
        while (true) {
            flushAsked.receive()
            while (flushOutbox() == FlushResult.RETRY) {
                val wait = flushDelayMs
                flushDelayMs = (wait * 2).coerceAtMost(BACKOFF_MAX_MS)
                // A new connection cuts the wait short: the server is back.
                if (withTimeoutOrNull(wait) { reconnected.receive() } != null) flushDelayMs = BACKOFF_FIRST_MS
            }
        }
    }

    private suspend fun flushAll(): FlushResult {
        while (true) {
            if (paused) return FlushResult.PAUSED
            val batch = db.outbox().first(BATCH_SIZE)
            if (batch.isEmpty()) {
                staleJobs.toList().forEach { refetch(it) }
                return FlushResult.EMPTY
            }
            send(batch)?.let { return it }
        }
    }

    /** Sends one batch; null when it got somewhere and flushing goes on. */
    private suspend fun send(batch: List<OutboxEntity>): FlushResult? {
        val ops = batch.map { it.toOp() }
        val results = try {
            api.ops(ops)
        } catch (e: IOException) {
            return FlushResult.RETRY
        } catch (e: ApiException) {
            if (e.status != 401) return FlushResult.RETRY // 5xx, 429 and the rest: the ops answered nothing, so keep them
            pauseForAuth()
            return FlushResult.PAUSED
        }
        flushDelayMs = BACKOFF_FIRST_MS
        return if (settle(batch, ops, results)) null else FlushResult.RETRY
    }

    /**
     * Removes the answered ops (unanswered ones go again). A refused op's job gets the server's state back, and the
     * refusal is reported on [rejected]. False when nothing was answered.
     */
    private suspend fun settle(batch: List<OutboxEntity>, ops: List<Op>, results: List<OpResult>): Boolean {
        val byId = results.associateBy { it.opId }
        val answered = batch.indices.filter { ops[it].opId in byId }
        if (answered.isEmpty()) return false
        val refused = answered.filter { !byId.getValue(ops[it].opId).ok }
        val jobs = refused.mapNotNullTo(LinkedHashSet()) { applier.jobOf(ops[it]) }
        db.outbox().delete(answered.map { batch[it].seq })
        jobs.forEach { refetch(it) }
        refused.forEach { _rejected.tryEmit(byId.getValue(ops[it].opId)) }
        return true
    }
}

/** [p] replaces the user's older position where it stands, or joins at the end. */
private fun List<CrewPosition>.withPosition(p: CrewPosition): List<CrewPosition> {
    val i = indexOfFirst { it.userId == p.userId }
    return when {
        i < 0 -> this + p
        this[i].at > p.at -> this
        else -> toMutableList().also { it[i] = p }
    }
}
