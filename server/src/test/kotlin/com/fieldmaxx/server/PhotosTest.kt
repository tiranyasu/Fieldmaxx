package com.fieldmaxx.server

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.OpResult
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoRegistration
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.RegisteredPhoto
import com.fieldmaxx.core.model.ReuseCandidate
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.server.jobs.toPhoto
import com.fieldmaxx.server.ops.OpsRequest
import com.fieldmaxx.server.ops.OpsResponse
import com.fieldmaxx.server.photos.renamePhotoForDelivery
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json

class PhotosTest {
    private class CountingKick : DeliveryKick {
        var kicks = 0
        override fun kick() { kicks++ }
    }

    /** Job 'job-1' with POLE 1 (pt-1, 39.6,-119.7) and 'job-2' with POLE 5 (pt-2, 39.6005,-119.7) ~55 m away, plus a far point (pt-3). */
    private fun seed(app: TestApp) = app.ctx.db.tx {
        createStatement().use {
            it.execute("INSERT INTO users(id, username, display_name, password_hash, role, created_at) VALUES ('creator','creator','Creator','x','tech',0)")
            for ((job, name) in listOf("job-1" to "Run one", "job-2" to "Run two")) {
                it.execute("INSERT INTO jobs(id, name, status, created_by, created_at) VALUES ('$job','$name','active','creator',0)")
            }
            fun pt(id: String, job: String, num: String, lat: Double, lon: Double) = it.execute(
                "INSERT INTO points(id, job_id, layer, type, num, label, lat, lon, description, status, created_at, updated_at) " +
                    "VALUES ('$id','$job','POLE','POLE','$num','POLE $num',$lat,$lon,'','open',0,0)",
            )
            pt("pt-1", "job-1", "1", 39.6, -119.7)
            pt("pt-2", "job-2", "5", 39.6005, -119.7)
            pt("pt-3", "job-2", "9", 40.5, -119.7)
        }
    }

    private fun jpeg(w: Int = 1000, h: Int = 500): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.fillRect(0, 0, w / 2, h); g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun reg(id: String, bytes: ByteArray, pointId: String = "pt-1", jobId: String = "job-1", lat: Double? = 39.636130, lon: Double? = -119.691104, size: Long = bytes.size.toLong(), sha: String = sha(bytes)) =
        PhotoRegistration(id, jobId, pointId, size, sha, 1_000L, lat, lon, 5.0, 90.0)

