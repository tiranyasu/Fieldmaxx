package com.fieldmaxx.app.map

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.fieldmaxx.app.sync.SyncStatus
import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.model.CrewPosition
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.PointStatus
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.shredzone.commons.suncalc.SunTimes

private const val MIN = 60_000L

/** A point of the job "j1"; [type] doubles as its layer, as the importer reads pole and other files. */
internal fun mapPoint(
    id: String,
    type: String = "POLE",
    num: String = id.filter { it.isDigit() },
    status: PointStatus = PointStatus.open,
    lat: Double = 39.636130,
    lon: Double = -119.691104,
    description: String = "",
    label: String = "$type $num",
) = Point(
    id = id, jobId = "j1", layer = type, type = type, num = num, label = label, lat = lat, lon = lon, origLat = null, origLon = null,
    description = description, status = status, cantReason = null, cantText = null, pole = null, comment = "", folderPath = null,
    folderCandidates = null, refImageIds = emptyList(), flags = emptyList(), addedBy = null, updatedAt = 0, updatedBy = null, deletedAt = null,
)

private fun crew(minutesAgo: Long, paused: Boolean = false, now: Long = 100 * 60 * MIN) =
    CrewPosition("u2", "Bob Tech", 39.6, -119.7, 5.0, at = now - minutesAgo * MIN, paused = paused)

private fun serverPhoto(id: String, pointId: String, status: PhotoStatus) = PhotoInfo(
    id = id, jobId = "j1", pointId = pointId, userId = "u2", fileName = null, size = 1, status = status, destPath = null, lastError = null,
    takenAt = 1, lat = null, lon = null, backedUp = false, confirmedAt = null, uploadMs = null, reusedFrom = null,
)

class MapModelTest {
    private val dir: File = Files.createTempDirectory("views").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test fun progressCountsCantShootIgnoresDeleted() {
        val points = listOf(
            mapPoint("p1", status = PointStatus.done),
            mapPoint("p2", status = PointStatus.cant_shoot),
            mapPoint("p3"),
            mapPoint("p4", status = PointStatus.done).copy(deletedAt = 5),
            mapPoint("h1", type = "HH"),
            mapPoint("h2", type = "HH", status = PointStatus.done),
            mapPoint("q1", type = "QUERY 1", status = PointStatus.cant_shoot),
        )

        val p = progress(points)

        assertEquals(4, p.done, "done and can't shoot both count; the deleted point doesn't")
        assertEquals(6, p.total)
        assertEquals(67, p.pct) // 4/6 rounds to 67
        assertEquals(
            listOf(TypeProgress("POLE", 2, 3), TypeProgress("HH", 1, 2), TypeProgress("QUERY 1", 1, 1)),
            p.byType,
            "one entry per type, in the order the job lists them",
        )
        assertEquals(Progress(0, 0, 0, emptyList()), progress(emptyList()))
        assertEquals(96, progress(List(159) { mapPoint("p$it", status = if (it < 152) PointStatus.done else PointStatus.open) }).pct)
        assertEquals(99, progress(List(1000) { mapPoint("p$it", status = if (it < 999) PointStatus.done else PointStatus.open) }).pct, "100% only when nothing is left")
    }

    @Test fun chipLabels() {
        assertEquals("✓ POLE (80/80)", chipLabel(TypeProgress("POLE", 80, 80)))
        assertEquals("POLE (12/80)", chipLabel(TypeProgress("POLE", 12, 80)))
        assertEquals("QUERY 1 (0/4)", chipLabel(TypeProgress("QUERY 1", 0, 4)))
    }

    @Test fun sunsetFormat() {
        val zone = ZoneId.of("America/Los_Angeles")
        val sunset = SunTimes.compute().on(LocalDate.of(2026, 11, 5)).timezone(zone).at(39.53, -119.81).execute().set!!.toInstant()

        assertEquals("☀️ 5h 40m until sunset", sunsetText(sunset.minus(Duration.ofMinutes(340)).toEpochMilli(), 39.53, -119.81, zone))
        assertEquals("🌙 After sunset", sunsetText(sunset.plus(Duration.ofMinutes(1)).toEpochMilli(), 39.53, -119.81, zone))
        assertEquals("☀️ 25m until sunset", sunsetText(sunset.minus(Duration.ofMinutes(25)).toEpochMilli(), 39.53, -119.81, zone))
    }

    @Test fun crewDots() {
        val now = 100 * 60 * MIN
        assertEquals(CrewDot.FRESH, crewDotState(crew(9), now))
        assertEquals(CrewDot.PAUSED, crewDotState(crew(2, paused = true), now))
        assertEquals(CrewDot.FADED, crewDotState(crew(11), now))
        assertEquals(CrewDot.HIDDEN, crewDotState(crew(61), now))
        assertEquals(CrewDot.HIDDEN, crewDotState(crew(61, paused = true), now), "older than an hour is hidden, app closed or not")
    }

    @Test fun crewLabelsSayAppClosedAndAge() {
        val now = 100 * 60 * MIN
        assertEquals("Bob Tech · 2 min ago", crewLabel(crew(2), now))
        assertEquals("Bob Tech · app closed · 3 min ago", crewLabel(crew(3, paused = true), now))
        assertEquals("Bob Tech · just now", crewLabel(crew(0), now))
    }

