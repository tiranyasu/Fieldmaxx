package com.fieldmaxx.server

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.ActivityItem
import com.fieldmaxx.core.model.AppConfig
import com.fieldmaxx.core.model.AppRelease
import com.fieldmaxx.core.model.CantShootReason
import com.fieldmaxx.core.model.HealthReport
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.server.ops.OpsRequest
import com.fieldmaxx.server.release.publishApk
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer

class AdminTest {
    private fun seed(app: TestApp) = app.ctx.db.tx {
        createStatement().use {
            it.execute("INSERT INTO jobs(id, name, status, created_by, created_at) VALUES ('job-1','Run 1','active','x',0)")
            it.execute("INSERT INTO jobs(id, name, status, created_by, created_at) VALUES ('job-2','Run 2','active','x',0)")
            for ((pt, job, n) in listOf(Triple("pt-1", "job-1", "1"), Triple("pt-2", "job-2", "2"))) {
                it.execute(
                    "INSERT INTO points(id, job_id, layer, type, num, label, lat, lon, description, status, created_at, updated_at) " +
                        "VALUES ('$pt','$job','POLE','POLE','$n','POLE $n',39.6,-119.7,'','open',0,0)",
                )
            }
        }
    }

    private fun photo(app: TestApp, id: String, status: String, takenAt: Long, confirmedAt: Long? = null, uploadMs: Long? = null, attempts: Int = 0) =
        app.ctx.db.tx {
            prepareStatement(
                "INSERT INTO photos(id, job_id, point_id, user_id, file_name, taken_at, status, confirmed_at, upload_ms, attempts, last_error, created_at) " +
                    "VALUES (?, 'job-1','pt-1','u','$id.jpg',?,?,?,?,?,?,0)",
            ).use {
                it.setString(1, id); it.setLong(2, takenAt); it.setString(3, status)
                if (confirmedAt == null) it.setNull(4, java.sql.Types.INTEGER) else it.setLong(4, confirmedAt)
                if (uploadMs == null) it.setNull(5, java.sql.Types.INTEGER) else it.setLong(5, uploadMs)
                it.setInt(6, attempts); it.setString(7, if (status == "stuck") "No signal" else null); it.executeUpdate()
            }
        }

    private suspend fun ApplicationTestBuilder.getAs(token: String, path: String) = client.get(path) { bearerAuth(token) }