    private suspend fun ApplicationTestBuilder.register(token: String, r: PhotoRegistration): HttpResponse =
        client.post("/api/photos/register") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(FmxJson.encodeToString(PhotoRegistration.serializer(), r))
        }

    private suspend fun ApplicationTestBuilder.registered(token: String, r: PhotoRegistration): RegisteredPhoto {
        val res = register(token, r)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return FmxJson.decodeFromString(RegisteredPhoto.serializer(), res.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.chunk(token: String, id: String, offset: Long, bytes: ByteArray): HttpResponse =
        client.put("/api/photos/$id/backup?offset=$offset") {
            bearerAuth(token); contentType(ContentType.Application.OctetStream); setBody(bytes)
        }

    private suspend fun ApplicationTestBuilder.uploadAll(token: String, id: String, bytes: ByteArray, step: Int = 4096) {
        var off = 0
        while (off < bytes.size) {
            val part = bytes.copyOfRange(off, minOf(off + step, bytes.size))
            val res = chunk(token, id, off.toLong(), part)
            assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
            off += part.size
        }
    }

    private suspend fun ApplicationTestBuilder.send(token: String, vararg ops: Op): List<OpResult> {
        val res = client.post("/api/ops") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(FmxJson.encodeToString(OpsRequest.serializer(), OpsRequest(ops.toList())))
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return FmxJson.decodeFromString(OpsResponse.serializer(), res.bodyAsText()).results
    }

    private fun photo(app: TestApp, id: String): PhotoInfo = app.ctx.db.read {
        prepareStatement("SELECT * FROM photos WHERE id = ?").use { ps ->
            ps.setString(1, id); ps.executeQuery().use { it.next(); it.toPhoto() }
        }
    }

    private fun col(app: TestApp, id: String, column: String): Any? = app.ctx.db.read {
        prepareStatement("SELECT $column FROM photos WHERE id = ?").use { ps -> ps.setString(1, id); ps.executeQuery().use { it.next(); it.getObject(1).let { v -> if (v is Int) v.toLong() else v } } }
    }

    private fun photoChanges(app: TestApp, id: String): List<PhotoInfo> =
        app.ctx.changes.since(0).first.filter { it.entity == "photo" && it.entityId == id }.map { assertNotNull(it.photo) }

    @Test fun registerAssignsCadastrusName() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val r = registered(t, reg("ph-1", jpeg()))
        assertEquals(PhotoStatus.registered, r.status)
        assertEquals(0L, r.backupOffset)
        assertTrue(Regex("""POLE_1_39\.636130_-119\.691104_\d+\.jpg""").matches(r.fileName), r.fileName)
        val second = registered(t, reg("ph-2", jpeg()))
        assertNotEquals(r.fileName, second.fileName)
        assertEquals(1, photoChanges(app, "ph-1").size)
    }

    @Test fun registerIsIdempotentPerId() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val a = registered(t, reg("ph-1", jpeg()))
        val b = registered(t, reg("ph-1", jpeg()))
        assertEquals(a, b)
        assertEquals(1L, col(app, "ph-1", "(SELECT COUNT(*) FROM photos)"))
        assertEquals(1L, app.ctx.db.read { createStatement().use { s -> s.executeQuery("SELECT value FROM counters WHERE name='photo'").use { it.next(); it.getLong(1) } } })
    }

    @Test fun noGpsUsesPointCoords() = TestApp().run { app ->
        seed(app)
        val r = registered(tokenFor("ann"), reg("ph-1", jpeg(), lat = null, lon = null))
        assertTrue(Regex("""POLE_1_39\.600000_-119\.700000_\d+\.jpg""").matches(r.fileName), r.fileName)
    }

    @Test fun registerForDeletedPointIs410() = TestApp().run { app ->
        seed(app)
        app.ctx.db.tx { createStatement().use { it.execute("UPDATE points SET deleted_at = 5 WHERE id = 'pt-1'") } }
        assertEquals(HttpStatusCode.Gone, register(tokenFor("ann"), reg("ph-1", jpeg())).status)
        assertEquals(HttpStatusCode.NotFound, register(tokenFor("ann"), reg("ph-1", jpeg(), pointId = "nope")).status)
    }

    @Test fun backupChunksCompleteWithShaCheck() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val bytes = jpeg()
        registered(t, reg("ph-1", bytes))
        val half = bytes.size / 2
        val first = chunk(t, "ph-1", 0, bytes.copyOfRange(0, half))
        assertEquals(HttpStatusCode.OK, first.status)
        val j = Json.parseToJsonElement(first.bodyAsText()).jsonObject
        assertEquals(half.toString(), j.getValue("offset").jsonPrimitive.content)
        assertEquals("false", j.getValue("complete").jsonPrimitive.content)
        val last = chunk(t, "ph-1", half.toLong(), bytes.copyOfRange(half, bytes.size))
        assertEquals("true", Json.parseToJsonElement(last.bodyAsText()).jsonObject.getValue("complete").jsonPrimitive.content)
        assertTrue(Files.exists(app.ctx.config.dataDir.resolve("photos/ph-1.jpg")))
        assertTrue(!Files.exists(app.ctx.config.dataDir.resolve("photos/ph-1.part")))
        assertTrue(photoChanges(app, "ph-1").last().backedUp)
        val file = client.get("/api/photos/ph-1/file") { bearerAuth(t) }
        assertEquals(HttpStatusCode.OK, file.status)
        assertTrue(bytes.contentEquals(file.bodyAsBytes()))
        // The final chunk sent again is harmless and reports completion.
        val again = chunk(t, "ph-1", half.toLong(), bytes.copyOfRange(half, bytes.size))
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("true", Json.parseToJsonElement(again.bodyAsText()).jsonObject.getValue("complete").jsonPrimitive.content)
    }

    @Test fun wrongOffset409WithCurrent() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val bytes = jpeg()
        registered(t, reg("ph-1", bytes))
        chunk(t, "ph-1", 0, bytes.copyOfRange(0, 100))
        val res = chunk(t, "ph-1", 50, bytes.copyOfRange(50, 150))
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("100", Json.parseToJsonElement(res.bodyAsText()).jsonObject.getValue("offset").jsonPrimitive.content)
        assertEquals(100L, col(app, "ph-1", "backup_offset"))
    }

    @Test fun shaMismatch422ResetsBackup() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val bytes = jpeg()
        registered(t, reg("ph-1", bytes, sha = "0".repeat(64)))
        val res = chunk(t, "ph-1", 0, bytes)
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
        assertEquals(0L, col(app, "ph-1", "backup_offset"))
        assertNull(col(app, "ph-1", "backup_received_at"))
        assertTrue(!Files.exists(app.ctx.config.dataDir.resolve("photos/ph-1.part")))
        assertTrue(!Files.exists(app.ctx.config.dataDir.resolve("photos/ph-1.jpg")))
    }

    @Test fun backupOver40MB413() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val mb2 = ByteArray(2 * 1024 * 1024)
        registered(t, reg("ph-1", mb2, size = 50L * 1024 * 1024))
        for (i in 0 until 20) assertEquals(HttpStatusCode.OK, chunk(t, "ph-1", i * mb2.size.toLong(), mb2).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, chunk(t, "ph-1", 20L * mb2.size, ByteArray(1)).status)
        // A single request above 2 MB is refused too.
        assertEquals(HttpStatusCode.PayloadTooLarge, chunk(t, "ph-1", 20L * mb2.size, ByteArray(2 * 1024 * 1024 + 1)).status)
    }

    @Test fun thumbnailMadeOnBackupComplete() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val bytes = jpeg(1600, 800)
        registered(t, reg("ph-1", bytes))
        uploadAll(t, "ph-1", bytes)
        val res = client.get("/api/photos/ph-1/thumb") { bearerAuth(t) }
        assertEquals(HttpStatusCode.OK, res.status)
        val img = ImageIO.read(res.bodyAsBytes().inputStream())
        assertEquals(320, maxOf(img.width, img.height))
        assertEquals(160, minOf(img.width, img.height))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/photos/nope/thumb") { bearerAuth(t) }.status)
    }

    @Test fun thumbnailFailureDoesNotBreakUpload() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val junk = ByteArray(5000) { (it * 7).toByte() }
        registered(t, reg("ph-1", junk))
        val res = chunk(t, "ph-1", 0, junk)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals("true", Json.parseToJsonElement(res.bodyAsText()).jsonObject.getValue("complete").jsonPrimitive.content)
        assertNotNull(col(app, "ph-1", "backup_received_at"))
        assertNull(col(app, "ph-1", "thumb_path"))
        assertEquals(0, com.fieldmaxx.server.photos.regenerateMissingThumbs(app.ctx))
    }

    @Test fun missingThumbnailIsRegenerated() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val bytes = jpeg(1600, 800)
        registered(t, reg("ph-1", bytes)); uploadAll(t, "ph-1", bytes)
        Files.delete(app.ctx.config.dataDir.resolve("thumbs/ph-1.jpg"))
        app.ctx.db.tx { createStatement().use { it.execute("UPDATE photos SET thumb_path = NULL WHERE id = 'ph-1'") } }
        assertEquals(1, com.fieldmaxx.server.photos.regenerateMissingThumbs(app.ctx))
        assertEquals("thumbs/ph-1.jpg", col(app, "ph-1", "thumb_path"))
        assertEquals(HttpStatusCode.OK, client.get("/api/photos/ph-1/thumb") { bearerAuth(t) }.status)
        assertEquals(0, com.fieldmaxx.server.photos.regenerateMissingThumbs(app.ctx))
    }

    @Test fun reuseCreatedBeforeSourceThumbGetsItLater() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val bytes = jpeg()
        registered(t, reg("ph-1", bytes)); uploadAll(t, "ph-1", bytes)
        app.ctx.db.tx { createStatement().use { it.execute("UPDATE photos SET thumb_path = NULL WHERE id = 'ph-1'") } }
        assertTrue(send(t, Op.PhotoReuse("ru", "ph-2", "ph-1", "pt-2")).single().ok)
        assertNull(col(app, "ph-2", "thumb_path"))
        assertEquals(1, com.fieldmaxx.server.photos.regenerateMissingThumbs(app.ctx))
        assertEquals("thumbs/ph-1.jpg", col(app, "ph-2", "thumb_path"))
    }

    @Test fun removingSourceKeepsBackupForPendingReuse() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val bytes = jpeg()
        registered(t, reg("ph-1", bytes)); uploadAll(t, "ph-1", bytes)
        assertTrue(send(t, Op.PhotoReuse("ru", "ph-2", "ph-1", "pt-2")).single().ok)
        assertEquals(col(app, "ph-1", "thumb_path"), col(app, "ph-2", "thumb_path"))
        assertTrue(com.fieldmaxx.server.photos.backupStillNeeded(app.ctx, "ph-1"))
        assertTrue(send(t, Op.PhotoRemove("rm", "ph-1")).single().ok)
        assertEquals(PhotoStatus.removed, photo(app, "ph-1").status)
        assertNotNull(col(app, "ph-1", "backup_path"))
        assertTrue(Files.exists(app.ctx.config.dataDir.resolve("photos/ph-1.jpg")))
        // Once the copy is delivered nothing needs the original's server copy any more.
        send(t, Op.PhotoConfirmed("c", "ph-2", "x.jpg", 1))
        assertTrue(!com.fieldmaxx.server.photos.backupStillNeeded(app.ctx, "ph-1"))
    }

    @Test fun confirmedOpSetsDestAndTimingsAndChange() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        registered(t, reg("ph-1", jpeg()))
        app.clock.nowMs += 5_000
        val op = Op.PhotoConfirmed("op-1", "ph-1", "POLE/POLE_1/x.jpg", 2_345)
        assertTrue(send(t, op).single().ok)
        assertEquals(PhotoStatus.confirmed, photo(app, "ph-1").status)
        assertEquals("POLE/POLE_1/x.jpg", col(app, "ph-1", "dest_path"))
        assertEquals(2_345L, col(app, "ph-1", "upload_ms"))
        assertEquals(app.clock.nowMs, col(app, "ph-1", "confirmed_at"))
        val last = photoChanges(app, "ph-1").last()
        assertEquals(PhotoStatus.confirmed, last.status)
        assertEquals("POLE/POLE_1/x.jpg", last.destPath)
        // Replay: same stored result, no second change.
        val n = photoChanges(app, "ph-1").size
        assertTrue(send(t, op).single().ok)
        assertEquals(n, photoChanges(app, "ph-1").size)
        // Another user's replay of the opId is refused.
        assertEquals("op_id_conflict", send(tokenFor("bob"), op).single().error)
    }

    @Test fun failedOpRecordsErrorStuckAt6() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        registered(t, reg("ph-1", jpeg()))
        send(t, Op.PhotoFailed("f1", "ph-1", "403 forbidden", 5))
        assertEquals(PhotoStatus.registered, photo(app, "ph-1").status)
        assertEquals("403 forbidden", photo(app, "ph-1").lastError)
        send(t, Op.PhotoFailed("f2", "ph-1", "403 forbidden", 6))
        assertEquals(PhotoStatus.stuck, photo(app, "ph-1").status)
        assertEquals(6L, col(app, "ph-1", "attempts"))
        assertEquals(PhotoStatus.stuck, photoChanges(app, "ph-1").last().status)
        // A later success still wins.
        send(t, Op.PhotoConfirmed("c1", "ph-1", "p.jpg", 1))
        assertEquals(PhotoStatus.confirmed, photo(app, "ph-1").status)
        send(t, Op.PhotoFailed("f3", "ph-1", "late", 9))
        assertEquals(PhotoStatus.confirmed, photo(app, "ph-1").status)
    }

    private suspend fun ApplicationTestBuilder.nearby(token: String, lat: Double, lon: Double, extra: String = ""): List<ReuseCandidate> {
        val res = client.get("/api/photos/nearby?lat=$lat&lon=$lon$extra") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return FmxJson.decodeFromString(ListSerializer(ReuseCandidate.serializer()), res.bodyAsText())
    }

    @Test fun nearbyReturnsOtherJobsWithin150mSortedByDistance() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        // ph-far: ~110 m north of pt-1 (in job-2 on pt-2), ph-near: ~11 m, ph-out: pt-3 far away, ph-nogps: uses its point coords.
        registered(t, reg("ph-far", jpeg(), "pt-2", "job-2", lat = 39.6010, lon = -119.7))
        registered(t, reg("ph-near", jpeg(), "pt-2", "job-2", lat = 39.6001, lon = -119.7))
        registered(t, reg("ph-out", jpeg(), "pt-3", "job-2", lat = 40.5, lon = -119.7))
        registered(t, reg("ph-own", jpeg(), "pt-1", "job-1", lat = 39.6, lon = -119.7))
        val list = nearby(t, 39.6, -119.7, "&excludePointId=pt-1")
        assertEquals(listOf("ph-near", "ph-far"), list.map { it.photo.id })
        assertEquals("POLE 5", list[0].pointLabel)
        assertEquals("Run two", list[0].jobName)
        assertTrue(list[0].distanceM!! in 5.0..20.0, "${list[0].distanceM}")
        assertTrue(list[1].distanceM!! > list[0].distanceM!!)
        assertEquals(setOf("ph-near", "ph-far", "ph-own"), nearby(t, 39.6, -119.7).map { it.photo.id }.toSet())
        assertEquals(listOf("ph-near"), nearby(t, 39.6, -119.7, "&radiusM=50&excludePointId=pt-1").map { it.photo.id })
        // Removed photos and photos of deleted jobs are not offered.
        send(t, Op.PhotoRemove("rm", "ph-near"))
        app.ctx.db.tx { createStatement().use { it.execute("UPDATE jobs SET deleted_at = 1 WHERE id = 'job-1'") } }
        assertEquals(listOf("ph-far"), nearby(t, 39.6, -119.7).map { it.photo.id })
        val thisJob = client.get("/api/jobs/job-2/photos") { bearerAuth(t) }
        assertEquals(HttpStatusCode.OK, thisJob.status)
        val jobList = FmxJson.decodeFromString(ListSerializer(ReuseCandidate.serializer()), thisJob.bodyAsText())
        assertEquals(setOf("ph-far", "ph-out"), jobList.map { it.photo.id }.toSet())
        assertNull(jobList[0].distanceM)
    }

    @Test fun reuseCreatesRegisteredPhotoWithOriginalCoordsAndKicksDelivery() = TestApp().run { app ->
        seed(app)
        val kick = CountingKick().also { app.ctx.delivery = it }
        val t = tokenFor("ann")
        val bytes = jpeg()
        val orig = registered(t, reg("ph-1", bytes, lat = 39.636130, lon = -119.691104))
        uploadAll(t, "ph-1", bytes)
        val res = send(t, Op.PhotoReuse("ru-1", "ph-2", "ph-1", "pt-2")).single()
        assertTrue(res.ok, res.error)
        assertEquals(1, kick.kicks)
        val p = photo(app, "ph-2")
        assertEquals(PhotoStatus.registered, p.status)
        assertEquals("pt-2", p.pointId)
        assertEquals("job-2", p.jobId)
        assertEquals("ph-1", p.reusedFrom)
        assertEquals(39.636130, p.lat)
        assertEquals(-119.691104, p.lon)
        assertTrue(Regex("""POLE_5_39\.636130_-119\.691104_\d+\.jpg""").matches(p.fileName!!), p.fileName)
        assertNotEquals(orig.fileName, p.fileName)
        assertEquals("backup", col(app, "ph-2", "source_kind"))
        assertNotNull(col(app, "ph-2", "thumb_path"))
        assertEquals(PhotoStatus.registered, photoChanges(app, "ph-2").last().status)
        // A replay changes and kicks nothing more.
        assertTrue(send(t, Op.PhotoReuse("ru-1", "ph-2", "ph-1", "pt-2")).single().ok)
        assertEquals(1, kick.kicks)
        // Without a backup the copy comes from OneDrive.
        registered(t, reg("ph-3", jpeg()))
        assertTrue(send(t, Op.PhotoReuse("ru-2", "ph-4", "ph-3", "pt-2")).single().ok)
        assertEquals("onedrive", col(app, "ph-4", "source_kind"))
        assertEquals("not_found", send(t, Op.PhotoReuse("ru-3", "ph-5", "nope", "pt-2")).single().error)
    }

    @Test fun removeBeforeConfirmDeletesBackupAfterOnlyHides() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val a = jpeg(); val b = jpeg(800, 600)
        registered(t, reg("ph-a", a)); uploadAll(t, "ph-a", a)
        registered(t, reg("ph-b", b)); uploadAll(t, "ph-b", b)
        send(t, Op.PhotoConfirmed("c", "ph-b", "x.jpg", 1))
        val dir = app.ctx.config.dataDir.resolve("photos")
        assertTrue(Files.exists(dir.resolve("ph-a.jpg")))
        assertTrue(send(t, Op.PhotoRemove("r1", "ph-a")).single().ok)
        assertTrue(!Files.exists(dir.resolve("ph-a.jpg")))
        assertEquals(PhotoStatus.removed, photo(app, "ph-a").status)
        assertNull(col(app, "ph-a", "backup_path"))
        assertEquals(PhotoStatus.removed, photoChanges(app, "ph-a").last().status)
        assertTrue(send(t, Op.PhotoRemove("r2", "ph-b")).single().ok)
        assertTrue(Files.exists(dir.resolve("ph-b.jpg")))
        assertEquals(PhotoStatus.removed, photo(app, "ph-b").status)
        assertEquals("ann", app.ctx.db.read { createStatement().use { s -> s.executeQuery("SELECT u.username FROM photos p JOIN users u ON u.id = p.removed_by WHERE p.id='ph-b'").use { it.next(); it.getString(1) } } })
        // A removed-after-delivery photo still counts as delivered, so a point with it is protected from tech deletes.
        assertEquals("photos_delivered", send(tokenFor("bob"), Op.PointDelete("d1", "pt-1")).single().error)
    }

    @Test fun removeByOtherTechForbiddenAdminAllowed() = TestApp().run { app ->
        seed(app)
        val ann = tokenFor("ann")
        registered(ann, reg("ph-1", jpeg()))
        assertEquals("forbidden", send(tokenFor("bob"), Op.PhotoRemove("r1", "ph-1")).single().error)
        assertEquals(PhotoStatus.registered, photo(app, "ph-1").status)
        assertTrue(send(tokenFor("root", Role.admin), Op.PhotoRemove("r2", "ph-1")).single().ok)
        assertEquals(PhotoStatus.removed, photo(app, "ph-1").status)
        assertEquals("not_found", send(ann, Op.PhotoRemove("r3", "nope")).single().error)
    }

    @Test fun renameAssignsNewCounter() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val r = registered(t, reg("ph-1", jpeg()))
        val res = client.post("/api/photos/ph-1/rename?from=${r.fileName}") { bearerAuth(t) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val renamed = FmxJson.decodeFromString(RegisteredPhoto.serializer(), res.bodyAsText())
        assertNotEquals(r.fileName, renamed.fileName)
        assertTrue(renamed.fileName.startsWith("POLE_1_39.636130_-119.691104_"))
        assertEquals(renamed.fileName, photo(app, "ph-1").fileName)
        assertEquals(renamed.fileName, photoChanges(app, "ph-1").last().fileName)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/photos/ph-1/rename?from=${renamed.fileName}") { bearerAuth(tokenFor("bob")) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/photos/ph-1/rename") { bearerAuth(t) }.status, "a rename names the name it leaves")
    }

    /** R82: a rename leaving a name the photo no longer has answers with its current name, and takes no counter. */
    @Test fun renameFromAStaleNameReturnsTheCurrentOne() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val first = registered(t, reg("ph-1", jpeg())).fileName
        fun counter() = app.ctx.db.read { createStatement().use { s -> s.executeQuery("SELECT value FROM counters WHERE name='photo'").use { it.next(); it.getLong(1) } } }
        suspend fun rename(from: String) = client.post("/api/photos/ph-1/rename?from=$from") { bearerAuth(t) }.let { res ->
            assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
            FmxJson.decodeFromString(RegisteredPhoto.serializer(), res.bodyAsText()).fileName
        }
        // The server's own delivery met another file under the first name and renamed it.
        val second = renamePhotoForDelivery(app.ctx, "ph-1", from = first).fileName
        assertNotEquals(first, second)
        assertEquals(2L, counter())
        // The phone met the same file a moment later: it converges on the server's name.
        assertEquals(second, rename(from = first))
        assertEquals(2L, counter(), "no new counter")
        assertEquals(second, renamePhotoForDelivery(app.ctx, "ph-1", from = first).fileName)
        assertEquals(2L, counter())
        assertEquals(second, photo(app, "ph-1").fileName)
        // A rename from the current name does take the next counter.
        val third = rename(from = second)
        assertNotEquals(second, third)
        assertEquals(3L, counter())
    }
}