    @Test fun searchMatchesLabelNumDescription() {
        val points = listOf(
            mapPoint("p140", num = "140"),
            mapPoint("p14", num = "14"),
            mapPoint("p7", num = "7", description = "Behind the blue house"),
            mapPoint("h2", type = "HH", num = "2", description = "near 14 Main St"),
            mapPoint("q43", type = "QUERY 1", num = "43", label = "Query 1-43"),
            mapPoint("p9", num = "9").copy(deletedAt = 3),
        )

        assertEquals(listOf("p14", "p140", "h2"), searchPoints(points, "14").map { it.id }, "the exact number first")
        assertEquals(listOf("p7"), searchPoints(points, "BEHIND").map { it.id }, "the description, any case")
        assertEquals(listOf("q43"), searchPoints(points, "43").map { it.id })
        assertEquals(listOf("q43"), searchPoints(points, "query 1-43").map { it.id })
        assertEquals(listOf("p14", "p140"), searchPoints(points, " pole 14 ").map { it.id })
        assertEquals(emptyList(), searchPoints(points, "9"), "deleted points aren't found")
        assertEquals(emptyList(), searchPoints(points, "  "))
    }

    @Test fun visiblePointsFilterTypeAndShot() {
        val points = listOf(
            mapPoint("p1"), mapPoint("p2", status = PointStatus.done), mapPoint("p3", status = PointStatus.cant_shoot), mapPoint("h1", type = "HH"),
        )
        assertEquals(listOf("p1", "p2", "p3", "h1"), visiblePoints(points, null, hideShot = false).map { it.id })
        assertEquals(listOf("p1", "h1"), visiblePoints(points, null, hideShot = true).map { it.id })
        assertEquals(listOf("h1"), visiblePoints(points, "HH", hideShot = false).map { it.id })
        assertEquals(listOf("p1"), visiblePoints(points, "POLE", hideShot = true).map { it.id })
    }

    @Test fun leftSortedByDistanceFromYou() {
        val here = LatLon(39.6000, -119.7000)
        val points = listOf(
            mapPoint("far", lat = 39.6100, lon = -119.7000),
            mapPoint("shot", lat = 39.6000, lon = -119.7000, status = PointStatus.done),
            mapPoint("near", lat = 39.6010, lon = -119.7000),
            mapPoint("mid", lat = 39.6050, lon = -119.7000),
        )
        assertEquals(listOf("near", "mid", "far"), remainingByDistance(points, here).map { it.id })
        assertEquals(listOf("far", "near", "mid"), remainingByDistance(points, null).map { it.id }, "the job's order without a fix")
        assertEquals("360 ft", distanceText(110.0))
        assertEquals("1.2 mi", distanceText(1_931.0))
    }

    @Test fun pendingBadgesComeFromServerAndPhone() {
        val server = listOf(
            serverPhoto("a", "p1", PhotoStatus.registered),
            serverPhoto("b", "p2", PhotoStatus.confirmed),
            serverPhoto("c", "p3", PhotoStatus.stuck),
            serverPhoto("d", "p4", PhotoStatus.removed),
        )
        assertEquals(setOf("p1", "p3", "p5"), pendingPointIds(server, localWaiting = listOf("p5", "p1")))
    }

    @Test fun pillTextForEachState() {
        assertEquals("Offline", pillText(SyncStatus.OFFLINE, 0))
        assertEquals("Offline · 3 photos waiting", pillText(SyncStatus.OFFLINE, 3))
        assertEquals("Syncing…", pillText(SyncStatus.SYNCING, 3))
        assertEquals("3 photos waiting", pillText(SyncStatus.ONLINE, 3))
        assertEquals("1 photo waiting", pillText(SyncStatus.ONLINE, 1))
        assertEquals("Online", pillText(SyncStatus.ONLINE, 0))
    }

    @Test fun refusalsReadAsSentences() {
        assertEquals("Only an admin can delete a point whose photos reached OneDrive, so the point was kept.", rejectionText("photos_delivered"))
        assertEquals("That point was deleted, so the change was undone.", rejectionText("point_deleted"))
        assertEquals("The server refused a change, so it was undone.", rejectionText("something_new"))
        assertEquals("The server refused a change, so it was undone.", rejectionText(null))
    }

    @Test fun imageryKeyGoesAsToken() {
        val esri = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        assertEquals(esri, tileUrl(esri, null))
        assertEquals(esri, tileUrl(esri, " "))
        assertEquals("$esri?token=a%2Bb", tileUrl(esri, "a+b"))
        assertEquals("https://tiles.example.com/{z}/{x}/{y}?style=sat&token=k1", tileUrl("https://tiles.example.com/{z}/{x}/{y}?style=sat", "k1"))
    }

    @Test fun viewMemoryPerUserPerJob() = runBlocking {
        val file = File(dir, "map_views.preferences_pb")
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val views = DataStoreViewMemory(PreferenceDataStoreFactory.create(scope = first) { file })
        assertNull(views.load("u1", "j1"))

        views.save("u1", "j1", CameraView(39.6361, -119.6911, 17.5))
        views.save("u1", "j2", CameraView(40.0, -120.0, 12.0))
        views.save("u2", "j1", CameraView(41.0, -121.0, 14.0))
        views.save("u1", "j1", CameraView(39.6362, -119.6912, 18.25)) // every move replaces the last
        assertEquals(CameraView(39.6362, -119.6912, 18.25), views.load("u1", "j1"))

        // As after the process died: a new store on the same file.
        first.coroutineContext.job.cancelAndJoin()
        val again = DataStoreViewMemory(PreferenceDataStoreFactory.create(scope = scope) { file })
        assertEquals(CameraView(39.6362, -119.6912, 18.25), again.load("u1", "j1"))
        assertEquals(CameraView(40.0, -120.0, 12.0), again.load("u1", "j2"))
        assertEquals(CameraView(41.0, -121.0, 14.0), again.load("u2", "j1"))
        assertNull(again.load("u2", "j2"))
    }
}
