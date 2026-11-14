package com.fieldmaxx.app.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import com.fieldmaxx.app.AppGraph
import com.fieldmaxx.app.FieldmaxxApp
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.MetaEntity
import com.fieldmaxx.app.data.toOp
import com.fieldmaxx.app.data.toOutbox
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.net.InMemoryTokenVault
import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.CantShootReason
import com.fieldmaxx.core.model.CrewMember
import com.fieldmaxx.core.model.JobFileInfo
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.OpResult
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.PointStatus
import com.fieldmaxx.core.model.PoleData
import com.fieldmaxx.core.model.RefImageInfo
import com.fieldmaxx.core.model.ServerMessage
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val SERVER = "https://fmx.example.com"

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val api = FakeApi()
    private val live = FakeLiveSocket()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = AtomicLong(50_000)
    private val engines = mutableListOf<SyncEngine>()

    private val j1 = job("j1")
    private val p1 = point("p1")
    private val p2 = point("p2")

    private fun newEngine(database: AppDatabase = db, socket: LiveSocket = live, server: () -> String = { SERVER }) =
        SyncEngine(api, database, socket, scope, clock::get, server).also { engines += it }

    @AfterTest fun tearDown() {
        engines.forEach { it.stop() }
        runBlocking { scope.coroutineContext.job.cancelAndJoin() } // nothing may still be writing when the database closes
        db.close()
    }

    /** j1 opened on this phone from a snapshot taken at [seq], holding p1 and p2. */
    private suspend fun SyncEngine.openSeededJob(seq: Long = 10) {
        api.head = maxOf(api.head, seq)
        api.snapshots["j1"] = snapshot(j1, listOf(p1, p2), seq)
        openJob("j1")
    }

    /** A change the server committed and broadcast. */
    private suspend fun serverChange(c: com.fieldmaxx.core.model.Change) {
        api.record(c)
        live.serverSends(ServerMessage.ChangeMsg(c))
    }

    private suspend fun <T> Flow<T>.await(predicate: (T) -> Boolean): T = withTimeout(5_000) { first(predicate) }

    private suspend fun outboxIds() = db.outbox().all().map { it.toOp().opId }

    // ---- the brief's tests ----

    @Test fun enqueueAppliesLocallyAtOnce() = runBlocking {
        api.opsReply = { throw IOException("no signal") }
        val engine = newEngine()
        engine.start(ALICE)
        engine.openSeededJob()

        engine.enqueue(Op.PointUpdate("op-1", "p1", pole = PoleData(lineHeightFt = 22.5), comment = "Leaning"))
        engine.enqueue(Op.PointClose("op-2", "p1", listOf("ph-1")))

        val shown = engine.points("j1").first().single { it.id == "p1" }
        assertEquals("Leaning", shown.comment)
        assertEquals(22.5, shown.pole?.lineHeightFt)
        assertEquals(PointStatus.done, shown.status)
        assertEquals(clock.get(), shown.updatedAt)
        assertEquals(ALICE.id, shown.updatedBy)
        assertEquals(listOf("op-1", "op-2"), outboxIds())
    }

    @Test fun offlineOpsSurviveRestartAndFlushInOrder() = runBlocking {
        val file = File(context.filesDir, "restart.db")
        fun openFileDb() = Room.databaseBuilder(context, AppDatabase::class.java, file.path).build()
        val ops = listOf(
            Op.PointUpdate("op-1", "p1", comment = "Guy wire"),
            Op.PointMove("op-2", "p1", 39.636200, -119.691200),
            Op.PointCantShoot("op-3", "p2", CantShootReason.no_access),
        )
        api.opsReply = { throw IOException("no signal") }
        val first = openFileDb()
        val before = newEngine(first)
        before.start(ALICE)
        before.openSeededJob()
        ops.forEach { before.enqueue(it) }
        awaitTrue("a send attempt") { api.sentBatches.isNotEmpty() }
        before.stop()
        first.close()

        // The app starts again, now with signal.
        api.opsReply = { batch -> batch.map { OpResult(it.opId, true) } }
        val again = openFileDb()
        try {
            val after = newEngine(again, FakeLiveSocket())
            after.start(ALICE)
            again.outbox().observeCount().await { it == 0 }
            assertEquals(ops, api.sentBatches.last(), "all three, in the order they were made")
            val shown = after.points("j1").first()
            assertEquals("Guy wire", shown.single { it.id == "p1" }.comment)
            assertEquals(PointStatus.cant_shoot, shown.single { it.id == "p2" }.status)
        } finally {
            again.close()
        }
    }

    @Test fun serverChangeOverwritesLocal() = runBlocking {
        val engine = newEngine()
        engine.start(ALICE)
        engine.openSeededJob()
        engine.enqueue(Op.PointUpdate("op-1", "p1", comment = "mine"))
        db.outbox().observeCount().await { it == 0 }

        // Later, another tech's edit of the same field reaches the phone.
        serverChange(pointChange(11, p1.copy(comment = "theirs", updatedBy = "u2")))

        val shown = engine.points("j1").await { pts -> pts.first().comment == "theirs" }.first()
        assertEquals("u2", shown.updatedBy)
    }

    @Test fun reconnectFetchesChangesSinceLastSeq() = runBlocking {
        val engine = newEngine()
        engine.openSeededJob(seq = 0)
        api.record(pointChange(1, p1.copy(comment = "a")), pointChange(2, p2.copy(comment = "b")))
        engine.start(ALICE)
        live.serverSends(ServerMessage.Hello(2))
        engine.points("j1").await { pts -> pts.map { it.comment } == listOf("a", "b") }
        awaitTrue("the first catch-up") { api.sinceCalls.size == 2 }

        // While the signal was gone the server moved on; the new connection brings only what is new.
        api.record(pointChange(3, p1.copy(comment = "c")))
        live.serverReconnects(ServerMessage.Hello(3))

        engine.points("j1").await { pts -> pts.first().comment == "c" }
        awaitTrue("the second catch-up") { api.sinceCalls.size == 4 }
        assertEquals(listOf(0L, 2L, 2L, 3L), api.sinceCalls.toList())
        assertEquals("3", db.meta().get("lastSeq"))
    }

    @Test fun unauthorizedPausesOutboxKeepsOps() = runBlocking {
        val engine = newEngine()
        engine.openSeededJob()
        api.opsReply = { throw ApiException(401, "Sign in required") }
        val signedOut = async(start = CoroutineStart.UNDISPATCHED) { engine.authRequired.first() }
        engine.start(ALICE)
        engine.enqueue(Op.PointUpdate("op-1", "p1", comment = "kept"))

        withTimeout(5_000) { signedOut.await() }
        assertEquals(listOf("op-1"), outboxIds())
        assertEquals("kept", engine.points("j1").first().first().comment, "still shown on the phone")
        assertFalse(live.connected.value, "socket closed")
        assertEquals(SyncStatus.OFFLINE, engine.status.value)
        val sends = api.sentBatches.size
        engine.enqueue(Op.PointUpdate("op-2", "p2", comment = "also kept"))
        assertEquals(FlushResult.PAUSED, engine.flushOutbox())
        assertEquals(sends, api.sentBatches.size, "nothing is sent while paused")
        assertEquals(1, live.connects.value, "no reconnect")

        // Signed in again: the engine resumes and the kept ops go out.
        api.opsReply = { ops -> ops.map { OpResult(it.opId, true) } }
        engine.start(ALICE)
        db.outbox().observeCount().await { it == 0 }
        assertEquals(listOf("op-1", "op-2"), api.sentBatches.last().map { it.opId })
        assertEquals(2, live.connects.value)
    }

    @Test fun outboxWorkerFlushesInBackground() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"results":[{"opId":"op-1","ok":true},{"opId":"op-2","ok":true}]}"""),
        )
        server.start()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val app = context as FieldmaxxApp
        val graph = AppGraph(app, InMemoryTokenVault("tok-w")).also { app.graph = it }
        graph.server.set(server.url("/").toString().trimEnd('/'))
        try {
            // The app isn't on screen and nothing started the engine; a photo step queues its results.
            runBlocking {
                graph.sync.enqueue(Op.PhotoConfirmed("op-1", "ph-1", "POLE/POLE_1/POLE_1_39.636130_-119.691104_7.jpg", 900))
                graph.sync.enqueue(Op.PhotoFailed("op-2", "ph-2", "Read timed out", 1))
            }
            val workManager = WorkManager.getInstance(context)
            val info = runBlocking {
                var found: WorkInfo? = null
                awaitTrue("background flush lined up") {
                    found = workManager.getWorkInfosForUniqueWork(OutboxWorker.NAME).get().singleOrNull()
                    found != null
                }
                found!!
            }
            assertEquals(WorkInfo.State.ENQUEUED, info.state)
            assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)

            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(info.id)

            val request = assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
            assertEquals("POST /api/ops", "${request.method} ${request.path}")
            assertEquals("Bearer tok-w", request.getHeader("Authorization"))
            val sent = FmxJson.decodeFromJsonElement(
                ListSerializer(Op.serializer()),
                FmxJson.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("ops").jsonArray,
            )
            assertEquals(listOf("op-1", "op-2"), sent.map { it.opId })
            runBlocking {
                awaitTrue("work succeeded") { workManager.getWorkInfoById(info.id).get()?.state == WorkInfo.State.SUCCEEDED }
                assertEquals(emptyList(), graph.db.outbox().all())
            }
            assertEquals(1, server.requestCount, "ops only: no positions, no socket")
        } finally {
            graph.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
            server.shutdown()
        }
    }

    @Test fun opQueuedAsABackgroundFlushEndsGetsAnotherFlush() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val sent = FmxJson.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("ops").jsonArray
                val results = FmxJson.decodeFromJsonElement(ListSerializer(Op.serializer()), sent).map { OpResult(it.opId, true) }
                val body = FmxJson.encodeToString(ListSerializer(OpResult.serializer()), results)
                return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"results":$body}""")
            }
        }
        server.start()
        val app = context as FieldmaxxApp
        val runs = AtomicInteger()
        // The first background flush ends just as a photo step queues its result: past the flush's last look at the outbox.
        val workers = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                object : CoroutineWorker(appContext, workerParameters) {
                    override suspend fun doWork(): Result {
                        val result = OutboxWorker(appContext, workerParameters).doWork()
                        if (runs.incrementAndGet() == 1) {
                            app.graph.sync.enqueue(Op.PhotoFailed("op-2", "ph-2", "Read timed out", 1))
                            delay(300) // still running while the outbox reports the new op
                        }
                        return result
                    }
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(workers).build(),
        )
        val graph = AppGraph(app, InMemoryTokenVault("tok-w")).also { app.graph = it }
        graph.server.set(server.url("/").toString().trimEnd('/'))
        val workManager = WorkManager.getInstance(context)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        suspend fun queued(except: java.util.UUID? = null): WorkInfo {
            var found: WorkInfo? = null
            awaitTrue("a background flush lined up") {
                found = workManager.getWorkInfosForUniqueWork(OutboxWorker.NAME).get().firstOrNull { it.id != except && it.state == WorkInfo.State.ENQUEUED }
                found != null
            }
            return found!!
        }
        try {
            runBlocking {
                graph.sync.enqueue(Op.PhotoFailed("op-1", "ph-1", "Read timed out", 1))
                val first = queued()
                driver.setAllConstraintsMet(first.id)

                // KEEP lines up another flush only once the first has ended (WorkManager then prunes the finished one).
                val second = queued(except = first.id)
                assertEquals(1, runs.get(), "the first flush ran and ended")
                driver.setAllConstraintsMet(second.id)
                graph.db.outbox().observeCount().await { it == 0 }
            }
            assertEquals(2, server.requestCount, "op-1, then op-2")
        } finally {
            graph.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
            server.shutdown()
        }
    }

    // ---- controller rulings ----

    @Test fun opInFlightOverANewerWriteEndsOnTheWinner() = runBlocking {
        val engine = newEngine()
        engine.openSeededJob(seq = 10)
        val answer = CompletableDeferred<Unit>().also { api.opsGate = it }
        engine.start(ALICE)
        engine.enqueue(Op.PointUpdate("op-1", "p1", comment = "mine"))
        awaitTrue("the batch in flight") { api.sentBatches.isNotEmpty() }

        // The server took ours (11), then another tech's write to the same field (12), and 12 reaches the phone first.
        val theirs = p1.copy(comment = "theirs", updatedBy = "u2", updatedAt = 900)
        api.record(pointChange(11, p1.copy(comment = "mine", updatedBy = ALICE.id, updatedAt = 800)))
        api.snapshots["j1"] = snapshot(j1, listOf(theirs, p2), seq = 12)
        serverChange(pointChange(12, theirs))
        awaitTrue("their change applied") { db.points().get("p1")?.seq == 12L }
        assertEquals("mine", engine.points("j1").first().first().comment, "ours, still unanswered, applied again on top (R16)")
        answer.complete(Unit)

        engine.points("j1").await { pts -> pts.first().comment == "theirs" }
        assertEquals(listOf("j1", "j1"), api.snapshotCalls.toList(), "opened, then fetched again once the answer came")
        assertEquals(emptyList(), outboxIds())
    }

    @Test fun ownChangeLandingInFlightNeedsNoNewSnapshot() = runBlocking {
        val engine = newEngine()
        engine.openSeededJob(seq = 10)
        val answer = CompletableDeferred<Unit>().also { api.opsGate = it }
        engine.start(ALICE)
        engine.enqueue(Op.PointUpdate("op-1", "p1", comment = "mine"))
        awaitTrue("the batch in flight") { api.sentBatches.isNotEmpty() }

        // Our own write comes back over the socket before the answer, as it usually does.
        val ours = p1.copy(comment = "mine", updatedBy = ALICE.id, updatedAt = 800)
        serverChange(pointChange(11, ours))
        awaitTrue("the change applied") { db.points().get("p1")?.seq == 11L }
        answer.complete(Unit)

        db.outbox().observeCount().await { it == 0 }
        engine.enqueue(Op.PointUpdate("op-2", "p2", comment = "next")) // goes out after the answer was handled
        db.outbox().observeCount().await { it == 0 }
        assertEquals(listOf("j1"), api.snapshotCalls.toList(), "the server already has our value: no snapshot needed")
        assertEquals("mine", engine.points("j1").first().first().comment)
    }

    @Test fun snapshotAndItsLastSeqRewindLandTogether() = runBlocking {
        val killable = KillableDb(context)
        try {
            killable.db.meta().put(MetaEntity("lastSeq", "20"))
            val engine = newEngine(database = killable.db)
            api.snapshots["j1"] = snapshot(j1, listOf(p1, p2), seq = 12) // read before changes 13..20 reached the phone

            killable.killAtMetaKey = "lastSeq"
            assertFails { engine.openJob("j1") }

            val stored = killable.db.meta().get("extras:j1") != null
            val lastSeq = killable.db.meta().get("lastSeq")?.toLong()
            assertFalse(stored && lastSeq!! > 12, "a stored job never sits under a lastSeq that skips its changes")
            engine.openJob("j1") // the next try
            assertEquals("12", killable.db.meta().get("lastSeq"))
            assertEquals(listOf("p1", "p2"), engine.points("j1").first().map { it.id })
        } finally {
            engines.forEach { it.stop() }
            scope.coroutineContext.job.cancelAndJoin()
            killable.db.close()
        }
    }

    @Test fun statusIsOfflineWhenAuthRequiredIsHeard() = runBlocking {
        val socket = FakeLiveSocket(dropOnClose = false)
        val engine = newEngine(socket = socket)
        engine.openSeededJob()
        api.opsReply = { throw ApiException(401, "Sign in required") }
        var heard: SyncStatus? = null
        // Unconfined: the listener runs the moment authRequired is emitted, as a quick screen would.
        val listener = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            engine.authRequired.first()
            heard = engine.status.value
        }
        engine.start(ALICE)
        socket.connects.await { it > 0 }
        engine.enqueue(Op.PointUpdate("op-1", "p1", comment = "x"))

        withTimeout(5_000) { listener.join() }
        assertEquals(SyncStatus.OFFLINE, heard)
    }

    @Test fun anotherServerAddressStartsAFreshCache() = runBlocking {
        var address = "https://old.example.com"
        val engine = newEngine(server = { address })
        api.opsReply = { throw IOException("no signal") }
        engine.start(ALICE)
        engine.openSeededJob(seq = 10)
        live.serverSends(ServerMessage.Hello(10))
        awaitTrue("caught up") { db.meta().get("lastSeq") == "10" }
        engine.enqueue(Op.PointUpdate("op-1", "p1", comment = "kept"))
        db.localPhotos().upsert(localPhoto("lp-1"))

        // Signed in again, to another server: its j1 is another job.
        engine.stop()
        address = "https://new.example.com"
        api.restoreTo(3)
        api.snapshots["j1"] = snapshot(job("j1", "A05K51V"), listOf(point("q7")), seq = 3)
        engine.start(ALICE)
        live.serverSends(ServerMessage.Hello(3))

        engine.points("j1").await { pts -> pts.map { it.id } == listOf("q7") }
        assertEquals("A05K51V", engine.job("j1").first()?.name)
        awaitTrue("lastSeq follows the new server") { db.meta().get("lastSeq") == "3" }
        assertEquals(listOf("op-1"), outboxIds(), "the outbox stays")
        assertNotNull(db.localPhotos().get("lp-1"), "photos taken here stay")
        assertEquals(ALICE, engine.savedUser())
    }

    @Test fun serverRestoredFromABackupStartsAFreshCache() = runBlocking {
        val engine = newEngine()
        engine.start(ALICE)
        engine.openSeededJob(seq = 10)
        live.serverSends(ServerMessage.Hello(10))
        awaitTrue("caught up") { db.meta().get("lastSeq") == "10" }

        // The server comes back from a backup taken at seq 6: there j1 has only p1, with an older comment.
        val restored = p1.copy(comment = "from the backup")
        api.restoreTo(6)
        api.snapshots["j1"] = snapshot(j1, listOf(restored), seq = 6)
        live.serverReconnects(ServerMessage.Hello(6))

        engine.points("j1").await { pts -> pts == listOf(restored) }
        awaitTrue("lastSeq follows the restored server") { db.meta().get("lastSeq") == "6" }
    }

    @Test fun olderChangeArrivingLateIsIgnored() = runBlocking {
        val engine = newEngine()
        engine.start(ALICE)
        engine.openSeededJob(seq = 10)

        // The server committed 11 then 12, but published 12 first.
        serverChange(pointChange(12, p1.copy(comment = "newer")))
        serverChange(pointChange(11, p1.copy(comment = "older")))
        serverChange(pointChange(13, p2.copy(comment = "marker"))) // handled after 11

        val shown = engine.points("j1").await { pts -> pts.any { it.comment == "marker" } }
        assertEquals("newer", shown.single { it.id == "p1" }.comment)

        // A snapshot older than a row doesn't roll the row back either.
        api.snapshots["j1"] = snapshot(j1, listOf(p1.copy(comment = "stale"), p2), seq = 11)
        engine.openJob("j1")
        val after = engine.points("j1").first()
        assertEquals("newer", after.single { it.id == "p1" }.comment)
        assertEquals("marker", after.single { it.id == "p2" }.comment)
    }

    @Test fun pendingOpStaysVisibleOverIncomingChange() = runBlocking {
        api.opsReply = { throw IOException("no signal") }
        val engine = newEngine()
        engine.start(ALICE)
        engine.openSeededJob()
        engine.enqueue(Op.PointUpdate("op-1", "p1", comment = "Anchor buried"))

        // Meanwhile another tech drags the same point.
        serverChange(pointChange(11, p1.copy(lat = 39.637, lon = -119.692, updatedBy = "u2")))

        val shown = engine.points("j1").await { pts -> pts.first().lat == 39.637 }.first()
        assertEquals(-119.692, shown.lon)
        assertEquals("Anchor buried", shown.comment, "the edit still waiting to be sent stays on screen")
    }

    @Test fun pendingAddedPointSurvivesASnapshotWithoutIt() = runBlocking {
        api.opsReply = { throw IOException("no signal") }
        val engine = newEngine()
        engine.start(ALICE)
        engine.openSeededJob()
        engine.enqueue(Op.PointAdd("op-1", "j1", "p9", "POLE", "POLE", "1B", 39.6362, -119.6912))
        engine.enqueue(Op.PointUpdate("op-2", "p9", comment = "New pole"))

        // The job is opened again; the server doesn't have the new point yet, and p2 is gone from it.
        api.snapshots["j1"] = snapshot(j1, listOf(p1), seq = 12)
        engine.openJob("j1")

        val points = engine.points("j1").first()
        assertEquals(listOf("p1", "p9"), points.map { it.id }, "added point kept once; the server's missing point removed")
        val added = points.last()
        assertEquals("POLE 1B", added.label)
        assertEquals("New pole", added.comment)
        assertEquals(ALICE.id, added.addedBy)
    }

    @Test fun rejectedOpIsDroppedAndTheJobRefetched() = runBlocking {
        val engine = newEngine()
        engine.openSeededJob()
        // On the server p1 was deleted meanwhile, so it refuses the edit.
        api.opsReply = { ops -> ops.map { OpResult(it.opId, false, "point_deleted") } }
        api.snapshots["j1"] = snapshot(j1, listOf(p1.copy(deletedAt = 900), p2), seq = 14)
        val refused = async(start = CoroutineStart.UNDISPATCHED) { engine.rejected.first() }
        engine.start(ALICE)
        engine.enqueue(Op.PointUpdate("op-1", "p1", comment = "Too late"))

        assertEquals(OpResult("op-1", false, "point_deleted"), withTimeout(5_000) { refused.await() })
        assertEquals(emptyList(), outboxIds())
        assertEquals(listOf("j1", "j1"), api.snapshotCalls.toList(), "opened, then fetched again")
        assertEquals(listOf("p2"), engine.points("j1").first().map { it.id }, "server truth: p1 is deleted")
    }

    @Test fun sinceFetchPagesUntilAnEmptyPage() = runBlocking {
        val engine = newEngine()
        engine.openSeededJob(seq = 0)
        api.pageSize = 2
        api.record((1L..5L).map { pointChange(it, p1.copy(comment = "v$it")) })
        engine.start(ALICE)
        live.serverSends(ServerMessage.Hello(5))

        awaitTrue("four pages") { api.sinceCalls.size == 4 }
        assertEquals(listOf(0L, 2L, 4L, 5L), api.sinceCalls.toList())
        assertEquals("v5", engine.points("j1").first().first().comment)
        assertEquals("5", db.meta().get("lastSeq"))
    }

    @Test fun liveChangesAdvanceLastSeqOnlyWithoutGaps() = runBlocking {
        val engine = newEngine()
        engine.openSeededJob(seq = 0)
        engine.start(ALICE)
        live.serverSends(ServerMessage.Hello(0))
        awaitTrue("the first catch-up") { api.sinceCalls.size == 1 }

        serverChange(pointChange(1, p1.copy(comment = "a")))
        awaitTrue("lastSeq 1") { db.meta().get("lastSeq") == "1" }

        // 3 is broadcast before 2: it shows at once, but lastSeq waits until a catch-up fetch has filled the gap.
        val catchUp = CompletableDeferred<Unit>().also { api.changesGate = it }
        api.record(pointChange(2, p2.copy(comment = "b")))
        serverChange(pointChange(3, p1.copy(comment = "c")))
        engine.points("j1").await { pts -> pts.first().comment == "c" }
        awaitTrue("the catch-up fetch") { api.sinceCalls.size == 2 }
        assertEquals("1", db.meta().get("lastSeq"))

        catchUp.complete(Unit)
        awaitTrue("lastSeq 3") { db.meta().get("lastSeq") == "3" }
        assertEquals("b", engine.points("j1").first().last().comment)
        assertEquals(1L, api.sinceCalls[1], "the catch-up starts after the last change applied in order")
    }

    @Test fun pointsOfAJobNotOpenedHereArriveWithItsSnapshot() = runBlocking<Unit> {
        val j2 = job("j2", "A05K51V")
        val q1 = point("q1", jobId = "j2")
        api.record(jobChange(1, j2), pointChange(2, q1.copy(comment = "early")), pointChange(3, q1.copy(comment = "latest")))
        val engine = newEngine()
        engine.start(ALICE)
        live.serverSends(ServerMessage.Hello(3))
        awaitTrue("caught up") { db.meta().get("lastSeq") == "3" }

        assertEquals(j2, engine.job("j2").first(), "job rows always stay current")
        assertEquals(emptyList(), engine.points("j2").first(), "never a partial point list")

        // Opened later from a snapshot read at seq 2, before change 3: the phone catches up from there.
        api.snapshots["j2"] = snapshot(j2, listOf(q1.copy(comment = "early")), seq = 2)
        engine.openJob("j2")
        engine.points("j2").await { pts -> pts.singleOrNull()?.comment == "latest" }
    }

    @Test fun flowsHideDeletedPointsRemovedPhotosAndOwnPosition() = runBlocking<Unit> {
        val engine = newEngine()
        api.snapshots["j1"] = snapshot(
            j1, listOf(p1, p2.copy(deletedAt = 5)), seq = 10,
            photos = listOf(photo("ph-1", "p1"), photo("ph-2", "p1", status = PhotoStatus.removed)),
        )
        engine.openJob("j1")
        assertEquals(listOf("p1"), engine.points("j1").first().map { it.id })
        assertEquals(listOf("ph-1"), engine.photos("p1").first().map { it.id })
        assertEquals(listOf("ph-1"), engine.jobPhotos("j1").first().map { it.id })

        api.positionsReply = listOf(position(ALICE.id, at = 1), position("u2", at = 1))
        engine.start(ALICE)
        live.serverSends(ServerMessage.Hello(10))
        engine.crew.await { crew -> crew.map { it.userId } == listOf("u2") }
        live.serverSends(ServerMessage.PositionMsg(position("u3", at = 2)))
        live.serverSends(ServerMessage.PositionMsg(position(ALICE.id, at = 3)))
        live.serverSends(ServerMessage.PositionMsg(position("u2", at = 4, lat = 39.7)))
        engine.crew.await { crew -> crew.map { it.userId to it.at } == listOf("u2" to 4L, "u3" to 2L) }
    }

    @Test fun openJobKeepsExtrasAndPrefetchesReferenceImages() = runBlocking {
        val asked = CopyOnWriteArrayList<List<String>>()
        val engine = SyncEngine(api, db, live, scope, clock::get, server = { SERVER }) { urls -> asked += urls }
            .also { engines += it }
        val refs = listOf(RefImageInfo("imp-1-page-1", "page", 1), RefImageInfo("imp-1-item-3", "item", null))
        val files = listOf(JobFileInfo("f1", "A05J9XE.pdf", "POLE"))
        val crew = listOf(CrewMember("u2", "Bob Tech"))
        api.snapshots["j1"] = snapshot(j1, listOf(p1), seq = 3, refImages = refs, files = files, crew = crew)

        engine.openJob("j1")

        assertEquals(JobExtras(refs, files, crew), engine.extras("j1").first())
        assertEquals(
            listOf(listOf("https://fmx.example.com/api/jobs/j1/ref/imp-1-page-1", "https://fmx.example.com/api/jobs/j1/ref/imp-1-item-3")),
            asked.toList(),
        )
        assertEquals("https://fmx.example.com/api/jobs/j1/ref/r1", refImageUrl("https://fmx.example.com/", "j1", "r1"))
    }

    @Test fun prefetchKeepsReferenceImagesOnDiskWithTheSessionToken() {
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
        val snapshotJson = FmxJson.encodeToString(
            com.fieldmaxx.core.model.JobSnapshot.serializer(),
            snapshot(j1, listOf(p1), seq = 3, refImages = listOf(RefImageInfo("r1", "page", 1))),
        )
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/jobs/j1/snapshot" -> MockResponse().setHeader("Content-Type", "application/json").setBody(snapshotJson)
                "/api/jobs/j1/ref/r1" -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val graph = AppGraph(context, InMemoryTokenVault("tok-r"))
        graph.server.set(server.url("/").toString().trimEnd('/'))
        val url = refImageUrl(graph.server.get(), "j1", "r1")
        try {
            runBlocking {
                graph.sync.openJob("j1")
                awaitTrue("image in the disk cache") { graph.imageLoader.diskCache!!.openSnapshot(url)?.use { true } == true }
            }
            val requests = (1..server.requestCount).map { server.takeRequest() }
            val image = requests.single { it.path == "/api/jobs/j1/ref/r1" }
            assertEquals("Bearer tok-r", image.getHeader("Authorization"))
        } finally {
            graph.close()
            server.shutdown()
        }
    }

    @Test fun flushSendsBatchesOfAtMostFiftyInOrder() = runBlocking {
        val engine = newEngine()
        engine.openSeededJob()
        val ops = (1..120).map { Op.PointUpdate("op-$it", if (it % 2 == 0) "p1" else "p2", comment = "c$it") }
        ops.forEach { engine.enqueue(it) } // not started yet: nothing goes out

        engine.start(ALICE)

        db.outbox().observeCount().await { it == 0 }
        assertEquals(listOf(50, 50, 20), api.sentBatches.map { it.size })
        assertEquals(ops, api.sentBatches.flatten())
    }

    @Test fun failedBatchesKeepTheirOpsUntilTheServerTakesThem() = runBlocking {
        val failures = ArrayDeque(
            listOf(IOException("no signal"), ApiException(503, "Server restarting"), ApiException(429, "Too many requests"), ApiException(400, "Bad request")),
        )
        api.opsReply = { ops -> synchronized(failures) { failures.removeFirstOrNull() }?.let { throw it } ?: ops.map { OpResult(it.opId, true) } }
        val engine = newEngine()
        engine.openSeededJob()
        val refused = CopyOnWriteArrayList<OpResult>()
        val listening = launch(start = CoroutineStart.UNDISPATCHED) { engine.rejected.collect { refused += it } }
        engine.enqueue(Op.PointUpdate("op-1", "p1", comment = "a"))
        engine.enqueue(Op.PointUpdate("op-2", "p2", comment = "b"))
        engine.start(ALICE)

        for (attempt in 1..4) {
            awaitTrue("attempt $attempt") { api.sentBatches.size >= attempt }
            assertEquals(listOf("op-1", "op-2"), outboxIds(), "kept after failed attempt $attempt")
            live.serverReconnects(ServerMessage.Hello(10)) // the server is back: no need to wait the backoff out
        }

        db.outbox().observeCount().await { it == 0 }
        assertEquals(List(5) { listOf("op-1", "op-2") }, api.sentBatches.map { batch -> batch.map { it.opId } }, "always the whole batch, in order")
        assertEquals(emptyList(), refused.toList(), "nothing refused")
        listening.cancel()
    }

    @Test fun statusShowsOpsStillWaiting() = runBlocking {
        api.opsReply = { throw ApiException(503, "Server restarting") }
        db.outbox().insert(Op.PointUpdate("op-1", "p1", comment = "from the last run").toOutbox())
        val engine = newEngine()
        assertEquals(SyncStatus.OFFLINE, engine.status.value, "not started")

        engine.start(ALICE)
        awaitTrue("a send attempt") { api.sentBatches.isNotEmpty() }
        val online = withTimeoutOrNull(500) { engine.status.first { it == SyncStatus.ONLINE } }
        assertNull(online, "never online while an op waits")
        assertEquals(SyncStatus.SYNCING, engine.status.value)

        api.opsReply = { ops -> ops.map { OpResult(it.opId, true) } }
        live.serverReconnects(ServerMessage.Hello(0))
        engine.status.await { it == SyncStatus.ONLINE }
        assertEquals(emptyList(), outboxIds())
    }

    @Test fun unauthorizedFetchSnapshotOrPositionsPausesTheEngine() = runBlocking {
        val ended = ApiException(401, "Sign in required")
        for (endpoint in listOf("changes", "positions", "snapshot")) {
            api.changesError = ended.takeIf { endpoint == "changes" }
            api.positionsError = ended.takeIf { endpoint == "positions" }
            api.snapshotError = ended.takeIf { endpoint == "snapshot" }
            val engine = newEngine()
            val signedOut = async(start = CoroutineStart.UNDISPATCHED) { engine.authRequired.first() }
            engine.start(ALICE)

            if (endpoint == "snapshot") assertFailsWith<ApiException> { engine.openJob("j1") } else live.serverSends(ServerMessage.Hello(0))

            withTimeout(5_000) { signedOut.await() }
            assertFalse(live.connected.value, "$endpoint: socket closed")
            assertEquals(SyncStatus.OFFLINE, engine.status.value, endpoint)
            assertEquals(FlushResult.PAUSED, engine.flushOutbox(), "$endpoint: outbox paused")
            engine.stop()
        }
    }

    @Test fun startAndStopAreIdempotentAndStopKeepsEverything() = runBlocking<Unit> {
        api.opsReply = { throw IOException("no signal") }
        val engine = newEngine()
        engine.openSeededJob()
        engine.start(ALICE)
        engine.start(ALICE)
        live.connects.await { it > 0 }
        engine.enqueue(Op.PointUpdate("op-1", "p1", comment = "kept"))
        assertEquals(1, live.connects.value, "started once")

        engine.stop()
        engine.stop()

        assertFalse(live.connected.value)
        assertEquals(SyncStatus.OFFLINE, engine.status.value)
        assertEquals(listOf("op-1"), outboxIds(), "the outbox stays")
        assertEquals(listOf("p1", "p2"), engine.points("j1").first().map { it.id }, "and so does everything stored")
        assertEquals("kept", engine.points("j1").first().first().comment)
        engine.start(ALICE)
        live.connects.await { it == 2 } // started again after a stop
    }

    @OptIn(DelicateCoilApi::class)
    @Test fun appImageLoaderIsTheGraphs() {
        val app = context as FieldmaxxApp
        val graph = AppGraph(app, InMemoryTokenVault("tok-i")).also { app.graph = it }
        SingletonImageLoader.reset() // the singleton outlives a test's application
        try {
            assertSame(graph.imageLoader, SingletonImageLoader.get(app), "screens load images through the signed-in client")
            assertNotNull(graph.imageLoader.diskCache, "reference images stay on disk for work without signal")
        } finally {
            SingletonImageLoader.reset()
            graph.close()
        }
    }

    @Test fun socketClosedForSignInPausesTheEngine() = runBlocking {
        val engine = newEngine()
        val signedOut = async(start = CoroutineStart.UNDISPATCHED) { engine.authRequired.first() }
        engine.start(ALICE)
        live.serverSignsOut()

        withTimeout(5_000) { signedOut.await() }
        assertFalse(live.connected.value)
        assertEquals(FlushResult.PAUSED, engine.flushOutbox())
        assertEquals(ALICE, engine.savedUser(), "kept for the next start without signal")
    }
}
