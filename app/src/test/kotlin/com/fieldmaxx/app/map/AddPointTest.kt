package com.fieldmaxx.app.map

import android.Manifest
import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.fieldmaxx.app.menu.AddedTags
import com.fieldmaxx.app.menu.PointsIAddedScreen
import com.fieldmaxx.app.menu.myAddedPoints
import com.fieldmaxx.app.platform.Fix
import com.fieldmaxx.app.ui.FieldmaxxTheme
import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.naming.ADD_CATEGORIES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

private val HERE = LatLon(39.6361, -119.6911)

private fun pole(num: String, lat: Double = 39.6361, lon: Double = -119.6911) =
    mapPoint("p$num", type = "POLE", num = num, lat = lat, lon = lon)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddPointTest {
    @get:Rule val compose = createComposeRule()

    private val deps = FakeMapDeps()
    private val map = FakeMapController()
    @Volatile private var selected: String? = null

    @Test fun nearestPole14SuggestsPOLE14B() {
        val points = listOf(pole("14", lat = 39.6361), pole("20", lat = 39.7000), pole("3", lat = 39.5000))
        assertEquals("POLE 14B", suggestName(points, "POLE", "POLE", LatLon(39.6362, -119.6911)))
    }

    @Test fun with14BSuggests14C() {
        val points = listOf(pole("14", lat = 39.6361), pole("14B", lat = 39.6362), pole("20", lat = 39.7000))
        assertEquals("POLE 14C", suggestName(points, "POLE", "POLE", LatLon(39.6361, -119.6911)))
    }

    @Test fun streetImagesNext3() {
        val points = listOf(mapPoint("s1", type = "Street images", num = "1"), mapPoint("s2", type = "Street images", num = "2"), pole("9"))
        assertEquals("Street images 3", suggestName(points, "Street images", "Street images", HERE))
        assertEquals("Street images 1", suggestName(emptyList(), "Street images", "Street images", HERE))
    }

    @Test fun typeChoicesOrderNoDupes() {
        val choices = typeChoices(listOf("Query 1", "HH", "POLE"))
        assertEquals(listOf("Query 1", "HH", "POLE") + ADD_CATEGORIES.filter { it != "HH" && it != "POLE" }, choices)
        assertEquals(choices.size, choices.toSet().size)
    }

    @Test fun confirmEnqueuesAddAtCrosshairOpensSheet() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        deps.fixes.value = Fix(HERE.lat, HERE.lon, 5.0, null, 0L)
        deps.pointsFlow.value = listOf(pole("14"))
        compose.setContent {
            FieldmaxxTheme { MapScreen("j1", deps, map, onBack = {}, onJobGone = {}, pointSheet = { id, _ -> selected = id }) }
        }
        compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodesWithTag(MapTags.ADD).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(MapTags.ADD).performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); map.moves.any { it.lat == HERE.lat && it.lon == HERE.lon } }
        map.settle()
        map.userMoves(CameraView(39.6370, -119.6920, 18.5)) // the tech drags the map
        map.rest()
        compose.waitForIdle()

        compose.onNodeWithTag(AddTags.NEXT).performClick()
        compose.onNodeWithTag(AddTags.type("POLE")).performClick()
        compose.onNodeWithTag(AddTags.NAME).performTextClearance()
        compose.onNodeWithTag(AddTags.CONFIRM).performClick()
        compose.onNodeWithText("Name it POLE <number>, like POLE 1").assertExists() // empty number refused
        assertTrue(deps.enqueued.isEmpty())

        compose.onNodeWithTag(AddTags.NAME).performTextInput("POLE 14B")
        compose.onNodeWithTag(AddTags.CONFIRM).performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); deps.enqueued.isNotEmpty() && selected != null }

        val op = deps.enqueued.single() as Op.PointAdd
        assertEquals(Triple("POLE", "POLE", "14B"), Triple(op.layer, op.type, op.num))
        assertEquals(LatLon(39.6370, -119.6920), LatLon(op.lat, op.lon))
        assertEquals("j1", op.jobId)
        assertEquals(op.pointId, selected)
        assertEquals(0, compose.onAllNodesWithTag(AddTags.NAME).fetchSemanticsNodes().size, "the flow is over")
    }

    @Test fun addedPointKeepsJobLayerAndCategoryBecomesOwnLayer() {
        val points = listOf(mapPoint("q1", type = "POLE", num = "1").copy(layer = "Query 2"))
        assertEquals(LayerType("Query 2", "POLE"), layerTypeFor(points, "Query 2"))
        assertEquals(LayerType("CULVERT", "CULVERT"), layerTypeFor(points, "CULVERT"))
        val (op, _) = addPointOp("j1", points, "Query 2", "Query 2-7", HERE, "o", "p")
        assertEquals("7", op?.num)
        assertEquals("Query 2", op?.layer)
    }

    @Test fun blankNumberRefusedForEveryType() {
        for (type in ADD_CATEGORIES) {
            for (name in listOf(type, "$type ", type.lowercase())) {
                assertTrue(splitName(type, type, name) is NameResult.Bad, "'$name' has no number")
            }
            assertEquals(NameResult.Ok(type, "7"), splitName(type, type, "$type 7"))
        }
        assertEquals(NameResult.Ok("POLE", "14B"), splitName("POLE", "POLE", "pole 14B"))
        assertTrue(splitName("POLE", "POLE", "Street 3") is NameResult.Bad, "the name must match the chosen type")
    }

    @Test fun queryNumberMustMatchLayer() {
        assertEquals(NameResult.Ok("POLE", "7"), splitName("Query 2", "POLE", "Query 2-7"))
        val bad = splitName("Query 2", "POLE", "Query 5-7") as NameResult.Bad
        assertEquals("This point is in Query 2 — name it Query 2-<number>", bad.message)
        assertTrue(splitName("Query 2", "POLE", "Query 2-") is NameResult.Bad)
        assertTrue(splitName("Query 2", "POLE", "Query 2") is NameResult.Bad)
    }

    @Test fun pointsIAddedOnlyMine() {
        val mine = pole("1").copy(addedBy = "u1", label = "POLE 1B")
        val newer = pole("2").copy(addedBy = "u1", label = "POLE 1A")
        val points = listOf(mine, newer, pole("3").copy(addedBy = "u2"), pole("4"))
        assertEquals(listOf(newer, mine), myAddedPoints(points, "u1"), "sorted by label")

        var opened: String? = null
        compose.setContent {
            FieldmaxxTheme { PointsIAddedScreen(MutableStateFlow(points), { "u1" }, onOpen = { opened = it }, onBack = {}) }
        }
        compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodesWithTag(AddedTags.row(newer.id)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, compose.onAllNodesWithTag(AddedTags.row(mine.id)).fetchSemanticsNodes().size + compose.onAllNodesWithTag(AddedTags.row(newer.id)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag(AddedTags.row("p3")).fetchSemanticsNodes().size)
        compose.onNodeWithTag(AddedTags.row(newer.id)).performClick()
        assertEquals(newer.id, opened)
        assertEquals(0, compose.onAllNodesWithText("Added", substring = true).fetchSemanticsNodes().size, "no time is shown")
    }

    @Test fun markerTapsIgnoredWhileAdding() {
        deps.pointsFlow.value = listOf(pole("14"))
        compose.setContent {
            FieldmaxxTheme { MapScreen("j1", deps, map, onBack = {}, onJobGone = {}, pointSheet = { id, _ -> selected = id }) }
        }
        compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodesWithTag(MapTags.ADD).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(MapTags.ADD).performClick()
        compose.waitForIdle()
        map.pointTaps.tryEmit("p14")
        compose.waitForIdle()
        assertEquals(null, selected)
    }

    @Test fun enqueueFailureShowsMessageAndAllowsRetry() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        deps.fixes.value = Fix(HERE.lat, HERE.lon, 5.0, null, 0L)
        deps.enqueueError = IllegalStateException("disk full")
        compose.setContent {
            FieldmaxxTheme { MapScreen("j1", deps, map, onBack = {}, onJobGone = {}, pointSheet = { id, _ -> selected = id }) }
        }
        compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodesWithTag(MapTags.ADD).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(MapTags.ADD).performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); map.moves.isNotEmpty() }
        map.settle()
        compose.waitForIdle()
        compose.onNodeWithTag(AddTags.NEXT).performClick()
        compose.onNodeWithTag(AddTags.type("POLE")).performClick()
        compose.onNodeWithTag(AddTags.CONFIRM).performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodesWithTag(AddTags.ERROR).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(null, selected)
        deps.enqueueError = null
        compose.onNodeWithTag(AddTags.CONFIRM).performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); selected != null }
    }
}
