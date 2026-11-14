package com.fieldmaxx.app.photos

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.fieldmaxx.app.AppGraph
import com.fieldmaxx.app.FieldmaxxApp
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.LocalPhotoEntity
import com.fieldmaxx.app.data.toOp
import com.fieldmaxx.app.data.toPoint
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.net.InMemoryTokenVault
import com.fieldmaxx.app.sync.FakeLiveSocket
import com.fieldmaxx.app.sync.OpApplier
import com.fieldmaxx.app.sync.SyncEngine
import com.fieldmaxx.app.sync.job
import com.fieldmaxx.app.sync.jobChange
import com.fieldmaxx.app.sync.point
import com.fieldmaxx.app.sync.snapshot
import com.fieldmaxx.core.model.Change
import com.fieldmaxx.core.model.JobSnapshot
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.OpResult
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoRegistration
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.RegisteredPhoto
import com.fieldmaxx.core.naming.defaultFolderName
import com.fieldmaxx.core.naming.photoFileName
import com.fieldmaxx.sharepoint.ChunkSession
import com.fieldmaxx.sharepoint.DestinationErrorCode
import com.fieldmaxx.sharepoint.FakeDestination
import com.fieldmaxx.sharepoint.PhotoDestination
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
private val NOW = ZonedDateTime.of(2026, 11, 5, 13, 35, 47, 0, ZONE).toInstant().toEpochMilli()
private const val SERVER = "https://fmx.example.com"
private const val LINK = "https://1drv.ms/f/c/184ec04aa3230f57/Field-Notes"
private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR
private const val CHUNK = 4096
private const val BACKUP_CHUNK = 3000
private const val FOLDER = "POLE/POLE_1"

private val META = CaptureMeta(
    jobName = "A05J9XE", pointLabel = "POLE 1", source = SOURCE_CAMERA, lat = 39.636130, lon = -119.691104, accuracyM = 4.877,
    altitudeM = 1350.26, headingDeg = 47.0, takenAt = NOW, gpsStale = false, headingTrueNorth = true, techId = "u1",
)

/** A process death as the code under it sees it: nothing after it runs, and its open transaction is rolled back. */
private class Killed : Error("killed")

