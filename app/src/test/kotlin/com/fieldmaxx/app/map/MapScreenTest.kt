package com.fieldmaxx.app.map

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.net.DEFAULT_CONFIG
import com.fieldmaxx.app.platform.Fix
import com.fieldmaxx.app.sync.SyncStatus
import com.fieldmaxx.app.sync.job
import com.fieldmaxx.app.sync.localPhoto
import com.fieldmaxx.app.ui.FieldmaxxTheme
import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.model.AppConfig
import com.fieldmaxx.core.model.CrewPosition
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.OpResult
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.PointStatus
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

private const val NOW = 1_800_000_000_000L
private const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
private const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
private const val BACKGROUND = "android.permission.ACCESS_BACKGROUND_LOCATION"

/**
 * The map as the screen drives it. Every call is recorded, and [camera] is reported by the adapter's own
 * [CameraTracker], fed the way MapLibre feeds it: a move takes a while and only [settle]s when the test says so, and
 * the tech's own drags and flings ([userMoves]) move the map, which may still be gliding until it [rest]s.
 */
class FakeMapController : MapController {
    private val tracker = CameraTracker()
    private val inFlight = CopyOnWriteArrayList<CameraView>()
    @Volatile private var position: CameraView? = null
    val moves = CopyOnWriteArrayList<CameraView>()
    val fits = CopyOnWriteArrayList<List<Point>>()
    @Volatile var shown: List<Point> = emptyList()
    @Volatile var pending: Set<String> = emptySet()
    @Volatile var crew: List<CrewPosition> = emptyList()
    @Volatile var fix: Fix? = null
    @Volatile var shownConfig: AppConfig? = null
    @Volatile var labels: Boolean? = null
    @Volatile var shownHouseNumbers: List<HouseNumber>? = null
    override val camera: StateFlow<CameraView?> = tracker.camera
    override val pointTaps = MutableSharedFlow<String>(extraBufferCapacity = 8)

    override fun setConfig(c: AppConfig) {
        shownConfig = c
    }

    override fun setPoints(points: List<Point>, pendingPhotoPointIds: Set<String>) {
        shown = points
        pending = pendingPhotoPointIds
    }

    override fun setCrew(crew: List<CrewPosition>, now: Long) {
        this.crew = crew
    }

    override fun setMyFix(f: Fix?) {
        fix = f
    }

    override fun moveTo(v: CameraView) {
        moves += v
        go(v)
    }

    override fun fitAll(points: List<Point>) {
        fits += points
        go(CameraView(points.map { it.lat }.average(), points.map { it.lon }.average(), 16.0))
    }

    private fun go(destination: CameraView) {
        inFlight += destination
        tracker.asked(destination)
        tracker.applied()
    }

    /** The map finishes the moves asked so far, as MapLibre's animations do a few hundred milliseconds later. */
    fun settle() {
        for (d in inFlight) tracker.ended(d, d)
        inFlight.lastOrNull()?.let {
            position = it
            tracker.settled(it)
        }
        inFlight.clear()
    }

    /** The tech drags, flings or pinches the map through [path]; after a fling it may still be gliding. */
    fun userMoves(vararg path: CameraView) {
        tracker.gestureStarted()
        for (p in path) {
            position = p
            tracker.moved(p)
        }
    }

    /** The map comes to rest after the tech's gesture. */
    fun rest() {
        position?.let(tracker::settled)
    }

    override fun setLabelsVisible(v: Boolean) {
        labels = v
    }

    override fun setHouseNumbers(items: List<HouseNumber>) {
        shownHouseNumbers = items
    }

    override fun beginDrag(pointId: String): Flow<LatLon> = emptyFlow()

    override fun endDrag() {}
}

class FakeViewMemory : ViewMemory {
    val views = ConcurrentHashMap<Pair<String, String>, CameraView>()
    val saves = CopyOnWriteArrayList<CameraView>()

    override suspend fun save(userId: String, jobId: String, v: CameraView) {
        views[userId to jobId] = v
        saves += v
    }

    override suspend fun load(userId: String, jobId: String): CameraView? = views[userId to jobId]
}

