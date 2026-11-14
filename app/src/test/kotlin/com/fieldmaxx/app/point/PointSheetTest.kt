package com.fieldmaxx.app.point

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.map.FakeMapController
import com.fieldmaxx.app.map.MapController
import com.fieldmaxx.app.photos.PhotoUiStatus
import com.fieldmaxx.app.photos.PhotoView
import com.fieldmaxx.app.platform.Fix
import com.fieldmaxx.app.sync.ALICE
import com.fieldmaxx.app.sync.localPhoto
import com.fieldmaxx.app.sync.point
import com.fieldmaxx.app.sync.refImageUrl
import com.fieldmaxx.app.ui.FieldmaxxTheme
import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.model.Attachment
import com.fieldmaxx.core.model.CantShootReason
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.PointStatus
import com.fieldmaxx.core.model.PoleData
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

private const val SERVER = "https://fmx.example.com"
private val ADMIN = User("u7", "owner", "Owner Admin", Role.admin, disabled = false)

/** What the sheet reads and writes, driven by the test. [db], when given, holds the phone's photos (R46). */
class FakePointDeps(context: Context, private val db: AppDatabase? = null) : PointDeps {
    val pointsFlow = MutableStateFlow<List<Point>>(emptyList())
    val photosFlow = MutableStateFlow<List<PhotoView>>(emptyList())
    val onPhoneFlow = MutableStateFlow(0)
    override val fixes = MutableStateFlow<Fix?>(null)
    @Volatile var me: User? = ALICE
    val ops = CopyOnWriteArrayList<Op>()

    /** What the sheet did, in order: "enqueue:<op kind>" and "discard:<pointId>". */
    val log = CopyOnWriteArrayList<String>()

    /** The images Coil was asked for. */
    val requested = CopyOnWriteArrayList<Any?>()

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

    fun updates(): List<Op.PointUpdate> = ops.filterIsInstance<Op.PointUpdate>()

    override fun points(jobId: String): Flow<List<Point>> = pointsFlow
    override fun photos(pointId: String): Flow<List<PhotoView>> = photosFlow
    override fun photosOnPhone(pointId: String): Flow<Int> = db?.localPhotos()?.observeOnPhoneCount(pointId) ?: onPhoneFlow
    override fun server(): String = SERVER
    override suspend fun user(): User? = me

    override suspend fun enqueue(op: Op) {
        ops += op
        log += "enqueue:${op::class.simpleName}"
    }

    override suspend fun discardPhonePhotos(pointId: String) {
        log += "discard:$pointId"
        db?.let { discardPointPhotos(it, pointId) }
    }
}

/**
 * The map under the sheet. [markers] are where it draws each point (a point it doesn't show can't be dragged), and
 * [dragTo] is the tech's finger on the dragged marker. Camera moves are recorded by the [FakeMapController] beneath.
 */
class DragMap(private val base: FakeMapController = FakeMapController()) : MapController by base {
    val markers = ConcurrentHashMap<String, LatLon>()
    @Volatile var dragging: String? = null
    val endDrags = AtomicInteger()
    @Volatile private var drag: SendChannel<LatLon>? = null
    val moves get() = base.moves
    val fits get() = base.fits

    override fun beginDrag(pointId: String): Flow<LatLon> = callbackFlow {
        val at = markers[pointId]
        if (at == null) {
            close()
            return@callbackFlow
        }
        dragging = pointId
        drag = channel
        trySend(at)
        awaitClose {
            dragging = null
            drag = null
        }
    }

    fun dragTo(at: LatLon) {
        drag!!.trySend(at)
    }

    override fun endDrag() {
        endDrags.incrementAndGet()
        drag?.close()
    }
}

private fun photoView(id: String, status: PhotoUiStatus = PhotoUiStatus.ON_PHONE) =
    PhotoView(id, thumb = "$SERVER/thumbs/$id", status = status, backedUp = false, error = null, destPath = null, ownerId = "u1")

private fun fixWithin(meters: Double) = Fix(39.636, -119.691, meters, null, 1)

