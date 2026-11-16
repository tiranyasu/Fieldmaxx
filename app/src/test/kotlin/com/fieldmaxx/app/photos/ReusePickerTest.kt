package com.fieldmaxx.app.photos

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import com.fieldmaxx.app.sync.job
import com.fieldmaxx.app.sync.photo
import com.fieldmaxx.app.sync.point
import com.fieldmaxx.app.ui.FieldmaxxTheme
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.ReuseCandidate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private val RZONE: ZoneId = ZoneId.of("America/Los_Angeles")

private class ListReuseDeps(
    context: Context,
    private val points: List<Point>,
    private val nearby: List<ReuseCandidate>,
) : ReuseDeps {
    val ops = CopyOnWriteArrayList<Op>()
    override val imageLoader: ImageLoader = ImageLoader.Builder(context).build()
    override fun server(): String = "https://fmx.example.com"
    override fun job(jobId: String): Flow<Job?> = flowOf(job("j1", "A05J9XE"))
    override fun points(jobId: String): Flow<List<Point>> = flowOf(points)
    override fun jobPhotos(jobId: String): Flow<List<PhotoInfo>> = flowOf(emptyList())
    override fun deletedPhotoIds(): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun nearby(lat: Double, lon: Double, excludePointId: String?) = nearby
    override suspend fun serverJobPhotos(jobId: String): List<ReuseCandidate> = emptyList()
    override suspend fun enqueue(op: Op) {
        ops += op
    }
    override suspend fun saveSource(photoId: String, source: ReuseSource) {}
    override fun zone(): ZoneId = RZONE
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReusePickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun cand(id: String, pointId: String, label: String, m: Double?, at: Long) =
        ReuseCandidate(photo(id, pointId, jobId = "j0").copy(takenAt = at), label, "A05J9XE", m)

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitForTag(tag: String) = compose.waitUntil(5_000) { compose.waitForIdle(); exists(tag) }
    private fun press(tag: String) = compose.onNodeWithTag(tag).performClick()

    @Test fun nearbyGroupsByPointNearestFirst() {
        val groups = nearbyByPoint(
            listOf(
                cand("a1", "pa", "POLE 5", 40.0, 100),
                cand("b1", "pb", "POLE 6", 10.0, 50),
                cand("a2", "pa", "POLE 5", 40.0, 300),
                cand("c1", "pc", "POLE 7", null, 400),
            ),
        )
        assertEquals(listOf("pb", "pa", "pc"), groups.map { it.pointId }, "nearest first, no distance last")
        assertEquals(3, groups.size)
        assertEquals(listOf("a2", "a1"), groups[1].photos.map { it.photo.id }, "newest first")
        assertEquals(40.0, groups[1].distanceM)
    }

    @Test fun nearbyTabListsPointsThenPhotos() {
        val here = point("q43")
        val deps = ListReuseDeps(
            context, listOf(here),
            listOf(
                cand("a1", "pa", "POLE 5", 40.0, 100),
                cand("b1", "pb", "POLE 5", 10.0, 50),
                cand("a2", "pa", "POLE 5", 40.0, 300),
            ),
        )
        compose.setContent { FieldmaxxTheme { ReusePicker("j1", "q43", deps, onDone = {}) } }

        waitForTag(ReuseTags.nearbyPoint("pb"))
        compose.onNodeWithText("POLE 5 · A05J9XE · 10 m").assertExists()
        compose.onNodeWithText("POLE 5 · A05J9XE · 40 m").assertExists()
        assertFalse(exists(ReuseTags.candidate("b1")), "points first, photos after a tap")

        press(ReuseTags.nearbyPoint("pa"))
        waitForTag(ReuseTags.candidate("a1"))
        assertTrue(exists(ReuseTags.candidate("a2")))
        assertFalse(exists(ReuseTags.candidate("b1")))

        press(ReuseTags.NEARBY_BACK)
        waitForTag(ReuseTags.nearbyPoint("pb"))
        press(ReuseTags.nearbyPoint("pb"))
        waitForTag(ReuseTags.candidate("b1"))
        press(ReuseTags.candidate("b1"))
        press(ReuseTags.NEARBY_BACK)
        press(ReuseTags.nearbyPoint("pa"))
        waitForTag(ReuseTags.candidate("a2"))
        press(ReuseTags.candidate("a2"))
        press(ReuseTags.USE)
        compose.waitUntil(5_000) { deps.ops.size == 2 }
        assertEquals(setOf("b1", "a2"), deps.ops.filterIsInstance<Op.PhotoReuse>().map { it.fromPhotoId }.toSet())
    }

    /** I-M2: system Back from a Nearby point's photos returns to the point list, the photos picked kept. */
    @Test fun backFromNearbyPhotosReturnsToPoints() {
        val deps = ListReuseDeps(
            context, listOf(point("q43")),
            listOf(cand("a1", "pa", "POLE 5", 40.0, 100), cand("b1", "pb", "POLE 6", 10.0, 50)),
        )
        compose.setContent { FieldmaxxTheme { ReusePicker("j1", "q43", deps, onDone = {}) } }

        waitForTag(ReuseTags.nearbyPoint("pb"))
        press(ReuseTags.nearbyPoint("pb"))
        waitForTag(ReuseTags.candidate("b1"))
        press(ReuseTags.candidate("b1"))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForTag(ReuseTags.nearbyPoint("pa"))
        assertFalse(exists(ReuseTags.candidate("b1")), "back on the point list")

        press(ReuseTags.nearbyPoint("pa"))
        waitForTag(ReuseTags.candidate("a1"))
        press(ReuseTags.candidate("a1"))
        press(ReuseTags.USE)
        compose.waitUntil(5_000) { deps.ops.size == 2 }
        assertEquals(setOf("b1", "a1"), deps.ops.filterIsInstance<Op.PhotoReuse>().map { it.fromPhotoId }.toSet())
    }
}
