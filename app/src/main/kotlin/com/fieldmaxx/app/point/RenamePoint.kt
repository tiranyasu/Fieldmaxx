package com.fieldmaxx.app.point

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.naming.ADD_CATEGORIES

/** The type chips: the job's own types first, then the standard categories, each once. */
internal fun renameTypeChoices(jobTypes: List<String>): List<String> = (jobTypes + ADD_CATEGORIES).distinct()

/** The warning when [delivered] of the point's photos are confirmed in OneDrive in [folder]; null when none are. */
internal fun renameWarning(delivered: Int, folder: String): String? = when {
    delivered <= 0 -> null
    delivered == 1 ->
        "1 photo of this point is already in OneDrive in $folder. New photos will go to the new name's folder; that one stays where it is."
    else ->
        "$delivered photos of this point are already in OneDrive in $folder. New photos will go to the new name's folder; those stay where they are."
}

/**
 * Edit / Rename: the point's type and number. Saving sends one [com.fieldmaxx.core.model.Op.PointRename]; the label and
 * the photo folder follow from the new name. Save needs both fields filled.
 */
@Composable
internal fun RenamePointDialog(
    point: Point,
    jobTypes: List<String>,
    delivered: Int,
    onDismiss: () -> Unit,
    onSave: (type: String, num: String) -> Unit,
) {
    var type by remember { mutableStateOf(point.type) }
    var num by remember { mutableStateOf(point.num) }
    val ok = type.isNotBlank() && num.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ${point.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = type, onValueChange = { type = it }, label = { Text("Type") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(PointTags.RENAME_TYPE),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in renameTypeChoices(jobTypes)) {
                        FilterChip(selected = type == choice, onClick = { type = choice }, label = { Text(choice) })
                    }
                }
                OutlinedTextField(
                    value = num, onValueChange = { num = it }, label = { Text("Number") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(PointTags.RENAME_NUM),
                )
                renameWarning(delivered, point.folderPath ?: plannedFolder(point))?.let { Text(it, color = FmxColors.Warning) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(type.trim(), num.trim()) }, enabled = ok, modifier = Modifier.testTag(PointTags.RENAME_SAVE)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
