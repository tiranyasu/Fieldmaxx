package com.fieldmaxx.server

import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoRegistration
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import com.fieldmaxx.core.retry.nextDelayMs
import com.fieldmaxx.server.delivery.DeliveryWorker
import com.fieldmaxx.server.delivery.backupDays
import com.fieldmaxx.server.delivery.backupMaintenance
import com.fieldmaxx.server.delivery.diskUsedPct
import com.fieldmaxx.server.delivery.purgeBackups
import com.fieldmaxx.server.delivery.retryPhoto
import com.fieldmaxx.server.jobs.deleteJob
import com.fieldmaxx.server.jobs.path
import com.fieldmaxx.server.jobs.purgeDeletedJobs
import com.fieldmaxx.server.jobs.toJob
import com.fieldmaxx.server.jobs.toPhoto
import com.fieldmaxx.server.jobs.toPoint
import com.fieldmaxx.server.jobs.updateJob
import com.fieldmaxx.server.ops.applyOps
import com.fieldmaxx.server.photos.appendBackupChunk
import com.fieldmaxx.server.photos.registerPhoto
import com.fieldmaxx.sharepoint.ChunkSession
import com.fieldmaxx.sharepoint.DestinationErrorCode
import com.fieldmaxx.sharepoint.FakeDestination
import com.fieldmaxx.sharepoint.FileInfo
import com.fieldmaxx.sharepoint.PhotoDestination
import io.ktor.http.HttpStatusCode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
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

private const val MIN = 60_000L
private const val DAY = 24 * 60 * MIN
private const val TAKEOVER = 30 * MIN

/** Wraps the fake to see what the worker asks for: resume sessions, parallel uploads, and a hook just before an upload. */
private class Spy(val inner: FakeDestination) : PhotoDestination by inner {
    val resumes: MutableList<ChunkSession?> = CopyOnWriteArrayList()
    val maxActive = AtomicInteger()
    private val active = AtomicInteger()

    /** Sizes to report for files the fake doesn't hold yet (SharePoint shows a chunked upload's partial file). */
    val partialInfo: MutableMap<String, FileInfo> = ConcurrentHashMap()

    @Volatile var beforeUpload: ((String) -> Unit)? = null

    override fun fileInfo(relPath: String): FileInfo? = inner.fileInfo(relPath) ?: partialInfo[relPath]

    override fun uploadChunked(relPath: String, file: File, chunkBytes: Int, resume: ChunkSession?, onProgress: (ChunkSession) -> Unit) {
        resumes += resume
        beforeUpload?.invoke(relPath)
        maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
        try {
            inner.uploadChunked(relPath, file, chunkBytes, resume, onProgress)
        } finally {
            active.decrementAndGet()
        }
    }
}

class DeliveryTest {
    private val ann = User("u-ann", "ann", "Ann", Role.tech, false)
    private val boss = User("u-boss", "boss", "Boss", Role.admin, false)

    private fun withApp(fileDb: Boolean = false, spy: Boolean = false, block: (TestApp, FakeDestination, Spy) -> Unit) {
        val fake = FakeDestination()
        val s = Spy(fake)
        val app = TestApp(destination = fake, destinations = if (spy) ({ _ -> s }) else null, fileDb = fileDb)
        try {
            seed(app)
            block(app, fake, s)
        } finally {
            app.ctx.db.close()
        }
    }

    private fun <T> TestApp.worker(concurrency: Int = 4, chunkBytes: Int? = null, block: (DeliveryWorker) -> T): T {
        val w = if (chunkBytes == null) DeliveryWorker(ctx, concurrency) else DeliveryWorker(ctx, concurrency, chunkBytes)
        try {
            return block(w)
        } finally {
            w.stop()
        }
    }