private const val FOOT_M = 0.3048

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PointSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val deps = FakePointDeps(context)
    private val map = DragMap()
    private val closes = AtomicInteger()

    private val pole18 = point("p18") // POLE 18 at 39.636130, -119.691104
    private val query43 = point("q43").copy(
        layer = "QUERY 1", type = "QUERY 1", num = "43", label = "Query 1-43", description = "Is the riser on this pole or the next one?",
        refImageIds = listOf("imp-2-item-3", "imp-2-page-1"),
    )

    private fun show(p: Point = pole18, sheetDeps: FakePointDeps = deps) {
        sheetDeps.pointsFlow.value = listOf(p)
        map.markers[p.id] = LatLon(p.lat, p.lon)
        compose.setContent { FieldmaxxTheme { PointSheet("j1", p.id, sheetDeps, map, onClose = { closes.incrementAndGet() }) } }
        waitForTag(PointTags.TITLE)
    }

    /** Lets the main looper run between checks too, and the test clock move a frame each time. */
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(5_000) {
        compose.waitForIdle()
        condition()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitForTag(tag: String) = waitFor { exists(tag) }
    private fun waitForText(text: String) = waitFor { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun enabled(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
    private fun textOf(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** A control in the sheet, which scrolls. */
    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    /** A control in a dialog or the move bar. */
    private fun press(tag: String) {
        compose.onNodeWithTag(tag).performClick()
    }

    // ---- the brief's tests ----

    @Test fun poleFormEnqueuesUpdate() {
        show()
        compose.onNodeWithText("Photograph the pole and the carrier equipment on it.").assertExists()
        compose.onAllNodesWithTag(PointTags.REFERENCE).assertCountEquals(0) // no client query on a pole

        compose.onNodeWithTag(PointTags.LINE_HEIGHT).performScrollTo().performTextInput("25.5")
        tap(PointTags.ANCHOR)
        tap(PointTags.attachment(Attachment.lashed))
        tap(PointTags.tag("Damaged cable"))
        tap(PointTags.tag("Carrier label"))

        val want = PoleData(lineHeightFt = 25.5, anchor = true, attachment = Attachment.lashed, tags = listOf("Carrier label", "Damaged cable"))
        waitFor { deps.updates().lastOrNull()?.pole == want }
        val updates = deps.updates()
        assertTrue(updates.all { it.pointId == "p18" && it.pole != null && it.comment == null }, "the whole pole data and nothing else: $updates")
        assertEquals(updates.size, deps.ops.size)

        // Self-supporting takes lashed's place; tapping it again leaves neither.
        tap(PointTags.attachment(Attachment.self_supporting))
        waitFor { deps.updates().last().pole == want.copy(attachment = Attachment.self_supporting) }
        tap(PointTags.attachment(Attachment.self_supporting))
        waitFor { deps.updates().last().pole == want.copy(attachment = null) }

        // Text that isn't a height isn't saved; clearing the field is.
        compose.onNodeWithTag(PointTags.LINE_HEIGHT).performTextReplacement("25.5.5")
        waitForText("Feet, like 25.5")
        compose.onNodeWithTag(PointTags.LINE_HEIGHT).performTextReplacement("")
        waitFor { deps.updates().last().pole == want.copy(lineHeightFt = null, attachment = null) }
        assertTrue(deps.updates().none { it.pole?.lineHeightFt != 25.5 && it.pole?.lineHeightFt != null }, "${deps.updates()}")
    }

    @Test fun doneDisabledWithoutPhotos() {
        show()
        compose.onNodeWithTag(PointTags.DONE).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Done needs at least one photo").assertExists()

        deps.photosFlow.value = listOf(photoView("ph1"), photoView("ph2", PhotoUiStatus.IN_ONEDRIVE))
        waitFor { enabled(PointTags.DONE) }
        tap(PointTags.DONE)

        waitFor { deps.ops.isNotEmpty() }
        val close = deps.ops.single() as Op.PointClose
        assertEquals("p18", close.pointId)
        assertEquals(listOf("ph1", "ph2"), close.photoIds, "the point's photos as the sheet lists them (R48)")
    }

    @Test fun cantShootReasonRequiredOtherNeedsText() {
        show()
        tap(PointTags.CANT_SHOOT)
        waitForTag(PointTags.CANT_SHOOT_SAVE)
        compose.onNodeWithTag(PointTags.CANT_SHOOT_SAVE).assertIsNotEnabled() // a reason first

        press(PointTags.reason(CantShootReason.no_access))
        compose.onNodeWithTag(PointTags.CANT_SHOOT_SAVE).assertIsEnabled()
        press(PointTags.reason(CantShootReason.other))
        compose.onNodeWithTag(PointTags.CANT_SHOOT_SAVE).assertIsNotEnabled() // Other needs words
        compose.onNodeWithTag(PointTags.REASON_TEXT).performTextInput("   ")
        compose.onNodeWithTag(PointTags.CANT_SHOOT_SAVE).assertIsNotEnabled()
        compose.onNodeWithTag(PointTags.REASON_TEXT).performTextReplacement("  Locked gate, dog in yard ")
        compose.onNodeWithTag(PointTags.CANT_SHOOT_SAVE).assertIsEnabled()
        press(PointTags.CANT_SHOOT_SAVE)

        waitFor { deps.ops.isNotEmpty() }
        val op = deps.ops.single()
        assertEquals(Op.PointCantShoot(op.opId, "p18", CantShootReason.other, "Locked gate, dog in yard"), op)
        compose.onAllNodesWithTag(PointTags.CANT_SHOOT_SAVE).assertCountEquals(0)

        // Once it lands, the point shows why, and can be reopened.
        deps.pointsFlow.value = listOf(pole18.copy(status = PointStatus.cant_shoot, cantReason = CantShootReason.other, cantText = "Locked gate, dog in yard"))
        waitForText("Can't shoot: Other · Locked gate, dog in yard")
        compose.onAllNodesWithTag(PointTags.DONE).assertCountEquals(0)
        tap(PointTags.REOPEN)
        waitFor { deps.ops.size == 2 }
        assertEquals(Op.PointReopen(deps.ops[1].opId, "p18"), deps.ops[1])
    }

    @Test fun navigateIntentUri() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=39.636130,-119.691104",
            navigateUri(39.63613, -119.691104).toString(),
        )

        show()
        tap(PointTags.NAVIGATE)

        val app = shadowOf(context as Application)
        waitFor { app.peekNextStartedActivity() != null }
        val started = app.nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(navigateUri(pole18.lat, pole18.lon), started.data)
        assertEquals(emptyList(), map.moves, "the map stays where it is (R38)")
    }

    @Test fun moveConfirmEnqueuesMoveUndoWithin10sRestores() {
        show()
        tap(PointTags.MOVE)
        waitFor { map.dragging == "p18" }
        compose.onAllNodesWithTag(PointTags.TITLE).assertCountEquals(0) // the sheet steps aside for the drag
        compose.onNodeWithTag(PointTags.MOVE_CONFIRM).assertIsNotEnabled() // nothing dragged yet

        val there = LatLon(39.637010, -119.692050)
        map.dragTo(LatLon(39.636500, -119.691500))
        map.dragTo(there)
        waitFor { enabled(PointTags.MOVE_CONFIRM) }
        press(PointTags.MOVE_CONFIRM)

        waitFor { deps.ops.isNotEmpty() }
        assertEquals(Op.PointMove(deps.ops[0].opId, "p18", there.lat, there.lon), deps.ops.single(), "saved at once")
        waitForTag(PointTags.UNDO_MOVE) // the sheet is back, offering "Undo move"
        assertNull(map.dragging, "the drag is over")

        compose.mainClock.advanceTimeBy(9_000)
        tap(PointTags.UNDO_MOVE)
        waitFor { deps.ops.size == 2 }
        assertEquals(Op.PointMove(deps.ops[1].opId, "p18", pole18.lat, pole18.lon), deps.ops[1], "back where it was before the drag")
        waitFor { !exists(PointTags.UNDO_MOVE) }

        // Moved again, "Undo move" goes after 10 s.
        tap(PointTags.MOVE)
        waitFor { map.dragging == "p18" }
        map.dragTo(there)
        waitFor { enabled(PointTags.MOVE_CONFIRM) }
        press(PointTags.MOVE_CONFIRM)
        waitForTag(PointTags.UNDO_MOVE)
        compose.mainClock.advanceTimeBy(10_100)
        waitFor { !exists(PointTags.UNDO_MOVE) }

        assertEquals(3, deps.ops.size)
        assertEquals(3, deps.ops.map { it.opId }.toSet().size, "each op has its own id")
        assertEquals(emptyList(), map.moves, "the camera stays where the tech put it (R38)")
        assertEquals(emptyList(), map.fits)
    }

    @Test fun deleteHiddenAfterOneDriveForTech() {
        assertTrue(canDeletePoint(ALICE, emptyList()))
        assertTrue(canDeletePoint(ALICE, listOf(photoView("a"), photoView("b", PhotoUiStatus.UPLOADING), photoView("c", PhotoUiStatus.STUCK))))
        assertFalse(canDeletePoint(ALICE, listOf(photoView("a"), photoView("b", PhotoUiStatus.IN_ONEDRIVE))))
        assertTrue(canDeletePoint(ADMIN, listOf(photoView("b", PhotoUiStatus.IN_ONEDRIVE))), "an admin still can")

        deps.photosFlow.value = listOf(photoView("a", PhotoUiStatus.UPLOADING))
        show()
        waitForTag(PointTags.DELETE) // nothing reached OneDrive yet: the tech may delete it

        deps.photosFlow.value = listOf(photoView("a", PhotoUiStatus.IN_ONEDRIVE))
        waitFor { !exists(PointTags.DELETE) }
    }

    @Test fun deleteWarnsPhotosOnPhoneDiscarded() {
        assertEquals("1 photo on this phone will be discarded", discardWarning(1))
        assertNull(discardWarning(0))

        deps.onPhoneFlow.value = 2
        show()
        tap(PointTags.DELETE)
        waitForText("2 photos on this phone will be discarded")
        press(PointTags.DELETE_CONFIRM)

        waitFor { "discard:p18" in deps.log }
        assertEquals(listOf("enqueue:PointDelete", "discard:p18"), deps.log.toList(), "the delete is queued, then the phone's photos go (R46)")
        assertEquals("p18", (deps.ops.single() as Op.PointDelete).pointId)
        waitFor { closes.get() == 1 }
    }

    @Test fun queryShowsReferenceImages() {
        show(query43)
        waitForTag(PointTags.REFERENCE)
        compose.onNodeWithText("Client query · reference").assertExists()
        compose.onNodeWithText("Is the riser on this pole or the next one?").assertExists() // the query, from the file
        assertTrue(exists(PointTags.refImage("imp-2-item-3")) && exists(PointTags.refImage("imp-2-page-1")))

        // Through the app's loader, at the addresses the job's opening prefetched (R21): they show without signal.
        val urls = setOf(refImageUrl(SERVER, "j1", "imp-2-item-3"), refImageUrl(SERVER, "j1", "imp-2-page-1"))
        waitFor { deps.requested.map { it.toString() }.toSet() == urls }
        compose.onAllNodesWithTag(PointTags.LINE_HEIGHT).assertCountEquals(0) // pole data is for poles
    }

    @Test fun ambiguousFolderPickerEnqueuesSetFolder() {
        show(pole18.copy(folderCandidates = listOf("POLE/POLE_18", "Poles/18")))
        waitForTag(PointTags.folderChoice("Poles/18"))
        compose.onNodeWithTag(PointTags.folderChoice("POLE/POLE_18")).assertTextEquals("→ Field Notes/POLE/POLE_18")
        compose.onAllNodesWithTag(PointTags.FOLDER).assertCountEquals(0) // nothing chosen yet: it asks

        tap(PointTags.folderChoice("Poles/18"))
        waitFor { deps.ops.isNotEmpty() }
        assertEquals(Op.PointSetFolder(deps.ops.single().opId, "p18", "Poles/18"), deps.ops.single())

        deps.pointsFlow.value = listOf(pole18.copy(folderPath = "Poles/18")) // the choice, stored and shared
        waitForTag(PointTags.FOLDER)
        compose.onNodeWithTag(PointTags.FOLDER).assertTextEquals("→ Field Notes/Poles/18")
        compose.onAllNodesWithTag(PointTags.folderChoice("POLE/POLE_18")).assertCountEquals(0) // asked once
    }

    @Test fun folderLineShowsPath() {
        assertEquals("QUERY 1/Query_1-43", plannedFolder(query43))

        show() // no folder found yet: the one that would be made
        compose.onNodeWithTag(PointTags.FOLDER).performScrollTo().assertTextEquals("→ Field Notes/POLE/POLE_18")

        deps.pointsFlow.value = listOf(pole18.copy(folderPath = "POLES/Pole 18")) // an existing folder, reused
        waitFor { compose.onAllNodesWithText("→ Field Notes/POLES/Pole 18").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun commentDebouncedSave() {
        show()
        compose.onNodeWithTag(PointTags.COMMENT).performScrollTo().performTextInput("Needs a ladder")
        compose.mainClock.advanceTimeBy(300)
        assertEquals(emptyList(), deps.ops, "not while typing")
        compose.onNodeWithTag(PointTags.COMMENT).performTextInput(", gate locked")
        compose.mainClock.advanceTimeBy(300)
        assertEquals(emptyList(), deps.ops, "500 ms after the last keystroke, not the first")
        compose.mainClock.advanceTimeBy(400)

        val update = deps.ops.single() as Op.PointUpdate
        assertEquals("p18", update.pointId)
        assertEquals("Needs a ladder, gate locked", update.comment)
        assertNull(update.pole, "the pole data wasn't touched")

        // The crew's edit shows while nothing waits to be saved here.
        deps.pointsFlow.value = listOf(pole18.copy(comment = "Bob: ladder in truck 2"))
        waitFor { textOf(PointTags.COMMENT) == "Bob: ladder in truck 2" }
    }

    // ---- focused tests for the rulings ----

    @Test fun confirmedDeleteDiscardsPhonePhotoRowsAndFiles() { // R46
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dir = File(context.filesDir, "photos").apply { mkdirs() }
            fun file(name: String) = File(dir, name).apply { writeText("jpeg") }
            val saved = localPhoto("a", pointId = "p18").copy(originalPath = file("a.jpg").path)
            val stamped = localPhoto("b", pointId = "p18").copy(state = "stamped", originalPath = file("b.jpg").path, stampedPath = file("b-stamped.jpg").path)
            val inOneDrive = localPhoto("c", pointId = "p18").copy(state = "confirmed", originalPath = file("c.jpg").path, stampedPath = file("c-stamped.jpg").path)
            val otherPoint = localPhoto("d", pointId = "p19").copy(state = "registered", originalPath = file("d.jpg").path)
            runBlocking { listOf(saved, stamped, inOneDrive, otherPoint).forEach { db.localPhotos().upsert(it) } }
            val roomDeps = FakePointDeps(context, db)

            show(sheetDeps = roomDeps)
            tap(PointTags.DELETE)
            waitForText("2 photos on this phone will be discarded")
            press(PointTags.DELETE_CONFIRM)

            waitFor { runBlocking { db.localPhotos().get("a") == null && db.localPhotos().get("b") == null } }
            assertEquals(listOf("enqueue:PointDelete", "discard:p18"), roomDeps.log.toList())
            assertFalse(File(dir, "a.jpg").exists() || File(dir, "b.jpg").exists() || File(dir, "b-stamped.jpg").exists(), "their files are gone")
            runBlocking {
                assertEquals(inOneDrive, db.localPhotos().get("c"), "a photo already in OneDrive is never touched")
                assertEquals(otherPoint, db.localPhotos().get("d"))
            }
            assertTrue(File(dir, "c.jpg").exists() && File(dir, "c-stamped.jpg").exists() && File(dir, "d.jpg").exists())
        } finally {
            db.close()
        }
    }

    @Test fun moveCancelEnqueuesNothingAndTheMarkerReturns() { // R47
        show()
        tap(PointTags.MOVE)
        waitFor { map.dragging == "p18" }
        map.dragTo(LatLon(39.70, -119.70))
        waitFor { enabled(PointTags.MOVE_CONFIRM) }
        press(PointTags.MOVE_CANCEL)

        waitForTag(PointTags.TITLE) // the sheet comes back
        assertNull(map.dragging, "the drag ended: the map draws the marker where the point is")
        assertEquals(1, map.endDrags.get())
        compose.onAllNodesWithTag(PointTags.UNDO_MOVE).assertCountEquals(0)

        // Back during a drag cancels it too.
        tap(PointTags.MOVE)
        waitFor { map.dragging == "p18" }
        map.dragTo(LatLon(39.71, -119.71))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForTag(PointTags.TITLE)
        assertNull(map.dragging)

        compose.waitForIdle()
        assertEquals(emptyList(), deps.ops, "a cancelled move saves nothing")
        assertEquals(emptyList(), map.moves)
        assertEquals(emptyList(), map.fits)
    }

    // ---- more guards ----

    @Test fun moveOfAPointTheMapDoesNotShowSaysSo() {
        show()
        map.markers.clear() // filtered out by a type chip, say
        tap(PointTags.MOVE)
        waitForText(NOT_ON_MAP)
        assertEquals(emptyList(), deps.ops)
    }

    @Test fun editsWaitingWhenTheSheetClosesAreSaved() {
        var open by mutableStateOf(true)
        deps.pointsFlow.value = listOf(pole18)
        compose.setContent { FieldmaxxTheme { if (open) PointSheet("j1", "p18", deps, map, onClose = {}) } }
        waitForTag(PointTags.TITLE)
        compose.onNodeWithTag(PointTags.COMMENT).performScrollTo()
        val before = compose.mainClock.currentTime
        compose.onNodeWithTag(PointTags.COMMENT).performTextInput("Gate code 4512")

        // The tech swipes the sheet away at once; the clock moves only a frame at a time, until the sheet is gone.
        compose.mainClock.autoAdvance = false
        open = false
        while (exists(PointTags.TITLE) && compose.mainClock.currentTime - before < EDIT_QUIET_MS) compose.mainClock.advanceTimeByFrame()

        assertFalse(exists(PointTags.TITLE), "the sheet is gone")
        assertTrue(compose.mainClock.currentTime - before < EDIT_QUIET_MS, "before the edit's quiet time was up")
        assertEquals("Gate code 4512", (deps.ops.single() as Op.PointUpdate).comment, "saved as the sheet goes, not lost")
    }

    @Test fun anotherPointGetsItsOwnSheet() {
        var id by mutableStateOf("p18")
        val pole19 = point("p19").copy(comment = "Pole 19's own comment")
        deps.pointsFlow.value = listOf(pole18, pole19)
        compose.setContent { FieldmaxxTheme { PointSheet("j1", id, deps, map, onClose = {}) } }
        waitForTag(PointTags.TITLE)
        compose.onNodeWithTag(PointTags.COMMENT).performScrollTo().performTextInput("Pole 18 note")

        id = "p19" // another marker tapped before the save
        waitFor { compose.onAllNodesWithText("POLE 19").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Pole 19's own comment", textOf(PointTags.COMMENT))
        waitFor { deps.ops.isNotEmpty() }
        val update = deps.ops.single() as Op.PointUpdate
        assertEquals("p18" to "Pole 18 note", update.pointId to update.comment, "the first point keeps its own edit")
    }

    @Test fun gpsBadgeShowsAccuracyInFeet() {
        assertEquals(GpsBadge("No GPS yet", Tone.NONE), gpsBadge(null))
        assertEquals(GpsBadge("GPS ±12 ft", Tone.GREEN), gpsBadge(fixWithin(12 * FOOT_M)))
        assertEquals(GpsBadge("GPS ±16 ft", Tone.GREEN), gpsBadge(fixWithin(16 * FOOT_M)))
        assertEquals(GpsBadge("GPS ±17 ft", Tone.AMBER), gpsBadge(fixWithin(17 * FOOT_M)))
        assertEquals(GpsBadge("GPS ±50 ft", Tone.AMBER), gpsBadge(fixWithin(50 * FOOT_M)))
        assertEquals(GpsBadge("GPS ±51 ft", Tone.RED), gpsBadge(fixWithin(51 * FOOT_M)))
        assertEquals(Tone.RED, gpsBadge(fixWithin(Double.MAX_VALUE)).tone, "a fix without an accuracy")

        show()
        compose.onNodeWithTag(PointTags.GPS).assertTextEquals("No GPS yet")
        deps.fixes.value = fixWithin(12 * FOOT_M)
        waitFor { compose.onAllNodesWithText("GPS ±12 ft").fetchSemanticsNodes().isNotEmpty() }
    }
}