/**
 * The photo pipeline end to end, as WorkManager runs it: real workers (through a factory that hands them the test's
 * pipeline), the work-testing driver for the network and the backoff delays, Room in memory, the sync engine's outbox,
 * an in-memory OneDrive ([FakeDestination]) and a fake Fieldmaxx server. A "restart" throws the pipeline away and builds
 * a new one from the database, as a new process would, then calls resumeAll().
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipelineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dying = DyingDb(context)
    private val db: AppDatabase = dying.db
    private val clock = AtomicLong(NOW)
    private val fake = FakeDestination()
    private val dest = TestDestination(fake)
    private val server = PhotoServer()
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sync = SyncEngine(server, db, FakeLiveSocket(), syncScope, clock::get, { SERVER })
    private val linksAsked = CopyOnWriteArrayList<String>()
    @Volatile private var quality = 85
    @Volatile private var galleryOn = false
    private var pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var pipeline: PhotoPipeline = newPipeline()
    private val photos = LocalPhotos(db, context.filesDir, { id -> pipeline.start(id) }, clock::get, { throw FileNotFoundException() }, { ZONE })
    private val shots = AtomicInteger()
    private val metConstraints = HashSet<UUID>()
    private val metDelays = HashSet<UUID>()

    /** A noisy 160×120 photo: its stamped copy takes several 4 KB chunks. */
    private val source: ByteArray by lazy {
        val random = Random(11)
        val pixels = IntArray(160 * 120) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        ByteArrayOutputStream().also { Bitmap.createBitmap(pixels, 160, 120, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
            .toByteArray()
    }

    @Before fun setUp() {
        // The test scheduler hands out scheduling slots as JobScheduler does (20 by default), so 20 photos waiting for signal
        // would keep the 21st from its stamp. On a phone, WorkManager's in-process scheduler runs work that needs no
        // network at once; the most slots WorkManager allows stands in for it.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(Workers()).setMaxSchedulerLimit(50).build(),
        )
        server.snapshots["j1"] = snapshot(job("j1").copy(fieldNotesUrl = LINK), listOf(point("p1"), point("p2")), seq = 10)
        runBlocking {
            sync.openJob("j1")
            pipeline.resumeAll().join()
        }
    }

    @After fun tearDown() {
        workManager.cancelAllWork().result.get()
        awaitIdle()
        sync.stop()
        runBlocking {
            pipelineScope.coroutineContext.job.cancelAndJoin()
            syncScope.coroutineContext.job.cancelAndJoin()
        }
        db.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    // ---- the brief's tests ----

    @Test fun happyPathSavedToConfirmedAndBackedUp() {
        val id = take()
        runUntil("confirmed and backed up") { done(id) }

        val row = assertNotNull(row(id))
        val name = server.names.getValue(id)
        assertEquals("POLE_1_39.636130_-119.691104_1.jpg", name, "the server's name")
        assertEquals(name, row.fileName)
        val stamped = File(assertNotNull(row.stampedPath)).readBytes()
        assertEquals(setOf("$FOLDER/$name"), fake.files.keys, "Field Notes/POLE/POLE_1, made for the point")
        assertContentEquals(stamped, fake.files.getValue("$FOLDER/$name"))
        assertContentEquals(stamped, server.copies.getValue(id), "the server's backup copy")
        val registration = server.registrations.getValue(id)
        assertEquals(stamped.size.toLong(), registration.size)
        assertEquals(sha256(stamped), registration.sha256)
        assertEquals(NOW, registration.takenAt)
        assertEquals(47.0, registration.headingDeg)

        val confirmed = confirmedOps(id)
        assertEquals(listOf("photo.confirmed:$id"), confirmed.map { it.opId })
        assertEquals("$FOLDER/$name", confirmed.single().destPath)
        assertEquals(listOf(FOLDER), ops().filterIsInstance<Op.PointSetFolder>().map { it.folderPath }, "the folder is kept on the point")
        assertEquals(FOLDER, localPoint("p1").folderPath)
        assertEquals(listOf(LINK), linksAsked.distinct(), "through the job's Field Notes link")
        assertNull(row.oneDriveUploadId)
        assertEquals(0, row.attempts)
        assertFalse(row.stuck)
        assertEquals(emptyList(), ops().filterIsInstance<Op.PhotoFailed>())
    }

    @Test fun pipelineSurvivesAKillAfterEveryStep() {
        for (loseQueue in listOf(false, true)) {
            val id = take()
            await("stamped") { row(id)?.state == STAMPED } // the stamp needs no network
            for (step in listOf(Step.REGISTER, Step.ONEDRIVE, Step.BACKUP)) {
                restart(loseQueue)
                runStep(step, id)
            }
            restart(loseQueue)
            runUntil("nothing left to do") { done(id) && idle() }
            assertDeliveredOnce(id)
        }
        assertEquals(2, fake.files.size)
    }

    @Test fun pipelineSurvivesAKillInsideEveryStep() {
        val armed = AtomicBoolean(false)
        fun die() {
            if (armed.compareAndSet(true, false)) throw Killed()
        }
        val kills: List<Pair<String, () -> Unit>> = listOf(
            "as the stamp was recorded" to { dying.killOn = "SET state = 'stamped'" },
            "after the server registered it, before its answer came" to { server.afterRegister = ::die },
            "as the registration was recorded" to { dying.killOn = "SET state = 'registered'" },
            "after the first chunk reached OneDrive" to { dest.onChunk = { die() } },
            "after the last chunk, before the check" to { dest.afterUpload = { die() } },
            "as the confirmation was recorded" to { dying.killOn = "SET state = 'confirmed'" },
            "after the server took a backup chunk" to { server.afterBackupChunk = ::die },
            "as the backup was recorded" to { dying.killOn = "SET backupDone = 1" },
        )
        val ids = mutableListOf<String>()
        for (loseQueue in listOf(false, true)) {
            // Killed right after the row was written, before the pipeline heard of it.
            val dyingStarter = LocalPhotos(db, context.filesDir, { throw Killed() }, clock::get, { throw FileNotFoundException() }, { ZONE })
            assertFailsWith<Killed> { runBlocking { dyingStarter.saveCaptured("j1", "p1", nextMeta()) { it.writeBytes(source) } } }
            val saved = runBlocking { db.localPhotos().inStates(listOf(SAVED)) }.single()
            restart(loseQueue)
            runUntil("the photo saved as the app died") { done(saved.id) }
            ids += saved.id

            for ((moment, arm) in kills) {
                armed.set(true)
                dying.kills.set(0)
                arm()
                val id = take()
                runUntil("killed $moment") { !armed.get() || dying.kills.get() > 0 }
                disarm()
                restart(loseQueue)
                runUntil("delivered after a kill $moment") { done(id) && idle() }
                ids += id
            }
        }
        ids.forEach(::assertDeliveredOnce)
        assertEquals(ids.size, fake.files.size, "one file per photo, whatever the kill")
    }

    @Test fun resumesChunkedUploadFromPersistedOffset() {
        fake.dropChunkAfter = 1 // the signal drops once the first chunk is through
        val id = take()
        runUntil("the dropped upload", backoffOver = false) { row(id)?.let { it.attempts == 1 && it.oneDriveUploadId != null } == true }
        val dropped = assertNotNull(row(id))
        assertEquals(CHUNK.toLong(), dropped.oneDriveOffset, "the offset OneDrive confirmed")
        assertTrue(dropped.createdThisFile)
        assertEquals("connection dropped", dropped.lastError)

        restart(loseQueue = false) // a later worker run, in a new process
        runUntil("confirmed") { done(id) }
        assertEquals(listOf(null, ChunkSession(dropped.oneDriveUploadId!!, CHUNK.toLong())), dest.resumes, "resumed where it stopped")
        assertContentEquals(stamped(id), fake.files.getValue(destPath(id)))
        assertEquals(1, fake.files.size)
    }

    @Test fun sameNameSameSizeCountsAsDelivered() {
        val id = take()
        runStep(Step.REGISTER, id)
        // The server's takeover (or an attempt whose answer was lost) put the photo there already.
        val path = "$FOLDER/${row(id)!!.fileName}"
        val there = ByteArray(stamped(id).size) { 7 }
        fake.ensureFolder(FOLDER)
        fake.files[path] = there

        runUntil("confirmed") { done(id) }
        assertTrue(fake.calls.none { it.startsWith("uploadChunked") }, "nothing uploaded")
        assertContentEquals(there, fake.files[path], "never overwritten")
        assertEquals(listOf(path), confirmedOps(id).map { it.destPath })
        assertEquals(0, server.renames.get())
    }

    @Test fun sameNameDifferentSizeRenamesViaServer() {
        val id = take()
        runStep(Step.REGISTER, id)
        val first = row(id)!!.fileName!!
        val other = byteArrayOf(1, 2, 3)
        fake.ensureFolder(FOLDER)
        fake.files["$FOLDER/$first"] = other // someone else's file under the name

        runUntil("confirmed") { done(id) }
        val renamed = row(id)!!.fileName!!
        assertEquals("POLE_1_39.636130_-119.691104_2.jpg", renamed, "the server's next counter")
        assertEquals(1, server.renames.get())
        assertContentEquals(other, fake.files["$FOLDER/$first"], "the other file is never touched")
        assertContentEquals(stamped(id), fake.files["$FOLDER/$renamed"])
        assertEquals(listOf("$FOLDER/$renamed"), confirmedOps(id).map { it.destPath })
    }

    @Test fun ambiguousFolderWaitsForChoice() {
        fake.ensureFolder("POLE/1")
        fake.ensureFolder("POLE/POLE_1") // two folders could be POLE 1's
        val id = take()
        runUntil("the question on the point") { localPoint("p1").folderCandidates != null }
        assertEquals(listOf("POLE/1", "POLE/POLE_1"), localPoint("p1").folderCandidates, "the point sheet asks which")
        awaitIdle()
        val waiting = assertNotNull(row(id))
        assertEquals(REGISTERED, waiting.state)
        assertEquals(0, waiting.attempts, "waiting for a choice is not a failure")
        assertFalse(waiting.stuck)
        assertTrue(fake.calls.none { it.startsWith("uploadChunked") })
        assertTrue(work(Step.ONEDRIVE, id).none { it.state == WorkInfo.State.ENQUEUED }, "no retry: it waits for the choice")

        runBlocking { sync.enqueue(Op.PointSetFolder("pick-1", "p1", "POLE/POLE_1")) } // the tech picks one
        runUntil("confirmed") { done(id) }
        assertEquals(setOf("POLE/POLE_1/${row(id)!!.fileName}"), fake.files.keys)
        assertEquals(listOf("pick-1"), ops().filterIsInstance<Op.PointSetFolder>().map { it.opId }, "the tech's choice, nothing more")
    }

    @Test fun retriesThenStuckAfter6ReportsFailedOp() {
        fake.failNext(100, DestinationErrorCode.FORBIDDEN) // the link no longer allows uploads
        val id = take()
        val delays = mutableListOf<Long>()
        for (attempt in 1..6) {
            runUntil("attempt $attempt", backoffOver = false) { row(id)?.attempts == attempt }
            val retry = pendingRetry(Step.ONEDRIVE, id)
            delays += retry.initialDelayMillis
            if (attempt < 6) assertEquals(emptyList(), ops().filterIsInstance<Op.PhotoFailed>(), "not reported before it is stuck")
            if (attempt < 6) driver.setInitialDelayMet(retry.id)
        }
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 1_800_000L), delays, "15 s doubling; stuck: every 30 min")
        val stuck = assertNotNull(row(id))
        assertTrue(stuck.stuck)
        assertEquals(6, stuck.attempts)
        assertEquals("injected failure for listFolders ", stuck.lastError, "the reason, as OneDrive gave it")
        val failed = ops().filterIsInstance<Op.PhotoFailed>().single()
        assertEquals(Op.PhotoFailed("photo.failed:$id:6", id, stuck.lastError!!, 6), failed)
        runUntil("the backup, which went ahead meanwhile", backoffOver = false) { row(id)?.backupDone == true }
    }

    @Test fun retryNowForcesAttempt() {
        fake.failNext(1, DestinationErrorCode.NETWORK)
        val id = take()
        runUntil("a failed attempt", backoffOver = false) { row(id)?.attempts == 1 }
        assertEquals(15_000L, pendingRetry(Step.ONEDRIVE, id).initialDelayMillis)

        runBlocking { pipeline.retryNow(id).join() }
        runUntil("confirmed without waiting out the backoff", backoffOver = false) { row(id)?.state == CONFIRMED }
        assertEquals(1, fake.files.size)
    }

    @Test fun neverMoreThan3OneDriveUploads() {
        fake.uploadDelayMs = 15
        val ids = (1..8).map { take() }
        runUntil("all 8 in OneDrive") { ids.all { row(it)?.state == CONFIRMED } }
        assertEquals(3, dest.maxInFlight.get(), "three at a time, never more")
        assertEquals(8, fake.files.size)
    }

    @Test fun backupRunsWhenOneDriveFails() {
        fake.failNext(1000, DestinationErrorCode.REQUIRES_SIGN_IN)
        val id = take()
        runUntil("backed up, OneDrive failing", backoffOver = false) { row(id)?.let { it.backupDone && it.attempts >= 1 } == true }
        val row = assertNotNull(row(id))
        assertEquals(REGISTERED, row.state)
        assertContentEquals(stamped(id), server.copies[id], "safe on our server while OneDrive refuses")
        assertTrue(fake.files.isEmpty())
    }

    @Test fun serverConfirmedFirstMarksLocalConfirmed() {
        val id = take()
        runStep(Step.REGISTER, id)
        var told = false
        dest.onChunk = {
            if (!told) {
                told = true
                serverDelivered(id) // the server took over and delivered it, mid-upload
                await("marked confirmed") { row(id)?.state == CONFIRMED }
            }
        }
        runUntil("confirmed") { row(id)?.state == CONFIRMED }
        awaitIdle()
        assertTrue(told)
        assertTrue(fake.files.isEmpty(), "the phone's upload stopped: the server's copy is the one in OneDrive")
        assertEquals(emptyList(), confirmedOps(id), "the server confirmed it itself")
        val row = assertNotNull(row(id))
        assertNull(row.oneDriveUploadId)
        assertTrue(row.backupDone, "the server holds its copy")
    }

    @Test fun cleanupDeletesOnly3DaysAfterConfirmedAndBackedUp() {
        val a = take()
        runUntil("a delivered") { done(a) }
        val b = take()
        server.backupBlocked += b
        runUntil("b in OneDrive, not backed up", backoffOver = false) { row(b)?.state == CONFIRMED }
        fake.failNext(1000, DestinationErrorCode.NETWORK)
        val c = take()
        runUntil("c backed up, not in OneDrive", backoffOver = false) { row(c)?.backupDone == true && row(c)!!.attempts > 0 }
        awaitIdle()
        val cleanup = workManager.getWorkInfosForUniqueWork(CLEANUP_WORK).get().single()
        assertEquals(6 * HOUR, cleanup.periodicityInfo?.repeatIntervalMillis, "every 6 h")

        clock.set(NOW + 3 * DAY - 1)
        runBlocking { pipeline.cleanUp() }
        assertTrue(listOf(a, b, c).all { row(it) != null && folder(it).isDirectory })

        clock.set(NOW + 3 * DAY)
        driver.setInitialDelayMet(cleanup.id) // the periodic worker's run
        await("a's copies deleted") { row(a) == null }
        assertFalse(folder(a).exists(), "original and stamped copy")
        assertTrue(listOf(b, c).all { row(it) != null && File(row(it)!!.originalPath).isFile && File(row(it)!!.stampedPath!!).isFile })

        server.backupBlocked -= b // b's backup gets through now, three days in
        runUntil("b backed up") { row(b)?.backupDone == true }
        clock.set(NOW + 6 * DAY - 1)
        runBlocking { pipeline.cleanUp() }
        assertNotNull(row(b), "three days after it was both confirmed and backed up, not before")
        clock.set(NOW + 6 * DAY)
        runBlocking { pipeline.cleanUp() }
        assertNull(row(b))
        assertFalse(folder(b).exists())
        clock.set(NOW + 60 * DAY)
        runBlocking { pipeline.cleanUp() }
        assertNotNull(row(c), "never while OneDrive doesn't have it")
    }

    @Test fun galleryCopyWhenSettingOn() {
        val gallery = Robolectric.setupContentProvider(Gallery::class.java, MediaStore.AUTHORITY)
        galleryOn = true
        val a = take()
        await("in the gallery") { gallery.published.size == 1 }
        val (collection, values) = gallery.inserts.single()
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, collection)
        assertEquals("Pictures/Fieldmaxx", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH), "album Fieldmaxx")
        assertEquals("image/jpeg", values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("A05J9XE_POLE_1_20261105_133547.jpg", values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertContentEquals(stamped(a), gallery.published.single().readBytes(), "the stamped photo, published once written")

        galleryOn = false
        val b = take()
        await("stamped") { row(b)?.state == STAMPED }
        awaitIdle()
        assertEquals(1, gallery.inserts.size, "no copy with the setting off")
    }

    @Test fun queueSurvivesOfflineHours() {
        val ids = (1..30).map { take() }
        await("all 30 stamped", 30_000) { ids.all { row(it)?.state == STAMPED } }
        // Hours without signal, and the app killed twice meanwhile: nothing goes out, nothing is lined up twice.
        clock.addAndGet(5 * HOUR)
        restart(loseQueue = false)
        clock.addAndGet(5 * HOUR)
        restart(loseQueue = false)
        assertEquals(0, server.registerCalls.get())
        assertEquals(30, ids.sumOf { work(Step.REGISTER, it).count { w -> w.state == WorkInfo.State.ENQUEUED } }, "one register step each")

        runUntil("all 30 delivered", timeoutMs = 60_000) { ids.all(::done) }
        assertEquals(30, fake.files.size)
        val uploads = fake.calls.filter { it.startsWith("uploadChunked") }
        assertEquals(30, uploads.size, "each uploaded once")
        assertEquals(30, uploads.toSet().size)
        assertEquals(ids.toSet(), confirmedOps().map { it.photoId }.toSet())
        assertEquals(30, confirmedOps().size)
    }

    // ---- controller rulings ----

    /** R55: a running worker's own retry isn't dropped (KEEP would drop it); it runs once its delay is over. */
    @Test fun failedAttemptRunsAgainAfterItsDelay() {
        fake.failNext(1, DestinationErrorCode.NETWORK)
        val id = take()
        runUntil("the first attempt failed", backoffOver = false) { row(id)?.attempts == 1 }
        val retry = pendingRetry(Step.ONEDRIVE, id)
        assertEquals(15_000L, retry.initialDelayMillis)
        signal(backoffOver = false)
        Thread.sleep(300)
        assertEquals(REGISTERED, row(id)!!.state, "it waits its 15 s")

        driver.setInitialDelayMet(retry.id)
        runUntil("the retry ran", backoffOver = false) { row(id)?.state == CONFIRMED }
        assertEquals(0, row(id)!!.attempts)
        assertNull(row(id)!!.lastError)
    }

    /** R55: stuck after 6, and still tried every 30 min. */
    @Test fun stuckPhotoRunsAgainAfter30Min() {
        fake.failNext(7, DestinationErrorCode.FORBIDDEN)
        val id = take()
        for (attempt in 1..7) {
            runUntil("attempt $attempt", backoffOver = false) { row(id)?.attempts == attempt }
            val retry = pendingRetry(Step.ONEDRIVE, id)
            if (attempt >= 6) assertEquals(30 * 60_000L, retry.initialDelayMillis, "stuck: every 30 min")
            assertEquals(attempt >= 6, row(id)!!.stuck)
            driver.setInitialDelayMet(retry.id)
        }
        runUntil("delivered on the 8th try", backoffOver = false) { row(id)?.state == CONFIRMED }
        assertFalse(row(id)!!.stuck)
        assertEquals(listOf("photo.failed:$id:6", "photo.failed:$id:7"), ops().filterIsInstance<Op.PhotoFailed>().map { it.opId })
        assertEquals(1, confirmedOps(id).size)
    }

    /** R56: the confirmation and its op are one transaction; a kill between the two leaves neither, and the rerun one op. */
    @Test fun killBetweenEnqueueAndStateCantDoubleTheOp() {
        dying.killOn = "SET state = 'confirmed'"
        val id = take()
        runUntil("killed as it recorded the confirmation") { dying.kills.get() > 0 }
        awaitIdle()
        assertEquals(emptyList(), confirmedOps(id), "the op went with the state it belongs to")
        assertEquals(REGISTERED, row(id)!!.state)
        assertEquals(1, fake.files.size, "the photo itself was in OneDrive already")

        restart(loseQueue = true)
        runUntil("confirmed") { done(id) }
        assertEquals(listOf("photo.confirmed:$id"), confirmedOps(id).map { it.opId })
        assertEquals(1, fake.files.size)
    }

    /** R57: once the server shows the photo confirmed (its own takeover), the stored session is dropped, never resumed. */
    @Test fun serverTakeoverAbandonsTheStoredSession() {
        fake.dropChunkAfter = 1
        val id = take()
        runUntil("the dropped upload", backoffOver = false) { row(id)?.let { it.attempts == 1 && it.oneDriveUploadId != null } == true }
        serverDelivered(id)
        await("confirmed through the server") { row(id)?.state == CONFIRMED }
        val row = assertNotNull(row(id))
        assertNull(row.oneDriveUploadId)
        assertEquals(0, row.oneDriveOffset)
        assertFalse(row.createdThisFile)
        assertTrue(work(Step.ONEDRIVE, id).none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }, "its retry is cancelled")
        signal()
        Thread.sleep(300)
        assertEquals(listOf<ChunkSession?>(null), dest.resumes, "the session is never resumed")
        assertEquals(emptyList(), confirmedOps(id))
    }

    /** R57: a partial file of the photo's name that this photo didn't create is someone else's: renamed, never resumed. */
    @Test fun notOursPartialGetsRenamed() {
        val id = take()
        runStep(Step.REGISTER, id)
        val name = row(id)!!.fileName!!
        val partial = stamped(id).copyOf(CHUNK) // the start of an upload this phone never made
        fake.ensureFolder(FOLDER)
        fake.files["$FOLDER/$name"] = partial

        runUntil("confirmed") { done(id) }
        val renamed = row(id)!!.fileName!!
        assertNotEquals(name, renamed)
        assertEquals(listOf<ChunkSession?>(null), dest.resumes, "a fresh upload under the new name")
        assertContentEquals(partial, fake.files["$FOLDER/$name"], "the partial is left as it was")
        assertContentEquals(stamped(id), fake.files["$FOLDER/$renamed"])
    }

    /** R58: a job without its Field Notes link isn't a failure; its photos wait, then go once the link is added. */
    @Test fun noLinkWaitsWithoutAttempts() {
        runBlocking { OpApplier(db).applyChange(jobChange(11, job("j1"))) } // the link is removed
        val id = take()
        runUntil("its OneDrive step ran") { work(Step.ONEDRIVE, id).any { it.state == WorkInfo.State.SUCCEEDED } }
        awaitIdle()
        val waiting = assertNotNull(row(id))
        assertEquals(REGISTERED, waiting.state)
        assertEquals(0, waiting.attempts)
        assertFalse(waiting.stuck)
        assertTrue(fake.calls.isEmpty(), "OneDrive isn't tried")
        assertTrue(work(Step.ONEDRIVE, id).none { it.state == WorkInfo.State.ENQUEUED }, "no backoff either")
        assertTrue(waiting.backupDone, "the server backup doesn't wait for the link")

        runBlocking { OpApplier(db).applyChange(jobChange(12, job("j1").copy(fieldNotesUrl = LINK))) } // the office adds it
        runUntil("confirmed") { done(id) }
        assertEquals(1, fake.files.size)
    }

    /** R58: a row deleted from under its steps (a deleted photo or point) ends its work quietly. */
    @Test fun vanishedRowFinishesQuietly() {
        val id = take()
        runStep(Step.REGISTER, id)
        deleteLocally(id) // deleted while its OneDrive and backup steps waited
        runUntil("both steps ran") { listOf(Step.ONEDRIVE, Step.BACKUP).all { s -> work(s, id).any { it.state == WorkInfo.State.SUCCEEDED } } }
        awaitIdle()
        assertNull(row(id), "not brought back")
        assertTrue(fake.calls.none { it.startsWith("uploadChunked") })
        assertNull(server.copies[id])

        // Deleted mid-upload: the upload stops at the next chunk.
        val during = take()
        dest.onChunk = { if (it.offset == CHUNK.toLong()) deleteLocally(during) }
        runUntil("its OneDrive step ended") { work(Step.ONEDRIVE, during).any { it.state == WorkInfo.State.SUCCEEDED } }
        awaitIdle()
        assertNull(row(during))
        assertTrue(fake.files.isEmpty(), "never completed")
        assertEquals(emptyList(), confirmedOps(), "and never reported")
        assertEquals(emptyList(), ops().filterIsInstance<Op.PhotoFailed>())
    }

    /** R58: a lost stamped copy is made again from the original; with the original lost too, the photo is stuck. */
    @Test fun lostStampedCopyIsStampedAgainLostOriginalIsStuck() {
        val id = take()
        runStep(Step.REGISTER, id)
        val registered = assertNotNull(row(id))
        File(registered.stampedPath!!).delete()
        runUntil("delivered from a fresh stamp") { done(id) }
        assertEquals(registered.fileName, row(id)!!.fileName, "registering again keeps its name")
        assertContentEquals(stamped(id), fake.files.getValue(destPath(id)))

        val lost = take()
        runStep(Step.REGISTER, lost)
        folder(lost).deleteRecursively() // original and stamped copy both gone
        runUntil("stuck", backoffOver = false) { row(lost)?.stuck == true }
        assertEquals("The photo's file is missing from this phone", row(lost)!!.lastError)
        assertEquals(Op.PhotoFailed("photo.failed:$lost:6", lost, "The photo's file is missing from this phone", 6), ops().filterIsInstance<Op.PhotoFailed>().single())
    }

    /**
     * R67: a deleted point's photos wait while the delete is unanswered. A refused delete brings the point back, and its
     * photos go on; an accepted one discards those not yet in OneDrive. Until the server shows the point deleted, nothing is
     * discarded, even with the delete answered: the refusal's fresh snapshot may still be on its way.
     */
    @Test fun deletedPointPausesThenResumesOrIsDiscarded() {
        val a = take("p1")
        runStep(Step.REGISTER, a)
        runBlocking { sync.enqueue(Op.PointDelete("del-1", "p1")) } // offline: the delete waits in the outbox
        runUntil("paused") { listOf(Step.ONEDRIVE, Step.BACKUP).all { s -> work(s, a).any { it.state == WorkInfo.State.SUCCEEDED } } }
        awaitIdle()
        assertTrue(fake.calls.none { it.startsWith("uploadChunked") }, "nothing goes out meanwhile")
        assertNull(server.copies[a])
        assertEquals(0, row(a)!!.attempts)

        // Refused (another phone's photo of it is in OneDrive), and at first the fresh snapshot can't be fetched.
        server.refuse = setOf("del-1")
        server.snapshotDown = true
        runBlocking { sync.flushOutbox() }
        Thread.sleep(300)
        assertNotNull(row(a), "the delete is answered, but the server hasn't shown the point deleted")
        assertTrue(folder(a).isDirectory)
        server.snapshotDown = false
        runBlocking { sync.openJob("j1") }
        runUntil("delivered after all") { done(a) }

        // Accepted: the photo still on the phone is discarded with the point.
        val b = take("p2")
        runStep(Step.REGISTER, b)
        runBlocking { sync.enqueue(Op.PointDelete("del-2", "p2")) }
        runUntil("paused") { work(Step.ONEDRIVE, b).any { it.state == WorkInfo.State.SUCCEEDED } }
        server.snapshots["j1"] = snapshot(job("j1").copy(fieldNotesUrl = LINK), listOf(point("p1"), point("p2").copy(deletedAt = NOW)), seq = 20)
        runBlocking { sync.flushOutbox() }
        runUntil("discarded") { row(b) == null }
        assertFalse(folder(b).exists())
        assertEquals(1, fake.files.size, "only a's")
        assertEquals(emptyList(), confirmedOps(b))
    }

    /** R80: a photo folder a power cut left without its row is rebuilt from its meta.json; one with neither is swept after 1 h. */
    @Test fun orphanFoldersAreRecoveredOrSwept() {
        val kept = take()
        runUntil("delivered") { done(kept) }
        val saved = runBlocking { photos.saveCaptured("j1", "p1", nextMeta()) { it.writeBytes(source) } }
        workManager.cancelAllWork().result.get()
        awaitIdle()
        runBlocking { db.localPhotos().delete(saved) } // the row a power cut took back; its folder and meta.json stayed
        val recent = File(context.filesDir, "photos/recent").apply { mkdirs(); resolve("original.jpg").writeBytes(source) }
        val old = File(context.filesDir, "photos/old").apply { mkdirs(); resolve("original.jpg").writeBytes(source) }
        val torn = File(context.filesDir, "photos/torn").apply { mkdirs(); resolve("meta.json").writeText("{\"id\":\"torn\",\"jo") }
        listOf(old, old.resolve("original.jpg"), torn, torn.resolve("meta.json")).forEach { it.setLastModified(NOW - 2 * HOUR) }
        recent.setLastModified(NOW - 10 * 60_000L)
        recent.resolve("original.jpg").setLastModified(NOW - 10 * 60_000L)

        restart(loseQueue = false)
        runUntil("the rebuilt photo delivered") { done(saved) }
        assertTrue(recent.isDirectory, "younger than 1 h: perhaps still being saved")
        assertFalse(old.exists())
        assertFalse(torn.exists(), "a meta.json cut off as it was written counts as none")
        assertTrue(folder(kept).isDirectory, "a folder with its row stays")
        assertEquals(2, fake.files.size)
    }

    /** R60: Android 9 needs the storage permission for a gallery copy; without it the copy is skipped, never asked for. */
    @Test fun androidNineWithoutPermissionSkipsTheGalleryCopy() {
        val gallery = Robolectric.setupContentProvider(Gallery::class.java, MediaStore.AUTHORITY)
        val stampedFile = File(context.filesDir, "x.jpg").apply { writeBytes(source) }
        assertFalse(GallerySaver(context.contentResolver, sdk = 28, legacyGranted = { false }).save(stampedFile, "x.jpg"))
        assertTrue(gallery.inserts.isEmpty())
        assertTrue(GallerySaver(context.contentResolver, sdk = 35, legacyGranted = { false }).save(stampedFile, "x.jpg"), "Android 10+: no permission needed")
        assertEquals(1, gallery.published.size)
    }

    /** FieldmaxxApp.onCreate resumes the photos a dead process left, through WorkManager's own factory and the app's graph. */
    @Test fun appStartResumesSavedPhotos() {
        workManager.cancelAllWork().result.get()
        awaitIdle()
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val app = context as FieldmaxxApp
        val graph = AppGraph(app, InMemoryTokenVault("tok")).also { app.graph = it }
        try {
            val id = runBlocking {
                LocalPhotos(graph.db, app.filesDir, {}, clock::get, { throw FileNotFoundException() }).saveCaptured("j1", "p1", nextMeta()) {
                    it.writeBytes(source)
                }
            }
            app.onCreate()
            await("stamped after the app started") { runBlocking { graph.db.localPhotos().get(id) }?.state == STAMPED }
            await("its registration waits for signal") { workManager.getWorkInfosForUniqueWork(Step.REGISTER.workName(id)).get().isNotEmpty() }
        } finally {
            workManager.cancelAllWork().result.get()
            awaitIdle()
            graph.close()
        }
    }

    // ---- driving WorkManager ----

    private inner class Workers : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
            when (workerClassName) {
                StampWorker::class.java.name -> StampWorker(appContext, workerParameters, pipeline)
                RegisterWorker::class.java.name -> RegisterWorker(appContext, workerParameters, pipeline)
                OneDriveWorker::class.java.name -> OneDriveWorker(appContext, workerParameters, pipeline)
                BackupWorker::class.java.name -> BackupWorker(appContext, workerParameters, pipeline)
                CleanupWorker::class.java.name -> CleanupWorker(appContext, workerParameters, pipeline)
                else -> null
            }
    }

    private val workManager get() = WorkManager.getInstance(context)
    private val driver get() = WorkManagerTestInitHelper.getTestDriver(context)!!

    private fun newPipeline() = PhotoPipeline(
        context, db, server, { url -> linksAsked += url; dest }, sync, clock::get,
        quality = { quality }, galleryCopy = { galleryOn }, gallery = GallerySaver(context), scope = pipelineScope, zone = { ZONE },
        chunkBytes = CHUNK, backupChunkBytes = BACKUP_CHUNK,
    )

    /** The app dies and starts again: a new pipeline from the database, then resumeAll. [loseQueue]: WorkManager lost its queue too. */
    private fun restart(loseQueue: Boolean) {
        awaitIdle()
        pipelineScope.cancel()
        if (loseQueue) {
            workManager.cancelAllWork().result.get()
            workManager.pruneWork().result.get()
            awaitIdle()
        }
        metConstraints.clear()
        metDelays.clear()
        pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pipeline = newPipeline()
        runBlocking { pipeline.resumeAll().join() }
    }

    private fun disarm() {
        dying.killOn = null
        server.afterRegister = null
        server.afterBackupChunk = null
        dest.onChunk = null
        dest.afterUpload = null
    }

    private fun photoWork(): List<WorkInfo> = workManager.getWorkInfosByTag(PHOTO_WORK_TAG).get()

    private fun work(step: Step, id: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(step.workName(id)).get()

    /** The step's next attempt, lined up with its backoff delay. */
    private fun pendingRetry(step: Step, id: String): WorkInfo =
        work(step, id).single { it.state == WorkInfo.State.ENQUEUED && it.initialDelayMillis > 0 }

    /** The phone has signal: each photo step waiting for the network may run; with [backoffOver], so may each retry waiting out its delay. */
    private fun signal(backoffOver: Boolean = true) {
        for (w in photoWork()) {
            if (w.state != WorkInfo.State.ENQUEUED || w.periodicityInfo != null) continue
            if (metConstraints.add(w.id)) driver.setAllConstraintsMet(w.id)
            if (backoffOver && w.initialDelayMillis > 0 && metDelays.add(w.id)) driver.setInitialDelayMet(w.id)
        }
    }

    private fun runUntil(message: String, backoffOver: Boolean = true, timeoutMs: Long = 20_000, done: () -> Boolean) = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            while (!done()) {
                signal(backoffOver)
                delay(10)
            }
        } ?: throw AssertionError("timed out waiting for $message")
    }

    /** Runs only [step] of photo [id]: the network for that step alone, until its result is recorded. */
    private fun runStep(step: Step, id: String) {
        await("the ${step.name} step lined up") { work(step, id).any { it.state == WorkInfo.State.ENQUEUED } }
        work(step, id).filter { it.state == WorkInfo.State.ENQUEUED }.forEach { driver.setAllConstraintsMet(it.id) }
        await("the ${step.name} step") {
            val row = row(id)
            when (step) {
                Step.REGISTER -> row?.state == REGISTERED
                Step.ONEDRIVE -> row?.state == CONFIRMED
                Step.BACKUP -> row?.backupDone == true
                Step.STAMP -> row?.state == STAMPED
            }
        }
        awaitIdle()
    }

    private fun await(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) = runBlocking {
        withTimeoutOrNull(timeoutMs) { while (!condition()) delay(10) } ?: throw AssertionError("timed out waiting for $message")
    }

    private fun idle() = photoWork().none { it.state == WorkInfo.State.RUNNING }

    /** No photo step is running (the one a kill hit has ended). */
    private fun awaitIdle() = await("no photo step running") { idle() }

    // ---- reading the results ----

    private fun row(id: String): LocalPhotoEntity? = runBlocking { db.localPhotos().get(id) }

    private fun done(id: String) = row(id)?.let { it.state == CONFIRMED && it.backupDone } == true

    /** Each photo is taken a second after the one before: its stamp, and so its bytes, are its own. */
    private fun nextMeta() = META.copy(takenAt = NOW + shots.getAndIncrement() * 1000L)

    private fun take(pointId: String = "p1"): String = runBlocking { photos.saveCaptured("j1", pointId, nextMeta()) { it.writeBytes(source) } }

    private fun stamped(id: String): ByteArray = File(row(id)!!.stampedPath!!).readBytes()

    private fun destPath(id: String): String = "$FOLDER/${row(id)!!.fileName}"

    private fun folder(id: String) = File(context.filesDir, "photos/$id")

    private fun deleteLocally(id: String) = runBlocking {
        folder(id).deleteRecursively()
        db.localPhotos().delete(id)
    }

    private fun localPoint(id: String): Point = runBlocking { db.points().get(id)!!.toPoint() }

    private fun ops(): List<Op> = runBlocking { db.outbox().all().map { it.toOp() } }

    private fun confirmedOps(id: String? = null) = ops().filterIsInstance<Op.PhotoConfirmed>().filter { id == null || it.photoId == id }

    /** In OneDrive exactly once, with the exact stamped bytes, and reported exactly once. */
    private fun assertDeliveredOnce(id: String) {
        val bytes = stamped(id)
        assertEquals(listOf(destPath(id)), fake.files.filterValues { it.contentEquals(bytes) }.keys.toList(), "photo $id in OneDrive once")
        assertEquals(listOf("photo.confirmed:$id"), confirmedOps(id).map { it.opId }, "photo $id reported once")
    }

    /** The server's own delivery got there first: its photo change arrives, as the sync engine applies it. */
    private fun serverDelivered(id: String) = runBlocking {
        val row = row(id)!!
        val info = PhotoInfo(
            id = id, jobId = "j1", pointId = row.pointId, userId = "u1", fileName = row.fileName, size = stamped(id).size.toLong(),
            status = PhotoStatus.confirmed, destPath = "$FOLDER/${row.fileName}", lastError = null, takenAt = NOW, lat = META.lat, lon = META.lon,
            backedUp = true, confirmedAt = NOW + 1, uploadMs = 900, reusedFrom = null,
        )
        OpApplier(db).applyChange(Change(30, "j1", "photo", id, photo = info, at = NOW + 1))
    }
}

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * The Fieldmaxx server as the pipeline sees it: registration (idempotent per photo id, names from the server-wide counter),
 * renames, resumable backups that check size and SHA-256 and answer a wrong offset with their own position, ops and
 * snapshots for the sync engine. The hooks run after the server did its part, before the phone hears back.
 */
