package com.fieldmaxx.app.point

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.fieldmaxx.app.map.MapController
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.model.Op

/** How long "Undo move" stays after a move is confirmed (R47). */
internal const val UNDO_MOVE_MS = 10_000L

internal const val NOT_ON_MAP = "The map isn't showing this point. Show it (its type chip, Hide shot) to move it."

/** A confirmed move's way back: where the point was before the drag. Each move has its own. */
internal class UndoMove(val to: LatLon)

/**
 * Move point (spec §6.4, R47). [start] puts the sheet aside while the tech drags the marker on the map ([follow], through
 * [MapController.beginDrag]). [confirm] saves the move at once and offers [undoMove] for 10 s, which puts the point back
 * where it was before the drag; [cancel] saves nothing and the marker goes back. Nothing here moves the camera (R38):
 * the map stays where the tech put it.
 */
@Stable
internal class MoveFlow(private val pointId: String, private val controller: MapController) {
    /** The marker is being dragged; the sheet is out of the way. */
    var dragging by mutableStateOf(false)
        private set

    /** Where the point was when the drag began and where its marker is now; null until the map says. */
    private var from by mutableStateOf<LatLon?>(null)
    private var at by mutableStateOf<LatLon?>(null)

    /** While "Undo move" shows. */
    var undo by mutableStateOf<UndoMove?>(null)
        private set

    /** Why the last move couldn't start. */
    var problem by mutableStateOf<String?>(null)
        private set

    /** The marker has been dragged somewhere else. */
    val canConfirm: Boolean
        get() {
            val f = from
            val t = at
            return f != null && t != null && t != f
        }

    fun start() {
        problem = null
        undo = null
        from = null
        at = null
        dragging = true
    }

    /** While [dragging]: follows the marker until the drag ends. A drag the map ends by itself (it doesn't show the point, say) saves nothing. */
    suspend fun follow() {
        try {
            controller.beginDrag(pointId).collect { p ->
                if (from == null) from = p
                at = p
            }
        } finally {
            if (dragging) {
                if (from == null) problem = NOT_ON_MAP
                dragging = false
            }
        }
    }

    /** Saves the move through [send] and offers "Undo move". */
    fun confirm(send: (Op) -> Unit) {
        val f = from ?: return
        val t = at ?: return
        if (t == f) return
        dragging = false // first: the drag's end mustn't read as the map ending it
        controller.endDrag()
        send(Op.PointMove(newOpId(), pointId, t.lat, t.lon))
        undo = UndoMove(f)
    }

    fun cancel() {
        dragging = false
        controller.endDrag()
    }

    /** A second move, back to where the point was before the drag. */
    fun undoMove(send: (Op) -> Unit) {
        val u = undo ?: return
        undo = null
        send(Op.PointMove(newOpId(), pointId, u.to.lat, u.to.lon))
    }

    /** [u]'s 10 s are over. */
    fun expire(u: UndoMove) {
        if (undo === u) undo = null
    }
}

/**
 * The bar over the map while the marker is dragged. It is a window of its own that doesn't take focus, so the map
 * around it keeps every touch.
 */
@Composable
internal fun MoveBar(canConfirm: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() // the popup's own window has no insets
    Popup(alignment = Alignment.BottomCenter, properties = PopupProperties(focusable = false)) {
        Surface(
            color = FmxColors.Bar,
            contentColor = Color.White,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = bottom + 12.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Drag the marker to where the point is.", style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onCancel, modifier = Modifier.testTag(PointTags.MOVE_CANCEL)) { Text("Cancel", color = Color.White) }
                    Button(onClick = onConfirm, enabled = canConfirm, modifier = Modifier.testTag(PointTags.MOVE_CONFIRM)) { Text("Confirm") }
                }
            }
        }
    }
}

/** "POLE 18 moved · Undo move", for 10 s after a move. */
@Composable
internal fun UndoMoveRow(label: String, onUndo: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(start = 12.dp),
    ) {
        Text("$label moved", modifier = Modifier.weight(1f))
        TextButton(onClick = onUndo, modifier = Modifier.testTag(PointTags.UNDO_MOVE)) { Text("Undo move") }
    }
}
