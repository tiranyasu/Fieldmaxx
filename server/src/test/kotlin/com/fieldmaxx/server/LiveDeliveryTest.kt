package com.fieldmaxx.server

import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoRegistration
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import com.fieldmaxx.server.delivery.DEFAULT_CHUNK_BYTES
import com.fieldmaxx.server.delivery.DeliveryWorker
import com.fieldmaxx.server.delivery.MIN_BACKUP_DAYS
import com.fieldmaxx.server.delivery.TAKEOVER_AFTER_MS
import com.fieldmaxx.server.delivery.purgeBackups
import com.fieldmaxx.server.ops.applyOps
import com.fieldmaxx.server.photos.MAX_CHUNK_BYTES
import com.fieldmaxx.server.photos.appendBackupChunk
import com.fieldmaxx.server.photos.getPhoto
import com.fieldmaxx.server.photos.registerPhoto
import com.fieldmaxx.sharepoint.ChunkSession
import com.fieldmaxx.sharepoint.FileInfo
import com.fieldmaxx.sharepoint.PhotoDestination
import com.fieldmaxx.sharepoint.SharePointLinkDestination
import okhttp3.OkHttpClient
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private const val JOB = "live-job"
private const val POLE_33 = "pt-pole-33"
private const val QUERY_43 = "pt-query-43"
private const val ORIGINAL = "live-pole-33"
private const val COPY = "live-query-43"
private const val DAY_MS = 24L * 60 * 60 * 1000

/** The job's link as the server stores it. The worker asks for its destination by it and gets this run's folder. */
private const val FIELD_NOTES = "https://1drv.ms/f/c/0000000000000000/Field-Notes"

/** Delivery attempts per photo: the first, then the worker's own retries after their delays. */
private const val ATTEMPTS = 3

/**
 * The server's delivery worker against the owner's real test folder, through the real [SharePointLinkDestination] built
 * as the running server builds it. The link comes only from `FMX_LIVE_TEST_LINK` (never commit it). The link can't
 * delete, so everything written stays: this run's `_fieldmaxx_it/<timestamp>/` is a fake job's Field Notes folder, for
 * the owner to delete by hand.
 */
@EnabledIfEnvironmentVariable(named = "FMX_LIVE_TEST_LINK", matches = ".+")
class LiveDeliveryTest {
    private val ann = User("u-ann", "ann", "Ann", Role.tech, false)