private class PhotoServer : Api by notCalled() {
    private val lock = Any()
    private val counter = AtomicLong()
    val registrations = ConcurrentHashMap<String, PhotoRegistration>()
    val names = ConcurrentHashMap<String, String>()
    val copies = ConcurrentHashMap<String, ByteArray>()
    private val complete: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val registerCalls = AtomicInteger()
    val renames = AtomicInteger()
    val snapshots = ConcurrentHashMap<String, JobSnapshot>()
    @Volatile var refuse: Set<String> = emptySet()
    val backupBlocked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile var snapshotDown = false
    @Volatile var afterRegister: (() -> Unit)? = null
    @Volatile var afterBackupChunk: (() -> Unit)? = null

    private fun name(r: PhotoRegistration): String {
        val p = snapshots.values.flatMap { it.points }.first { it.id == r.pointId }
        return photoFileName(defaultFolderName(p.label), r.lat ?: p.lat, r.lon ?: p.lon, counter.incrementAndGet())
    }

    override suspend fun registerPhoto(r: PhotoRegistration): RegisteredPhoto {
        registerCalls.incrementAndGet()
        val reply = synchronized(lock) {
            registrations.putIfAbsent(r.id, r)
            RegisteredPhoto(names.getOrPut(r.id) { name(r) }, PhotoStatus.registered, copies[r.id]?.size?.toLong() ?: 0)
        }
        afterRegister?.invoke()
        return reply
    }

