package com.fieldmaxx.server

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.CantShootReason
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.OpResult
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.PointStatus
import com.fieldmaxx.core.model.PoleData
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.server.jobs.toPoint
import com.fieldmaxx.server.ops.OpsRequest
import com.fieldmaxx.server.ops.OpsResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class OpsTest {
    private val job = "job-1"
    private val pt = "pt-1"

    /** A job with one open POLE 7 point at (39.6, -119.7), created by a user that is not otherwise used. */
    private fun seed(app: TestApp, deletedJob: Boolean = false) = app.ctx.db.tx {
        createStatement().use {
            it.execute("INSERT INTO users(id, username, display_name, password_hash, role, created_at) VALUES ('creator','creator','Creator','x','tech',0)")
            it.execute(
                "INSERT INTO jobs(id, name, status, created_by, created_at, deleted_at) VALUES ('$job','Run','active','creator',0," +
                    (if (deletedJob) "1" else "NULL") + ")",
            )
            it.execute(
                "INSERT INTO points(id, job_id, layer, type, num, label, lat, lon, description, status, folder_candidates_json, created_at, updated_at) " +
                    "VALUES ('$pt','$job','POLE','POLE','7','POLE 7',39.6,-119.7,'','open','[\"POLE/POLE_7\",\"POLE 7\"]',0,0)",
            )
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

    private fun point(app: TestApp, id: String = pt): Point = app.ctx.db.read {
        prepareStatement("SELECT * FROM points WHERE id = ?").use { ps -> ps.setString(1, id); ps.executeQuery().use { it.next(); it.toPoint() } }
    }

    private fun count(app: TestApp, table: String): Int = app.ctx.db.read {
        createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) } }
    }

    private fun confirmPhoto(app: TestApp) = app.ctx.db.tx {
        createStatement().use {
            it.execute(
                "INSERT INTO photos(id, job_id, point_id, user_id, file_name, taken_at, status, created_at) " +
                    "VALUES ('ph1','$job','$pt','creator','POLE_7.jpg',0,'confirmed',0)",
            )
        }
    }

    @Test fun replayReturnsStoredResultNoNewChange() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val op = Op.PointUpdate("op-1", pt, comment = "hello")
        assertEquals(listOf(OpResult("op-1", true)), send(t, op))
        val head = app.ctx.changes.headSeq()
        val events = count(app, "events")
        // Replay, even with a different payload: the stored result wins and nothing is applied again.
        assertEquals(listOf(OpResult("op-1", true)), send(t, Op.PointUpdate("op-1", pt, comment = "other")))
        assertEquals("hello", point(app).comment)
        assertEquals(head, app.ctx.changes.headSeq())
        assertEquals(events, count(app, "events"))
        // A failed op replays as the same failure.
        val bad = send(t, Op.PointClose("op-2", pt, emptyList()))
        assertEquals(bad, send(t, Op.PointClose("op-2", pt, listOf("p"))))
    }

    @Test fun updateSetsOnlyGivenFields() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val pole = PoleData(lineHeightFt = 25.0, anchor = true)
        send(t, Op.PointUpdate("a", pt, pole = pole))
        send(t, Op.PointUpdate("b", pt, comment = "c1"))
        val p = point(app)
        assertEquals(pole, p.pole)
        assertEquals("c1", p.comment)
        assertEquals("ann", app.ctx.db.read { createStatement().use { st -> st.executeQuery("SELECT username FROM users WHERE id = '${p.updatedBy}'").use { it.next(); it.getString(1) } } })
        assertEquals(app.ctx.clock.millis(), p.updatedAt)
    }

    @Test fun concurrentFieldEditsBothPersist() = TestApp(fileDb = true).run { app ->
        seed(app)
        val a = tokenFor("ann")
        val b = tokenFor("bob")
        val pole = PoleData(lineHeightFt = 30.0)
        coroutineScope {
            (0 until 5).map { i ->
                listOf(
                    async { send(a, Op.PointUpdate("a$i", pt, pole = pole)) },
                    async { send(b, Op.PointMove("b$i", pt, 39.7, -119.8)) },
                )
            }.flatten().awaitAll().forEach { r -> assertTrue(r.all { it.ok }, r.toString()) }
        }
        val p = point(app)
        assertEquals(pole, p.pole)
        assertEquals(39.7, p.lat)
        assertEquals(-119.8, p.lon)
        assertEquals(10, count(app, "ops_done"))
    }

    @Test fun firstMoveKeepsOriginal() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        send(t, Op.PointMove("m1", pt, 39.61, -119.71))
        send(t, Op.PointMove("m2", pt, 39.62, -119.72))
        val p = point(app)
        assertEquals(39.62, p.lat)
        assertEquals(-119.72, p.lon)
        assertEquals(39.6, p.origLat)
        assertEquals(-119.7, p.origLon)
    }

    @Test fun closeNeedsPhotoIds() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        assertEquals("needs_photo", send(t, Op.PointClose("c1", pt, emptyList())).single().error)
        assertEquals(PointStatus.open, point(app).status)
        assertTrue(send(t, Op.PointClose("c2", pt, listOf("ph1"))).single().ok)
        assertEquals(PointStatus.done, point(app).status)
    }

    @Test fun cantShootNeedsReasonOtherNeedsText() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        assertEquals("needs_text", send(t, Op.PointCantShoot("x1", pt, CantShootReason.other, " ")).single().error)
        assertEquals(PointStatus.open, point(app).status)
        assertTrue(send(t, Op.PointCantShoot("x2", pt, CantShootReason.other, "gate locked")).single().ok)
        var p = point(app)
        assertEquals(PointStatus.cant_shoot, p.status)
        assertEquals(CantShootReason.other, p.cantReason)
        assertEquals("gate locked", p.cantText)
        assertTrue(send(t, Op.PointCantShoot("x3", pt, CantShootReason.no_access)).single().ok)
        p = point(app)
        assertEquals(CantShootReason.no_access, p.cantReason)
        assertNull(p.cantText)
    }

    @Test fun reopenClearsCant() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        send(t, Op.PointCantShoot("x1", pt, CantShootReason.not_found))
        assertTrue(send(t, Op.PointReopen("r1", pt)).single().ok)
        val p = point(app)
        assertEquals(PointStatus.open, p.status)
        assertNull(p.cantReason)
        assertNull(p.cantText)
    }

    @Test fun addBuildsLabelAndAddedBy() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        assertTrue(send(t, Op.PointAdd("a1", job, "new-1", "POLE", "POLE", "99", 39.5, -119.5)).single().ok)
        assertTrue(send(t, Op.PointAdd("a2", job, "new-2", "Query 2", "Q", "5", 39.5, -119.5)).single().ok)
        val p = point(app, "new-1")
        assertEquals("POLE 99", p.label)
        assertEquals(PointStatus.open, p.status)
        assertEquals(job, p.jobId)
        assertNotNull(p.addedBy)
        assertEquals(p.addedBy, p.updatedBy)
        assertEquals("Query 2-5", point(app, "new-2").label)
        // A different op reusing a point id is refused.
        assertEquals("exists", send(t, Op.PointAdd("a3", job, "new-1", "POLE", "POLE", "1", 0.0, 0.0)).single().error)
        assertEquals("job_not_found", send(t, Op.PointAdd("a4", "nope", "new-3", "POLE", "POLE", "1", 0.0, 0.0)).single().error)
    }

    @Test fun techDeleteAllowedUntilConfirmedThenAdminOnly() = TestApp().run { app ->
        seed(app)
        val tech = tokenFor("ann")
        val admin = tokenFor("root", Role.admin)
        assertTrue(send(tech, Op.PointDelete("d1", pt)).single().ok)
        assertNotNull(point(app).deletedAt)
        assertTrue(send(admin, Op.PointRestore("r1", pt)).single().ok)
        assertNull(point(app).deletedAt)
        confirmPhoto(app)
        assertEquals("photos_delivered", send(tech, Op.PointDelete("d2", pt)).single().error)
        assertNull(point(app).deletedAt)
        assertTrue(send(admin, Op.PointDelete("d3", pt)).single().ok)
        assertNotNull(point(app).deletedAt)
    }

    @Test fun restoreAdminOnly() = TestApp().run { app ->
        seed(app)
        val tech = tokenFor("ann")
        send(tech, Op.PointDelete("d1", pt))
        assertEquals("admin_only", send(tech, Op.PointRestore("r1", pt)).single().error)
        assertNotNull(point(app).deletedAt)
    }

    @Test fun setFolderStoresPathClearsCandidates() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        assertNotNull(point(app).folderCandidates)
        assertTrue(send(t, Op.PointSetFolder("f1", pt, "POLE/POLE_7")).single().ok)
        val p = point(app)
        assertEquals("POLE/POLE_7", p.folderPath)
        assertNull(p.folderCandidates)
    }

    @Test fun opsOnDeletedJob() = TestApp().run { app ->
        seed(app, deletedJob = true)
        val t = tokenFor("ann")
        assertEquals("job_deleted", send(t, Op.PointUpdate("u1", pt, comment = "x")).single().error)
        assertEquals("job_deleted", send(t, Op.PointAdd("a1", job, "n", "POLE", "POLE", "1", 0.0, 0.0)).single().error)
        assertEquals("", point(app).comment)
    }

    @Test fun eachOpOneChangeOneEvent() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        val before = app.ctx.changes.headSeq()
        send(t, Op.PointUpdate("u1", pt, comment = "x"), Op.PointMove("u2", pt, 1.0, 2.0), Op.PointReopen("u3", pt))
        assertEquals(before + 3, app.ctx.changes.headSeq())
        assertEquals(3, count(app, "events"))
        val (changes, _) = app.ctx.changes.since(before)
        assertEquals(listOf("point", "point", "point"), changes.map { it.entity })
        assertEquals("x", changes.first().point?.comment)
        // A failing op in the batch records nothing and doesn't undo earlier ones.
        send(t, Op.PointUpdate("u4", pt, comment = "y"), Op.PointClose("u5", pt, emptyList()))
        assertEquals(before + 4, app.ctx.changes.headSeq())
        assertEquals(4, count(app, "events"))
        assertEquals("y", point(app).comment)
    }

    @Test fun photoOpsUnsupportedForNow() = TestApp().run { app ->
        seed(app)
        val t = tokenFor("ann")
        assertEquals("unsupported", send(t, Op.PhotoRemove("p1", "ph")).single().error)
    }
}
