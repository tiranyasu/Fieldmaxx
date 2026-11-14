package com.fieldmaxx.app.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.geo.distanceMeters
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.naming.ADD_CATEGORIES
import com.fieldmaxx.core.naming.isQueryLayer
import com.fieldmaxx.core.naming.nextNum
import com.fieldmaxx.core.naming.pointLabel
import com.fieldmaxx.core.naming.suggestAddedPoleNum
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.launch

// "+" on the map (spec §6.5): a crosshair at your position to adjust, then the type, then the name.

object AddTags {
    const val CROSSHAIR = "add.crosshair"
    const val NEXT = "add.next"
    const val CANCEL = "add.cancel"
    const val NAME = "add.name"
    const val CONFIRM = "add.confirm"
    const val ERROR = "add.error"
    fun type(choice: String) = "add.type:$choice"
}

/** The crosshair starts at least this close, so the spot can be placed on the imagery. */
private const val ADD_ZOOM = 18.0

/** The type picker: the job's own layers first, then the standard categories, each once. */
fun typeChoices(jobLayers: List<String>): List<String> = (jobLayers + ADD_CATEGORIES).distinct()

/** What a choice in the picker becomes: its layer and type (R49). */
data class LayerType(val layer: String, val type: String)

/**
 * R49: a job layer keeps its layer, with the type its points use (for a query layer, the type its imported points
 * carry); any other category is its own layer and type.
 */
fun layerTypeFor(points: List<Point>, choice: String): LayerType {
    val inLayer = points.filter { it.layer == choice }
    return if (inLayer.isEmpty()) LayerType(choice, choice) else LayerType(choice, inLayer.first().type)
}

/**
 * The name offered for a new point at [at] (§6.5): for a pole, the nearest pole's number and the next free letter
 * ("POLE 14B"); otherwise the next free number of that layer and type ("Street images 3").
 */
fun suggestName(points: List<Point>, type: String, layer: String, at: LatLon): String {
    if (type == "POLE") {
        val poles = points.filter { it.type == "POLE" }
        val nearest = poles.minByOrNull { distanceMeters(at, LatLon(it.lat, it.lon)) }
        if (nearest != null) return pointLabel(layer, type, suggestAddedPoleNum(poles.map { it.num }, nearest.num))
    }
    return pointLabel(layer, type, nextNum(points.filter { it.layer == layer && it.type == type }.map { it.num }))
}

/** The type and number an edited name stands for, or a message saying what is missing. */
sealed interface NameResult {
    data class Ok(val type: String, val num: String) : NameResult
    data class Bad(val message: String) : NameResult
}

const val NAME_NEEDS_NUMBER = "Add a number after the name, like POLE 14B"

private val QUERY_NAME = Regex("""^query\s*\d+\s*-\s*(\S+)$""", RegexOption.IGNORE_CASE)

/** Splits the name into [type] and number (R49); an empty number is refused. */
fun splitName(layer: String, type: String, name: String): NameResult {
    val text = name.trim().replace(Regex("""\s+"""), " ")
    if (isQueryLayer(layer)) {
        val num = QUERY_NAME.matchEntire(text)?.groupValues?.get(1)
        return if (num == null) NameResult.Bad("Name it like Query 1-44") else NameResult.Ok(type, num)
    }
    if (text.startsWith(type, ignoreCase = true) && text.length > type.length && text[type.length] == ' ') {
        return NameResult.Ok(type, text.substring(type.length + 1).trim())
    }
    val cut = text.lastIndexOf(' ')
    if (cut <= 0) return NameResult.Bad(NAME_NEEDS_NUMBER)
    return NameResult.Ok(text.substring(0, cut), text.substring(cut + 1))
}

/** The op for the confirmed [name], or the message to show. */
fun addPointOp(
    jobId: String,
    points: List<Point>,
    choice: String,
    name: String,
    at: LatLon,
    opId: String,
    pointId: String,
): Pair<Op.PointAdd?, String?> {
    val (layer, type) = layerTypeFor(points, choice)
    return when (val r = splitName(layer, type, name)) {
        is NameResult.Bad -> null to r.message
        is NameResult.Ok -> Op.PointAdd(opId, jobId, pointId, layer, r.type, r.num, at.lat, at.lon) to null
    }
}