    override suspend fun renamePhoto(id: String): RegisteredPhoto {
        renames.incrementAndGet()
        return synchronized(lock) {
            val r = registrations[id] ?: throw ApiException(404, "Photo not found")
            RegisteredPhoto(name(r).also { names[id] = it }, PhotoStatus.registered, copies[id]?.size?.toLong() ?: 0)
        }
    }

    override suspend fun backupChunk(id: String, offset: Long, bytes: ByteArray): Pair<Long, Boolean> {
        if (id in backupBlocked) throw IOException("Can't reach the server")
        val reply = synchronized(lock) {
            val r = registrations[id] ?: throw ApiException(404, "Photo not found")
            val have = copies[id] ?: ByteArray(0)
            when {
                id in complete -> r.size to true // a retry of a chunk already in: 409 {complete: true}
                offset != have.size.toLong() -> have.size.toLong() to false // 409: where the server's copy ends
                else -> {
                    val next = have + bytes
                    if (next.size > r.size) throw ApiException(413, "Photo copy is larger than allowed")
                    copies[id] = next
                    if (next.size.toLong() == r.size) {
                        if (sha256(next) != r.sha256) {
                            copies.remove(id)
                            throw ApiException(422, "Checksum mismatch; upload the photo again from the start")
                        }
                        complete += id
                        r.size to true
                    } else {
                        next.size.toLong() to false
                    }
                }
            }
        }
        afterBackupChunk?.invoke()
        return reply
    }

