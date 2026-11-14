package com.fieldmaxx.app.point

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.model.CantShootReason
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.PointStatus

private val Ink = Color(0xFF0F172A) // dark text on the green Done button

internal fun cantShootLabel(r: CantShootReason): String = when (r) {
    CantShootReason.not_found -> "Not found"
    CantShootReason.no_access -> "No access"
    CantShootReason.not_applicable -> "Not applicable"
    CantShootReason.other -> "Other"
}

/**
 * Closing the point (spec §6.4): **Done** needs at least one photo ([hasPhotos]); **Can't shoot** needs a reason, and
 * Other needs it in words, as the server does (ops/Ops.kt). Both count as complete; a closed point shows how it was
 * closed and can be reopened.
 */
@Composable
internal fun CloseControls(
    point: Point,
    hasPhotos: Boolean,
    onDone: () -> Unit,
    onCantShoot: (CantShootReason, String?) -> Unit,
    onReopen: () -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (point.status) {
            PointStatus.open -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onDone,
                        enabled = hasPhotos,
                        colors = ButtonDefaults.buttonColors(containerColor = FmxColors.Done, contentColor = Ink),
                        modifier = Modifier.testTag(PointTags.DONE),
                    ) { Text("Done") }
                    OutlinedButton(onClick = { asking = true }, modifier = Modifier.testTag(PointTags.CANT_SHOOT)) { Text("Can't shoot") }
                }
                if (!hasPhotos) {
                    Text("Done needs at least one photo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PointStatus.done, PointStatus.cant_shoot -> {
                Text(closedText(point), color = if (point.status == PointStatus.done) FmxColors.Done else FmxColors.Warning)
                OutlinedButton(onClick = onReopen, modifier = Modifier.testTag(PointTags.REOPEN)) { Text("Reopen") }
            }
        }
    }
    if (asking) {
        CantShootDialog(
            point.label,
            onDismiss = { asking = false },
            onSave = { reason, text ->
                asking = false
                onCantShoot(reason, text)
            },
        )
    }
}

/** "Done", or "Can't shoot: No access", with Other's words after it. */
private fun closedText(p: Point): String {
    if (p.status == PointStatus.done) return "Done"
    val reason = p.cantReason?.let(::cantShootLabel) ?: return "Can't shoot"
    return "Can't shoot: $reason" + (p.cantText?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
}

@Composable
private fun CantShootDialog(label: String, onDismiss: () -> Unit, onSave: (CantShootReason, String?) -> Unit) {
    var reason by remember { mutableStateOf<CantShootReason?>(null) }
    var text by remember { mutableStateOf("") }
    val chosen = reason
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Can't shoot $label") },
        text = {
            Column(Modifier.selectableGroup()) {
                for (r in CantShootReason.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .selectable(selected = r == chosen, role = Role.RadioButton, onClick = { reason = r })
                            .padding(vertical = 4.dp)
                            .testTag(PointTags.reason(r)),
                    ) {
                        RadioButton(selected = r == chosen, onClick = null)
                        Text(cantShootLabel(r), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (chosen == CantShootReason.other) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Why not?") },
                        modifier = Modifier.fillMaxWidth().testTag(PointTags.REASON_TEXT),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (chosen != null) onSave(chosen, if (chosen == CantShootReason.other) text.trim() else null) },
                enabled = chosen != null && (chosen != CantShootReason.other || text.isNotBlank()),
                modifier = Modifier.testTag(PointTags.CANT_SHOOT_SAVE),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