    private suspend fun ApplicationTestBuilder.putSettings(token: String, json: String) = client.put("/api/admin/settings") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(json)
    }

    @Test fun adminOnly() = TestApp().run { app ->
        seed(app)
        val tech = tokenFor("ann")
        for (path in listOf("/api/admin/health", "/api/admin/activity")) {
            val r = getAs(tech, path)
            assertEquals(HttpStatusCode.Forbidden, r.status, path)
            assertTrue(r.bodyAsText().contains("\"error\""))
        }
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/admin/photos/x/retry") { bearerAuth(tech) }.status)
        assertEquals(HttpStatusCode.Forbidden, putSettings(tech, """{"photoQuality":95}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/health").status)
        assertEquals(HttpStatusCode.OK, getAs(tokenFor("boss", Role.admin), "/api/admin/health").status)
    }

    @Test fun healthStuckFirstWithMedians() = TestApp().run { app ->
        seed(app)
        val now = app.clock.nowMs
        photo(app, "reg-old", "registered", takenAt = now - 5000)
        photo(app, "stuck-new", "stuck", takenAt = now - 1000, attempts = 6)
        photo(app, "removed", "removed", takenAt = 1)
        photo(app, "c1", "confirmed", takenAt = now - 100_000, confirmedAt = now - 90_000, uploadMs = 2000) // 10 s, 2 s
        photo(app, "c2", "confirmed", takenAt = now - 100_000, confirmedAt = now - 60_000, uploadMs = 4000) // 40 s, 4 s
        photo(app, "c3", "confirmed", takenAt = now - 100_000, confirmedAt = now - 70_000, uploadMs = 9000) // 30 s, 9 s
        photo(app, "c-old", "confirmed", takenAt = 0, confirmedAt = now - 2 * 24 * 3600_000L, uploadMs = 99_000) // outside 24 h
        val r = getAs(tokenFor("boss", Role.admin), "/api/admin/health")
        val h = FmxJson.decodeFromString(HealthReport.serializer(), r.bodyAsText())
        assertEquals(listOf("stuck-new", "reg-old"), h.photos.map { it.id })
        assertEquals(PhotoStatus.stuck, h.photos.first().status)
        assertEquals("No signal", h.photos.first().lastError)
        assertEquals(30L, h.phoneToConfirmedMedianS)
        assertEquals(4L, h.uploadMedianS)
        assertTrue(h.diskUsedPct in 0..100)
    }

    @Test fun healthMediansNullWhenNothingConfirmed() = TestApp().run { app ->
        seed(app)
        val h = FmxJson.decodeFromString(HealthReport.serializer(), getAs(tokenFor("boss", Role.admin), "/api/admin/health").bodyAsText())
        assertNull(h.phoneToConfirmedMedianS); assertNull(h.uploadMedianS); assertTrue(h.photos.isEmpty())
    }

    @Test fun retryResets() = TestApp().run { app ->
        seed(app)
        photo(app, "p1", "stuck", takenAt = 5, attempts = 6)
        val r = client.post("/api/admin/photos/p1/retry") { bearerAuth(tokenFor("boss", Role.admin)) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        app.ctx.db.read {
            prepareStatement("SELECT status, attempts, last_error FROM photos WHERE id='p1'").use { ps ->
                ps.executeQuery().use { rs -> rs.next(); assertEquals("registered", rs.getString(1)); assertEquals(0, rs.getInt(2)); assertNull(rs.getString(3)) }
            }
        }
        assertEquals(HttpStatusCode.NotFound, client.post("/api/admin/photos/nope/retry") { bearerAuth(tokenFor("boss", Role.admin)) }.status)
    }

    @Test fun activityListsCantShootReason() = TestApp().run { app ->
        seed(app)
        val ann = tokenFor("ann")
        app.clock.nowMs += 1000
        suspend fun send(op: Op) = client.post("/api/ops") {
            bearerAuth(ann); contentType(ContentType.Application.Json)
            setBody(FmxJson.encodeToString(OpsRequest.serializer(), OpsRequest(listOf(op))))
        }
        assertEquals(HttpStatusCode.OK, send(Op.PointCantShoot("o1", "pt-1", CantShootReason.other, "gate locked")).status)
        app.clock.nowMs += 1000
        assertEquals(HttpStatusCode.OK, send(Op.PointCantShoot("o2", "pt-2", CantShootReason.no_access, null)).status)
        val boss = tokenFor("boss", Role.admin)
        val all = FmxJson.decodeFromString(ListSerializer(ActivityItem.serializer()), getAs(boss, "/api/admin/activity").bodyAsText())
        assertEquals(2, all.size)
        assertEquals("POLE 2", all[0].label) // newest first
        assertEquals("no_access", all[0].detail)
        assertEquals("POLE 1", all[1].label)
        assertEquals("other: gate locked", all[1].detail)
        assertEquals("Ann", all[1].user)
        assertEquals("point.cantShoot", all[1].kind)
        val one = FmxJson.decodeFromString(ListSerializer(ActivityItem.serializer()), getAs(boss, "/api/admin/activity?jobId=job-1").bodyAsText())
        assertEquals(listOf("POLE 1"), one.map { it.label })
    }

    @Test fun settingsDefaultsAndBackupDaysMin3() = TestApp().run { app ->
        val boss = tokenFor("boss", Role.admin)
        fun cfg(text: String) = FmxJson.decodeFromString(AppConfig.serializer(), text)
        val low = putSettings(boss, """{"backupDays":2}""")
        assertEquals(HttpStatusCode.BadRequest, low.status)
        assertEquals("""{"error":"Backups must be kept at least 3 days"}""", low.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, putSettings(boss, """{"photoQuality":90}""").status)
        // A rejected request writes nothing, even its valid fields.
        assertEquals(HttpStatusCode.BadRequest, putSettings(boss, """{"photoQuality":95,"backupDays":1}""").status)
        assertEquals(85, cfg(getAs(boss, "/api/config").bodyAsText()).photoQuality)
        val ok = putSettings(boss, """{"photoQuality":95,"backupDays":7,"imageryKey":"k1"}""")
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val c = cfg(getAs(tokenFor("ann"), "/api/config").bodyAsText())
        assertEquals(95, c.photoQuality); assertEquals(7, c.backupDays); assertEquals("k1", c.imageryKey)
        assertEquals(7, com.fieldmaxx.server.delivery.backupDays(app.ctx))
        // A blank key clears it; omitted fields stay.
        putSettings(boss, """{"imageryKey":""}""")
        val c2 = cfg(getAs(boss, "/api/config").bodyAsText())
        assertNull(c2.imageryKey); assertEquals(95, c2.photoQuality)
    }

    @Test fun configDefaultsToEsriKeyless() = TestApp().run { _ ->
        val c = FmxJson.decodeFromString(AppConfig.serializer(), getAs(tokenFor("ann"), "/api/config").bodyAsText())
        assertEquals("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", c.imageryUrl)
        assertEquals("https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}", c.labelsUrl)
        assertNull(c.imageryKey); assertEquals(85, c.photoQuality); assertEquals(3, c.backupDays)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/config").status)
    }

    private fun apk(bytes: ByteArray) = Files.createTempFile("fmx", ".apk").also { Files.write(it, bytes); it.toFile().deleteOnExit() }

    @Test fun publishApkThenLatest() = TestApp().run { app ->
        val tech = tokenFor("ann")
        assertEquals("null", getAs(tech, "/api/app/latest").bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/app/latest").status)
        val v1 = ByteArray(5000) { (it % 251).toByte() }
        val v2 = ByteArray(7000) { (it % 13).toByte() }
        publishApk(app.ctx, apk(v2), 12, "1.2.0")
        publishApk(app.ctx, apk(v1), 9, "0.9.0")
        val latest = FmxJson.decodeFromString(AppRelease.serializer(), getAs(tech, "/api/app/latest").bodyAsText())
        assertEquals(12, latest.versionCode); assertEquals("1.2.0", latest.versionName)
        assertEquals("http://localhost/app/fieldmaxx-1.2.0.apk", latest.url)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(v2).joinToString("") { "%02x".format(it) }, latest.sha256)
        // The file is public (the download page links to it) and served with the APK content type.
        val res = client.get("/app/fieldmaxx-1.2.0.apk")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("application/vnd.android.package-archive", res.headers["Content-Type"])
        assertContentEquals(v2, res.bodyAsBytes())
        assertContentEquals(v1, client.get("/app/fieldmaxx-0.9.0.apk").bodyAsBytes())
        // Only what the database knows is served; paths never come from the URL.
        assertEquals(HttpStatusCode.NotFound, client.get("/app/fieldmaxx-9.9.9.apk").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/app/..%2Ffieldmaxx.db").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/app/fieldmaxx-..%2F..%2Fx.apk").status)
        // Re-publishing a version code is refused.
        assertTrue(runCatching { publishApk(app.ctx, apk(v1), 12, "1.2.1") }.isFailure)
    }

    @Test fun publishApkCli() = TestApp().run { app ->
        val lines = mutableListOf<String>()
        assertTrue(runCli(arrayOf("publish-apk", apk(byteArrayOf(1, 2, 3)).toString(), "4", "0.4.0"), app.ctx, emptyMap(), lines::add))
        assertTrue(lines.single().startsWith("Published 0.4.0"), lines.toString())
        lines.clear()
        runCli(arrayOf("publish-apk", "/nonexistent.apk", "5", "0.5.0"), app.ctx, emptyMap(), lines::add)
        assertTrue(lines.single().startsWith("Could not publish"), lines.toString())
        lines.clear()
        runCli(arrayOf("publish-apk", apk(byteArrayOf(1)).toString(), "6", "../evil"), app.ctx, emptyMap(), lines::add)
        assertTrue(lines.single().startsWith("Could not publish"), lines.toString())
    }

    @Test fun downloadPageHasButtonAndSteps() = TestApp().run { app ->
        val empty = client.get("/")
        assertEquals(HttpStatusCode.OK, empty.status)
        assertTrue(empty.headers["Content-Type"]!!.startsWith("text/html"))
        assertTrue(empty.bodyAsText().contains("No version published yet"))
        assertTrue(!empty.bodyAsText().contains("Download Fieldmaxx for Android"))
        publishApk(app.ctx, apk(byteArrayOf(1, 2, 3)), 3, "0.3.0")
        val html = client.get("/").bodyAsText()
        assertTrue(html.contains("Download Fieldmaxx for Android"))
        assertTrue(html.contains("""href="/app/fieldmaxx-0.3.0.apk""""))
        assertTrue(html.contains("""name="viewport""""))
        assertTrue(html.contains("#0f172a") && html.contains("#2563eb"))
        assertTrue(html.contains("<ol>") && html.split("<li>").size == 4)
        assertTrue(html.contains("Allow install from this source"))
        assertTrue(html.contains("Open the downloaded file"))
        assertTrue(html.contains("sign in", ignoreCase = true))
    }
}
