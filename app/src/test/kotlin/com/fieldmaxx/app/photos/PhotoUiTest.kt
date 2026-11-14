package com.fieldmaxx.app.photos

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.LocalPhotoEntity
import com.fieldmaxx.app.data.MetaEntity
import com.fieldmaxx.app.data.toOp
import com.fieldmaxx.app.data.toPhoto
import com.fieldmaxx.app.jobs.JobList
import com.fieldmaxx.app.jobs.PHOTOS_WILL_WAIT
import com.fieldmaxx.app.point.DragMap
import com.fieldmaxx.app.point.FakePointDeps
import com.fieldmaxx.app.point.PointSheet
import com.fieldmaxx.app.point.PointTags
import com.fieldmaxx.app.point.canDeletePoint
import com.fieldmaxx.app.map.pendingPointIds
import com.fieldmaxx.app.sync.ALICE
import com.fieldmaxx.app.sync.FakeApi
import com.fieldmaxx.app.sync.FakeLiveSocket
import com.fieldmaxx.app.sync.MetaKeys
import com.fieldmaxx.app.sync.SyncEngine
import com.fieldmaxx.app.sync.job
import com.fieldmaxx.app.sync.localPhoto
import com.fieldmaxx.app.sync.photo
import com.fieldmaxx.app.sync.point
import com.fieldmaxx.app.sync.snapshot
import com.fieldmaxx.app.ui.FieldmaxxTheme
import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.JobSnapshot
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.ReuseCandidate
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

private const val SERVER = "https://fmx.example.com"
private const val LINK = "https://1drv.ms/f/c/0000000000000000/Field-Notes"
private val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
private val NOV5 = ZonedDateTime.of(2026, 11, 5, 13, 35, 47, 0, ZONE).toInstant().toEpochMilli()
private const val DAY = 24 * 3_600_000L
private val ADMIN = User("u7", "owner", "Owner Admin", Role.admin, disabled = false)
private val BOB = User("u2", "bob", "Bob Tech", Role.tech, disabled = false) // the tech of the photo() fixtures
private const val NAME_18 = "POLE_18_39.636130_-119.691104_7.jpg"
private const val NAME_18_DELIVERED = "POLE_18_39.636130_-119.691104_6.jpg"

/** The strip's needs: a Coil loader that reaches nothing and records what it was asked for; "Retry now" and removals recorded. */
private class FakeStripDeps(context: Context) : PhotoStripDeps {
    val requested = CopyOnWriteArrayList<Any?>()
    val retried = CopyOnWriteArrayList<String>()
    val removed = CopyOnWriteArrayList<String>()
    @Volatile var me: User? = ALICE

    override val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components {
            add(
                object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        requested += chain.request.data
                        return ErrorResult(null, chain.request, IOException("no signal in tests"))
                    }
                },
            )
        }
        .build()

    override suspend fun user(): User? = me

    override fun retryNow(localPhotoId: String) {
        retried += localPhotoId
    }

    override suspend fun remove(photoId: String) {
        removed += photoId
    }
}

/** The picker's needs: the phone's own copy of the job (Room, through the sync engine) and a server that answers as set. */
private class FakeReuseDeps(private val sync: SyncEngine, private val db: AppDatabase, override val imageLoader: ImageLoader) : ReuseDeps {
    val nearbyAsked = CopyOnWriteArrayList<Triple<Double, Double, String?>>()
    @Volatile var nearbyReply: () -> List<ReuseCandidate> = { emptyList() }
    @Volatile var jobReply: () -> List<ReuseCandidate> = { emptyList() }

    override fun server(): String = SERVER
    override fun job(jobId: String): Flow<Job?> = sync.job(jobId)
    override fun points(jobId: String): Flow<List<Point>> = sync.points(jobId)
    override fun jobPhotos(jobId: String): Flow<List<PhotoInfo>> = sync.jobPhotos(jobId)
    override fun deletedPhotoIds(): Flow<Set<String>> = deletedPhotoIds(db)

    override suspend fun nearby(lat: Double, lon: Double, excludePointId: String?): List<ReuseCandidate> {
        nearbyAsked += Triple(lat, lon, excludePointId)
        return nearbyReply()
    }

    override suspend fun serverJobPhotos(jobId: String): List<ReuseCandidate> = jobReply()
    override suspend fun enqueue(op: Op) = sync.enqueue(op)
    override suspend fun saveSource(photoId: String, source: ReuseSource) = ReuseSources(db.meta()).put(photoId, source)
    override fun zone(): ZoneId = ZONE
}

/**
 * An in-memory database that tells the test the moment a local photo row is deleted (the last step of a photo's delete),
 * so the test can look at what is left on the disk right then.
 */
private class WatchedDb(context: Context) {
    @Volatile var onRowDelete: (() -> Unit)? = null

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
            seen()
            s.execute()
        }

        override fun executeUpdateDelete(): Int {
            seen()
            return s.executeUpdateDelete()
        }

        private fun seen() {
            if (sql.trimStart().startsWith("DELETE FROM LocalPhotoEntity")) onRowDelete?.invoke()
        }
    }
}