    @Test
    fun takenOverPhotoAndItsReuseCopyLandInTheTestFolder() = redacting {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val run = "_fieldmaxx_it/$stamp"
        val live: PhotoDestination = SharePointLinkDestination(System.getenv("FMX_LIVE_TEST_LINK"), OkHttpClient())
        val madeItFolder = "_fieldmaxx_it" !in live.listFolders("")
        live.ensureFolder(run)
        val fieldNotes = RunFolder(live, run)
        val app = TestApp(destinations = { _ -> fieldNotes })
        val worker = DeliveryWorker(app.ctx) // the running server's: 4 at a time, 3.2 MB chunks
        try {
            seed(app)
            val bytes = photoJpeg()
            assertTrue(bytes.size > 2 * DEFAULT_CHUNK_BYTES, "${bytes.size} bytes: a phone-sized photo, in three chunks")

            // The phone's POLE 33 photo: registered, its server copy complete, then no OneDrive confirmation for 30 min.
            val registration = PhotoRegistration(
                ORIGINAL, JOB, POLE_33, bytes.size.toLong(), sha256(bytes), app.clock.nowMs, 39.636130, -119.691104, 5.0, 47.0,
            )
            val name = registerPhoto(app.ctx, ann, registration).fileName
            var at = 0
            while (at < bytes.size) {
                val end = minOf(bytes.size, at + MAX_CHUNK_BYTES)
                appendBackupChunk(app.ctx, ann, ORIGINAL, at.toLong(), bytes.copyOfRange(at, end))
                at = end
            }
            assertTrue(getPhoto(app.ctx, ORIGINAL).backedUp)
            app.clock.nowMs += TAKEOVER_AFTER_MS

            val original = deliver(app, worker, ORIGINAL)
            assertEquals("POLE/POLE_33/$name", original.destPath)
            assertEquals(FileInfo(bytes.size.toLong()), fieldNotes.fileInfo(original.destPath!!))

            // Days later its server copy is gone (kept 3 days once in OneDrive) and a query point reuses the photo: the
            // copy is read back from the original in OneDrive.
            app.clock.nowMs += MIN_BACKUP_DAYS * DAY_MS + 60_000
            assertEquals(1, purgeBackups(app.ctx))
            val reused = applyOps(app.ctx, ann, listOf(Op.PhotoReuse("op-reuse", COPY, ORIGINAL, QUERY_43))).single()
            assertTrue(reused.ok, reused.error)
            assertEquals("onedrive", sourceKind(app, COPY))

            val copy = deliver(app, worker, COPY)
            assertEquals("QUERY 1/Query_1-43/${copy.fileName}", copy.destPath)
            assertEquals(FileInfo(bytes.size.toLong()), fieldNotes.fileInfo(copy.destPath!!))
            assertEquals(listOf(original.destPath, copy.destPath), fieldNotes.files.distinct(), "one file per photo, nothing else")

            println("photo ${bytes.size} bytes; POLE 33 delivered in ${original.uploadMs} ms, its reuse copy in ${copy.uploadMs} ms")
            println("made in the test folder by this run:")
            if (madeItFolder) println("  _fieldmaxx_it/")
            println("  $run/")
            fieldNotes.folders.flatMap(::withParents).distinct().forEach { println("  $run/$it/") }
            fieldNotes.files.distinct().forEach { println("  $run/$it") }
        } finally {
            worker.stop()
            app.ctx.db.close()
        }
    }

    /** POLE 33 and Query 1-43 (a query layer's point, as the importer makes them) in one job with a Field Notes link. */
    private fun seed(app: TestApp) = app.ctx.db.tx {
        createStatement().use {
            it.execute("INSERT INTO users(id, username, display_name, password_hash, role, created_at) VALUES ('${ann.id}','ann','Ann','x','tech',0)")
            it.execute("INSERT INTO jobs(id, name, field_notes_url, status, created_by, created_at) VALUES ('$JOB','Live delivery check','$FIELD_NOTES','active','${ann.id}',0)")
            for ((id, layer, num, label) in listOf(listOf(POLE_33, "POLE", "33", "POLE 33"), listOf(QUERY_43, "QUERY 1", "43", "Query 1-43"))) {
                it.execute(
                    "INSERT INTO points(id, job_id, layer, type, num, label, lat, lon, description, status, created_at, updated_at) " +
                        "VALUES ('$id','$JOB','$layer','$layer','$num','$label',39.636130,-119.691104,'','open',0,0)",
                )
            }
        }
    }

    /** Rounds of the worker, as the running server's poll makes them, until [id] is confirmed; a failure waits its retry delay. */
    private fun deliver(app: TestApp, worker: DeliveryWorker, id: String): PhotoInfo {
        repeat(ATTEMPTS) { n ->
            worker.runOnce()
            val p = getPhoto(app.ctx, id)
            if (p.status == PhotoStatus.confirmed) return p
            println("$id attempt ${n + 1}: ${redact(p.lastError)}")
            val wait = ((nextAttemptAt(app, id) ?: app.clock.nowMs) - app.clock.nowMs).coerceAtLeast(0)
            Thread.sleep(wait)
            app.clock.nowMs += wait
        }
        fail("$id was not delivered: ${redact(getPhoto(app.ctx, id).lastError)}")
    }

    private fun nextAttemptAt(app: TestApp, id: String): Long? = app.ctx.db.read {
        prepareStatement("SELECT next_attempt_at FROM photos WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1).let { v -> if (rs.wasNull()) null else v } }
        }
    }

