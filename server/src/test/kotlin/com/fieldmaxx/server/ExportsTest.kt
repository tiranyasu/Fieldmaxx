package com.fieldmaxx.server

import com.fieldmaxx.core.model.FinishResult
import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.server.exports.POLE_COLUMNS
import com.fieldmaxx.server.jobs.path
import com.fieldmaxx.server.exports.poleDataCsv
import com.fieldmaxx.server.exports.poleDataRows
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlinx.coroutines.async
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.poi.xssf.usermodel.XSSFWorkbook

class ExportsTest {
    private val job = "job-1"
    private val link = "https://example.test/field-notes"

    private fun seedJob(app: TestApp, link: String? = this.link) = app.ctx.db.tx {
        createStatement().use {
            it.execute("INSERT INTO users(id, username, display_name, password_hash, role, created_at) VALUES ('u1','creator','Creator','x','tech',0)")
            it.execute(
                "INSERT INTO jobs(id, name, status, created_by, created_at, field_notes_url) VALUES ('$job','Run 5','active','u1',0," +
                    (if (link == null) "NULL" else "'$link'") + ")",
            )
        }
    }

    private fun point(
        app: TestApp, id: String, num: String, type: String = "POLE", layer: String = type, status: String = "open",
        lat: Double = 39.6, lon: Double = -119.7, origLat: Double? = null, origLon: Double? = null,
        pole: String? = null, comment: String = "", folder: String? = null, deleted: Boolean = false,
    ) = app.ctx.db.tx {
        prepareStatement(
            "INSERT INTO points(id, job_id, layer, type, num, label, lat, lon, orig_lat, orig_lon, description, status, pole_json, comment, " +
                "folder_path, created_at, updated_at, deleted_at) VALUES (?,?,?,?,?,?,?,?,?,?,'',?,?,?,?,0,0,?)",
        ).use {
            it.setString(1, id); it.setString(2, job); it.setString(3, layer); it.setString(4, type); it.setString(5, num)
            it.setString(6, "$type $num"); it.setDouble(7, lat); it.setDouble(8, lon)
            it.setObject(9, origLat); it.setObject(10, origLon)
            it.setString(11, status); it.setString(12, pole); it.setString(13, comment); it.setString(14, folder)
            it.setObject(15, if (deleted) 5L else null)
            it.executeUpdate()
        }
    }

    private fun photo(app: TestApp, id: String, pointId: String, status: String, name: String = "$id.jpg", backup: ByteArray? = null) {
        var rel: String? = null
        if (backup != null) {
            rel = "backups/$id.jpg"
            val f = app.ctx.path(rel)
            Files.createDirectories(f.parent)
            Files.write(f, backup)
        }
        app.ctx.db.tx {
            prepareStatement(
                "INSERT INTO photos(id, job_id, point_id, user_id, file_name, taken_at, status, backup_path, backup_received_at, created_at) " +
                    "VALUES (?,?,?,'u1',?,0,?,?,?,0)",
            ).use {
                it.setString(1, id); it.setString(2, job); it.setString(3, pointId); it.setString(4, name); it.setString(5, status)
                it.setString(6, rel); it.setObject(7, if (rel != null) 1L else null)
                it.executeUpdate()
            }
        }
    }

    private fun event(app: TestApp, pointId: String, kind: String, at: Long) = app.ctx.db.tx {
        prepareStatement("INSERT INTO events(job_id, user_id, kind, data_json, at) VALUES (?,(SELECT id FROM users WHERE username = 'sam'),?,?,?)").use {
            it.setString(1, job); it.setString(2, kind)
            it.setString(3, """{"opId":"o$at","pointId":"$pointId"}"""); it.setLong(4, at); it.executeUpdate()
        }
    }

    private val header = "Point,Original lat,Original lon,Moved lat,Moved lon,Line height (ft),Anchor,Self-supporting/Lashed,Tags,Comment,Photos,Tech,Closed at"

    @Test fun csvHeaderExact() = TestApp().run { app ->
        seedJob(app)
        assertEquals(POLE_COLUMNS.joinToString(","), header)
        assertEquals(header, poleDataCsv(poleDataRows(app.ctx, job)).lines().first())
        val token = tokenFor("sam")
        val res = client.get("/api/jobs/$job/pole-data.csv") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(header, res.bodyAsText().lines().first())
    }

