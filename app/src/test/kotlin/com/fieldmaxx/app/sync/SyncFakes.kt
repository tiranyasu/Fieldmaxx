package com.fieldmaxx.app.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.LocalPhotoEntity
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.core.model.Change
import com.fieldmaxx.core.model.ClientMessage
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
import com.fieldmaxx.core.model.PointStatus
import com.fieldmaxx.core.model.RefImageInfo
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.ServerMessage
import com.fieldmaxx.core.model.User
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout

val ALICE = User("u1", "alice", "Alice Tech", Role.tech, disabled = false)

fun job(id: String, name: String = "A05J9XE") =
    Job(id, name, notes = "", fieldNotesUrl = null, status = "active", createdBy = "u9", createdAt = 1, finishedAt = null, deletedAt = null, deliveryProblem = null)

fun point(id: String, jobId: String = "j1", num: String = id.drop(1)) = Point(
    id = id, jobId = jobId, layer = "POLE", type = "POLE", num = num, label = "POLE $num", lat = 39.636130, lon = -119.691104,
    origLat = null, origLon = null, description = "", status = PointStatus.open, cantReason = null, cantText = null, pole = null,
    comment = "", folderPath = null, folderCandidates = null, refImageIds = emptyList(), flags = emptyList(), addedBy = null,
    updatedAt = 0, updatedBy = null, deletedAt = null,
)

fun photo(id: String, pointId: String, jobId: String = "j1", status: PhotoStatus = PhotoStatus.registered) = PhotoInfo(
    id = id, jobId = jobId, pointId = pointId, userId = "u2", fileName = "POLE_1_39.636130_-119.691104_7.jpg", size = 1000,
    status = status, destPath = null, lastError = null, takenAt = 5, lat = 39.636130, lon = -119.691104, backedUp = true,
    confirmedAt = null, uploadMs = null, reusedFrom = null,
)

fun snapshot(
    job: Job, points: List<Point>, seq: Long, photos: List<PhotoInfo> = emptyList(), refImages: List<RefImageInfo> = emptyList(),
    files: List<JobFileInfo> = emptyList(), crew: List<CrewMember> = emptyList(),
) = JobSnapshot(job, points, photos, refImages, files, crew, seq)

fun pointChange(seq: Long, p: Point) = Change(seq, p.jobId, "point", p.id, point = p, at = seq)

fun jobChange(seq: Long, j: Job) = Change(seq, j.id, "job", j.id, job = j, at = seq)

fun position(userId: String, at: Long, lat: Double = 39.6) = CrewPosition(userId, "Tech $userId", lat, -119.6, 5.0, at, paused = false)