/** What the map reads, driven by the test; opening the job finds no signal unless [openJobError] says otherwise. */
class FakeMapDeps : MapDeps {
    val jobFlow = MutableStateFlow<Job?>(job("j1", "A05J9XE"))
    val pointsFlow = MutableStateFlow<List<Point>>(emptyList())
    val pendingFlow = MutableStateFlow<Set<String>>(emptySet())
    override val crew = MutableStateFlow<List<CrewPosition>>(emptyList())
    override val status = MutableStateFlow(SyncStatus.OFFLINE)
    override val photosWaiting = MutableStateFlow(0)
    override val rejected = MutableSharedFlow<OpResult>(extraBufferCapacity = 8)
    override val fixes = MutableStateFlow<Fix?>(null)
    override val config = MutableStateFlow(DEFAULT_CONFIG)
    override val views = FakeViewMemory()
    override val clock = MutableStateFlow(NOW)
    val houseOn = MutableStateFlow(false)
    override val houseNumbersOn: Flow<Boolean> = houseOn
    val houseRequests = CopyOnWriteArrayList<BBox>()
    @Volatile var houseAnswer: () -> List<HouseNumber> = { emptyList() }
    @Volatile var openJobError: Exception? = IOException("no signal")
    val opened = CopyOnWriteArrayList<String>()
    val permissionChanges = AtomicInteger()
    val enqueued = CopyOnWriteArrayList<Op>()
    @Volatile var enqueueError: Exception? = null

    override fun job(jobId: String): Flow<Job?> = jobFlow
    override fun points(jobId: String): Flow<List<Point>> = pointsFlow
    override fun pendingPhotoPointIds(jobId: String): Flow<Set<String>> = pendingFlow
    override fun now(): Long = clock.value
    override suspend fun userId(): String = "u1"
    override suspend fun setHouseNumbersOn(on: Boolean) {
        houseOn.value = on
    }

    override suspend fun houseNumbers(box: BBox): List<HouseNumber> {
        houseRequests += box
        return houseAnswer()
    }
    override suspend fun enqueue(op: Op) {
        enqueueError?.let { throw it }
        enqueued += op
    }

    override suspend fun openJob(jobId: String) {
        opened += jobId
        openJobError?.let { throw it }
    }

    override fun permissionChanged() {
        permissionChanges.incrementAndGet()
    }
}

/** Records what the screen asks the system for, instead of showing a permission dialog. */
private class RecordingRegistry : ActivityResultRegistry() {
    val launches = CopyOnWriteArrayList<Any?>()
    @Volatile var lastCode = -1

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        lastCode = requestCode
        launches += input
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val deps = FakeMapDeps()
    private val map = FakeMapController()
    private val registry = RecordingRegistry()
    @Volatile private var gone: String? = null
    @Volatile private var selected: String? = null
    private val menus = AtomicInteger()