    @Test fun movedOnlyWhenMovedTagsSemicolonAnchorYesNo() = TestApp().run { app ->
        seedJob(app)
        tokenFor("sam")
        point(app, "a", "1", status = "done", lat = 39.5, lon = -119.5,
            pole = """{"lineHeightFt":35.0,"anchor":true,"attachment":"lashed","tags":["No fiber","Grounding"]}""", comment = "leaning, \"old\"")
        point(app, "b", "2", status = "done", lat = 39.7, lon = -119.8, origLat = 39.6, origLon = -119.7,
            pole = """{"lineHeightFt":40.5,"anchor":false,"attachment":"self_supporting","tags":[]}""")
        point(app, "gone", "3", deleted = true)
        point(app, "ped", "4", type = "PED")
        photo(app, "p1", "a", "confirmed"); photo(app, "p2", "a", "registered"); photo(app, "p3", "a", "removed")
        event(app, "a", "point.close", 1_000)
        event(app, "a", "point.close", 2_000)
        val rows = poleDataRows(app.ctx, job)
        assertEquals(2, rows.size)
        assertEquals(
            listOf("POLE 1", "39.5", "-119.5", "", "", "35", "Yes", "Lashed", "No fiber; Grounding", "leaning, \"old\"", "2", "Sam"),
            rows[0].dropLast(1),
        )
        assertEquals("1970-01-01T00:00:02", rows[0].last()) // the later of the two close events
        assertEquals(
            listOf("POLE 2", "39.6", "-119.7", "39.7", "-119.8", "40.5", "No", "Self-supporting", "", "", "0", "", ""),
            rows[1],
        )
        assertTrue(poleDataCsv(rows).contains("\"leaning, \"\"old\"\"\""))
    }

    @Test fun rowsSortedNumericThenSuffix() = TestApp().run { app ->
        seedJob(app)
        listOf("14B", "10", "2", "14", "1").forEachIndexed { i, n -> point(app, "p$i", n) }
        assertEquals(listOf("POLE 1", "POLE 2", "POLE 10", "POLE 14", "POLE 14B"), poleDataRows(app.ctx, job).map { it[0] })
    }

