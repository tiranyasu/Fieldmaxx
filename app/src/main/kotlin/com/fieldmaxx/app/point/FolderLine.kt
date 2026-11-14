package com.fieldmaxx.app.point

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.naming.defaultFolderName
import com.fieldmaxx.core.naming.isQueryLayer

/**
 * The folder a point gets when none is found for it (§6.6.1): `<Category>/<TYPE>_<ID>`, as the folder resolver makes
 * it (`POLE/POLE_33`, `QUERY 1/Query_1-43`).
 */
internal fun plannedFolder(p: Point): String =
    (if (isQueryLayer(p.layer)) p.layer.trim() else p.type) + "/" + defaultFolderName(p.label)

/** "→ Field Notes/POLE/POLE_18": [path] is inside the job's Field Notes folder. */
internal fun folderLineText(path: String): String = "→ Field Notes/$path"

/**
 * Where the point's photos go (spec §6.4): its stored folder, or until one is found, the folder that would be made. When
 * the folder search found more than one folder that could be the point's, it asks which; the choice is stored on the
 * point for the whole crew ([onPick]), so it asks once.
 */
@Composable
internal fun FolderLine(point: Point, onPick: (String) -> Unit) {
    val candidates = point.folderCandidates.orEmpty()
    if (point.folderPath == null && candidates.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("More than one folder could be this point's. Which one should its photos go to?", color = FmxColors.Warning)
            for (c in candidates) {
                OutlinedButton(onClick = { onPick(c) }, modifier = Modifier.fillMaxWidth().testTag(PointTags.folderChoice(c))) {
                    Text(folderLineText(c))
                }
            }
        }
    } else {
        Text(
            folderLineText(point.folderPath ?: plannedFolder(point)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(PointTags.FOLDER),
        )
    }
}