    override suspend fun ops(ops: List<Op>): List<OpResult> =
        ops.map { if (it.opId in refuse) OpResult(it.opId, false, "photos_delivered") else OpResult(it.opId, true) }

    override suspend fun snapshot(jobId: String): JobSnapshot {
        if (snapshotDown) throw IOException("Can't reach the server")
        return snapshots[jobId] ?: throw ApiException(404, "Job not found")
    }
}

private fun notCalled(): Api = Proxy.newProxyInstance(Api::class.java.classLoader, arrayOf(Api::class.java)) { _, method, _ ->
    throw UnsupportedOperationException("Api.${method.name} is not part of the photo pipeline")
} as Api

/**
 * The fake OneDrive with probes: how many uploads run at once, which session each upload started from, and hooks that run
 * after the pipeline's own progress callback ([onChunk]) or once an upload is through ([afterUpload]).
 */
private class TestDestination(private val fake: FakeDestination) : PhotoDestination by fake {
    private val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()
    val resumes = CopyOnWriteArrayList<ChunkSession?>()
    @Volatile var onChunk: ((ChunkSession) -> Unit)? = null
    @Volatile var afterUpload: (() -> Unit)? = null

    override fun uploadChunked(relPath: String, file: File, chunkBytes: Int, resume: ChunkSession?, onProgress: (ChunkSession) -> Unit) {
        resumes += resume
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
        try {
            fake.uploadChunked(relPath, file, chunkBytes, resume) { s ->
                onProgress(s)
                onChunk?.invoke(s)
            }
        } finally {
            inFlight.decrementAndGet()
        }
        afterUpload?.invoke()
    }
}