    @Test fun xlsxReadableWithSameHeader() = TestApp().run { app ->
        seedJob(app)
        point(app, "a", "3", pole = """{"lineHeightFt":30.0,"anchor":true,"tags":["N/A"]}""")
        val token = tokenFor("sam")
        val res = client.get("/api/jobs/$job/pole-data.xlsx") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status)
        XSSFWorkbook(ByteArrayInputStream(res.bodyAsBytes())).use { wb ->
            val sheet = wb.getSheetAt(0)
            assertEquals(POLE_COLUMNS, (0 until POLE_COLUMNS.size).map { sheet.getRow(0).getCell(it).stringCellValue })
            assertEquals("POLE 3", sheet.getRow(1).getCell(0).stringCellValue)
            assertEquals("Yes", sheet.getRow(1).getCell(6).stringCellValue)
        }
    }

    @Test fun zipUsesFolderPathOrDefault() = TestApp().run { app ->
        seedJob(app)
        point(app, "a", "1", folder = "POLE/Pole 1 old")
        point(app, "b", "2")
        point(app, "q", "43", type = "POLE", layer = "QUERY 1")
        point(app, "c", "3")
        photo(app, "p1", "a", "confirmed", "POLE_1_a.jpg", byteArrayOf(1, 2, 3))
        photo(app, "p2", "b", "registered", "POLE_2_b.jpg", byteArrayOf(4))
        photo(app, "p3", "q", "confirmed", "Q_c.jpg", byteArrayOf(5))
        photo(app, "p4", "c", "registered", "no_backup.jpg")
        photo(app, "p5", "c", "removed", "removed.jpg", byteArrayOf(6))
        val admin = tokenFor("boss", Role.admin)
        val tech = tokenFor("tech1")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/jobs/$job/photos.zip") { bearerAuth(tech) }.status)
        val res = client.get("/api/jobs/$job/photos.zip") { bearerAuth(admin) }
        assertEquals(HttpStatusCode.OK, res.status)
        val entries = mutableMapOf<String, List<Byte>>()
        ZipInputStream(ByteArrayInputStream(res.bodyAsBytes())).use { z ->
            while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes().toList() }
        }
        assertEquals(
            mapOf(
                "POLE/Pole 1 old/POLE_1_a.jpg" to listOf<Byte>(1, 2, 3),
                "POLE/POLE_2/POLE_2_b.jpg" to listOf<Byte>(4),
                "QUERY 1/POLE_43/Q_c.jpg" to listOf<Byte>(5),
            ),
            entries,
        )
    }

    @Test fun finishBlockedCounts() = TestApp().run { app ->
        seedJob(app)
        repeat(7) { point(app, "o$it", "$it") }
        point(app, "d", "50", status = "done")
        point(app, "gone", "51", deleted = true)
        repeat(3) { photo(app, "x$it", "d", if (it == 0) "stuck" else "registered") }
        photo(app, "ok", "d", "confirmed"); photo(app, "rm", "d", "removed")
        val token = tokenFor("sam")
        val res = client.post("/api/jobs/$job/finish") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(FinishResult(false, 7, 3), FmxJson.decodeFromString(FinishResult.serializer(), res.bodyAsText()))
        assertTrue(app.destination.files.isEmpty())
        assertEquals("active", jobStatus(app))
    }

    private fun jobStatus(app: TestApp) = app.ctx.db.read {
        createStatement().use { st -> st.executeQuery("SELECT status FROM jobs WHERE id = '$job'").use { it.next(); it.getString(1) } }
    }

    @Test fun finishUploadsXlsxToRootNextFreeNameAndFinishes() = TestApp().run { app ->
        seedJob(app)
        point(app, "d", "1", status = "done")
        photo(app, "ok", "d", "confirmed")
        app.destination.files["Run 5_pole_data.xlsx"] = byteArrayOf(9)
        val token = tokenFor("sam")
        val before = app.ctx.changes.headSeq()
        val res = client.post("/api/jobs/$job/finish") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(FinishResult(true), FmxJson.decodeFromString(FinishResult.serializer(), res.bodyAsText()))
        assertEquals(setOf("Run 5_pole_data.xlsx", "Run 5_pole_data_2.xlsx"), app.destination.files.keys)
        assertEquals(1, app.destination.files.getValue("Run 5_pole_data.xlsx").size) // never overwritten
        XSSFWorkbook(ByteArrayInputStream(app.destination.files.getValue("Run 5_pole_data_2.xlsx"))).use {
            assertEquals("POLE 1", it.getSheetAt(0).getRow(1).getCell(0).stringCellValue)
        }
        assertEquals("finished", jobStatus(app))
        assertEquals(before + 1, app.ctx.changes.headSeq())
    }

    @Test fun finishNeedsLinkAndReportsDestinationFailure() = TestApp().run { app ->
        seedJob(app, link = null)
        val token = tokenFor("sam")
        val res = client.post("/api/jobs/$job/finish") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("""{"error":"Add the Field Notes link first"}""", res.bodyAsText())
    }

    @Test fun finishDestinationFailureIs502() = TestApp().run { app ->
        seedJob(app)
        app.destination.failNext(1, com.fieldmaxx.sharepoint.DestinationErrorCode.FORBIDDEN)
        val token = tokenFor("sam")
        val res = client.post("/api/jobs/$job/finish") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadGateway, res.status)
        assertTrue(res.bodyAsText().contains("injected failure"), res.bodyAsText())
        assertEquals("active", jobStatus(app))
    }

    @Test fun deletedJobIsNotFoundForTechs() = TestApp().run { app ->
        seedJob(app)
        app.ctx.db.tx { createStatement().use { it.execute("UPDATE jobs SET deleted_at = 5") } }
        val token = tokenFor("sam")
        assertEquals(HttpStatusCode.NotFound, client.get("/api/jobs/$job/pole-data.csv") { bearerAuth(token) }.status)
    }

    @Test fun finishRechecksAtCommit() {
        val fake = com.fieldmaxx.sharepoint.FakeDestination()
        lateinit var app: TestApp
        var reopened = false
        // The upload happens between the first count and the commit: a tech reopens a point meanwhile.
        val reopening = object : com.fieldmaxx.sharepoint.PhotoDestination by fake {
            override fun upload(relPath: String, bytes: ByteArray) {
                fake.upload(relPath, bytes)
                if (!reopened) {
                    reopened = true
                    app.ctx.db.tx { createStatement().use { it.execute("UPDATE points SET status = 'open' WHERE id = 'd'") } }
                }
            }
        }
        app = TestApp(destinations = { reopening })
        app.run {
            seedJob(app); point(app, "d", "1", status = "done")
            val token = tokenFor("sam")
            val res = client.post("/api/jobs/$job/finish") { bearerAuth(token) }
            assertEquals(FinishResult(false, 1, 0), FmxJson.decodeFromString(FinishResult.serializer(), res.bodyAsText()))
            assertEquals("active", jobStatus(app))
            assertEquals(1, fake.files.size) // stale snapshot stays
            app.ctx.db.tx { createStatement().use { it.execute("UPDATE points SET status = 'done'") } }
            assertEquals(FinishResult(true), FmxJson.decodeFromString(FinishResult.serializer(), client.post("/api/jobs/$job/finish") { bearerAuth(token) }.bodyAsText()))
            assertEquals(setOf("Run 5_pole_data.xlsx", "Run 5_pole_data_2.xlsx"), fake.files.keys) // never overwritten
        }
    }

    @Test fun finishingTwiceIs409AndUploadsOnce() = TestApp(fileDb = true).run { app ->
        seedJob(app); point(app, "d", "1", status = "done")
        val token = tokenFor("sam")
        val before = app.ctx.changes.headSeq()
        val codes = kotlinx.coroutines.coroutineScope {
            val client = client
            (1..2).map { async(kotlinx.coroutines.Dispatchers.IO) { client.post("/api/jobs/$job/finish") { bearerAuth(token) }.status } }
                .map { it.await() }
        }
        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.Conflict), codes.sortedBy { it.value })
        assertEquals(1, app.destination.files.size)
        assertEquals(before + 1, app.ctx.changes.headSeq())
        val again = client.post("/api/jobs/$job/finish") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("""{"error":"This job is already finished"}""", again.bodyAsText())
        assertEquals(1, app.destination.files.size)
    }

    @Test fun finishGivesUpAfter100NameTries() {
        val fake = com.fieldmaxx.sharepoint.FakeDestination()
        val taken = object : com.fieldmaxx.sharepoint.PhotoDestination by fake {
            override fun upload(relPath: String, bytes: ByteArray) =
                throw com.fieldmaxx.sharepoint.DestinationException(com.fieldmaxx.sharepoint.DestinationErrorCode.EXISTS, "exists")
        }
        TestApp(destinations = { taken }).run { app ->
            seedJob(app)
            val token = tokenFor("sam")
            val res = client.post("/api/jobs/$job/finish") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadGateway, res.status)
            assertEquals("""{"error":"Too many existing pole data exports in Field Notes"}""", res.bodyAsText())
            assertEquals("active", jobStatus(app))
            assertEquals(100, fake.calls.count { it.startsWith("fileInfo") })
        }
    }

    @Test fun zipNeutralisesHostilePathsAndNames() = TestApp().run { app ->
        seedJob(app)
        point(app, "a", "1", folder = "../../x/..\\..\\y//z/./")
        point(app, "b", "2", folder = "../..")
        photo(app, "p1", "a", "confirmed", "../../evil/a.jpg", byteArrayOf(1))
        photo(app, "p2", "b", "confirmed", "b.jpg", byteArrayOf(2))
        val admin = tokenFor("boss", Role.admin)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(client.get("/api/jobs/$job/photos.zip") { bearerAuth(admin) }.bodyAsBytes())).use { z ->
            while (true) { names += (z.nextEntry ?: break).name }
        }
        assertEquals(setOf("x/y/z/evil_a.jpg", "POLE/POLE_2/b.jpg"), names.toSet())
        assertTrue(names.none { ".." in it.split('/') || it.startsWith("/") || "//" in it })
    }

    @Test fun csvDefusesFormulasXlsxKeepsTextCells() = TestApp().run { app ->
        seedJob(app)
        point(app, "a", "1", status = "done", lat = -39.5, comment = "=1+1",
            pole = """{"lineHeightFt":30.0,"anchor":false,"tags":["@x","-y"]}""")
        val token = tokenFor("sam")
        val csv = client.get("/api/jobs/$job/pole-data.csv") { bearerAuth(token) }.bodyAsText().lines()[1]
        assertTrue(csv.startsWith("POLE 1,-39.5,"), csv) // numbers untouched
        assertTrue(csv.contains(",'@x; -y,'=1+1,"), csv)
        val x = client.get("/api/jobs/$job/pole-data.xlsx") { bearerAuth(token) }.bodyAsBytes()
        XSSFWorkbook(ByteArrayInputStream(x)).use {
            val c = it.getSheetAt(0).getRow(1).getCell(9)
            assertEquals(org.apache.poi.ss.usermodel.CellType.STRING, c.cellType)
            assertEquals("=1+1", c.stringCellValue)
        }
    }
}
