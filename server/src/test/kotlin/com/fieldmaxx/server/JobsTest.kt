package com.fieldmaxx.server

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.CreateJobInput
import com.fieldmaxx.core.model.ImportFlag
import com.fieldmaxx.core.model.ImportPreview
import com.fieldmaxx.core.model.JobImportInput
import com.fieldmaxx.core.model.JobListItem
import com.fieldmaxx.core.model.JobSnapshot
import com.fieldmaxx.core.model.LinkCheck
import com.fieldmaxx.core.model.PointStatus
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.importer.AERIAL
import com.fieldmaxx.importer.PdfImageSpec
import com.fieldmaxx.importer.PdfPageSpec
import com.fieldmaxx.importer.makePdf
import com.fieldmaxx.importer.solidPng
import com.fieldmaxx.server.jobs.CreateJobResponse
import com.fieldmaxx.server.jobs.AddImportResponse
import com.fieldmaxx.server.jobs.purgeDeletedJobs
import com.fieldmaxx.server.jobs.sweepImports
import com.fieldmaxx.sharepoint.DestinationErrorCode
import com.fieldmaxx.sharepoint.DestinationException
import com.fieldmaxx.sharepoint.FakeDestination
import com.fieldmaxx.sharepoint.PhotoDestination
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DAY = 24L * 3600 * 1000

class JobsTest {
    private val pdf = makePdf(listOf(PdfPageSpec(freeText = listOf(AERIAL), images = listOf(PdfImageSpec(solidPng(), 50f, 500f, 80f, 80f)))))

