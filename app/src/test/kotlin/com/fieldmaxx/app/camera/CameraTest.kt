package com.fieldmaxx.app.camera

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.fieldmaxx.app.FieldmaxxApp
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.LocalPhotoEntity
import com.fieldmaxx.app.photos.CaptureMeta
import com.fieldmaxx.app.photos.ContentPickedMedia
import com.fieldmaxx.app.photos.LocalPhotos
import com.fieldmaxx.app.photos.PickedMedia
import com.fieldmaxx.app.photos.cameraMeta
import com.fieldmaxx.app.photos.rowFromMeta
import com.fieldmaxx.app.photos.stampMeta
import com.fieldmaxx.app.platform.Fix
import com.fieldmaxx.app.platform.Heading
import com.fieldmaxx.app.ui.FieldmaxxTheme
import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.stamp.stampLines
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

private val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

/** 11/05/26 13:35:47 at the job sites (UTC-08:00): the time on the spec's stamp. */
private val NOW = ZonedDateTime.of(2026, 11, 5, 13, 35, 47, 0, ZONE).toInstant().toEpochMilli()
private const val DAY = 86_400_000L

/** The spec's stamp position: 4.877 m is ±16 ft and 1350.26 m is 4430 ft (Task 3's arithmetic). */
private val FIX = Fix(39.636130, -119.691104, 4.877, 1350.26, NOW - 2_000)
private val HEADING = Heading(47.0, NOW - 100, reliable = true, trueNorth = true)
private val SPEC_STAMP = listOf("A05J9XE · POLE 1", "39.636130, -119.691104", "±16 ft · alt 4430 ft", "NE 47°", "11/05/26 13:35:47")

private val ALL_STATES = listOf("saved", "stamped", "registered", "confirmed")
private val PICKED: Uri = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1000000034")
private const val LOCATION_EXTRA = "android.provider.extra.REQUEST_LOCATION_METADATA_ACCESS"
private const val NOT_SAVED = "Photo not saved: the camera closed. Try again."

/**
 * The JPEG fixtures, made with Pillow, independently of the app's EXIF reader. `gps.jpg` is tagged as a phone camera tags
 * A05J9XE POLE 1: 39° 38' 10.068" N, 119° 41' 27.9744" W, 1350.26 m above sea level, GPSHPositioningError 4.877 m,
 * GPSImgDirection 47 (ref T), DateTimeOriginal 2026:11:05 13:35:47 with OffsetTimeOriginal -08:00, make and model.
 * `plain.jpg` is the same 64×48 picture with no EXIF at all.
 */
private fun fixture(name: String): ByteArray =
    CameraTest::class.java.classLoader!!.getResourceAsStream("camera/$name")!!.use { it.readBytes() }

/** What the camera writes for a photo here. */
private val JPEG: ByteArray by lazy { fixture("plain.jpg") }

/** One planned capture: it waits at [gate] (as while CameraX is still writing the photo), then fails with [failure] if set. */
private class Shot(val gate: CompletableDeferred<Unit>? = CompletableDeferred(), val failure: Exception? = null)

/** The camera's capture: the next [plan]ned shot, or else [gate] and [failure] for every one. */
private class FakeCapture : CaptureController {
    @Volatile var gate: CompletableDeferred<Unit>? = null
    @Volatile var failure: Exception? = null
    val plan = ConcurrentLinkedQueue<Shot>()
    val started = AtomicInteger()

    override suspend fun capture(to: File) {
        val shot = plan.poll() ?: Shot(gate, failure)
        started.incrementAndGet()
        shot.gate?.await()
        shot.failure?.let { throw it }
        to.writeBytes(JPEG)
    }
}

/** Picked photos by uri, as the phone's storage hands them over (their location hidden or not). */
private class FakeMedia : PickedMedia {
    val files = ConcurrentHashMap<Uri, () -> InputStream>()

    fun add(uri: Uri, bytes: ByteArray) {
        files[uri] = { ByteArrayInputStream(bytes) }
    }

    override fun open(uri: Uri): InputStream = files[uri]?.invoke() ?: throw FileNotFoundException("No photo at $uri")
}

private class FakeCameraDeps(override val photos: LocalPhotos, private val clock: () -> Long) : CameraDeps {
    val targetFlow = MutableStateFlow<CameraTarget?>(CameraTarget("A05J9XE", "POLE 1"))
    override val fixes = MutableStateFlow<Fix?>(FIX)
    override val heading = MutableStateFlow<Heading?>(HEADING)
    @Volatile var tech: String? = "u1"
    override fun target(jobId: String, pointId: String): Flow<CameraTarget?> = targetFlow
    override suspend fun techId(): String? = tech
    override fun now(): Long = clock()
    override fun zone(): ZoneId = ZONE
}

/** What the screen asked the system for, and the contract it asked with. */
private class Launch(val contract: ActivityResultContract<*, *>, val input: Any?) {
    @Suppress("UNCHECKED_CAST")
    fun intent(context: Context): Intent = (contract as ActivityResultContract<Any?, *>).createIntent(context, input)
}

/** Records what the screen asks the system for, instead of showing a permission dialog or the photo picker. */
private class RecordingRegistry : ActivityResultRegistry() {
    val launches = CopyOnWriteArrayList<Launch>()
    @Volatile private var lastCode = -1

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        lastCode = requestCode
        launches += Launch(contract, input)
    }

    /** The system's answer to the last request. */
    fun answer(result: Any?) {
        dispatchResult(lastCode, result)
    }
}