/** Polls [condition] in real time; the engine works on its own threads. */
suspend fun awaitTrue(message: String = "condition", condition: suspend () -> Boolean) {
    try {
        withTimeout(5_000) { while (!condition()) delay(10) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("timed out waiting for $message", e)
    }
}

/**
 * The server side of sync: a change feed paged like the real one (at most [pageSize] per call, then the seq to continue
 * from), snapshots, positions, and an ops endpoint whose answer each test sets. Everything else is unused.
 */
class FakeApi : Api by unused() {
    private val log = mutableListOf<Change>()
    @Volatile var head = 0L
    @Volatile var pageSize = 1000
    val sinceCalls = CopyOnWriteArrayList<Long>()
    val sentBatches = CopyOnWriteArrayList<List<Op>>()
    @Volatile var opsReply: (List<Op>) -> List<OpResult> = { ops -> ops.map { OpResult(it.opId, true) } }
    val snapshots = ConcurrentHashMap<String, JobSnapshot>()
    val snapshotCalls = CopyOnWriteArrayList<String>()
    @Volatile var positionsReply: List<CrewPosition> = emptyList()

    /** Set to make that endpoint fail (an ended session, say). */
    @Volatile var changesError: Exception? = null

    /** Set to hold every since-fetch until the test completes it. */
    @Volatile var changesGate: CompletableDeferred<Unit>? = null

    /** Set to hold the ops' answer (the batch is recorded as sent at once) until the test completes it. */
    @Volatile var opsGate: CompletableDeferred<Unit>? = null
    @Volatile var snapshotError: Exception? = null
    @Volatile var positionsError: Exception? = null

    fun record(vararg changes: Change) = record(changes.toList())

    fun record(changes: List<Change>) = synchronized(log) {
        log += changes
        head = maxOf(head, changes.maxOf { it.seq })
    }

    /** The server is restored from a backup taken at [seq]: later changes are gone. */
    fun restoreTo(seq: Long) = synchronized(log) {
        log.removeAll { it.seq > seq }
        head = seq
    }

    override suspend fun changes(since: Long): Pair<List<Change>, Long> {
        sinceCalls += since
        changesGate?.await()
        changesError?.let { throw it }
        return synchronized(log) {
            val page = log.filter { it.seq > since }.sortedBy { it.seq }.take(pageSize)
            page to (page.lastOrNull()?.seq ?: head)
        }
    }

    override suspend fun ops(ops: List<Op>): List<OpResult> {
        sentBatches += ops
        opsGate?.await()
        return opsReply(ops)
    }

    override suspend fun snapshot(jobId: String): JobSnapshot {
        snapshotCalls += jobId
        snapshotError?.let { throw it }
        return snapshots[jobId] ?: throw ApiException(404, "Job not found")
    }

    override suspend fun positions(): List<CrewPosition> {
        positionsError?.let { throw it }
        return positionsReply
    }
}

private fun unused(): Api = Proxy.newProxyInstance(Api::class.java.classLoader, arrayOf(Api::class.java)) { _, method, _ ->
    throw UnsupportedOperationException("Api.${method.name} is not part of sync")
} as Api

/**
 * The engine's socket, driven by the test as the server would. With [dropOnClose] false, a [close] isn't reflected in
 * [connected] yet, as when the socket reports its state late.
 */
class FakeLiveSocket(private val dropOnClose: Boolean = true) : LiveSocket {
    private val frames = MutableSharedFlow<ServerMessage>()
    override val messages: SharedFlow<ServerMessage> = frames
    override val connected = MutableStateFlow(false)
    private val signOut = MutableSharedFlow<Unit>()
    override val signInRequired: SharedFlow<Unit> = signOut
    val connects = MutableStateFlow(0)
    val closes = AtomicInteger()
    val sent = CopyOnWriteArrayList<ClientMessage>()

    override fun connect() {
        connected.value = true
        connects.update { it + 1 }
    }

    override fun close() {
        closes.incrementAndGet()
        if (dropOnClose) connected.value = false
    }

    override fun send(m: ClientMessage) {
        sent += m
    }

    /** Delivered once the engine has connected (it listens before it connects). */
    suspend fun serverSends(m: ServerMessage) {
        withTimeout(5_000) { connects.first { it > 0 } }
        frames.emit(m)
    }

    /** The connection drops and comes back; a new connection always starts with Hello. */
    suspend fun serverReconnects(hello: ServerMessage.Hello) {
        connected.value = false
        connected.value = true
        serverSends(hello)
    }

    /** The server closed the socket with 4401. */
    suspend fun serverSignsOut() {
        withTimeout(5_000) { connects.first { it > 0 } }
        connected.value = false
        signOut.emit(Unit)
    }
}

/** A photo this phone took, as the photo pipeline keeps it. */
fun localPhoto(id: String, pointId: String = "p1", jobId: String = "j1") = LocalPhotoEntity(
    id = id, jobId = jobId, pointId = pointId, originalPath = "/photos/$id.jpg", stampedPath = null, metaJson = "{}",
    state = "saved", fileName = null, oneDriveUploadId = null, oneDriveOffset = 0, createdThisFile = false, backupOffset = 0,
    backupDone = false, attempts = 0, nextAttemptAt = 0, lastError = null, stuck = false, createdAt = 1, confirmedAt = null,
)

/**
 * An in-memory database where the next write of the meta row [killAtMetaKey] fails, as if the app were killed at that
 * moment: whatever that write's transaction holds is lost, and whatever committed before stays.
 */
class KillableDb(context: Context) {
    @Volatile var killAtMetaKey: String? = null

    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .openHelperFactory { config -> Helper(FrameworkSQLiteOpenHelperFactory().create(config)) }
        .build()

    private inner class Helper(private val d: SupportSQLiteOpenHelper) : SupportSQLiteOpenHelper by d {
        override val writableDatabase: SupportSQLiteDatabase get() = Db(d.writableDatabase)
        override val readableDatabase: SupportSQLiteDatabase get() = Db(d.readableDatabase)
    }

    private inner class Db(private val d: SupportSQLiteDatabase) : SupportSQLiteDatabase by d {
        override fun compileStatement(sql: String): SupportSQLiteStatement = Statement(d.compileStatement(sql), sql)
    }

    private inner class Statement(private val s: SupportSQLiteStatement, private val sql: String) : SupportSQLiteStatement by s {
        private val texts = HashMap<Int, String>()

        override fun bindString(index: Int, value: String) {
            texts[index] = value
            s.bindString(index, value)
        }

        override fun clearBindings() {
            texts.clear()
            s.clearBindings()
        }

        override fun execute() {
            killHere()
            s.execute()
        }

        override fun executeInsert(): Long {
            killHere()
            return s.executeInsert()
        }

        override fun executeUpdateDelete(): Int {
            killHere()
            return s.executeUpdateDelete()
        }

        private fun killHere() {
            val key = killAtMetaKey ?: return
            if ("MetaEntity" !in sql || key !in texts.values) return
            killAtMetaKey = null
            throw SQLiteException("killed while writing $key")
        }
    }
}