private enum class Step { PLACE, TYPE, NAME }

/**
 * The flow over the map: the crosshair is the camera's centre, which starts at [start] (your latest fix) when known;
 * dragging the map adjusts it. "Next" takes the spot, then the type, then the name; confirming writes the op through
 * [enqueue] (on the phone at once, to the server when there is signal) and calls [onAdded] with the new point's id.
 */
@Composable
internal fun AddPointFlow(
    jobId: String,
    points: List<Point>,
    controller: MapController,
    start: LatLon?,
    enqueue: suspend (Op) -> Unit,
    onAdded: (pointId: String) -> Unit,
    onCancel: () -> Unit,
    newId: () -> String = { UUID.randomUUID().toString() },
) {
    var step by rememberSaveable { mutableStateOf(Step.PLACE) }
    var lat by rememberSaveable { mutableStateOf(0.0) }
    var lon by rememberSaveable { mutableStateOf(0.0) }
    var choice by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val camera by controller.camera.collectAsState()
    val scope = rememberCoroutineScope()

    // The start is the user's own tap on "+", so this move is allowed (R38).
    LaunchedEffect(Unit) {
        if (step == Step.PLACE && start != null) {
            controller.moveTo(CameraView(start.lat, start.lon, max(controller.camera.value?.zoom ?: ADD_ZOOM, ADD_ZOOM)))
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (step == Step.PLACE) {
            Crosshair(Modifier.align(Alignment.Center).testTag(AddTags.CROSSHAIR))
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(FmxColors.Bar, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (step) {
                Step.PLACE -> {
                    Text("Drag the map to put the cross on the spot", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onCancel, modifier = Modifier.testTag(AddTags.CANCEL)) { Text("Cancel") }
                        Button(
                            onClick = {
                                camera?.let { lat = it.lat; lon = it.lon; step = Step.TYPE }
                            },
                            enabled = camera != null,
                            modifier = Modifier.testTag(AddTags.NEXT),
                        ) { Text("Next") }
                    }
                }
                Step.TYPE -> {
                    Text("What is it?", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    val choices = remember(points) { typeChoices(points.map { it.layer }.distinct()) }
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(choices, key = { it }) { c ->
                            Text(
                                c,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    val (layer, type) = layerTypeFor(points, c)
                                    choice = c
                                    name = suggestName(points, type, layer, LatLon(lat, lon))
                                    error = null
                                    step = Step.NAME
                                }.padding(vertical = 12.dp).testTag(AddTags.type(c)),
                            )
                        }
                    }
                    TextButton(onClick = { step = Step.PLACE }) { Text("Back") }
                }
                Step.NAME -> {
                    Text("Name", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; error = null },
                        singleLine = true,
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth().testTag(AddTags.NAME),
                    )
                    error?.let { Text(it, color = FmxColors.Warning, modifier = Modifier.testTag(AddTags.ERROR)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { step = Step.TYPE }) { Text("Back") }
                        Button(
                            enabled = !saving,
                            onClick = {
                                val id = newId()
                                val (op, message) = addPointOp(jobId, points, choice, name, LatLon(lat, lon), newId(), id)
                                error = message
                                if (op != null) {
                                    saving = true
                                    scope.launch {
                                        enqueue(op)
                                        onAdded(id)
                                    }
                                }
                            },
                            modifier = Modifier.testTag(AddTags.CONFIRM),
                        ) { Text("Add point") }
                    }
                }
            }
        }
    }
}

/** A cross over the centre of the map. */
@Composable
private fun Crosshair(modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).drawBehind {
            val c = Offset(size.width / 2, size.height / 2)
            val line = Stroke(width = 3.dp.toPx())
            drawLine(Color.White, Offset(c.x, 0f), Offset(c.x, size.height), line.width)
            drawLine(Color.White, Offset(0f, c.y), Offset(size.width, c.y), line.width)
            drawCircle(FmxColors.Accent, radius = 6.dp.toPx(), center = c)
        },
    )
}