/**
 * The phone's gallery as MediaStore shows it to the app: each insert, the file written for it, and those [published]
 * (no longer pending).
 */
class Gallery : ContentProvider() {
    val inserts = CopyOnWriteArrayList<Pair<Uri, ContentValues>>()
    private val files = ConcurrentHashMap<Uri, File>()
    val published = CopyOnWriteArrayList<File>()

    override fun onCreate() = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        inserts += uri to ContentValues(values)
        val item = ContentUris.withAppendedId(uri, inserts.size.toLong())
        files[item] = File(context!!.cacheDir, "gallery-${inserts.size}.jpg")
        return item
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(files.getValue(uri), ParcelFileDescriptor.parseMode(mode))

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0) published += files.getValue(uri)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = if (files.remove(uri) != null) 1 else 0

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String = "image/jpeg"
}

/** An in-memory database where the next write whose SQL contains [killOn] dies with the process: its transaction is lost. */
private class DyingDb(context: Context) {
    @Volatile var killOn: String? = null
    val kills = AtomicInteger()

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
        override fun execute() {
            dieHere()
            s.execute()
        }

        override fun executeInsert(): Long {
            dieHere()
            return s.executeInsert()
        }

        override fun executeUpdateDelete(): Int {
            dieHere()
            return s.executeUpdateDelete()
        }

        private fun dieHere() {
            val fragment = killOn ?: return
            if (fragment !in sql) return
            killOn = null
            kills.incrementAndGet()
            throw Killed()
        }
    }
}