    private suspend fun ApplicationTestBuilder.upload(token: String, name: String = "Aerial Path.pdf", bytes: ByteArray = pdf): HttpResponse =
        client.post("/api/imports") {
            bearerAuth(token)
            setBody(MultiPartFormDataContent(formData {
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
                })
            }))
        }

    private suspend fun ApplicationTestBuilder.preview(token: String): ImportPreview {
        val res = upload(token)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return FmxJson.decodeFromString(ImportPreview.serializer(), res.bodyAsText())
    }

    private fun input(p: ImportPreview) = JobImportInput(p.importId, p.layerSuggestion, p.notes, p.points)

    private suspend fun ApplicationTestBuilder.create(token: String, p: ImportPreview, url: String? = null, name: String = "Elko run"): CreateJobResponse {
        val res = client.post("/api/jobs") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(FmxJson.encodeToString(CreateJobInput.serializer(), CreateJobInput(name, url, listOf(input(p)))))
        }
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        return FmxJson.decodeFromString(CreateJobResponse.serializer(), res.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.snapshot(token: String, jobId: String): JobSnapshot {
        val res = client.get("/api/jobs/$jobId/snapshot") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return FmxJson.decodeFromString(JobSnapshot.serializer(), res.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.list(token: String, query: String = ""): List<JobListItem> =
        FmxJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(JobListItem.serializer()),
            client.get("/api/jobs$query") { bearerAuth(token) }.bodyAsText(),
        )

    @Test fun uploadReturnsPreviewAndRefImages() = TestApp().run {
        val t = tokenFor("ann")
        val p = preview(t)
        assertEquals(7, p.points.size)
        assertEquals("Aerial Path.pdf", p.fileName)
        assertTrue(p.refImages.isNotEmpty())
        p.refImages.forEach { ref ->
            val img = client.get("/api/imports/${p.importId}/ref/${ref.id}") { bearerAuth(t) }
            assertEquals(HttpStatusCode.OK, img.status)
            assertTrue(img.bodyAsBytes().isNotEmpty())
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/imports/${p.importId}/ref/${p.refImages[0].id}").status)
    }

    @Test fun xlsxRejected415() = TestApp().run {
        val res = upload(tokenFor("ann"), "points.xlsx", byteArrayOf(1, 2, 3))
        assertEquals(HttpStatusCode.UnsupportedMediaType, res.status)
        assertTrue(res.bodyAsText().contains("Only PDF and Word (.docx) files"))
    }

    @Test fun unreadablePdfIs400() = TestApp().run {
        assertEquals(HttpStatusCode.BadRequest, upload(tokenFor("ann"), "bad.pdf", byteArrayOf(1, 2, 3)).status)
    }

    @Test fun createJobFromPreview() = TestApp().run { app ->
        val t = tokenFor("ann")
        val p = preview(t)
        val r = create(t, p, "https://example.com/notes")
        assertEquals("Elko run", r.job.name)
        assertEquals("https://example.com/notes", r.job.fieldNotesUrl)
        assertEquals(emptyList(), r.skippedDuplicates)
        val s = snapshot(t, r.job.id)
        assertEquals(7, s.points.size)
        assertTrue(s.points.all { it.status == PointStatus.open && it.jobId == r.job.id })
        assertEquals(p.refImages.map { it.id }.toSet(), s.refImages.map { it.id }.toSet())
        // the reference images now belong to the job and are served from it
        val img = client.get("/api/jobs/${r.job.id}/ref/${s.refImages[0].id}") { bearerAuth(t) }
        assertEquals(HttpStatusCode.OK, img.status)
        assertEquals(1, s.files.size)
    }

    @Test fun draftWithoutCoordinates400ListsLabels() = TestApp().run {
        val t = tokenFor("ann")
        val p = preview(t)
        val broken = p.points.mapIndexed { i, d -> if (i < 2) d.copy(lat = null, lon = null) else d }
        val res = client.post("/api/jobs") {
            bearerAuth(t); contentType(ContentType.Application.Json)
            setBody(FmxJson.encodeToString(CreateJobInput.serializer(), CreateJobInput("X", null, listOf(input(p).copy(points = broken)))))
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains(p.points[0].label) && body.contains(p.points[1].label), body)
        assertEquals(0, list(t).size)
    }

    @Test fun everyUserSeesEveryActiveJob() = TestApp().run {
        val ann = tokenFor("ann"); val bob = tokenFor("bob")
        val a = create(ann, preview(ann), name = "A").job
        val b = create(bob, preview(bob), name = "B").job
        for (t in listOf(ann, bob)) {
            val jobs = list(t)
            assertEquals(setOf(a.id, b.id), jobs.map { it.job.id }.toSet())
            assertTrue(jobs.all { it.pointsTotal == 7 && it.pointsDone == 0 })
        }
    }

    @Test fun snapshotHasFilesAndCrew() = TestApp().run {
        val ann = tokenFor("ann")
        val j = create(ann, preview(ann)).job
        val s = snapshot(ann, j.id)
        assertEquals("Aerial Path.pdf", s.files.single().name)
        assertEquals(listOf("Ann"), s.crew.map { it.displayName })
        val file = client.get("/api/jobs/${j.id}/files/${s.files[0].id}") { bearerAuth(ann) }
        assertEquals(HttpStatusCode.OK, file.status)
        assertTrue(file.bodyAsBytes().contentEquals(pdf))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/jobs/${j.id}/files/nope") { bearerAuth(ann) }.status)
    }

    @Test fun pointsDoneCountsDoneAndCantShootAndIgnoresDeleted() = TestApp().run { app ->
        val ann = tokenFor("ann")
        val j = create(ann, preview(ann)).job
        val ids = snapshot(ann, j.id).points.map { it.id }
        app.ctx.db.tx {
            createStatement().use {
                it.executeUpdate("UPDATE points SET status='done' WHERE id='${ids[0]}'")
                it.executeUpdate("UPDATE points SET status='cant_shoot' WHERE id='${ids[1]}'")
                it.executeUpdate("UPDATE points SET deleted_at=1 WHERE id='${ids[2]}'")
            }
        }
        val item = list(ann).single()
        assertEquals(6, item.pointsTotal)
        assertEquals(2, item.pointsDone)
    }

    @Test fun newLinkClearsProblemAndKicks() = TestApp().run { app ->
        var kicks = 0
        app.ctx.delivery = object : DeliveryKick { override fun kick() { kicks++ } }
        val ann = tokenFor("ann")
        val j = create(ann, preview(ann), "https://old").job
        app.ctx.db.tx { createStatement().use { it.executeUpdate("UPDATE jobs SET delivery_problem='bad link' WHERE id='${j.id}'") } }
        val renamed = client.patch("/api/jobs/${j.id}") {
            bearerAuth(ann); contentType(ContentType.Application.Json); setBody("""{"name":"Renamed"}""")
        }
        assertEquals(HttpStatusCode.OK, renamed.status)
        assertEquals(0, kicks)
        assertEquals("bad link", snapshot(ann, j.id).job.deliveryProblem)
        val res = client.patch("/api/jobs/${j.id}") {
            bearerAuth(ann); contentType(ContentType.Application.Json); setBody("""{"fieldNotesUrl":"https://new"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val job = snapshot(ann, j.id).job
        assertEquals("https://new", job.fieldNotesUrl)
        assertEquals("Renamed", job.name)
        assertEquals(null, job.deliveryProblem)
        assertEquals(1, kicks)
    }

    @Test fun creatorDeletesOtherTech403AdminMay() = TestApp().run {
        val ann = tokenFor("ann"); val bob = tokenFor("bob"); val root = tokenFor("root", Role.admin)
        val j1 = create(ann, preview(ann), name = "one").job
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/jobs/${j1.id}") { bearerAuth(bob) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/jobs/${j1.id}") { bearerAuth(ann) }.status)
        val j2 = create(ann, preview(ann), name = "two").job
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/jobs/${j2.id}") { bearerAuth(root) }.status)
        assertEquals(0, list(bob).size)
        assertEquals(0, list(root).size)
        assertEquals(setOf(j1.id, j2.id), list(root, "?include=deleted").map { it.job.id }.toSet())
        assertEquals(0, list(bob, "?include=deleted").size)
    }

    @Test fun adminRestoresWithin30DaysThen410() = TestApp().run { app ->
        val ann = tokenFor("ann"); val root = tokenFor("root", Role.admin)
        val a = create(ann, preview(ann), name = "a").job
        val b = create(ann, preview(ann), name = "b").job
        client.delete("/api/jobs/${a.id}") { bearerAuth(ann) }
        client.delete("/api/jobs/${b.id}") { bearerAuth(ann) }
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/jobs/${a.id}/restore") { bearerAuth(ann) }.status)
        app.clock.nowMs += 29 * DAY
        assertEquals(HttpStatusCode.NoContent, client.post("/api/jobs/${a.id}/restore") { bearerAuth(root) }.status)
        assertEquals(listOf(a.id), list(ann).map { it.job.id })
        app.clock.nowMs += 2 * DAY
        assertEquals(HttpStatusCode.Gone, client.post("/api/jobs/${b.id}/restore") { bearerAuth(root) }.status)
    }

    @Test fun purgeAfter30Days() = TestApp().run { app ->
        val ann = tokenFor("ann")
        val gone = create(ann, preview(ann), name = "gone").job
        val kept = create(ann, preview(ann), name = "kept").job
        client.delete("/api/jobs/${gone.id}") { bearerAuth(ann) }
        val dir = app.ctx.config.dataDir.resolve("jobs/${gone.id}")
        assertTrue(dir.toFile().exists())
        assertEquals(0, purgeDeletedJobs(app.ctx))
        app.clock.nowMs += 31 * DAY
        assertEquals(1, purgeDeletedJobs(app.ctx))
        assertEquals(0, purgeDeletedJobs(app.ctx))
        assertTrue(!dir.toFile().exists())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/jobs/${gone.id}/snapshot") { bearerAuth(ann) }.status)
        assertEquals(7, snapshot(ann, kept.id).points.size)
        val left = app.ctx.db.read {
            createStatement().use { it.executeQuery("SELECT COUNT(*) FROM points WHERE job_id='${gone.id}'").use { r -> r.next(); r.getInt(1) } }
        }
        assertEquals(0, left)
    }

    @Test fun duplicateImportSkipsIdenticalPoints() = TestApp().run {
        val t = tokenFor("ann")
        val p = preview(t)
        val j = create(t, p).job
        val res = client.post("/api/jobs/${j.id}/imports") {
            bearerAuth(t); contentType(ContentType.Application.Json)
            setBody(FmxJson.encodeToString(JobImportInput.serializer(), input(p)))
        }
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        val r = FmxJson.decodeFromString(AddImportResponse.serializer(), res.bodyAsText())
        assertEquals(0, r.points.size)
        assertEquals(7, r.skippedDuplicates.size)
        assertEquals(7, snapshot(t, j.id).points.size)
        // a preview repeated inside one create is also not doubled
        val twice = client.post("/api/jobs") {
            bearerAuth(t); contentType(ContentType.Application.Json)
            setBody(FmxJson.encodeToString(CreateJobInput.serializer(), CreateJobInput("Twice", null, listOf(input(p), input(p)))))
        }
        val tr = FmxJson.decodeFromString(CreateJobResponse.serializer(), twice.bodyAsText())
        assertEquals(7, tr.skippedDuplicates.size)
        assertEquals(7, snapshot(t, tr.job.id).points.size)
    }

    @Test fun sameLabelDifferentCoordsFlagged() = TestApp().run {
        val t = tokenFor("ann")
        val p = preview(t)
        val j = create(t, p).job
        val moved = p.points.take(1).map { it.copy(lat = it.lat!! + 0.01) }
        val res = client.post("/api/jobs/${j.id}/imports") {
            bearerAuth(t); contentType(ContentType.Application.Json)
            setBody(FmxJson.encodeToString(JobImportInput.serializer(), input(p).copy(points = moved)))
        }
        val r = FmxJson.decodeFromString(AddImportResponse.serializer(), res.bodyAsText())
        assertEquals(emptyList(), r.skippedDuplicates)
        assertEquals(1, r.points.size)
        assertTrue(ImportFlag.duplicate_label in r.points[0].flags)
        assertEquals(8, snapshot(t, j.id).points.size)
    }

    @Test fun linkCheckOkReturnsRootName() = TestApp().run {
        val t = tokenFor("ann")
        val res = client.post("/api/links/check") {
            bearerAuth(t); contentType(ContentType.Application.Json); setBody("""{"url":"https://example.com/x"}""")
        }
        assertEquals(LinkCheck(ok = true, rootName = "Field Notes"), FmxJson.decodeFromString(LinkCheck.serializer(), res.bodyAsText()))
    }

    @Test fun linkCheckReportsFailureReason() {
        val broken = object : PhotoDestination by FakeDestination() {
            override fun rootName(): String = throw DestinationException(DestinationErrorCode.REQUIRES_SIGN_IN, "This link needs a Microsoft sign-in")
        }
        TestApp(destinations = { _ -> broken }).run {
            val res = client.post("/api/links/check") {
                bearerAuth(tokenFor("ann")); contentType(ContentType.Application.Json); setBody("""{"url":"https://example.com/x"}""")
            }
            assertEquals(
                LinkCheck(ok = false, error = "This link needs a Microsoft sign-in"),
                FmxJson.decodeFromString(LinkCheck.serializer(), res.bodyAsText()),
            )
        }
    }

    @Test fun jobCreationRecordsChange() = TestApp().run { app ->
        val t = tokenFor("ann")
        val j = create(t, preview(t)).job
        val (changes, _) = app.ctx.changes.since(0)
        val jobChange = changes.single { it.entity == "job" }
        assertEquals(j.id, jobChange.jobId)
        assertEquals("Elko run", jobChange.job?.name)
        assertEquals(7, changes.count { it.entity == "point" && it.point != null && it.jobId == j.id })
    }

    @Test fun staleImportsAreSwept() = TestApp().run { app ->
        val t = tokenFor("ann")
        val p = preview(t)
        val dir = app.ctx.config.dataDir.resolve("imports/${p.importId}")
        assertTrue(dir.toFile().exists())
        assertEquals(0, sweepImports(app.ctx))
        app.clock.nowMs += 25 * 3600 * 1000
        assertEquals(1, sweepImports(app.ctx))
        assertTrue(!dir.toFile().exists())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/imports/${p.importId}/ref/${p.refImages[0].id}") { bearerAuth(t) }.status)
    }

    @Test fun deletedJobIsHiddenFromTechsButNotAdmins() = TestApp().run {
        val ann = tokenFor("ann"); val bob = tokenFor("bob"); val root = tokenFor("root", Role.admin)
        val p = preview(ann)
        val j = create(ann, p).job
        val s = snapshot(ann, j.id)
        val urls = listOf("/api/jobs/${j.id}/snapshot", "/api/jobs/${j.id}/ref/${s.refImages[0].id}",
            "/api/jobs/${j.id}/files/${s.files[0].id}", "/api/imports/${p.importId}/ref/${s.refImages[0].id}")
        for (u in urls) assertEquals(HttpStatusCode.OK, client.get(u) { bearerAuth(bob) }.status, u)
        client.delete("/api/jobs/${j.id}") { bearerAuth(ann) }
        for (u in urls) {
            assertEquals(HttpStatusCode.NotFound, client.get(u) { bearerAuth(bob) }.status, u)
            assertEquals(HttpStatusCode.NotFound, client.get(u) { bearerAuth(ann) }.status, u)
            assertEquals(HttpStatusCode.OK, client.get(u) { bearerAuth(root) }.status, u)
        }
    }

    @Test fun uploadOver50MbIs413() = TestApp().run {
        val res = upload(tokenFor("ann"), "big.pdf", ByteArray(50 * 1024 * 1024 + 1))
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
        assertTrue(res.bodyAsText().contains("File is too large (max 50 MB)"))
    }

    @Test fun pdfOver200PagesIs400() = TestApp().run {
        val res = upload(tokenFor("ann"), "long.pdf", makePdf(List(201) { PdfPageSpec() }))
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("PDF has too many pages (max 200)"))
    }
}