/**
 * Task 29: the photo UI. This phone's photos and the server's merged into one status per photo (R63), the strip with its
 * icons, shield and "Retry now", where the photos went (R65), the reuse picker (R66), the export ZIP, and deleting or
 * hiding a photo (R64). Room in memory and the sync engine's own enqueue, never started: no server and no OneDrive.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoUiTest {
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val watched = WatchedDb(context)
    private val db: AppDatabase = watched.db

    /**
     * The database closes only once the screen under test is gone: JUnit's @After runs before the compose rule ends the
     * activity, and a flow the screen still collects would then query a closed database.
     */
    @get:Rule val rules: RuleChain = RuleChain.outerRule(
        object : ExternalResource() {
            override fun after() = db.close()
        },
    ).around(compose)
    private val api = FakeApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sync = SyncEngine(api, db, FakeLiveSocket(), scope, { NOV5 }, { SERVER })
    private val strip = FakeStripDeps(context)
    private val reuse = FakeReuseDeps(sync, db, strip.imageLoader)
    private val photosDir = File(context.filesDir, "photos")

    private val pole18 = point("p18").copy(folderPath = "POLES/Pole 18")
    private val pole19 = point("p19")
    private val pole5 = point("p5")
    private val pole6 = point("p6")
    private val query43 = point("q43").copy(layer = "QUERY 1", type = "QUERY 1", num = "43", label = "Query 1-43", lat = 39.63650, lon = -119.69150)
    private val ph5 = photo("ph5", "p5").copy(takenAt = NOV5)
    private val ph6 = photo("ph6", "p6").copy(takenAt = NOV5 + DAY)
    private val phq = photo("phq", "q43").copy(takenAt = NOV5 + 2 * DAY) // the query point's own photo

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        forgetFileProviderRoots()
    }

    @After fun tearDown() {
        sync.stop()
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        WorkManagerTestInitHelper.closeWorkDatabase()
        photosDir.deleteRecursively()
        File(context.cacheDir, "shared").deleteRecursively()
        forgetFileProviderRoots()
    }

    /**
     * FileProvider keeps each authority's roots in a static map, while Robolectric gives every test a fresh cacheDir in the
     * same class loader: a root another test class read first would point at that test's cacheDir. Cleared before and after.
     */
    private fun forgetFileProviderRoots() {
        val roots = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>
        synchronized(roots) { roots.clear() }
    }

    // ---- fixtures ----

    /** Job A05J9XE on the phone as its snapshot left it, with the photos the server knows; Alice is signed in. */
    private fun seedJob(serverPhotos: List<PhotoInfo> = listOf(ph5, ph6, phq), link: String? = LINK) {
        api.snapshots["j1"] = snapshot(
            job("j1", "A05J9XE").copy(fieldNotesUrl = link), listOf(pole18, pole19, pole5, pole6, query43), seq = 10, photos = serverPhotos,
        )
        runBlocking {
            db.meta().put(MetaEntity(MetaKeys.USER, FmxJson.encodeToString(User.serializer(), ALICE)))
            sync.openJob("j1")
        }
    }

    /** The server's state of job A05J9XE moves on: [change] applied to its snapshot, which the phone then fetches. */
    private fun serverMovesOn(seq: Long, change: (JobSnapshot) -> JobSnapshot) {
        api.snapshots["j1"] = change(api.snapshots.getValue("j1")).copy(seq = seq)
        runBlocking { sync.openJob("j1") }
    }

    /** A photo Alice took on this phone (R78: its meta records her), at [state]; its files exist only when [files] is set. */
    private fun mine(
        id: String,
        state: String,
        pointId: String = "p18",
        fileName: String? = null,
        stuck: Boolean = false,
        error: String? = null,
        backupDone: Boolean = false,
        files: Boolean = false,
        stamped: String? = "stamped $id",
    ): LocalPhotoEntity {
        val meta = CaptureMeta(
            jobName = "A05J9XE", pointLabel = "POLE 18", source = SOURCE_CAMERA, lat = 39.636130, lon = -119.691104, accuracyM = 4.9,
            altitudeM = 1350.3, headingDeg = 47.0, takenAt = NOV5, gpsStale = false, techId = ALICE.id,
        )
        var row = localPhoto(id, pointId = pointId).copy(
            metaJson = FmxJson.encodeToString(CaptureMeta.serializer(), meta), state = state, fileName = fileName, stuck = stuck, lastError = error,
            backupDone = backupDone, attempts = if (stuck) 6 else 0, confirmedAt = if (state == CONFIRMED) NOV5 else null,
        )
        if (files) {
            val dir = File(photosDir, id).apply { mkdirs() }
            File(dir, META_JSON).writeText("{\"id\":\"$id\"}")
            val original = File(dir, "original.jpg").apply { writeText("original $id") }
            val stampedFile = stamped?.let { File(dir, STAMPED_JPG).apply { writeText(it) } }
            row = row.copy(originalPath = original.path, stampedPath = stampedFile?.path)
        }
        return row
    }

    private fun store(vararg rows: LocalPhotoEntity) = runBlocking { rows.forEach { db.localPhotos().upsert(it) } }

    private fun outbox(): List<Op> = runBlocking { db.outbox().all().map { it.toOp() } }

    private fun serverRow(id: String): PhotoInfo? = runBlocking { db.photos().get(id)?.toPhoto() }

    private fun filesLeft(): List<String> = photosDir.walk().filter { it.isFile }.map { it.relativeTo(photosDir).path }.sorted().toList()

    private fun showStrip(photos: List<PhotoView>, point: Point = pole18) {
        compose.setContent { FieldmaxxTheme { PhotoStrip(point, photos, strip) } }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(5_000) {
        compose.waitForIdle()
        condition()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitForTag(tag: String) = waitFor { exists(tag) }
    private fun hasTextNode(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun waitForText(text: String) = waitFor { hasTextNode(text) }
    /** [unmerged]: a tag inside a row that reads as one (a list row merges its texts for TalkBack). */
    private fun textOf(tag: String, unmerged: Boolean = false) =
        compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    private fun press(tag: String) {
        compose.onNodeWithTag(tag).performClick()
    }

    /** A photo in the strip, which scrolls sideways: brought into view first. */
    private fun tapPhoto(id: String) {
        compose.onNodeWithTag(PhotoTags.tile(id)).performScrollTo().performClick()
    }

    // ---- the brief's tests ----

    @Test fun statusMapping() {
        val local = listOf(
            mine("a", SAVED),
            mine("b", STAMPED),
            mine("c", REGISTERED, fileName = NAME_18),
            mine("d", CONFIRMED, fileName = NAME_18_DELIVERED),
            mine("e", REGISTERED, fileName = NAME_18, stuck = true, error = CANT_REACH_SERVER),
            mine("f", SAVED, stuck = true, error = MISSING_FILE), // stuck before it was ever registered (R83)
        )
        val server = listOf(
            photo("g", "p18"),
            photo("h", "p18", status = PhotoStatus.confirmed).copy(destPath = "POLES/Pole 18/$NAME_18_DELIVERED", confirmedAt = NOV5),
            photo("i", "p18", status = PhotoStatus.stuck).copy(lastError = "The link no longer allows uploads"),
            photo("j", "p18", status = PhotoStatus.removed),
        )
        val views = photoViews(local, server, SERVER).associateBy { it.id }

        assertEquals(
            mapOf(
                "a" to PhotoUiStatus.ON_PHONE, "b" to PhotoUiStatus.ON_PHONE, "c" to PhotoUiStatus.UPLOADING, "d" to PhotoUiStatus.IN_ONEDRIVE,
                "e" to PhotoUiStatus.STUCK, "f" to PhotoUiStatus.STUCK, "g" to PhotoUiStatus.UPLOADING, "h" to PhotoUiStatus.IN_ONEDRIVE,
                "i" to PhotoUiStatus.STUCK,
            ),
            views.mapValues { it.value.status },
            "a removed photo never shows",
        )
        assertEquals(CANT_REACH_SERVER, views.getValue("e").error)
        assertEquals(MISSING_FILE, views.getValue("f").error)
        assertEquals("The link no longer allows uploads", views.getValue("i").error)
        assertNull(views.getValue("c").error, "only a stuck photo says why")
        assertEquals("POLES/Pole 18/$NAME_18_DELIVERED", views.getValue("h").destPath)
        assertEquals(NAME_18, views.getValue("c").fileName, "the name the server gave it")

        // Whose each is, and where: this phone's photos are Alice's (R78); the server's carry their tech.
        assertEquals(setOf("a", "b", "c", "d", "e", "f"), views.values.filter { it.onThisPhone }.map { it.id }.toSet())
        assertTrue(views.values.filter { it.onThisPhone }.all { it.ownerId == ALICE.id })
        assertTrue(views.values.filterNot { it.onThisPhone }.all { it.ownerId == BOB.id })
    }

    @Test fun shieldWhenBackedUp() {
        val local = listOf(
            mine("a", REGISTERED, fileName = NAME_18),
            mine("b", REGISTERED, fileName = NAME_18, backupDone = true),
            mine("c", REGISTERED, fileName = NAME_18), // this phone hasn't heard yet; the server has the backup
        )
        val server = listOf(photo("c", "p18").copy(backedUp = true), photo("d", "p18").copy(backedUp = false), photo("e", "p18").copy(backedUp = true))
        val views = photoViews(local, server, SERVER)
        assertEquals(mapOf("a" to false, "b" to true, "c" to true, "d" to false, "e" to true), views.associate { it.id to it.backedUp })

        showStrip(views)
        waitForTag(PhotoTags.tile("e"))
        assertEquals(setOf("b", "c", "e"), views.map { it.id }.filter { exists(PhotoTags.shield(it)) }.toSet(), "a small shield once the server backup is in place")
    }

    @Test fun pendingCountNoDoubleCounting() {
        val local = listOf(
            mine("a", REGISTERED, fileName = NAME_18), // on both sides: counted once
            mine("b", CONFIRMED, fileName = NAME_18), // this phone saw it in OneDrive; the server hasn't heard yet
            mine("c", REGISTERED, fileName = NAME_18), // the server delivered it itself (R57)
            mine("e", REGISTERED, fileName = NAME_18), // removed: never counted
            mine("f", SAVED),
        )
        val server = listOf(
            photo("a", "p18"),
            photo("b", "p18"),
            photo("c", "p18", status = PhotoStatus.confirmed).copy(confirmedAt = NOV5),
            photo("d", "p18", status = PhotoStatus.stuck),
            photo("e", "p18", status = PhotoStatus.removed),
            photo("g", "p18", status = PhotoStatus.confirmed).copy(confirmedAt = NOV5),
        )
        assertEquals(3, pendingCount(local, server), "a, d and f are not in OneDrive yet")
        assertEquals(0, pendingCount(emptyList(), emptyList()))
        assertEquals(1, pendingCount(listOf(mine("x", STAMPED)), emptyList()))
    }

    @Test fun stripShowsIconsAndStuckReasonWithRetryNow() {
        val a = mine("a", STAMPED, files = true)
        val local = listOf(
            a,
            mine("b", REGISTERED, fileName = NAME_18),
            mine("c", CONFIRMED, fileName = NAME_18_DELIVERED),
            mine("d", REGISTERED, fileName = NAME_18, stuck = true, error = CANT_REACH_SERVER),
        )
        val server = listOf(photo("e", "p18", status = PhotoStatus.stuck).copy(lastError = "The link no longer allows uploads"))
        showStrip(photoViews(local, server, SERVER))

        // ⏳ on phone → ⬆ uploading → ✓ in OneDrive, or ⚠ stuck (spec §6.6.2).
        waitForTag(PhotoTags.tile("e"))
        assertEquals(
            mapOf("a" to "⏳", "b" to "⬆", "c" to "✓", "d" to "⚠", "e" to "⚠"),
            listOf("a", "b", "c", "d", "e").associateWith { textOf(PhotoTags.status(it)) },
        )
        // Thumbnails: the stamped copy on this phone, else the server's thumbnail through the signed-in loader.
        waitFor { File(a.stampedPath!!) in strip.requested && "$SERVER/api/photos/e/thumb" in strip.requested }
        assertTrue("$SERVER/api/photos/b/thumb" in strip.requested, "b's files aren't where its row says: the server's thumbnail")

        // Tap the stuck one: its reason, and "Retry now".
        assertFalse(exists(PhotoTags.RETRY), "only once tapped")
        tapPhoto("d")
        waitForText(CANT_REACH_SERVER)
        press(PhotoTags.RETRY)
        assertEquals(listOf("d"), strip.retried.toList(), "the pipeline tries this phone's photo at once")
        waitForText("Trying again now")

        // A stuck photo this phone doesn't hold (another phone's, or the server's own delivery): its reason, nothing to retry here.
        tapPhoto("e")
        waitForText("The link no longer allows uploads")
        assertFalse(exists(PhotoTags.RETRY))
        assertFalse(hasTextNode(CANT_REACH_SERVER), "one photo's details at a time")
    }

    @Test fun whereWentListsPathsAndOpensFieldNotesLink() {
        seedJob(
            listOf(
                photo("s1", "p18", status = PhotoStatus.confirmed).copy(fileName = NAME_18_DELIVERED, destPath = "POLES/Pole 18/$NAME_18_DELIVERED", confirmedAt = NOV5),
                photo("s2", "p18"),
                photo("s3", "p5", status = PhotoStatus.removed),
            ),
        )
        store(
            mine("s2", REGISTERED, fileName = NAME_18),
            mine("l1", STAMPED, pointId = "q43"),
            mine("l2", REGISTERED, pointId = "p19", stuck = true, error = CANT_REACH_SERVER, fileName = "POLE_19_39.636130_-119.691104_8.jpg"),
        )
        compose.setContent {
            FieldmaxxTheme {
                WherePhotosWentScreen(sync.job("j1"), sync.points("j1"), remember { jobPhotoViewsFor("j1", db, { SERVER }) }, onBack = {})
            }
        }

        waitForText("3 photos not in OneDrive yet")
        val list = compose.onNodeWithTag(WhereTags.LIST)
        for (line in listOf(
            "Field Notes/POLES/Pole 18/$NAME_18_DELIVERED", // in OneDrive: where it is
            "→ Field Notes/POLES/Pole 18/$NAME_18", // uploading: where it goes
            "→ Field Notes/QUERY 1/Query_1-43/ (named once the server has it)",
            "→ Field Notes/POLE/POLE_19/POLE_19_39.636130_-119.691104_8.jpg",
            "Stuck: $CANT_REACH_SERVER",
        )) {
            list.performScrollToNode(hasText(line))
        }
        assertFailsWith<AssertionError>("a point whose only photo was removed isn't listed") {
            list.performScrollToNode(hasTestTag(WhereTags.point("p5")))
        }

        // The Field Notes folder opens in the phone's browser or OneDrive app.
        list.performScrollToNode(hasTestTag(WhereTags.OPEN_FIELD_NOTES))
        press(WhereTags.OPEN_FIELD_NOTES)
        val app = shadowOf(context as Application)
        waitFor { app.peekNextStartedActivity() != null }
        val view = app.nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(Uri.parse(LINK), view.data)

        // Without a link it says so, as the job's check screen does (R65).
        serverMovesOn(11) { it.copy(job = it.job.copy(fieldNotesUrl = null)) }
        waitFor { !exists(WhereTags.OPEN_FIELD_NOTES) }
        list.performScrollToNode(hasText(PHOTOS_WILL_WAIT))
    }

    @Test fun reusePickerTabsNearbyThisJobSearch() {
        seedJob()
        val older = photo("phx", "px", jobId = "j0").copy(takenAt = NOV5 - 300 * DAY) // the same pole in an earlier job, and nearer
        // The server's order: nearest first.
        reuse.nearbyReply = { listOf(ReuseCandidate(older, "POLE 5", "A04X1Y", 40.0), ReuseCandidate(ph5, "POLE 5", "A05J9XE", 80.4)) }
        reuse.jobReply = { throw IOException("no signal") } // This job comes from the phone's own copy of the job
        var picking by mutableStateOf(true)
        compose.setContent {
            FieldmaxxTheme {
                if (picking) {
                    ReusePicker("j1", "q43", reuse, onDone = { picking = false })
                } else {
                    val photos by remember { photoViewsFor("q43", db, { SERVER }, { ZONE }) }.collectAsState(emptyList())
                    PhotoStrip(query43, photos, strip)
                }
            }
        }

        // Nearby: photos within 150 m of this point, from any job, newest first (R87), each with its distance.
        waitForTag(ReuseTags.candidate("phx"))
        assertEquals(listOf(Triple(query43.lat, query43.lon, "q43")), reuse.nearbyAsked.toList(), "this point's own photos left out")
        assertTrue(top(ReuseTags.candidate("ph5")) < top(ReuseTags.candidate("phx")), "newest first, not nearest first")
        assertEquals("POLE 5 · A05J9XE · 11/05/26", textOf(ReuseTags.line("ph5"), unmerged = true))
        assertEquals("80 m", textOf(ReuseTags.distance("ph5"), unmerged = true))
        assertEquals("40 m", textOf(ReuseTags.distance("phx"), unmerged = true))

        // This job: all its photos, grouped by point, newest first; the query point's own photo isn't offered.
        press(ReuseTags.THIS_JOB)
        waitForTag(ReuseTags.candidate("ph6"))
        assertFalse(exists(ReuseTags.candidate("phq")))
        assertFalse(exists(ReuseTags.candidate("phx")), "another job's photo is Nearby's")
        assertTrue(top(ReuseTags.group("p6")) < top(ReuseTags.candidate("ph6")))
        assertTrue(top(ReuseTags.candidate("ph6")) < top(ReuseTags.group("p5")))
        assertTrue(top(ReuseTags.group("p5")) < top(ReuseTags.candidate("ph5")))

        // Search by point label, over this job and Nearby.
        press(ReuseTags.SEARCH)
        compose.onNodeWithTag(ReuseTags.SEARCH_FIELD).performTextInput("pole 5")
        waitFor { exists(ReuseTags.candidate("ph5")) && exists(ReuseTags.candidate("phx")) }
        assertFalse(exists(ReuseTags.candidate("ph6")))
        compose.onNodeWithTag(ReuseTags.SEARCH_FIELD).performTextReplacement("POLE_6")
        waitFor { exists(ReuseTags.candidate("ph6")) && !exists(ReuseTags.candidate("ph5")) }

        // Picked, the point shows the reused photo and where it came from.
        compose.onNodeWithTag(ReuseTags.SEARCH_FIELD).performTextReplacement("5")
        waitForTag(ReuseTags.candidate("ph5"))
        press(ReuseTags.candidate("ph5"))
        compose.onNodeWithTag(ReuseTags.USE).assertTextEquals("Use 1 photo")
        press(ReuseTags.USE)
        waitForText("reused from POLE 5 · A05J9XE · 11/05/26")
    }

    @Test fun reuseEnqueuesPhotoReuseOps() {
        seedJob()
        reuse.nearbyReply = { listOf(ReuseCandidate(ph5, "POLE 5", "A05J9XE", 80.0)) }
        reuse.jobReply = { listOf(ReuseCandidate(ph6, "POLE 6", "A05J9XE", null), ReuseCandidate(ph5, "POLE 5", "A05J9XE", null)) }
        var done = false
        compose.setContent { FieldmaxxTheme { ReusePicker("j1", "q43", reuse, onDone = { done = true }) } }

        waitForTag(ReuseTags.candidate("ph5"))
        press(ReuseTags.candidate("ph5"))
        press(ReuseTags.THIS_JOB)
        waitForTag(ReuseTags.candidate("ph6"))
        press(ReuseTags.candidate("ph6"))
        compose.onNodeWithTag(ReuseTags.USE).assertTextEquals("Use 2 photos")
        press(ReuseTags.USE)
        waitFor { done }

        // One PhotoReuse per picked photo, each copy under a fresh id of its own (R66).
        val ops = outbox().filterIsInstance<Op.PhotoReuse>()
        assertEquals(setOf("ph5", "ph6"), ops.map { it.fromPhotoId }.toSet())
        assertEquals(2, ops.size)
        assertTrue(ops.all { it.pointId == "q43" })
        ops.forEach { UUID.fromString(it.photoId) } // client UUIDs
        assertEquals(4, (ops.map { it.opId } + ops.map { it.photoId }).toSet().size, "every id its own")
        assertEquals(ops, outbox(), "and nothing else")

        // The copies show on the point at once: their sources are on this phone (the server sends the copies to OneDrive itself).
        val copies = ops.map { assertNotNull(serverRow(it.photoId)) }
        assertEquals(setOf("ph5", "ph6"), copies.map { it.reusedFrom }.toSet())
        assertTrue(copies.all { it.pointId == "q43" && it.status == PhotoStatus.registered })
    }

    @Test fun exportZipFieldNotesLayoutOnlyUnconfirmed() {
        val points = listOf(pole18, query43, pole19).associateBy { it.id }
        val a = mine("a", REGISTERED, fileName = NAME_18, files = true)
        val b = mine("b", STAMPED, pointId = "q43", files = true)
        val c = mine("c", CONFIRMED, fileName = NAME_18_DELIVERED, files = true)
        val d = mine("d", SAVED, pointId = "p19", files = true, stamped = null) // not stamped yet: it goes as taken
        val e = mine("e", STAMPED, pointId = "p404", files = true) // its point is gone
        val out = File(context.cacheDir, "test-export.zip")

        assertEquals(3, buildExportZip(listOf(a, b, c, d, e), points, out))
        val entries = ZipFile(out).use { zip -> zip.entries().toList().associate { it.name to zip.getInputStream(it).readBytes().decodeToString() } }
        assertEquals(
            mapOf(
                "POLES/Pole 18/$NAME_18" to "stamped a", // the point's folder and the server's name
                "QUERY 1/Query_1-43/b.jpg" to "stamped b", // the folder that would be made, and the local id
                "POLE/POLE_19/d.jpg" to "original d",
            ),
            entries,
        )

        // Menu → Export photos to OneDrive app: the job's photos on this phone that aren't in OneDrive, through the share sheet.
        val f = mine("f", REGISTERED, fileName = "POLE_18_39.636130_-119.691104_9.jpg", files = true) // the server delivered it (R57)
        seedJob(listOf(ph5, photo("f", "p18", status = PhotoStatus.confirmed).copy(confirmedAt = NOV5)))
        store(a, b, c, d, f)
        assertNull(runBlocking { exportPhotosToOneDriveApp(compose.activity, db, "j1") })

        val app = shadowOf(context as Application)
        val chooser = assertNotNull(app.nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = assertNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/zip", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0, "the chosen app may read it")
        val uri = assertNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("${context.packageName}.files", uri.authority, "through the app's FileProvider")
        val shared = File(context.cacheDir, "shared").walk().single { it.isFile }
        assertEquals("A05J9XE_photos.zip", shared.name)
        val names = ZipFile(shared).use { zip -> zip.entries().toList().map { it.name }.toSet() }
        assertEquals(setOf("POLES/Pole 18/$NAME_18", "QUERY 1/Query_1-43/b.jpg", "POLE/POLE_19/d.jpg"), names)

        // Nothing left to export: said, and no share sheet.
        runBlocking { listOf("a", "b", "d", "f").forEach { db.localPhotos().delete(it) } }
        assertEquals(NOTHING_TO_EXPORT, runBlocking { exportPhotosToOneDriveApp(compose.activity, db, "j1") })
        assertNull(app.nextStartedActivity)
    }

    @Test fun deletePhotoRules() {
        fun view(status: PhotoUiStatus, owner: String = ALICE.id, fileName: String? = null) =
            PhotoView("x", "", status, backedUp = false, error = null, destPath = null, ownerId = owner, fileName = fileName)

        // The owner before OneDrive, an admin always; after OneDrive it only hides (R64, ledger R14).
        for (s in PhotoUiStatus.entries) {
            assertTrue(canDeletePhoto(ALICE, view(s)), "her own photo, $s")
            assertFalse(canDeletePhoto(ALICE, view(s, owner = BOB.id)), "Bob's photo, $s")
            assertTrue(canDeletePhoto(ADMIN, view(s, owner = BOB.id)), "an admin, $s")
        }
        assertFalse(canDeletePhoto(ALICE, view(PhotoUiStatus.ON_PHONE, owner = "")), "no known owner: admins only")
        assertEquals(Removal.DELETE, removalOf(view(PhotoUiStatus.STUCK)))
        assertEquals(Removal.HIDE, removalOf(view(PhotoUiStatus.IN_ONEDRIVE)))

        // What the confirmation says (R64, R84).
        assertEquals(
            listOf("It is deleted from this phone and won't go to OneDrive."),
            removalPrompt(view(PhotoUiStatus.ON_PHONE).copy(onThisPhone = true)).lines,
        )
        assertEquals(
            "An empty file named $NAME_18 may stay in OneDrive. Remove it there by hand.",
            removalPrompt(view(PhotoUiStatus.UPLOADING, fileName = NAME_18)).lines.last(),
        )
        assertTrue(removalPrompt(view(PhotoUiStatus.STUCK, fileName = NAME_18)).lines.any { "An empty file named $NAME_18" in it })
        val hide = removalPrompt(view(PhotoUiStatus.IN_ONEDRIVE, fileName = NAME_18))
        assertEquals("Hide", hide.confirm)
        assertTrue(hide.lines.any { "stays in OneDrive" in it }, "${hide.lines}")
        assertFalse(hide.lines.any { "empty file" in it })

        // Before OneDrive the photo goes from this phone: PhotoRemove first when the server knows it, then its files, then its
        // row (T28: meta.json goes first of all, so a kill in between can't let the orphan sweep bring it back).
        seedJob(listOf(ph5, photo("reg", "p18").copy(userId = ALICE.id), photo("bobs", "p18")))
        store(mine("saved", SAVED, files = true), mine("reg", REGISTERED, fileName = NAME_18, files = true))
        val events = CopyOnWriteArrayList<String>()
        val pipeline = PhotoPipeline(context, db, api, { error("no OneDrive in tests") }, sync, { NOV5 })
        val remover = PhotoRemover(db, { op -> events += "queued ${op::class.simpleName}"; sync.enqueue(op) }, pipeline::discard)
        watched.onRowDelete = { events += "row deleted, files left: ${filesLeft()}" }

        assertEquals(Removal.DELETE, runBlocking { remover.remove("saved") })
        assertEquals(
            listOf("row deleted, files left: [reg/meta.json, reg/original.jpg, reg/stamped.jpg]"),
            events.toList(),
            "never registered: nothing to tell the server",
        )
        assertNull(runBlocking { db.localPhotos().get("saved") })

        events.clear()
        assertEquals(Removal.DELETE, runBlocking { remover.remove("reg") })
        assertEquals(listOf("queued PhotoRemove", "row deleted, files left: []"), events.toList())
        assertEquals(listOf(Op.PhotoRemove(outbox().single().opId, "reg")), outbox())
        assertEquals(PhotoStatus.removed, serverRow("reg")?.status, "the point no longer lists it")
        assertNull(runBlocking { db.localPhotos().get("reg") })

        // An admin deleting another tech's photo before OneDrive: only the server's copy is known here, so only PhotoRemove.
        events.clear()
        assertEquals(Removal.DELETE, runBlocking { remover.remove("bobs") })
        assertEquals(listOf("queued PhotoRemove"), events.toList())

        // On the strip: the owner is asked first, with R84's sentence; another tech's photo has no delete.
        showStrip(photoViews(listOf(mine("up", REGISTERED, fileName = NAME_18)), listOf(photo("theirs", "p18")), SERVER))
        tapPhoto("theirs")
        waitForTag(PhotoTags.DETAIL)
        assertFalse(exists(PhotoTags.REMOVE), "Bob's photo: Alice can't delete it")
        tapPhoto("up")
        waitForTag(PhotoTags.REMOVE)
        press(PhotoTags.REMOVE)
        waitForText("An empty file named $NAME_18 may stay in OneDrive. Remove it there by hand.")
        assertEquals(emptyList(), strip.removed.toList(), "not before it is confirmed")
        press(PhotoTags.REMOVE_CONFIRM)
        waitFor { strip.removed.toList() == listOf("up") }
    }

    // ---- focused tests for the rulings ----

    @Test fun serverTakeoverOutranksLocalRegistered() { // R63
        val local = listOf(
            mine("a", REGISTERED, fileName = NAME_18),
            mine("b", REGISTERED, fileName = NAME_18),
            mine("c", CONFIRMED, fileName = NAME_18),
            mine("d", REGISTERED, fileName = NAME_18, stuck = true, error = CANT_REACH_SERVER),
        )
        val server = listOf(
            // The server delivered it itself (its takeover, R57) before this phone heard.
            photo("a", "p18", status = PhotoStatus.confirmed).copy(destPath = "POLES/Pole 18/$NAME_18", confirmedAt = NOV5, userId = ALICE.id),
            // The server's own delivery is stuck: the job's link stopped taking uploads.
            photo("b", "p18", status = PhotoStatus.stuck).copy(lastError = "The link no longer allows uploads", userId = ALICE.id),
            // An older server state never outranks this phone's confirmation.
            photo("c", "p18", status = PhotoStatus.stuck).copy(lastError = "old", userId = ALICE.id),
            photo("d", "p18", status = PhotoStatus.confirmed).copy(confirmedAt = NOV5, userId = ALICE.id),
        )
        val views = photoViews(local, server, SERVER).associateBy { it.id }

        assertEquals(PhotoUiStatus.IN_ONEDRIVE, views.getValue("a").status)
        assertEquals("POLES/Pole 18/$NAME_18", views.getValue("a").destPath)
        assertEquals(PhotoUiStatus.STUCK, views.getValue("b").status)
        assertEquals("The link no longer allows uploads", views.getValue("b").error)
        assertTrue(views.getValue("b").onThisPhone, "this phone still holds it, so Retry now can try its own upload")
        assertEquals(PhotoUiStatus.IN_ONEDRIVE, views.getValue("c").status)
        assertNull(views.getValue("c").error)
        assertEquals(PhotoUiStatus.IN_ONEDRIVE, views.getValue("d").status, "a stuck upload the server finished")
        assertEquals(4, views.size, "one view per photo")
        assertEquals(2, pendingCount(local + mine("e", SAVED), server), "b and e")
    }

    @Test fun hideAfterOneDriveOnlyEnqueuesPhotoRemove() { // R64
        seedJob(
            listOf(
                ph5,
                photo("c", "p18", status = PhotoStatus.confirmed).copy(userId = ALICE.id, destPath = "POLES/Pole 18/$NAME_18_DELIVERED", confirmedAt = NOV5),
                photo("d", "p18").copy(userId = ALICE.id),
            ),
        )
        val c = mine("c", CONFIRMED, fileName = NAME_18_DELIVERED, files = true)
        val d = mine("d", REGISTERED, fileName = NAME_18, files = true)
        val e = mine("e", SAVED, pointId = "p5", files = true)
        store(c, d, e)
        val files = filesLeft()
        val pipeline = PhotoPipeline(context, db, api, { error("no OneDrive in tests") }, sync, { NOV5 })
        val remover = PhotoRemover(db, sync::enqueue, pipeline::discard)

        assertEquals(Removal.HIDE, runBlocking { remover.remove("c") })

        assertEquals(listOf(Op.PhotoRemove(outbox().single().opId, "c")), outbox(), "only hidden: nothing is deleted in OneDrive")
        assertEquals(PhotoStatus.removed, serverRow("c")?.status)
        runBlocking { assertEquals(listOf(c, d, e), listOf("c", "d", "e").map { db.localPhotos().get(it) }, "every row stays") }
        assertEquals(files, filesLeft(), "and every file: c's copies go with the usual cleanup")
        val shown = runBlocking { photoViewsFor("p18", db, { SERVER }).first() }
        assertEquals(listOf("d"), shown.map { it.id }, "the point no longer lists it")
    }

    @Test fun removedDeliveredPhotoStillKeepsATechFromDeletingThePoint() { // the delete-point rule (Task 25's ledger note)
        val hidden = photo("h", "p18", status = PhotoStatus.removed).copy(confirmedAt = NOV5) // delivered, then hidden
        assertTrue(hiddenDelivered(emptyList(), listOf(hidden)))
        assertFalse(hiddenDelivered(emptyList(), listOf(hidden.copy(confirmedAt = null))), "removed before it reached OneDrive")
        assertTrue(hiddenDelivered(listOf(mine("h", CONFIRMED)), listOf(hidden.copy(confirmedAt = null))), "this phone saw it in OneDrive")
        assertEquals(emptyList(), photoViews(emptyList(), listOf(hidden), SERVER), "and it isn't listed")
        assertFalse(canDeletePoint(ALICE, emptyList(), hiddenDelivered = true))
        assertTrue(canDeletePoint(ADMIN, emptyList(), hiddenDelivered = true))
        assertTrue(canDeletePoint(ALICE, emptyList(), hiddenDelivered = false))

        // The sheet reads it from a flow that includes removed photos.
        seedJob(listOf(hidden))
        assertTrue(runBlocking { hiddenDeliveredFor("p18", db).first() })
        val deps = FakePointDeps(context)
        deps.pointsFlow.value = listOf(pole18)
        deps.hiddenDeliveredFlow.value = true
        compose.setContent { FieldmaxxTheme { PointSheet("j1", "p18", deps, DragMap(), onClose = {}) } }
        waitForTag(PointTags.TITLE)
        assertFalse(exists(PointTags.DELETE), "the server would refuse Alice's delete (ops/Ops.kt)")
        deps.hiddenDeliveredFlow.value = false
        waitForTag(PointTags.DELETE)
    }

    @Test fun adminDeletingAnotherTechsUnconfirmedPhotoIsWarnedOfTheEmptyFile() { // R86, R84
        val bobsName = "POLE_1_39.636130_-119.691104_7.jpg" // the name the server gave Bob's photo (the photo() fixture's)
        val theirs = photoViews(emptyList(), listOf(photo("theirs", "p18")), SERVER).single() // registered, not in OneDrive yet
        assertEquals(PhotoUiStatus.UPLOADING, theirs.status)
        assertFalse(theirs.onThisPhone, "the admin's phone has only the server's copy")
        assertEquals(
            RemovalPrompt(
                "Delete this photo?",
                listOf("It won't go to OneDrive.", "An empty file named $bobsName may stay in OneDrive. Remove it there by hand."),
                "Delete",
            ),
            removalPrompt(theirs),
        )

        // On the admin's strip: asked first, with the sentence, then removed.
        strip.me = ADMIN
        showStrip(listOf(theirs))
        tapPhoto("theirs")
        press(PhotoTags.REMOVE)
        waitForText("An empty file named $bobsName may stay in OneDrive. Remove it there by hand.")
        assertTrue(hasTextNode("It won't go to OneDrive."))
        press(PhotoTags.REMOVE_CONFIRM)
        waitFor { strip.removed.toList() == listOf("theirs") }
    }

    @Test fun tombstonedPhotosAreNeitherListedNorCounted() { // fix round 1: a photo deleted here, its server row not removed yet
        val gone = photo("gone", "p19").copy(userId = ALICE.id, fileName = "POLE_19_39.636130_-119.691104_9.jpg")
        seedJob(listOf(ph5, gone))
        // A kill left its row (I1) and the server still has it registered (I2); a photo of POLE 18 waits too.
        store(mine("gone", REGISTERED, pointId = "p19", fileName = gone.fileName, files = true), mine("kept", STAMPED, files = true))
        runBlocking { PhotoTombstones(db.meta()).put("gone", PhotoTombstone(NOV5)) }

        runBlocking {
            assertEquals(emptyList(), photoViewsFor("p19", db, { SERVER }).first(), "the point sheet's strip and Done")
            val job = jobPhotoViewsFor("j1", db, { SERVER }).first()
            assertEquals(setOf("p18", "p5"), job.byPoint.keys, "where the photos went: POLE 19's photo was deleted")
            assertEquals(mapOf("p18" to 1, "p5" to 1), job.pending)
            assertEquals(1, db.localPhotos().observeWaitingCount().first(), "the map's pill")
            assertEquals(listOf("p18"), db.localPhotos().observeWaitingPointIds("j1").first(), "the map's badge, this phone's part")
            assertEquals(setOf("p5"), pendingPointIds(listOf(ph5, gone), emptyList(), deleted = setOf("gone")), "and the server's part")
            assertEquals(0, db.localPhotos().observeOnPhoneCount("p19").first(), "the delete-point warning")
            val contents = JobList(api, db) { SERVER }.contents("j1")
            assertEquals(2 to 2, contents.photos to contents.undelivered, "the job's delete warning: ph5 and kept")
        }
        assertNull(runBlocking { exportPhotosToOneDriveApp(compose.activity, db, "j1") })
        val shared = File(context.cacheDir, "shared").walk().single { it.isFile }
        assertEquals(setOf("POLES/Pole 18/kept.jpg"), ZipFile(shared).use { zip -> zip.entries().toList().map { it.name }.toSet() }, "nor exported")

        // Nor offered for reuse, from this phone's copy of the job or from the server's lists.
        reuse.nearbyReply = { listOf(ReuseCandidate(gone, "POLE 19", "A05J9XE", 30.0), ReuseCandidate(ph5, "POLE 5", "A05J9XE", 80.0)) }
        reuse.jobReply = { listOf(ReuseCandidate(gone, "POLE 19", "A05J9XE", null)) }
        compose.setContent { FieldmaxxTheme { ReusePicker("j1", "q43", reuse, onDone = {}) } }
        waitForTag(ReuseTags.candidate("ph5"))
        assertFalse(exists(ReuseTags.candidate("gone")), "Nearby")
        press(ReuseTags.THIS_JOB)
        waitForTag(ReuseTags.candidate("ph5"))
        assertFalse(exists(ReuseTags.candidate("gone")), "This job")
    }

    @Test fun reusePickerWorksOfflineOnThisJob() { // R66
        seedJob()
        reuse.nearbyReply = { throw IOException("no signal") }
        reuse.jobReply = { throw IOException("no signal") }
        compose.setContent { FieldmaxxTheme { ReusePicker("j1", "q43", reuse, onDone = {}) } }

        waitForText(NEARBY_NEEDS_SIGNAL)
        press(ReuseTags.THIS_JOB)
        waitFor { exists(ReuseTags.candidate("ph5")) && exists(ReuseTags.candidate("ph6")) } // from the phone's own copy of the job
        assertEquals("POLE 6 · A05J9XE · 11/06/26", textOf(ReuseTags.line("ph6"), unmerged = true))

        press(ReuseTags.SEARCH)
        compose.onNodeWithTag(ReuseTags.SEARCH_FIELD).performTextInput("6")
        waitForTag(ReuseTags.candidate("ph6"))
        assertTrue(hasTextNode(SEARCH_OFFLINE), "it says the search covers this job's photos only")

        // Once there's signal again, Nearby can be asked again.
        reuse.nearbyReply = { listOf(ReuseCandidate(ph5, "POLE 5", "A05J9XE", 80.0)) }
        press(ReuseTags.NEARBY)
        waitForTag(ReuseTags.TRY_AGAIN)
        press(ReuseTags.TRY_AGAIN)
        waitForTag(ReuseTags.candidate("ph5"))
        assertFalse(hasTextNode(NEARBY_NEEDS_SIGNAL))
    }

    @Test fun reusedFromAnotherJobShowsWhereItCameFrom() { // R66: the picking phone keeps the source's label and job
        seedJob()
        val older = photo("phx", "px", jobId = "j0").copy(takenAt = NOV5 - 300 * DAY)
        reuse.nearbyReply = { listOf(ReuseCandidate(older, "POLE 5", "A04X1Y", 121.0)) }
        var done = false
        compose.setContent { FieldmaxxTheme { ReusePicker("j1", "q43", reuse, onDone = { done = true }) } }
        waitForTag(ReuseTags.candidate("phx"))
        press(ReuseTags.candidate("phx"))
        press(ReuseTags.USE)
        waitFor { done }

        // Its source isn't on this phone, so the copy comes with the server's change.
        val op = outbox().single() as Op.PhotoReuse
        assertNull(serverRow(op.photoId))
        val copy = photo(op.photoId, "q43").copy(userId = ALICE.id, reusedFrom = "phx", takenAt = older.takenAt)
        serverMovesOn(12) { it.copy(photos = it.photos + copy) }
        val views = runBlocking { photoViewsFor("q43", db, { SERVER }, { ZONE }).first() }
        assertEquals("reused from POLE 5 · A04X1Y · 01/09/26", views.single { it.id == op.photoId }.reuseNote)
        assertEquals("$SERVER/api/photos/phx/thumb", views.single { it.id == op.photoId }.thumb, "the original's thumbnail, which the server's copy shares")

        // Another phone's reuse from a job this phone doesn't have says only that much.
        val theirs = photo("theirs", "q43").copy(reusedFrom = "elsewhere", takenAt = NOV5)
        serverMovesOn(13) { it.copy(photos = it.photos + theirs) }
        val again = runBlocking { photoViewsFor("q43", db, { SERVER }, { ZONE }).first() }
        assertEquals("reused from another job · 11/05/26", again.single { it.id == "theirs" }.reuseNote)
        assertNull(again.single { it.id == "phq" }.reuseNote, "a photo taken here says nothing")
    }
}