    private fun sourceKind(app: TestApp, id: String): String? = app.ctx.db.read {
        prepareStatement("SELECT source_kind FROM photos WHERE id = ?").use { ps ->
            ps.setString(1, id); ps.executeQuery().use { it.next(); it.getString(1) }
        }
    }
}

/**
 * This run's `_fieldmaxx_it/<timestamp>/` as a job's Field Notes folder: every path the worker uses is inside it. Keeps the
 * folders and the files the worker asked to make (the link can't delete them).
 */
private class RunFolder(private val live: PhotoDestination, private val root: String) : PhotoDestination {
    val folders: MutableList<String> = CopyOnWriteArrayList()
    val files: MutableList<String> = CopyOnWriteArrayList()

    private fun inRun(relPath: String) = relPath.trim('/').let { if (it.isEmpty()) root else "$root/$it" }

    override fun rootName(): String = root.substringAfterLast('/')
    override fun listFolders(relPath: String): List<String> = live.listFolders(inRun(relPath))

    override fun ensureFolder(relPath: String) {
        folders += relPath
        live.ensureFolder(inRun(relPath))
    }

    override fun fileInfo(relPath: String): FileInfo? = live.fileInfo(inRun(relPath))

    override fun upload(relPath: String, bytes: ByteArray) {
        files += relPath
        live.upload(inRun(relPath), bytes)
    }

    override fun uploadChunked(relPath: String, file: File, chunkBytes: Int, resume: ChunkSession?, onProgress: (ChunkSession) -> Unit) {
        if (resume == null) files += relPath // a fresh upload makes the file; a resumed one goes on in it
        live.uploadChunked(inRun(relPath), file, chunkBytes, resume, onProgress)
    }

    override fun download(relPath: String, out: OutputStream) = live.download(inRun(relPath), out)
}

/** `a/b/c` → `a`, `a/b`, `a/b/c`. */
private fun withParents(path: String): List<String> {
    val parts = path.split('/').filter { it.isNotEmpty() }
    return parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
}

/** A phone-sized photo (3000×4000, about 7 MB of noise, so it goes up in three chunks). */
private fun photoJpeg(): ByteArray {
    val img = BufferedImage(3000, 4000, BufferedImage.TYPE_INT_RGB)
    val r = Random(33)
    for (y in 0 until img.height) for (x in 0 until img.width) img.setRGB(x, y, r.nextInt(0xFFFFFF))
    return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
}

private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/** Runs [block]; a failure is thrown again with its message redacted, since SharePoint's messages name the folder's path. */
private inline fun redacting(block: () -> Unit) = try {
    block()
} catch (e: Throwable) {
    throw AssertionError("${e.javaClass.simpleName}: ${redact(e.message)}").apply { stackTrace = e.stackTrace }
}

/**
 * [text] without the link or any part of it (the account id, the share code), without the folder's server path, which
 * SharePoint's messages carry (`personal/<account>/Documents/<folder>/…`), and without the people they name.
 */
private fun redact(text: String?): String {
    val link = System.getenv("FMX_LIVE_TEST_LINK").orEmpty().trim()
    val uri = runCatching { URI(link) }.getOrNull()
    val parts = listOf(link) + uri?.rawPath.orEmpty().split('/') + uri?.rawQuery.orEmpty().split('&', '=')
    var out = text.orEmpty()
    for (part in parts.filter { it.length >= 4 }) out = out.replace(part, "…", ignoreCase = true)
    return out.replace(Regex("""/?personal/[^\s"'<>]*?(?=/_fieldmaxx_it)"""), "<test folder>")
        .replace(Regex("""personal/[^/\s"'<>]+"""), "personal/…")
        .replace(Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+"""), "<email>")
        .replace(Regex("""((?:use|modified) by )[^."]+"""), "\$1<user>")
}