/** An in-memory database that runs [beforeWrite] as each LocalPhotoEntity row is written; it may throw, as a full disk does. */
private class HookedDb(context: Context, private val beforeWrite: () -> Unit) {
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
            hook()
            s.execute()
        }

        override fun executeInsert(): Long {
            hook()
            return s.executeInsert()
        }

        override fun executeUpdateDelete(): Int {
            hook()
            return s.executeUpdateDelete()
        }

        private fun hook() {
            if ("LocalPhotoEntity" in sql) beforeWrite()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val started = CopyOnWriteArrayList<String>()
    private val media = FakeMedia()
    @Volatile private var clock = NOW
    private val filesDir: File by lazy { temp.newFolder("files") }
    private val photos: LocalPhotos by lazy { LocalPhotos(db, filesDir, { started += it }, { clock }, media, { ZONE }) }
    private val deps: FakeCameraDeps by lazy { FakeCameraDeps(photos) { clock } }
    private val capture = FakeCapture()
    private val registry = RecordingRegistry()
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }
    private val backs = AtomicInteger()

    @After fun closeDb() {
        db.close()
    }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { FieldmaxxTheme { content() } }
    }

    /** Shown while [shown], so a test can rebuild the screen as a rotation does; what outlives the screen stays. */
    private val shown = mutableStateOf(true)

    private fun show(with: FakeCameraDeps = deps) = compose.setContent {
        Host { if (shown.value) CameraScreen("j1", "p1", with, capture, onBack = { backs.incrementAndGet() }) }
    }

    /** "Pick from gallery" on its own, as the point sheet offers it. */
    private fun showGalleryButton() = compose.setContent {
        Host {
            val gallery = rememberGalleryPick(deps)
            Button(onClick = { gallery.pick("j1", "p1") }) { Text("Pick from gallery") }
        }
    }

    private fun grant(vararg permissions: String) = shadowOf(context as Application).grantPermissions(*permissions)

    /** Lets the main looper run between checks too: under Robolectric that's what delivers state written outside a frame. */
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(5_000) {
        compose.waitForIdle()
        condition()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun waitForText(text: String) = waitFor { shows(text) }
    private fun enabled(tag: String) =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
    private fun textOf(tag: String): String? =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("")

    private fun press(tag: String) {
        compose.onNodeWithTag(tag).performClick()
    }

    private fun rows(): List<LocalPhotoEntity> = runBlocking { db.localPhotos().inStates(ALL_STATES) }
    private fun meta(row: LocalPhotoEntity): CaptureMeta = FmxJson.decodeFromString(CaptureMeta.serializer(), row.metaJson)
    private fun leftOnDisk(): List<File> = filesDir.walk().filter { it.isFile }.toList()

    /** [jpeg] with its GPS values zeroed where they lie, which is how Android hides a photo's location from an app. */
    private fun redacted(jpeg: ByteArray): ByteArray {
        val exif = ExifInterface(temp.newFile().apply { writeBytes(jpeg) })
        val bytes = jpeg.copyOf()
        val gpsTags = listOf(
            ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        )
        for (tag in gpsTags) {
            val (offset, length) = exif.getAttributeRange(tag) ?: continue
            bytes.fill(0, offset.toInt(), (offset + length).toInt())
        }
        return bytes
    }

    // ---- the brief's tests ----

    @Test fun shutterUsesHeadingAndFixAtPressTime() {
        grant(Manifest.permission.CAMERA)
        capture.gate = CompletableDeferred()
        show()
        waitFor { enabled(CameraTags.SHUTTER) }
        press(CameraTags.SHUTTER)
        waitFor { capture.started.get() == 1 }

        // While the camera is still writing the photo, the phone turns, the position moves and the clock goes on.
        deps.fixes.value = Fix(39.7, -119.8, 30.0, 1200.0, NOW + 4_000)
        deps.heading.value = Heading(200.0, NOW + 4_000, reliable = true, trueNorth = false)
        clock = NOW + 5_000
        capture.gate!!.complete(Unit)

        waitFor { started.size == 1 } // once saved: the row comes first, then the pipeline's start
        val row = rows().single()
        assertEquals(
            CaptureMeta(
                "A05J9XE", "POLE 1", "camera", FIX.lat, FIX.lon, FIX.accuracyM, FIX.altitudeM, 47.0, NOW, gpsStale = false,
                headingTrueNorth = true, techId = "u1",
            ),
            meta(row),
            "what the phone read as the shutter was pressed",
        )
        assertEquals(SPEC_STAMP, stampLines(meta(row).stampMeta(ZONE)))
        assertEquals(listOf(row.id), started.toList())
    }

    @Test fun staleFixMarked() {
        val tenSecondsOld = FIX.copy(at = NOW - 10_000)
        val older = FIX.copy(at = NOW - 10_001)
        assertEquals(SPEC_STAMP, overlayLines("A05J9XE", "POLE 1", tenSecondsOld, HEADING, NOW, ZONE), "10 s old is still fresh")
        val stale = overlayLines("A05J9XE", "POLE 1", older, HEADING, NOW, ZONE)
        assertEquals("39.636130, -119.691104 (GPS stale)", stale[1])
        assertTrue(cameraMeta("A05J9XE", "POLE 1", older, HEADING, NOW).gpsStale, "the photo is saved as stale too")
        assertFalse(cameraMeta("A05J9XE", "POLE 1", tenSecondsOld, HEADING, NOW).gpsStale)
        assertEquals(stampLines(cameraMeta("A05J9XE", "POLE 1", older, HEADING, NOW).stampMeta(ZONE)), stale, "the overlay is the stamp")
        assertEquals(listOf("A05J9XE · POLE 1", "No GPS", "NE 47°", "11/05/26 13:35:47"), overlayLines("A05J9XE", "POLE 1", null, HEADING, NOW, ZONE))

        // On screen, the fix goes stale as the clock runs, and the overlay follows within its refresh.
        grant(Manifest.permission.CAMERA)
        show()
        waitForText("39.636130, -119.691104")
        clock = FIX.at + 10_001
        waitForText("39.636130, -119.691104 (GPS stale)")
    }

    @Test fun originalPersistedBeforePipelineStarts() = runBlocking<Unit> {
        var written = false
        val checked = CopyOnWriteArrayList<String>()
        val checking = LocalPhotos(
            db, filesDir,
            starter = { id ->
                assertTrue(written, "the write is over")
                val row = runBlocking { db.localPhotos().get(id) }
                assertNotNull(row, "the row is in the database")
                assertEquals("saved", row.state)
                assertContentEquals(JPEG, File(row.originalPath).readBytes(), "the whole original is on disk")
                checked += id
            },
            now = { clock }, media = media, zone = { ZONE },
        )
        val meta = cameraMeta("A05J9XE", "POLE 1", FIX, HEADING, NOW)

        val id = checking.saveCaptured("j1", "p1", meta) { file ->
            file.outputStream().use { out ->
                for (chunk in JPEG.asList().chunked(256)) {
                    out.write(chunk.toByteArray())
                    yield()
                }
            }
            written = true
        }

        assertEquals(listOf(id), checked.toList(), "the pipeline started once the photo was safe")
        val original = File(filesDir, "photos/$id/original.jpg")
        assertEquals(
            LocalPhotoEntity(
                id = id, jobId = "j1", pointId = "p1", originalPath = original.path, stampedPath = null,
                metaJson = FmxJson.encodeToString(CaptureMeta.serializer(), meta), state = "saved", fileName = null,
                oneDriveUploadId = null, oneDriveOffset = 0, createdThisFile = false, backupOffset = 0, backupDone = false,
                attempts = 0, nextAttemptAt = 0, lastError = null, stuck = false, createdAt = NOW, confirmedAt = null,
            ),
            db.localPhotos().get(id),
        )
    }

    @Test fun failingWriteLeavesNoRow() = runBlocking<Unit> {
        val meta = cameraMeta("A05J9XE", "POLE 1", FIX, HEADING, NOW)
        val error = assertFailsWith<IOException> {
            photos.saveCaptured("j1", "p1", meta) { file ->
                file.outputStream().use { it.write(JPEG, 0, JPEG.size / 2) }
                throw IOException("No space left on device")
            }
        }
        assertEquals("No space left on device", error.message, "the caller hears why")
        assertEquals(emptyList(), db.localPhotos().inStates(ALL_STATES), "no row")
        assertEquals(emptyList(), started.toList(), "nothing to deliver")
    }

    @Test fun galleryUsesExifGpsWhenPresent() = runBlocking<Unit> {
        media.add(PICKED, fixture("gps.jpg"))
        clock = NOW + 3 * DAY // picked three days after it was taken, somewhere else
        val here = Fix(39.7, -119.8, 30.0, 1200.0, clock - 1_000)

        val id = photos.saveFromGallery("j1", "p1", PICKED, "A05J9XE", "POLE 1", fallback = here)

        val row = assertNotNull(db.localPhotos().get(id))
        val meta = meta(row)
        assertEquals("gallery", meta.source)
        assertEquals(39.636130, assertNotNull(meta.lat), 1e-6)
        assertEquals(-119.691104, assertNotNull(meta.lon), 1e-6)
        assertEquals(4.877, assertNotNull(meta.accuracyM), 1e-9)
        assertEquals(1350.26, assertNotNull(meta.altitudeM), 1e-9)
        assertEquals(47.0, meta.headingDeg)
        assertTrue(meta.headingTrueNorth, "GPSImgDirectionRef T")
        assertEquals(NOW, meta.takenAt, "its own time: 13:35:47 at UTC-08:00")
        assertFalse(meta.gpsStale)
        assertEquals(SPEC_STAMP.dropLast(1) + "11/05/26 13:35:47 (from gallery)", stampLines(meta.stampMeta(ZONE)))

        assertEquals("saved", row.state)
        assertEquals(File(filesDir, "photos/$id/original.jpg").path, row.originalPath)
        assertContentEquals(fixture("gps.jpg"), File(row.originalPath).readBytes(), "the photo itself, copied into app storage")
        assertEquals(clock, row.createdAt)
        assertEquals(listOf(id), started.toList())
    }

    @Test fun galleryWithoutExifUsesFixAndMarksFromGallery() = runBlocking<Unit> {
        media.add(PICKED, fixture("plain.jpg"))
        val here = FIX.copy(at = NOW - 3_000)

        val first = meta(assertNotNull(db.localPhotos().get(photos.saveFromGallery("j1", "p1", PICKED, "A05J9XE", "POLE 1", here))))
        assertEquals(
            CaptureMeta("A05J9XE", "POLE 1", "gallery", here.lat, here.lon, here.accuracyM, here.altitudeM, null, NOW, gpsStale = false),
            first,
            "where the phone is and the time now; no compass reading belongs to a picked photo",
        )
        assertEquals(
            listOf("A05J9XE · POLE 1", "39.636130, -119.691104", "±16 ft · alt 4430 ft", "Compass n/a", "11/05/26 13:35:47 (from gallery)"),
            stampLines(first.stampMeta(ZONE)),
        )

        // A fix older than 10 s is marked as on the camera; with no fix there's no position rather than a made-up one.
        val stale = meta(assertNotNull(db.localPhotos().get(photos.saveFromGallery("j1", "p1", PICKED, "A05J9XE", "POLE 1", FIX.copy(at = NOW - 60_000)))))
        assertEquals("39.636130, -119.691104 (GPS stale)", stampLines(stale.stampMeta(ZONE))[1])
        val none = meta(assertNotNull(db.localPhotos().get(photos.saveFromGallery("j1", "p1", PICKED, "A05J9XE", "POLE 1", null))))
        assertEquals(listOf("A05J9XE · POLE 1", "No GPS", "Compass n/a", "11/05/26 13:35:47 (from gallery)"), stampLines(none.stampMeta(ZONE)))
        assertEquals(3, started.size)
    }

    @Test fun burstCounterIncrements() {
        grant(Manifest.permission.CAMERA)
        show()
        waitFor { enabled(CameraTags.SHUTTER) }
        assertFalse(exists(CameraTags.COUNT), "nothing taken yet")

        for (n in 1..3) {
            press(CameraTags.SHUTTER)
            waitFor { textOf(CameraTags.COUNT) == if (n == 1) "1 photo taken" else "$n photos taken" }
        }

        waitFor { started.size == 3 }
        val saved = rows()
        assertEquals(3, saved.map { it.id }.toSet().size, "three photos in a row, each its own")
        assertTrue(saved.all { File(it.originalPath).readBytes().contentEquals(JPEG) })
        assertEquals(saved.map { it.id }.toSet(), started.toSet())
        assertEquals(0, backs.get(), "without leaving the camera")
    }

    @Test fun compassCalibrationHintWhenUnreliable() {
        assertEquals("Compass: calibrate", calibrationHint(HEADING.copy(reliable = false)))
        assertNull(calibrationHint(HEADING))
        assertNull(calibrationHint(null), "no compass reading: nothing to calibrate")

        grant(Manifest.permission.CAMERA)
        deps.heading.value = HEADING.copy(reliable = false)
        show()
        waitForText("Compass: calibrate")
        assertTrue(shows("NE 47°"), "the reading still shows: it is what will be stamped")

        deps.heading.value = HEADING
        waitFor { !shows("Compass: calibrate") }
    }

    // ---- the rulings ----

    @Test fun headingTrueNorthFollowsTheReading() { // R51
        assertTrue(cameraMeta("A05J9XE", "POLE 1", FIX, HEADING.copy(trueNorth = true), NOW).headingTrueNorth)
        assertFalse(cameraMeta("A05J9XE", "POLE 1", FIX, HEADING.copy(trueNorth = false), NOW).headingTrueNorth, "magnetic until a fix is known")
        assertFalse(cameraMeta("A05J9XE", "POLE 1", FIX, null, NOW).headingTrueNorth)

        // Kept in the row the pipeline reads; a row without the field reads as magnetic.
        val meta = cameraMeta("A05J9XE", "POLE 1", FIX, HEADING, NOW)
        assertEquals(meta, FmxJson.decodeFromString(CaptureMeta.serializer(), FmxJson.encodeToString(CaptureMeta.serializer(), meta)))
        val older = """{"jobName":"A05J9XE","pointLabel":"POLE 1","source":"camera","lat":null,"lon":null,"accuracyM":null,""" +
            """"altitudeM":null,"headingDeg":10.0,"takenAt":1,"gpsStale":false}"""
        assertFalse(FmxJson.decodeFromString(CaptureMeta.serializer(), older).headingTrueNorth)

        // Through the shutter: a magnetic reading is saved as magnetic.
        grant(Manifest.permission.CAMERA)
        deps.heading.value = HEADING.copy(trueNorth = false)
        show()
        waitFor { enabled(CameraTags.SHUTTER) }
        press(CameraTags.SHUTTER)
        waitFor { rows().size == 1 }
        assertFalse(meta(rows().single()).headingTrueNorth)
    }

    @Test fun galleryWithoutMediaLocationUsesFix() = runBlocking<Unit> { // R52, R52a
        // Without ACCESS_MEDIA_LOCATION (and unless the picker asked the tech), Android hands over the photo with its
        // location zeroed.
        val hidden = redacted(fixture("gps.jpg"))
        val opened = CopyOnWriteArrayList<Uri>()
        val asAndroidGivesIt = ContentPickedMedia(sdk = 35, hasPermission = { false }, openStream = { uri ->
            opened += uri
            ByteArrayInputStream(hidden)
        })
        val local = LocalPhotos(db, filesDir, { started += it }, { clock }, asAndroidGivesIt, { ZONE })
        clock = NOW + DAY
        val here = Fix(39.7, -119.8, 30.0, 1200.0, clock - 1_000)

        val meta = meta(assertNotNull(db.localPhotos().get(local.saveFromGallery("j1", "p1", PICKED, "A05J9XE", "POLE 1", here))))

        assertEquals(listOf(PICKED), opened.toList(), "no original asked for without the permission")
        assertEquals(listOf(here.lat, here.lon, here.accuracyM, here.altitudeM), listOf(meta.lat, meta.lon, meta.accuracyM, meta.altitudeM), "the fix stands in")
        assertNull(meta.headingDeg, "the photo's direction was hidden with it")
        assertEquals(NOW, meta.takenAt, "its own time is not location data: kept")
        assertEquals("11/05/26 13:35:47 (from gallery)", stampLines(meta.stampMeta(ZONE)).last())
    }

    @Test fun locationThePickerSharesIsUsed() = runBlocking<Unit> { // R52a: a newer picker asks the tech itself
        val shared = ContentPickedMedia(sdk = 35, hasPermission = { false }, openStream = { ByteArrayInputStream(fixture("gps.jpg")) })
        val local = LocalPhotos(db, filesDir, { started += it }, { clock }, shared, { ZONE })
        val here = Fix(39.7, -119.8, 30.0, 1200.0, NOW - 1_000)

        val meta = meta(assertNotNull(db.localPhotos().get(local.saveFromGallery("j1", "p1", PICKED, "A05J9XE", "POLE 1", here))))

        assertEquals(SPEC_STAMP.dropLast(1) + "11/05/26 13:35:47 (from gallery)", stampLines(meta.stampMeta(ZONE)), "its own spot, permission or not")
        assertTrue(meta.headingTrueNorth)
    }

    @Test fun hiddenLocationInTheFileUsesFix() = runBlocking<Unit> { // R52: a newer picker hides it unless the user agrees
        media.add(PICKED, redacted(fixture("gps.jpg")))
        val here = FIX.copy(lat = 39.7, lon = -119.8, at = NOW - 1_000)

        val meta = meta(assertNotNull(db.localPhotos().get(photos.saveFromGallery("j1", "p1", PICKED, "A05J9XE", "POLE 1", here))))

        assertEquals("39.700000, -119.800000", stampLines(meta.stampMeta(ZONE))[1], "never 0, 0 from zeroed values")
        assertNull(meta.headingDeg)
        assertEquals(NOW, meta.takenAt)

        // Some apps write 0, 0 for "no position": that isn't where the photo was taken either.
        val nullIsland = temp.newFile().apply { writeBytes(fixture("gps.jpg")) }
        ExifInterface(nullIsland).apply {
            setLatLong(0.0, 0.0)
            saveAttributes()
        }
        media.add(PICKED, nullIsland.readBytes())
        val zero = meta(assertNotNull(db.localPhotos().get(photos.saveFromGallery("j1", "p1", PICKED, "A05J9XE", "POLE 1", here))))
        assertEquals("39.700000, -119.800000", stampLines(zero.stampMeta(ZONE))[1])
    }

    @Test fun mediaLocationAskedOnceBeforeTheFirstPick() { // R52
        media.add(PICKED, fixture("plain.jpg"))
        showGalleryButton()

        compose.onNodeWithText("Pick from gallery").performClick()
        waitForText("Fieldmaxx will ask to read photo locations, so gallery photos keep the spot they were taken.")
        assertEquals(0, registry.launches.size, "the reason comes first")
        press(GalleryTags.REASON_OK)
        waitFor { registry.launches.size == 1 }
        assertEquals(Manifest.permission.ACCESS_MEDIA_LOCATION, registry.launches[0].input)

        compose.runOnUiThread { registry.answer(false) } // refused
        waitFor { registry.launches.size == 2 }
        val pick = registry.launches[1]
        assertEquals(PickVisualMedia.ImageOnly, (pick.input as PickVisualMediaRequest).mediaType, "photos only")
        assertTrue(pick.intent(context).getBooleanExtra(LOCATION_EXTRA, false), "the picker is always asked for the photos' locations (R52a)")
        compose.runOnUiThread { registry.answer(listOf(PICKED)) }
        waitFor { rows().size == 1 }
        val row = rows().single()
        assertEquals(listOf("j1", "p1", "gallery"), listOf(row.jobId, row.pointId, meta(row).source))
        waitFor { ShadowToast.getTextOfLatestToast() == "Added 1 photo to POLE 1." }

        // Asked once: the next pick goes straight to the picker.
        compose.onNodeWithText("Pick from gallery").performClick()
        waitFor { registry.launches.size == 3 }
        assertTrue(registry.launches[2].input is PickVisualMediaRequest)
        compose.onAllNodesWithTag(GalleryTags.REASON).assertCountEquals(0)

        // Allowed later in the settings: still no question, and still the picker is asked for the locations.
        grant(Manifest.permission.ACCESS_MEDIA_LOCATION)
        compose.onNodeWithText("Pick from gallery").performClick()
        waitFor { registry.launches.size == 4 }
        assertTrue(registry.launches[3].intent(context).getBooleanExtra(LOCATION_EXTRA, false))
    }

    @Test fun mediaLocationReadsTheOriginal() { // R52
        val original = MediaStore.setRequireOriginal(PICKED)
        var granted = false
        var originalRefused = false
        val opened = CopyOnWriteArrayList<Uri>()
        val open = { uri: Uri ->
            opened += uri
            if (uri == original && originalRefused) throw UnsupportedOperationException("Caller must hold ACCESS_MEDIA_LOCATION to access original")
            ByteArrayInputStream(uri.toString().toByteArray())
        }
        val picked = ContentPickedMedia(sdk = 35, hasPermission = { granted }, openStream = open)
        fun read(uri: Uri) = picked.open(uri).use { it.readBytes().decodeToString() }

        assertEquals(PICKED.toString(), read(PICKED), "without the permission: the photo as Android gives it")
        granted = true
        assertEquals(original.toString(), read(PICKED), "with it: the original, location included")
        originalRefused = true
        assertEquals(PICKED.toString(), read(PICKED), "a uri that can't give its original still gives the photo")
        val document = Uri.parse("content://com.android.providers.downloads.documents/document/12")
        assertEquals(document.toString(), read(document), "not a media uri: nothing to ask")
        assertEquals(listOf(PICKED, original, original, PICKED, document), opened.toList())

        val api28 = ContentPickedMedia(sdk = 28, hasPermission = { true }, openStream = open)
        assertEquals(PICKED.toString(), api28.open(PICKED).use { it.readBytes().decodeToString() }, "Android 9 hides nothing: no original to ask for")
    }

    @Test fun cameraRefusedOffersSettingsAndGallery() { // R53
        show()
        waitFor { registry.launches.size == 1 }
        assertEquals(Manifest.permission.CAMERA, registry.launches[0].input, "asked on entering the camera")
        compose.runOnUiThread { registry.answer(false) }

        waitFor { exists(CameraTags.REFUSED) }
        assertFalse(exists(CameraTags.SHUTTER))
        press(CameraTags.SETTINGS)
        val app = shadowOf(context as Application)
        waitFor { app.peekNextStartedActivity() != null }
        val settings = app.nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
        assertEquals(Uri.fromParts("package", context.packageName, null), settings.data)

        grant(Manifest.permission.ACCESS_MEDIA_LOCATION) // so the pick goes straight to the picker
        press(CameraTags.GALLERY)
        waitFor { registry.launches.size == 2 }
        assertTrue(registry.launches[1].input is PickVisualMediaRequest, "the gallery is still offered")
    }

    @Test fun cameraAndMediaLocationDeclared() { // R52, R53
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        val requested = info.requestedPermissions.orEmpty().toList()
        assertTrue(Manifest.permission.CAMERA in requested, "$requested")
        assertTrue(Manifest.permission.ACCESS_MEDIA_LOCATION in requested, "$requested")
    }

    @Test fun failedWriteLeavesNoPartialFile() = runBlocking<Unit> { // R54
        val meta = cameraMeta("A05J9XE", "POLE 1", FIX, HEADING, NOW)
        // Half a photo, then the disk is full.
        assertFailsWith<IOException> {
            photos.saveCaptured("j1", "p1", meta) { file ->
                file.outputStream().use { it.write(JPEG, 0, 100) }
                throw IOException("No space left on device")
            }
        }
        // The camera says it's done but wrote nothing, or an empty file.
        assertFailsWith<IOException> { photos.saveCaptured("j1", "p1", meta) { } }
        assertFailsWith<IOException> { photos.saveCaptured("j1", "p1", meta) { it.createNewFile() } }
        // The photo is written, but its meta.json can't be (R80).
        assertFailsWith<IOException> {
            photos.saveCaptured("j1", "p1", meta) { file ->
                file.writeBytes(JPEG)
                File(file.parentFile, "meta.json").mkdir() // something in its way
            }
        }
        // A picked photo that can't be read, or whose reading breaks off.
        assertFailsWith<FileNotFoundException> { photos.saveFromGallery("j1", "p1", Uri.parse("content://media/picker/0/x/media/9"), "A05J9XE", "POLE 1", FIX) }
        media.files[PICKED] = {
            object : InputStream() {
                private var left = 300
                override fun read(): Int = if (left-- > 0) 0xFF else throw IOException("The photo is no longer there")
            }
        }
        assertFailsWith<IOException> { photos.saveFromGallery("j1", "p1", PICKED, "A05J9XE", "POLE 1", FIX) }

        assertEquals(emptyList(), leftOnDisk(), "no partial file")
        assertEquals(emptyList(), File(filesDir, "photos").listFiles().orEmpty().toList(), "no photo folder either")
        assertEquals(emptyList(), db.localPhotos().inStates(ALL_STATES))
        assertEquals(emptyList(), started.toList())
    }

    @Test fun failedRecordLeavesNoFile() = runBlocking<Unit> { // R54: the row and the file go together
        val full = HookedDb(context) { throw SQLiteFullException("database or disk is full") }
        try {
            val local = LocalPhotos(full.db, filesDir, { started += it }, { clock }, media, { ZONE })
            assertFailsWith<SQLiteFullException> { local.saveCaptured("j1", "p1", cameraMeta("A05J9XE", "POLE 1", FIX, HEADING, NOW)) { it.writeBytes(JPEG) } }
            assertEquals(emptyList(), leftOnDisk(), "a photo the database doesn't know isn't kept")
            assertEquals(emptyList(), started.toList())
        } finally {
            full.db.close()
        }
    }

    @Test fun countSurvivesTheScreenBeingRebuilt() { // turning the phone rebuilds the screen: the count stays
        grant(Manifest.permission.CAMERA)
        show()
        waitFor { enabled(CameraTags.SHUTTER) }
        press(CameraTags.SHUTTER)
        waitFor { textOf(CameraTags.COUNT) == "1 photo taken" }
        press(CameraTags.SHUTTER)
        waitFor { textOf(CameraTags.COUNT) == "2 photos taken" }

        compose.runOnUiThread { shown.value = false }
        waitFor { !exists(CameraTags.SHUTTER) }
        compose.runOnUiThread { shown.value = true }
        waitFor { enabled(CameraTags.SHUTTER) }
        assertEquals("2 photos taken", textOf(CameraTags.COUNT))
    }

    @Test fun failedPhotoSaysWhyAndBackStays() {
        grant(Manifest.permission.CAMERA)
        capture.gate = CompletableDeferred()
        capture.failure = IOException("the camera closed")
        show()
        waitFor { enabled(CameraTags.SHUTTER) }
        press(CameraTags.SHUTTER)
        waitFor { capture.started.get() == 1 }
        press(CameraTags.CLOSE) // the tech is leaving while it's written
        capture.gate!!.complete(Unit)

        waitFor { exists(CameraTags.UNSAVED) } // it stays to say so
        assertTrue(shows(NOT_SAVED))
        assertEquals(0, backs.get())
        assertFalse(exists(CameraTags.COUNT))
        assertEquals(emptyList(), rows())
        assertEquals(emptyList(), leftOnDisk())

        // Staying keeps it on screen until it is read.
        press(CameraTags.UNSAVED_STAY)
        waitFor { !exists(CameraTags.UNSAVED) }
        assertEquals(NOT_SAVED, textOf(CameraTags.PROBLEM))
        press(CameraTags.PROBLEM_OK)
        waitFor { !exists(CameraTags.PROBLEM) }

        // The next photo works; with nothing unread, Back goes at once.
        capture.gate = null
        capture.failure = null
        press(CameraTags.SHUTTER)
        waitFor { textOf(CameraTags.COUNT) == "1 photo taken" }
        press(CameraTags.CLOSE)
        waitFor { backs.get() == 1 }
    }

    @Test fun failureStaysVisibleThroughALaterSuccess() { // R79
        grant(Manifest.permission.CAMERA)
        val failing = Shot(failure = IOException("the camera closed"))
        val fine = Shot()
        capture.plan += listOf(failing, fine)
        show()
        waitFor { enabled(CameraTags.SHUTTER) }
        press(CameraTags.SHUTTER)
        press(CameraTags.SHUTTER)
        waitFor { capture.started.get() == 2 }

        failing.gate!!.complete(Unit)
        waitFor { textOf(CameraTags.PROBLEM) == NOT_SAVED }
        fine.gate!!.complete(Unit)
        waitFor { textOf(CameraTags.COUNT) == "1 photo taken" }
        assertEquals(NOT_SAVED, textOf(CameraTags.PROBLEM), "a later photo doesn't hide the failure")

        // Leaving with it unread shows it first.
        press(CameraTags.CLOSE)
        waitFor { exists(CameraTags.UNSAVED) }
        assertEquals(0, backs.get())
        press(CameraTags.UNSAVED_LEAVE)
        waitFor { backs.get() == 1 }
    }

    @Test fun newPressClearsTheFailure() { // R79: pressing again is reading it
        grant(Manifest.permission.CAMERA)
        capture.failure = IOException("the camera closed")
        show()
        waitFor { enabled(CameraTags.SHUTTER) }
        press(CameraTags.SHUTTER)
        waitFor { textOf(CameraTags.PROBLEM) == NOT_SAVED }

        capture.failure = null
        capture.gate = CompletableDeferred()
        press(CameraTags.SHUTTER)
        waitFor { !exists(CameraTags.PROBLEM) } // as it's pressed, before the photo is written
        assertEquals(emptyList(), rows())
        capture.gate!!.complete(Unit)
        waitFor { textOf(CameraTags.COUNT) == "1 photo taken" }
        assertFalse(exists(CameraTags.PROBLEM))
    }

    @Test fun starterFailureStillCountsAsSaved() { // R79: a saved photo is never called unsaved
        val failingStart = LocalPhotos(db, filesDir, { throw IOException("the pipeline couldn't be scheduled") }, { clock }, media, { ZONE })
        grant(Manifest.permission.CAMERA)
        show(FakeCameraDeps(failingStart) { clock })
        waitFor { enabled(CameraTags.SHUTTER) }
        press(CameraTags.SHUTTER)

        waitFor { textOf(CameraTags.COUNT) == "1 photo taken" }
        assertFalse(exists(CameraTags.PROBLEM), "no 'Try again': a retake would upload twice")
        assertEquals(1, rows().size)
        // The gallery's count too: the pick returns its photo.
        media.add(PICKED, fixture("plain.jpg"))
        val id = runBlocking { failingStart.saveFromGallery("j1", "p1", PICKED, "A05J9XE", "POLE 1", FIX) }
        assertNotNull(runBlocking { db.localPhotos().get(id) })
    }

    @Test fun captureAndPickCarryTheSignedInTech() { // R78
        grant(Manifest.permission.CAMERA, Manifest.permission.ACCESS_MEDIA_LOCATION)
        media.add(PICKED, fixture("plain.jpg"))
        compose.setContent {
            Host {
                Box(Modifier.fillMaxSize()) {
                    CameraScreen("j1", "p1", deps, capture, onBack = {})
                    val gallery = rememberGalleryPick(deps)
                    Button(onClick = { gallery.pick("j1", "p2") }, modifier = Modifier.align(Alignment.Center)) { Text("Pick from gallery") }
                }
            }
        }
        waitFor { enabled(CameraTags.SHUTTER) }
        press(CameraTags.SHUTTER)
        waitFor { started.size == 1 }
        compose.onNodeWithText("Pick from gallery").performClick()
        waitFor { registry.launches.size == 1 }
        compose.runOnUiThread { registry.answer(listOf(PICKED)) }
        waitFor { started.size == 2 }

        assertEquals(mapOf("p1" to "u1", "p2" to "u1"), rows().associate { it.pointId to meta(it).techId }, "the camera's photo and the picked one")
    }

    @Test fun revokedCameraAsksAgainOnReturn() { // "Only this time" ends while the app is away
        grant(Manifest.permission.CAMERA)
        show()
        waitFor { enabled(CameraTags.SHUTTER) }
        assertEquals(0, registry.launches.size)

        shadowOf(context as Application).denyPermissions(Manifest.permission.CAMERA)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED) // the phone is locked on this screen
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) // and unlocked
        waitFor { registry.launches.size == 1 }
        assertEquals(Manifest.permission.CAMERA, registry.launches.single().input, "asked again, not a black screen")
        assertFalse(exists(CameraTags.SHUTTER))
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED) // the system's dialog comes and goes
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(1, registry.launches.size, "one request at a time")

        compose.runOnUiThread { registry.answer(false) }
        waitFor { exists(CameraTags.REFUSED) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(1, registry.launches.size, "refused: not asked again on every return")
        assertTrue(exists(CameraTags.REFUSED))
    }

    @Test fun databaseSyncsEveryCommit() = runBlocking<Unit>(Dispatchers.IO) { // R80: a dead battery keeps what was saved
        val opened = AppDatabase.open(context)
        try {
            assertTrue(opened.openHelper.writableDatabase.isWriteAheadLoggingEnabled, "WAL as on a phone, whose default is NORMAL")
            // The level belongs to each connection: read it on the one that writes, inside a transaction.
            val mode = opened.runInTransaction<Long> {
                opened.openHelper.writableDatabase.query("PRAGMA synchronous").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getLong(0)
                }
            }
            assertEquals(2L, mode, "FULL: each commit is on the disk before it returns")
        } finally {
            opened.close()
        }
    }

    @Test fun metaJsonOnDiskBeforeTheRow() = runBlocking<Unit> { // R80
        val atWrite = CopyOnWriteArrayList<LocalPhotoEntity>()
        val hooked = HookedDb(context) {
            for (dir in File(filesDir, "photos").listFiles().orEmpty()) atWrite += rowFromMeta(File(dir, "meta.json"))
        }
        try {
            val local = LocalPhotos(hooked.db, filesDir, { started += it }, { clock }, media, { ZONE })
            val id = local.saveCaptured("j1", "p1", cameraMeta("A05J9XE", "POLE 1", FIX, HEADING, NOW).copy(techId = "u1")) { it.writeBytes(JPEG) }
            assertEquals(listOf(assertNotNull(hooked.db.localPhotos().get(id))), atWrite.toList(), "meta.json was on the disk, whole, as the row went in")
        } finally {
            hooked.db.close()
        }
    }

    @Test fun metaJsonRebuildsTheRow() = runBlocking<Unit> { // R80: for Task 28's sweep after a power cut
        val camera = photos.saveCaptured("j1", "p1", cameraMeta("A05J9XE", "POLE 1", FIX, HEADING, NOW).copy(techId = "u1")) { it.writeBytes(JPEG) }
        media.add(PICKED, fixture("gps.jpg"))
        val picked = photos.saveFromGallery("j1", "p2", PICKED, "A05J9XE", "POLE 2", FIX, techId = "u1")

        for (id in listOf(camera, picked)) {
            assertEquals(assertNotNull(db.localPhotos().get(id)), rowFromMeta(File(filesDir, "photos/$id/meta.json")), "the row, from the file beside the photo")
        }
    }

    @Test fun cameraUsesTheCameraHeading() { // R35a: the stamp's heading is where the back camera points
        val graph = (context as FieldmaxxApp).graph
        val deps = GraphCameraDeps(graph)
        assertSame(graph.compass.cameraHeading, deps.heading)
        assertNotSame(graph.compass.heading, deps.heading, "not the map's screen-top heading")
    }

    @Test fun backWaitsForThePhotoBeingSaved() {
        grant(Manifest.permission.CAMERA)
        capture.gate = CompletableDeferred()
        show()
        waitFor { enabled(CameraTags.SHUTTER) }
        press(CameraTags.SHUTTER)
        waitFor { capture.started.get() == 1 }

        press(CameraTags.CLOSE)
        waitFor { exists(CameraTags.SAVING) }
        assertEquals(0, backs.get(), "the camera stays until the photo is saved")
        capture.gate!!.complete(Unit)
        waitFor { backs.get() == 1 }
        assertEquals(1, rows().size, "and the photo was saved")
    }
}