    private val pole1 = mapPoint("p1", lat = 39.6361)
    private val pole2 = mapPoint("p2", lat = 39.6371, status = PointStatus.done)
    private val pole3 = mapPoint("p3", lat = 39.6381, status = PointStatus.cant_shoot)
    private val hh1 = mapPoint("h1", type = "HH", lat = 39.6391)
    private val hh2 = mapPoint("h2", type = "HH", lat = 39.6401, status = PointStatus.done)
    private val job = listOf(pole1, pole2, pole3, hh1, hh2)

    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }

    private fun show() = compose.setContent { Screen(map) }

    @Composable
    private fun Screen(controller: MapController) {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            FieldmaxxTheme {
                MapScreen(
                    "j1", deps, controller, onBack = {}, onJobGone = { gone = it },
                    onMenu = { menus.incrementAndGet() },
                    pointSheet = { pointId, _ -> selected = pointId },
                )
            }
        }
    }

    /** Lets the main looper run between checks too: under Robolectric that's what delivers state written outside a frame. */
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(5_000) {
        compose.waitForIdle()
        condition()
    }

    private fun waitForTag(tag: String) = waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) = waitFor { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun grantLocation() = shadowOf(context as Application).grantPermissions(FINE, COARSE)

    @Test fun restoresSavedViewOnOpen() {
        val saved = CameraView(39.6400, -119.6900, 17.5)
        deps.views.views["u1" to "j1"] = saved
        deps.views.views["u1" to "j2"] = CameraView(1.0, 2.0, 3.0)
        deps.pointsFlow.value = job

        show()

        waitFor { map.moves.isNotEmpty() && map.shown.size == job.size }
        assertEquals(listOf(saved), map.moves)
        assertEquals(emptyList(), map.fits, "a saved view is never replaced by fit-all")
        waitFor { deps.opened.isNotEmpty() }
        assertEquals(listOf("j1"), deps.opened, "the job is refreshed in the background")
        assertNull(gone, "no signal: the stored job stays on screen")
        assertEquals(DEFAULT_CONFIG, map.shownConfig, "imagery works before the server's settings ever arrived")
        assertEquals(emptyList(), map.shownHouseNumbers, "off by default")
        assertEquals(true, map.labels)
    }

    @Test fun fitsAllWhenNoSavedView() {
        show() // the job's points aren't stored yet: its snapshot is still coming
        waitFor { deps.opened.isNotEmpty() }
        assertEquals(emptyList(), map.fits)

        deps.pointsFlow.value = job
        waitFor { map.fits.isNotEmpty() }
        assertEquals(listOf(job), map.fits)
        assertEquals(emptyList(), map.moves)
        map.settle()
        waitFor { deps.views.views["u1" to "j1"] != null }
        assertEquals(map.camera.value, deps.views.views["u1" to "j1"], "the fitted view is the one the next open restores")

        deps.pointsFlow.value = job + mapPoint("p4", lat = 39.70)
        waitFor { map.shown.size == job.size + 1 }
        assertEquals(1, map.fits.size, "only the first open fits")
    }

    @Test fun savesViewOnCameraMove() {
        val saved = CameraView(39.6400, -119.6900, 17.5)
        deps.views.views["u1" to "j1"] = saved
        show()
        waitFor { map.moves.isNotEmpty() }
        map.settle()

        map.userMoves(CameraView(39.6900, -119.7900, 16.5), CameraView(39.7000, -119.8000, 16.0)) // the tech pans and zooms out
        map.rest()
        waitFor { deps.views.views["u1" to "j1"] == CameraView(39.7000, -119.8000, 16.0) }
        map.userMoves(CameraView(39.7100, -119.8100, 18.0))
        map.rest()
        waitFor { deps.views.views["u1" to "j1"] == CameraView(39.7100, -119.8100, 18.0) }

        assertEquals(listOf(saved), map.moves, "saving never moves the map")
        assertEquals(emptyList(), map.fits)
    }

    @Test fun pointsUpdateDoesNotMoveCamera() {
        grantLocation()
        val saved = CameraView(39.6400, -119.6900, 17.5)
        deps.views.views["u1" to "j1"] = saved
        deps.pointsFlow.value = job
        show()
        waitFor { map.moves.size == 1 && map.shown.size == job.size }
        map.settle()

        // After open: a sync update, a new point, a closed point, a re-snapshot, crew and GPS updates, the pill, the config.
        deps.pointsFlow.value = job + mapPoint("p4", lat = 39.70)
        waitFor { map.shown.size == job.size + 1 }
        deps.pointsFlow.value = listOf(pole1.copy(status = PointStatus.done)) + job.drop(1)
        waitFor { map.shown.first().status == PointStatus.done }
        deps.pointsFlow.value = job.map { it.copy(updatedAt = 9) }
        waitFor { map.shown.first().updatedAt == 9L }
        deps.jobFlow.value = job("j1", "A05J9XE").copy(deliveryProblem = "This link needs a Microsoft sign-in")
        waitForTag(MapTags.BANNER)
        deps.pendingFlow.value = setOf("p1")
        waitFor { map.pending == setOf("p1") }
        deps.crew.value = listOf(CrewPosition("u2", "Bob Tech", 39.64, -119.69, 5.0, NOW - 60_000, paused = false))
        waitFor { map.crew.isNotEmpty() }
        val fix = Fix(39.6, -119.7, 4.0, null, NOW)
        deps.fixes.value = fix
        waitFor { map.fix == fix } // the blue dot moves; the camera doesn't
        deps.fixes.value = fix.copy(lat = 39.61, at = NOW + 2_000)
        waitFor { map.fix?.lat == 39.61 }
        deps.status.value = SyncStatus.ONLINE
        deps.config.value = DEFAULT_CONFIG.copy(imageryKey = "k2")
        waitFor { map.shownConfig?.imageryKey == "k2" }
        deps.clock.value = NOW + 30_000

        compose.waitForIdle()
        assertEquals(listOf(saved), map.moves, "data changes never move the camera")
        assertEquals(emptyList(), map.fits)
    }

    @Test fun recreatedScreenRestoresLastView() {
        deps.pointsFlow.value = job
        val maps = CopyOnWriteArrayList(listOf(map))
        var generation by mutableStateOf(0)
        compose.setContent { key(generation) { Screen(maps[generation]) } }
        waitFor { map.fits.size == 1 }
        map.settle()

        val panned = CameraView(39.7000, -119.8000, 16.0)
        map.userMoves(panned)
        map.rest()
        waitFor { deps.views.views["u1" to "j1"] == panned }

        // A rotation, or coming back from another screen: a new screen with a new map.
        maps += FakeMapController()
        generation = 1
        waitFor { maps[1].moves.isNotEmpty() }
        assertEquals(listOf(panned), maps[1].moves, "back where the tech left it")
        assertEquals(emptyList(), maps[1].fits)
    }

    @Test fun zoomTapDuringAGlideKeepsTheGlidesCentre() {
        deps.views.views["u1" to "j1"] = CameraView(39.6400, -119.6900, 15.0)
        show()
        waitFor { map.moves.size == 1 }
        map.settle()

        // A fling: the map glides on after the finger lifts, and the tech taps + before it comes to rest.
        val gliding = CameraView(39.6550, -119.7050, 15.0)
        map.userMoves(CameraView(39.6500, -119.7000, 15.0), gliding)
        compose.onNodeWithTag(MapTags.ZOOM_IN).performClick()

        waitFor { map.moves.size == 2 }
        assertEquals(gliding.copy(zoom = 16.0), map.moves[1], "zooms in where the map is, not back where it last rested")
    }

    @Test fun twoQuickZoomTapsGoTwoSteps() {
        deps.views.views["u1" to "j1"] = CameraView(39.6400, -119.6900, 15.0)
        show()
        waitFor { map.moves.size == 1 }
        map.settle()

        // Quicker than the map's zoom animation: nothing settles in between.
        compose.onNodeWithTag(MapTags.ZOOM_IN).performClick()
        compose.onNodeWithTag(MapTags.ZOOM_IN).performClick()
        waitFor { map.moves.size == 3 }
        assertEquals(listOf(15.0, 16.0, 17.0), map.moves.map { it.zoom })

        map.settle()
        compose.onNodeWithTag(MapTags.ZOOM_OUT).performClick()
        waitFor { map.moves.size == 4 }
        assertEquals(16.0, map.moves[3].zoom)
    }

    @Test fun gestureBeforeTheFirstPointsStillFits() {
        show() // nothing saved, and the job's points aren't stored yet (a slow snapshot on weak signal)
        waitFor { deps.opened.isNotEmpty() }

        // The tech pokes at the empty world map meanwhile (R61).
        map.userMoves(CameraView(10.0, 20.0, 3.0), CameraView(12.0, 25.0, 4.0))
        map.rest()
        Thread.sleep(SAVE_QUIET_MS + 300) // longer than a save takes to follow a move
        compose.waitForIdle()
        assertTrue(deps.views.saves.isEmpty(), "no view is saved before the map was placed: ${deps.views.saves}")

        deps.pointsFlow.value = job
        waitFor { map.fits.isNotEmpty() }
        assertEquals(listOf(job), map.fits, "the gesture didn't cancel the first fit")
        map.settle()
        waitFor { deps.views.views["u1" to "j1"] != null }
        assertEquals(map.camera.value, deps.views.views["u1" to "j1"])
    }

    @Test fun centerOnMeBeforeTheFirstPointsCancelsTheFit() {
        grantLocation()
        val fix = Fix(39.6100, -119.7100, 4.0, null, NOW)
        deps.fixes.value = fix
        show() // nothing saved, no points yet
        waitFor { map.fix == fix }

        compose.onNodeWithTag(MapTags.CENTER_ON_ME).performClick() // an explicit action (R61)
        waitFor { map.moves.size == 1 }
        deps.pointsFlow.value = job
        waitFor { map.shown.size == job.size }
        compose.waitForIdle()

        assertEquals(emptyList(), map.fits, "the tech's own move stands")
        waitFor { deps.views.views["u1" to "j1"] == CameraView(39.6100, -119.7100, 17.0) }
    }

    @Test fun leavingRightAfterAMoveStillSavesIt() {
        deps.views.views["u1" to "j1"] = CameraView(39.6400, -119.6900, 15.0)
        var showing by mutableStateOf(true)
        compose.setContent { if (showing) Screen(map) }
        waitFor { map.moves.size == 1 }
        map.settle()

        // The tech pans and leaves at once, before the map has even come to rest.
        val panned = CameraView(39.7000, -119.8000, 15.0)
        map.userMoves(panned)
        showing = false

        waitFor { deps.views.views["u1" to "j1"] == panned }
    }

    @Test fun hideShotFilters() {
        deps.pointsFlow.value = job
        show()
        waitFor { map.shown.size == job.size }

        compose.onNodeWithTag(MapTags.HIDE_SHOT).performClick()
        waitFor { map.shown.map { it.id } == listOf("p1", "h1") }
        compose.onNodeWithText("Progress: 3/5 (60%)").assertExists() // progress counts the whole job, shown or not
        compose.onNodeWithText("Left: 2").assertExists()

        compose.onNodeWithTag(MapTags.HIDE_SHOT).performClick()
        waitFor { map.shown.size == job.size }
    }

    @Test fun chipFiltersType() {
        deps.pointsFlow.value = job
        show()
        waitFor { map.shown.size == job.size }
        compose.onNodeWithText("All (5)").assertExists()
        compose.onNodeWithText("POLE (2/3)").assertExists()

        compose.onNodeWithText("HH (1/2)").performClick()
        waitFor { map.shown.map { it.id } == listOf("h1", "h2") }

        deps.pointsFlow.value = job.map { if (it.id == "h1") it.copy(status = PointStatus.done) else it }
        waitForText("✓ HH (2/2)")
        waitFor { map.shown.map { it.id } == listOf("h1", "h2") }

        compose.onNodeWithText("All (5)").performClick()
        waitFor { map.shown.size == job.size }
    }

    @Test fun pillShowsOnlineOfflineWaiting() {
        show()
        fun pill(text: String) = waitFor { compose.onAllNodes(hasTestTag(MapTags.PILL) and hasText(text)).fetchSemanticsNodes().isNotEmpty() }

        pill("Offline")
        deps.photosWaiting.value = 3
        pill("Offline · 3 photos waiting")
        deps.status.value = SyncStatus.SYNCING
        pill("Syncing…")
        deps.status.value = SyncStatus.ONLINE
        pill("3 photos waiting")
        deps.photosWaiting.value = 1
        pill("1 photo waiting")
        deps.photosWaiting.value = 0
        pill("Online")
    }

    @Test fun deliveryProblemBanner() {
        deps.jobFlow.value = job("j1", "A05J9XE").copy(deliveryProblem = "This link needs a Microsoft sign-in")
        show()

        waitForTag(MapTags.BANNER)
        compose.onNodeWithTag(MapTags.BANNER).assertTextEquals("Photos can't reach OneDrive: This link needs a Microsoft sign-in")
        compose.onNodeWithText("A05J9XE").assertExists()

        deps.jobFlow.value = job("j1", "A05J9XE")
        waitFor { compose.onAllNodesWithTag(MapTags.BANNER).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun asksOnlyWhileInUseLocationPermission() {
        deps.pointsFlow.value = job
        deps.fixes.value = Fix(39.6, -119.7, 4.0, null, NOW) // whatever the source says, a refused permission shows no dot
        show()

        waitFor { registry.launches.isNotEmpty() }
        val asked = (registry.launches.single() as Array<*>).toList()
        assertEquals(setOf(FINE, COARSE), asked.toSet(), "while-in-use location only")
        assertFalse(BACKGROUND in asked)

        compose.runOnUiThread { registry.dispatchResult(registry.lastCode, mapOf(FINE to false, COARSE to false)) }
        waitFor { deps.permissionChanges.get() == 1 }
        waitForTag(MapTags.LOCATION_OFF)
        compose.onNodeWithTag(MapTags.LOCATION_OFF).assertTextEquals("Location off")
        assertNull(map.fix, "no blue dot")
        assertEquals(job.size, map.shown.size, "the map works without location")
        compose.waitForIdle()
        assertEquals(1, registry.launches.size, "asked once per opening")
    }

    @Test fun doesNotAskWhenLocationAllowed() {
        grantLocation()
        val fix = Fix(39.6, -119.7, 4.0, null, NOW)
        deps.fixes.value = fix
        show()

        waitFor { map.fix == fix }
        assertEquals(emptyList(), registry.launches)
        compose.onAllNodesWithTag(MapTags.LOCATION_OFF).assertCountEquals(0)
    }

    @Test fun jobDeletedGoesBackWithMessage() {
        deps.openJobError = ApiException(404, "Job not found")
        deps.pointsFlow.value = job
        show()

        waitFor { gone != null }
        assertEquals("This job was deleted", gone)
    }

    @Test fun refusedOpShowsSnackbar() {
        show()
        waitFor { deps.rejected.subscriptionCount.value > 0 }

        deps.rejected.tryEmit(OpResult("op-1", ok = false, error = "photos_delivered"))
        waitForText("A change couldn't be saved: only an admin can delete a point whose photos reached OneDrive.")
    }

    @Test fun userActionsMoveTheCamera() {
        grantLocation()
        deps.fixes.value = Fix(39.6100, -119.7100, 4.0, null, NOW)
        deps.views.views["u1" to "j1"] = CameraView(39.6400, -119.6900, 15.0)
        deps.pointsFlow.value = job
        show()
        waitFor { map.moves.size == 1 && map.shown.size == job.size }
        map.settle()

        // A marker tap opens the point (Task 25's sheet) where it is: the camera stays.
        map.pointTaps.tryEmit("p2")
        waitFor { selected == "p2" }
        assertEquals(1, map.moves.size)

        compose.onNodeWithTag(MapTags.CENTER_ON_ME).performClick()
        waitFor { map.moves.size == 2 }
        assertEquals(CameraView(39.6100, -119.7100, 17.0), map.moves[1])

        compose.onNodeWithTag(MapTags.LEFT).performClick()
        waitForTag(MapTags.leftItem("h1"))
        compose.onNodeWithTag(MapTags.leftItem("h1")).performClick()
        waitFor { map.moves.size == 3 }
        assertEquals(CameraView(hh1.lat, hh1.lon, 18.0), map.moves[2])
        assertEquals("h1", selected)

        compose.onNodeWithTag(MapTags.SEARCH).performClick()
        waitForTag(MapTags.SEARCH_FIELD)
        compose.onNodeWithTag(MapTags.SEARCH_FIELD).performTextInput("pole 3")
        waitForTag(MapTags.searchResult("p3"))
        compose.onNodeWithTag(MapTags.searchResult("p3")).performClick()
        waitFor { map.moves.size == 4 }
        assertEquals(CameraView(pole3.lat, pole3.lon, 18.0), map.moves[3])
        assertEquals("p3", selected)

        compose.onNodeWithTag(MapTags.ADD).performClick()
        compose.onNodeWithTag(MapTags.MENU).performClick()
        waitForTag(AddTags.CROSSHAIR) // "+" starts the add flow
        assertEquals(1, menus.get())
        assertEquals(emptyList(), map.fits)
    }

    private val sixHouses = listOf(HouseNumber(39.6400, -119.6900, "12"), HouseNumber(39.6401, -119.6901, "14"))

    @Test fun houseNumbersAreOffByDefaultAndAskNothingOfOverpass() {
        deps.views.views["u1" to "j1"] = CameraView(39.6400, -119.6900, 18.0)
        deps.houseAnswer = { sixHouses }
        show()
        waitFor { map.moves.isNotEmpty() }
        waitForTag(MapTags.HOUSES)
        Thread.sleep(HOUSE_NUMBER_DEBOUNCE_MS + 300)
        compose.waitForIdle()

        assertTrue(deps.houseRequests.isEmpty(), "off: no request goes out")
        assertEquals(emptyList(), map.shownHouseNumbers)
        assertFalse(deps.houseOn.value)
    }

    @Test fun the123ButtonTurnsHouseNumbersOnAndOffAndRemembersIt() {
        deps.views.views["u1" to "j1"] = CameraView(39.6400, -119.6900, 18.0)
        deps.houseAnswer = { sixHouses }
        show()
        waitFor { map.moves.isNotEmpty() }
        map.settle()

        compose.onNodeWithTag(MapTags.HOUSES).performClick()
        waitFor { deps.houseOn.value }
        waitFor { map.shownHouseNumbers == sixHouses }
        assertEquals(1, deps.houseRequests.size, "asked once, for the box around the view")
        val box = deps.houseRequests[0]
        assertTrue(box.s < 39.6400 && 39.6400 < box.n && box.w < -119.6900 && -119.6900 < box.e)

        compose.onNodeWithTag(MapTags.HOUSES).performClick()
        waitFor { !deps.houseOn.value }
        waitFor { map.shownHouseNumbers == emptyList<HouseNumber>() }
    }

    @Test fun houseNumbersClearBelowZoom17AndComeBackAtZoom17() {
        deps.houseOn.value = true
        deps.views.views["u1" to "j1"] = CameraView(39.6400, -119.6900, 18.0)
        deps.houseAnswer = { sixHouses }
        show()
        waitFor { map.moves.isNotEmpty() }
        map.settle()
        waitFor { map.shownHouseNumbers == sixHouses }

        map.userMoves(CameraView(39.6400, -119.6900, 16.9))
        map.rest()
        waitFor { map.shownHouseNumbers == emptyList<HouseNumber>() }
        val asked = deps.houseRequests.size

        map.userMoves(CameraView(39.6400, -119.6900, 17.0))
        map.rest()
        waitFor { deps.houseRequests.size > asked }
        waitFor { map.shownHouseNumbers == sixHouses }
    }

    @Test fun failedHouseNumberRequestKeepsTheLabelsDrawn() {
        deps.houseOn.value = true
        deps.views.views["u1" to "j1"] = CameraView(39.6400, -119.6900, 18.0)
        deps.houseAnswer = { sixHouses }
        show()
        waitFor { map.moves.isNotEmpty() }
        map.settle()
        waitFor { map.shownHouseNumbers == sixHouses }

        deps.houseAnswer = { throw IOException("Overpass busy (429)") }
        map.userMoves(CameraView(39.6410, -119.6910, 18.0))
        map.rest()
        waitFor { deps.houseRequests.size == 2 }
        Thread.sleep(300)
        compose.waitForIdle()
        assertEquals(sixHouses, map.shownHouseNumbers, "no signal or a refusal: the overlay stays as it was")
    }

    @Test fun waitingPhotoQueries() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            runBlocking {
                db.localPhotos().upsert(localPhoto("a", pointId = "p1", jobId = "j1"))
                db.localPhotos().upsert(localPhoto("b", pointId = "p2", jobId = "j1").copy(state = "stamped"))
                db.localPhotos().upsert(localPhoto("c", pointId = "p2", jobId = "j1").copy(state = "registered"))
                db.localPhotos().upsert(localPhoto("d", pointId = "q1", jobId = "j2").copy(state = "registered"))
                db.localPhotos().upsert(localPhoto("e", pointId = "p3", jobId = "j1").copy(state = "confirmed"))

                assertEquals(4, db.localPhotos().observeWaitingCount().first(), "every job's photos not in OneDrive yet")
                assertEquals(setOf("p1", "p2"), db.localPhotos().observeWaitingPointIds("j1").first().toSet())
                assertTrue(db.localPhotos().observeWaitingPointIds("j3").first().isEmpty())
            }
        } finally {
            db.close()
        }
    }
}