    /** job-1 (POLE 1, pt-1) and job-2 (POLE 5, pt-2) have links; job-3 (POLE 2, pt-3) has none. */
    private fun seed(app: TestApp) = app.ctx.db.tx {
        createStatement().use {
            for (u in listOf(ann, boss)) {
                it.execute("INSERT INTO users(id, username, display_name, password_hash, role, created_at) VALUES ('${u.id}','${u.username}','${u.displayName}','x','${u.role}',0)")
            }
            for ((job, link) in listOf("job-1" to "'https://example.test/one'", "job-2" to "'https://example.test/two'", "job-3" to "NULL")) {
                it.execute("INSERT INTO jobs(id, name, field_notes_url, status, created_by, created_at) VALUES ('$job','Run $job',$link,'active','u-ann',0)")
            }
            for ((pt, job, num) in listOf(Triple("pt-1", "job-1", "1"), Triple("pt-2", "job-2", "5"), Triple("pt-3", "job-3", "2"))) {
                it.execute(
                    "INSERT INTO points(id, job_id, layer, type, num, label, lat, lon, description, status, created_at, updated_at) " +
                        "VALUES ('$pt','$job','POLE','POLE','$num','POLE $num',39.6,-119.7,'','open',0,0)",
                )
            }
        }
    }

    private fun jpeg(seed: Int = 1, w: Int = 240, h: Int = 160): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val r = Random(seed)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, r.nextInt(0xFFFFFF))
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Registers a phone photo; returns its file name. */
    private fun register(app: TestApp, id: String, bytes: ByteArray, pointId: String = "pt-1", jobId: String = "job-1"): String =
        registerPhoto(app.ctx, ann, PhotoRegistration(id, jobId, pointId, bytes.size.toLong(), sha(bytes), 1_000L, 39.636130, -119.691104, 5.0, 90.0)).fileName

    /** A phone photo whose server copy is complete (arrived now); returns its file name. */
    private fun backedUp(app: TestApp, id: String, bytes: ByteArray, pointId: String = "pt-1", jobId: String = "job-1"): String {
        val name = register(app, id, bytes, pointId, jobId)
        assertTrue(appendBackupChunk(app.ctx, ann, id, 0, bytes).second)
        return name
    }

    private fun op(app: TestApp, op: Op, user: User = ann) = applyOps(app.ctx, user, listOf(op)).single().also { assertTrue(it.ok, it.error) }

    private fun photo(app: TestApp, id: String): PhotoInfo = app.ctx.db.read {
        prepareStatement("SELECT * FROM photos WHERE id = ?").use { ps -> ps.setString(1, id); ps.executeQuery().use { it.next(); it.toPhoto() } }
    }

    private fun col(app: TestApp, id: String, column: String): Any? = app.ctx.db.read {
        prepareStatement("SELECT $column FROM photos WHERE id = ?").use { ps ->
            ps.setString(1, id); ps.executeQuery().use { it.next(); it.getObject(1).let { v -> if (v is Int) v.toLong() else v } }
        }
    }

    private fun point(app: TestApp, id: String): Point = app.ctx.db.read {
        prepareStatement("SELECT * FROM points WHERE id = ?").use { ps -> ps.setString(1, id); ps.executeQuery().use { it.next(); it.toPoint() } }
    }

    private fun job(app: TestApp, id: String): Job = app.ctx.db.read {
        prepareStatement("SELECT * FROM jobs WHERE id = ?").use { ps -> ps.setString(1, id); ps.executeQuery().use { it.next(); it.toJob() } }
    }

    private fun changes(app: TestApp, entity: String, id: String) = app.ctx.changes.since(0).first.filter { it.entity == entity && it.entityId == id }

    private fun uploads(fake: FakeDestination) = fake.calls.filter { it.startsWith("uploadChunked") }

    private fun backupFile(app: TestApp, id: String) = app.ctx.config.dataDir.resolve("photos/$id.jpg")

    private fun setting(app: TestApp, key: String, value: String) = app.ctx.db.tx {
        prepareStatement("INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").use {
            it.setString(1, key); it.setString(2, value); it.executeUpdate()
        }
    }

    // ---- takeover ----

    @Test fun backupNotTakenOverBefore30Min() = withApp { app, fake, _ ->
        backedUp(app, "ph-1", jpeg())
        app.worker { w ->
            app.clock.nowMs += TAKEOVER - 1
            assertEquals(0, w.runOnce())
            assertEquals(PhotoStatus.registered, photo(app, "ph-1").status)
            assertTrue(fake.calls.isEmpty(), fake.calls.toString())

            // A photo whose server copy never arrived is never taken over: the server has nothing to send.
            val noCopy = register(app, "ph-2", jpeg(2))
            app.clock.nowMs += 2 * 60 * MIN
            assertEquals(1, w.runOnce()) // ph-1 only
            assertEquals(PhotoStatus.confirmed, photo(app, "ph-1").status)
            assertEquals(PhotoStatus.registered, photo(app, "ph-2").status)
            assertTrue(fake.calls.none { noCopy in it }, fake.calls.toString())
        }
    }

    @Test fun takeoverAfter30MinDeliversAndConfirms() = withApp { app, fake, _ ->
        val bytes = jpeg()
        val name = backedUp(app, "ph-1", bytes)
        app.clock.nowMs += TAKEOVER
        app.worker { w -> assertEquals(1, w.runOnce()) }
        val p = photo(app, "ph-1")
        assertEquals(PhotoStatus.confirmed, p.status)
        assertEquals("POLE/POLE_1/$name", p.destPath)
        assertEquals(app.clock.nowMs, p.confirmedAt)
        assertNull(p.lastError)
        assertContentEquals(bytes, fake.files["POLE/POLE_1/$name"])
        assertEquals("POLE/POLE_1", point(app, "pt-1").folderPath)
        assertEquals("POLE/POLE_1", changes(app, "point", "pt-1").last().point?.folderPath)
        assertEquals(PhotoStatus.confirmed, changes(app, "photo", "ph-1").last().photo?.status)
        assertNull(col(app, "ph-1", "od_upload_id"))
        // Delivered once: a later run does nothing.
        app.worker { w -> assertEquals(0, w.runOnce()) }
        assertEquals(1, uploads(fake).size)
    }

    @Test fun storedFolderIsUsedWithoutSearching() = withApp { app, fake, _ ->
        fake.ensureFolder("Poles/Pole 1")
        op(app, Op.PointSetFolder("op-f", "pt-1", "Poles/Pole 1"))
        val name = backedUp(app, "ph-1", jpeg())
        fake.calls.clear()
        app.clock.nowMs += TAKEOVER
        app.worker { w -> assertEquals(1, w.runOnce()) }
        assertEquals("Poles/Pole 1/$name", photo(app, "ph-1").destPath)
        assertTrue(fake.calls.none { it.startsWith("listFolders") }, fake.calls.toString())
    }

    // ---- reuse ----

    @Test fun reusePhotoDeliveredImmediatelyFromBackup() = withApp { app, fake, _ ->
        val bytes = jpeg()
        backedUp(app, "ph-src", bytes, pointId = "pt-2", jobId = "job-2")
        val worker = DeliveryWorker(app.ctx)
        app.ctx.delivery = worker
        try {
            op(app, Op.PhotoReuse("op-r", "ph-copy", "ph-src", "pt-1")) // kicks the worker
            assertEquals("backup", col(app, "ph-copy", "source_kind"))
            val deadline = System.currentTimeMillis() + 10_000
            while (photo(app, "ph-copy").status != PhotoStatus.confirmed && System.currentTimeMillis() < deadline) Thread.sleep(20)
        } finally {
            worker.stop()
        }
        val copy = photo(app, "ph-copy")
        assertEquals(PhotoStatus.confirmed, copy.status)
        assertEquals("POLE/POLE_1/${copy.fileName}", copy.destPath)
        assertContentEquals(bytes, fake.files[copy.destPath!!])
        // The original is not 30 min old: still the phone's to deliver.
        assertEquals(PhotoStatus.registered, photo(app, "ph-src").status)
        assertTrue(fake.calls.none { it.startsWith("download") })
    }

    @Test fun reuseFromOneDriveDownloadsOriginalThenUploads() = withApp { app, fake, _ ->
        val bytes = jpeg()
        val srcName = register(app, "ph-src", bytes, pointId = "pt-2", jobId = "job-2") // no server copy
        op(app, Op.PhotoReuse("op-r", "ph-copy", "ph-src", "pt-1"))
        assertEquals("onedrive", col(app, "ph-copy", "source_kind"))
        app.worker { w ->
            // The original isn't in OneDrive yet: wait, without counting a failure.
            assertEquals(0, w.runOnce())
            assertEquals(PhotoStatus.registered, photo(app, "ph-copy").status)
            assertEquals(0L, col(app, "ph-copy", "attempts"))
            assertTrue(uploads(fake).isEmpty())

            // The phone delivers the original.
            fake.ensureFolder("POLE/POLE_5")
            fake.files["POLE/POLE_5/$srcName"] = bytes.copyOf()
            op(app, Op.PhotoConfirmed("op-c", "ph-src", "POLE/POLE_5/$srcName", 1200))

            assertEquals(1, w.runOnce())
        }
        val copy = photo(app, "ph-copy")
        assertEquals(PhotoStatus.confirmed, copy.status)
        assertEquals("POLE/POLE_1/${copy.fileName}", copy.destPath)
        assertContentEquals(bytes, fake.files[copy.destPath!!])
        assertTrue("download POLE/POLE_5/$srcName" in fake.calls, fake.calls.toString())
        // The temporary download is gone.
        assertTrue(Files.list(app.ctx.config.dataDir.resolve("delivery")).use { it.count() } == 0L)
    }

    @Test fun originalJobsLinkFailingDoesNotMarkThisJob() {
        val one = FakeDestination()
        val two = FakeDestination()
        val app = TestApp(destinations = { url -> if (url.endsWith("/two")) two else one })
        try {
            seed(app)
            val bytes = jpeg()
            val srcName = register(app, "ph-src", bytes, pointId = "pt-2", jobId = "job-2")
            two.ensureFolder("POLE/POLE_5")
            two.files["POLE/POLE_5/$srcName"] = bytes.copyOf()
            op(app, Op.PhotoConfirmed("op-c", "ph-src", "POLE/POLE_5/$srcName", 1200))
            op(app, Op.PhotoReuse("op-r", "ph-copy", "ph-src", "pt-1"))
            two.failNext(1, DestinationErrorCode.FORBIDDEN)
            app.worker { w -> assertEquals(1, w.runOnce()) }
            assertNull(job(app, "job-1").deliveryProblem)
            assertEquals(1L, col(app, "ph-copy", "attempts"))
            assertTrue(photo(app, "ph-copy").lastError!!.startsWith("Can't read the original photo"), photo(app, "ph-copy").lastError)

            app.clock.nowMs += nextDelayMs(1)
            app.worker { w -> assertEquals(1, w.runOnce()) }
            val copy = photo(app, "ph-copy")
            assertEquals(PhotoStatus.confirmed, copy.status)
            assertContentEquals(bytes, one.files[copy.destPath!!])
        } finally {
            app.ctx.db.close()
        }
    }

    // ---- never a duplicate ----

    @Test fun sameNameSameSizeCountsAsDelivered() = withApp { app, fake, _ ->
        val bytes = jpeg()
        val name = backedUp(app, "ph-1", bytes)
        // The phone uploaded it but never got to report it.
        fake.ensureFolder("POLE/POLE_1")
        fake.files["POLE/POLE_1/$name"] = bytes.copyOf()
        app.clock.nowMs += TAKEOVER
        app.worker { w -> assertEquals(1, w.runOnce()) }
        val p = photo(app, "ph-1")
        assertEquals(PhotoStatus.confirmed, p.status)
        assertEquals("POLE/POLE_1/$name", p.destPath)
        assertTrue(uploads(fake).isEmpty(), fake.calls.toString())

        // The phone's late report changes nothing.
        app.clock.nowMs += MIN
        op(app, Op.PhotoConfirmed("op-c", "ph-1", "POLE/POLE_1/$name", 900))
        assertEquals(p.confirmedAt, photo(app, "ph-1").confirmedAt)
        assertEquals(1, fake.files.size)
    }

    @Test fun sameNameDifferentSizeGetsNewCounter() = withApp { app, fake, _ ->
        val bytes = jpeg()
        val name = backedUp(app, "ph-1", bytes)
        val foreign = jpeg(7, 100, 100)
        fake.ensureFolder("POLE/POLE_1")
        fake.files["POLE/POLE_1/$name"] = foreign
        app.clock.nowMs += TAKEOVER
        app.worker { w -> assertEquals(1, w.runOnce()) }
        val p = photo(app, "ph-1")
        assertEquals(PhotoStatus.confirmed, p.status)
        assertNotEquals(name, p.fileName)
        assertEquals("POLE/POLE_1/${p.fileName}", p.destPath)
        assertContentEquals(bytes, fake.files[p.destPath!!])
        assertContentEquals(foreign, fake.files["POLE/POLE_1/$name"]) // never overwritten
    }

    @Test fun phoneFinishingFirstDuringUploadCountsAsDelivered() = withApp(spy = true) { app, fake, spy ->
        val bytes = jpeg()
        val name = backedUp(app, "ph-1", bytes)
        fake.ensureFolder("POLE/POLE_1")
        // Between the server's check and its upload, the phone's upload lands.
        spy.beforeUpload = { path -> fake.files.putIfAbsent(path, bytes.copyOf()) }
        app.clock.nowMs += TAKEOVER
        app.worker { w -> assertEquals(1, w.runOnce()) }
        val p = photo(app, "ph-1")
        assertEquals(PhotoStatus.confirmed, p.status)
        assertEquals("POLE/POLE_1/$name", p.destPath)
        assertEquals(1, fake.files.size)
    }

    @Test fun droppedUploadResumesItsSessionAfterRestart() = withApp(spy = true) { app, fake, spy ->
        val bytes = jpeg(w = 400, h = 300)
        val name = backedUp(app, "ph-1", bytes)
        val path = "POLE/POLE_1/$name"
        app.clock.nowMs += TAKEOVER
        fake.dropChunkAfter = 2
        app.worker(chunkBytes = 1024) { w -> assertEquals(1, w.runOnce()) }
        assertEquals(1L, col(app, "ph-1", "attempts"))
        val uploadId = assertNotNull(col(app, "ph-1", "od_upload_id")) as String
        assertEquals(2048L, col(app, "ph-1", "od_offset"))
        // SharePoint shows the partial file under the photo's name; it is ours, so it is resumed, not renamed.
        spy.partialInfo[path] = FileInfo(2048)

        app.clock.nowMs += nextDelayMs(1)
        app.worker(chunkBytes = 1024) { w -> assertEquals(1, w.runOnce()) } // a fresh worker: the server restarted
        val p = photo(app, "ph-1")
        assertEquals(PhotoStatus.confirmed, p.status)
        assertEquals(name, p.fileName)
        assertEquals(listOf(null, ChunkSession(uploadId, 2048)), spy.resumes)
        assertContentEquals(bytes, fake.files[path])
        assertEquals(1, fake.files.size)
        assertNull(col(app, "ph-1", "od_upload_id"))
    }

    // ---- retries ----

    @Test fun retriesFollowNextDelay() = withApp { app, fake, _ ->
        backedUp(app, "ph-1", jpeg())
        app.clock.nowMs += TAKEOVER
        fake.failNext(3, DestinationErrorCode.NETWORK)
        app.worker { w ->
            for (n in 1..3) {
                assertEquals(1, w.runOnce())
                assertEquals(n.toLong(), col(app, "ph-1", "attempts"))
                assertEquals(app.clock.nowMs + nextDelayMs(n), col(app, "ph-1", "next_attempt_at"))
                val p = photo(app, "ph-1")
                assertEquals(PhotoStatus.registered, p.status)
                assertNotNull(p.lastError)
                app.clock.nowMs += nextDelayMs(n) - 1
                assertEquals(0, w.runOnce())
                app.clock.nowMs += 1
            }
            assertEquals(1, w.runOnce())
        }
        assertEquals(PhotoStatus.confirmed, photo(app, "ph-1").status)
        assertNull(photo(app, "ph-1").lastError)
    }

    @Test fun stuckAfter6AdminRetryResets() = withApp { app, fake, _ ->
        backedUp(app, "ph-1", jpeg())
        app.clock.nowMs += TAKEOVER
        fake.failNext(7, DestinationErrorCode.NETWORK)
        app.worker { w ->
            for (n in 1..6) {
                assertEquals(1, w.runOnce())
                assertEquals(if (n < 6) PhotoStatus.registered else PhotoStatus.stuck, photo(app, "ph-1").status)
                app.clock.nowMs += nextDelayMs(n)
            }
            // Stuck photos are still retried.
            assertEquals(1, w.runOnce())
            assertEquals(PhotoStatus.stuck, photo(app, "ph-1").status)
            assertEquals(7L, col(app, "ph-1", "attempts"))
            assertEquals(0, w.runOnce()) // not due for another 16 min

            assertEquals(HttpStatusCode.Forbidden, assertFailsWith<HttpError> { retryPhoto(app.ctx, ann, "ph-1") }.status)
            retryPhoto(app.ctx, boss, "ph-1")
            val reset = photo(app, "ph-1")
            assertEquals(PhotoStatus.registered, reset.status)
            assertNull(reset.lastError)
            assertEquals(0L, col(app, "ph-1", "attempts"))
            assertEquals(PhotoStatus.registered, changes(app, "photo", "ph-1").last().photo?.status)

            assertEquals(1, w.runOnce()) // at once, no clock change
        }
        assertEquals(PhotoStatus.confirmed, photo(app, "ph-1").status)
        assertEquals(HttpStatusCode.Conflict, assertFailsWith<HttpError> { retryPhoto(app.ctx, boss, "ph-1") }.status)
    }

    @Test fun maxFourInFlight() = withApp(fileDb = true, spy = true) { app, fake, spy ->
        fake.uploadDelayMs = 60
        val ids = (1..10).map { "ph-$it" }
        ids.forEachIndexed { i, id -> backedUp(app, id, jpeg(i + 1)) }
        app.clock.nowMs += TAKEOVER
        app.worker { w -> assertEquals(10, w.runOnce()) }
        assertEquals(4, spy.maxActive.get())
        assertTrue(ids.all { photo(app, it).status == PhotoStatus.confirmed })
        assertEquals(10, fake.files.size)
    }

    // ---- folders and job problems ----

    @Test fun ambiguousFolderWaitsWithCandidates() = withApp { app, fake, _ ->
        fake.ensureFolder("POLE/1")
        fake.ensureFolder("POLE/POLE_1")
        val name = backedUp(app, "ph-1", jpeg())
        app.clock.nowMs += TAKEOVER
        app.worker { w ->
            assertEquals(0, w.runOnce())
            val pt = point(app, "pt-1")
            assertNull(pt.folderPath)
            assertEquals(listOf("POLE/1", "POLE/POLE_1"), pt.folderCandidates)
            assertEquals(listOf("POLE/1", "POLE/POLE_1"), changes(app, "point", "pt-1").last().point?.folderCandidates)
            assertEquals(PhotoStatus.registered, photo(app, "ph-1").status)
            assertEquals(0L, col(app, "ph-1", "attempts"))
            assertTrue(uploads(fake).isEmpty())

            // Waiting doesn't keep searching or re-announcing the point.
            val pointChanges = changes(app, "point", "pt-1").size
            app.clock.nowMs += MIN
            assertEquals(0, w.runOnce())
            assertEquals(pointChanges, changes(app, "point", "pt-1").size)

            op(app, Op.PointSetFolder("op-f", "pt-1", "POLE/1")) // someone picks one on the point screen
            assertEquals(1, w.runOnce())
        }
        assertEquals("POLE/1/$name", photo(app, "ph-1").destPath)
    }

    @Test fun forbiddenDestinationSetsJobDeliveryProblem() = withApp { app, fake, _ ->
        backedUp(app, "ph-1", jpeg())
        app.clock.nowMs += TAKEOVER
        app.worker { w ->
            fake.failNext(1, DestinationErrorCode.REQUIRES_SIGN_IN)
            assertEquals(1, w.runOnce())
            assertEquals("This link needs a Microsoft sign-in", job(app, "job-1").deliveryProblem)

            app.clock.nowMs += nextDelayMs(1)
            fake.failNext(1, DestinationErrorCode.FORBIDDEN)
            assertEquals(1, w.runOnce())
            assertEquals("The link no longer allows uploads", job(app, "job-1").deliveryProblem)
            assertEquals("The link no longer allows uploads", changes(app, "job", "job-1").last().job?.deliveryProblem)
            assertEquals(PhotoStatus.registered, photo(app, "ph-1").status)
            assertEquals(2L, col(app, "ph-1", "attempts"))

            app.clock.nowMs += nextDelayMs(2)
            assertEquals(1, w.runOnce())
        }
        assertEquals(PhotoStatus.confirmed, photo(app, "ph-1").status)
        assertNull(job(app, "job-1").deliveryProblem)
        assertNull(changes(app, "job", "job-1").last().job?.deliveryProblem)
    }

    @Test fun newLinkMakesWaitingPhotosDueAtOnce() = withApp { app, fake, _ ->
        backedUp(app, "ph-1", jpeg())
        app.clock.nowMs += TAKEOVER
        app.worker { w ->
            fake.failNext(1, DestinationErrorCode.FORBIDDEN)
            assertEquals(1, w.runOnce())
            assertEquals(0, w.runOnce())
            updateJob(app.ctx, "job-1", null, "https://example.test/one-fixed", null)
            assertNull(job(app, "job-1").deliveryProblem)
            assertEquals(1, w.runOnce())
        }
        assertEquals(PhotoStatus.confirmed, photo(app, "ph-1").status)
    }

    @Test fun noLinkSetsAddLinkProblem() = withApp { app, fake, _ ->
        backedUp(app, "ph-1", jpeg(), pointId = "pt-3", jobId = "job-3")
        app.clock.nowMs += TAKEOVER
        app.worker { w ->
            assertEquals(0, w.runOnce())
            assertEquals("Add the Field Notes link", job(app, "job-3").deliveryProblem)
            assertEquals("Add the Field Notes link", changes(app, "job", "job-3").last().job?.deliveryProblem)
            assertTrue(fake.calls.isEmpty())
            assertEquals(0L, col(app, "ph-1", "attempts"))
            val jobChanges = changes(app, "job", "job-3").size
            assertEquals(0, w.runOnce())
            assertEquals(jobChanges, changes(app, "job", "job-3").size) // set once, not every poll

            updateJob(app.ctx, "job-3", null, "https://example.test/three", null)
            assertEquals(1, w.runOnce())
        }
        assertEquals(PhotoStatus.confirmed, photo(app, "ph-1").status)
        assertNull(job(app, "job-3").deliveryProblem)
    }

    @Test fun deletedJobOrPointIsNotDelivered() = withApp { app, fake, _ ->
        backedUp(app, "ph-1", jpeg())
        backedUp(app, "ph-2", jpeg(2), pointId = "pt-2", jobId = "job-2")
        deleteJob(app.ctx, ann, "job-1")
        op(app, Op.PointDelete("op-d", "pt-2"))
        app.clock.nowMs += TAKEOVER
        app.worker { w -> assertEquals(0, w.runOnce()) }
        assertTrue(fake.calls.isEmpty())
    }

    // ---- retention ----

    @Test fun purgeKeepsUnconfirmedAndRespectsMinimum3Days() = withApp { app, fake, _ ->
        setting(app, "backupDays", "2")
        assertEquals(3, backupDays(app.ctx))
        val name = backedUp(app, "ph-a", jpeg(1))
        backedUp(app, "ph-b", jpeg(2))
        fake.ensureFolder("POLE/POLE_1")
        op(app, Op.PhotoConfirmed("op-c", "ph-a", "POLE/POLE_1/$name", 900))

        app.clock.nowMs += 2 * DAY + 60 * MIN // past the 2 days asked for, not past the minimum 3
        assertEquals(0, purgeBackups(app.ctx))
        assertTrue(Files.exists(backupFile(app, "ph-a")))

        app.clock.nowMs += DAY
        assertEquals(1, purgeBackups(app.ctx))
        assertFalse(Files.exists(backupFile(app, "ph-a")))
        assertNull(col(app, "ph-a", "backup_path"))
        assertEquals(app.clock.nowMs, col(app, "ph-a", "backup_purged_at"))
        assertTrue(photo(app, "ph-a").backedUp) // it did reach the server
        assertTrue(Files.exists(app.ctx.path(col(app, "ph-a", "thumb_path") as String))) // thumbnails are permanent

        // Never before OneDrive confirmation, however old.
        app.clock.nowMs += 60 * DAY
        assertEquals(0, purgeBackups(app.ctx))
        assertTrue(Files.exists(backupFile(app, "ph-b")))

        setting(app, "backupDays", "10")
        assertEquals(10, backupDays(app.ctx))
    }

    @Test fun purgeWaitsForPendingReuseThenSweepsKeptSources() = withApp { app, fake, _ ->
        // A confirmed original whose copy (in a job with no link) still needs its server copy.
        val name = backedUp(app, "ph-src", jpeg(1), pointId = "pt-2", jobId = "job-2")
        fake.ensureFolder("POLE/POLE_5")
        op(app, Op.PhotoConfirmed("op-c", "ph-src", "POLE/POLE_5/$name", 900))
        op(app, Op.PhotoReuse("op-r", "ph-copy", "ph-src", "pt-3"))
        // A removed, undelivered original kept only for its copy.
        backedUp(app, "ph-gone", jpeg(2))
        op(app, Op.PhotoReuse("op-r2", "ph-copy2", "ph-gone", "pt-2"))
        op(app, Op.PhotoRemove("op-x", "ph-gone"))
        assertTrue(Files.exists(backupFile(app, "ph-gone")))

        app.clock.nowMs += 4 * DAY
        assertEquals(0, purgeBackups(app.ctx))
        assertTrue(Files.exists(backupFile(app, "ph-src")))
        assertTrue(Files.exists(backupFile(app, "ph-gone")))

        app.worker { w -> assertEquals(1, w.runOnce()) } // delivers ph-copy2 (job-3 has no link)
        assertEquals(PhotoStatus.confirmed, photo(app, "ph-copy2").status)
        op(app, Op.PhotoRemove("op-x2", "ph-copy"))
        assertEquals(2, purgeBackups(app.ctx))
        assertFalse(Files.exists(backupFile(app, "ph-src")))
        assertFalse(Files.exists(backupFile(app, "ph-gone")))
    }

    @Test fun diskOver80WarnsNeverPurgesEarly() = withApp { app, fake, _ ->
        val name = backedUp(app, "ph-a", jpeg(1))
        backedUp(app, "ph-b", jpeg(2))
        fake.ensureFolder("POLE/POLE_1")
        op(app, Op.PhotoConfirmed("op-c", "ph-a", "POLE/POLE_1/$name", 900))
        app.clock.nowMs += DAY

        val full = backupMaintenance(app.ctx, usedPct = 95)
        assertTrue(full.diskWarning)
        assertEquals(95, full.diskUsedPct)
        assertEquals(0, full.purged)
        assertTrue(Files.exists(backupFile(app, "ph-a")))
        assertTrue(Files.exists(backupFile(app, "ph-b")))

        assertFalse(backupMaintenance(app.ctx, usedPct = 80).diskWarning)
        val pct = diskUsedPct(app.ctx.config.dataDir)
        assertTrue(pct in 0..100, "$pct")
    }

    @Test fun maintenanceRegeneratesMissingThumbs() = withApp { app, _, _ ->
        backedUp(app, "ph-1", jpeg())
        app.ctx.db.tx { createStatement().use { it.execute("UPDATE photos SET thumb_path = NULL") } }
        assertEquals(1, backupMaintenance(app.ctx, usedPct = 10).thumbsMade)
        assertNotNull(col(app, "ph-1", "thumb_path"))
    }

    @Test fun purgeDeletedJobKeepsThumbSharedByReuseCopy() = withApp { app, _, _ ->
        backedUp(app, "ph-src", jpeg(), pointId = "pt-2", jobId = "job-2")
        op(app, Op.PhotoReuse("op-r", "ph-copy", "ph-src", "pt-1"))
        val thumb = col(app, "ph-copy", "thumb_path") as String
        assertEquals(col(app, "ph-src", "thumb_path"), thumb)
        deleteJob(app.ctx, boss, "job-2")
        app.clock.nowMs += 31 * DAY
        assertEquals(1, purgeDeletedJobs(app.ctx))
        assertTrue(Files.exists(app.ctx.path(thumb)))
        assertFalse(Files.exists(backupFile(app, "ph-src")))
    }
}
