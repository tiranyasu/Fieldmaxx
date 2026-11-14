package com.fieldmaxx.app.jobs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.app.ui.fmxBarColors
import com.fieldmaxx.core.model.DraftPoint
import com.fieldmaxx.core.model.ImportPreview
import com.fieldmaxx.core.model.UnreadLine
import java.util.Locale
import kotlinx.coroutines.launch

const val PHOTOS_WILL_WAIT = "Photos will wait until the Field Notes link is added"

object CheckTags {
    const val LIST = "check.list"
    const val NAME = "check.name"
    const val SAVE = "check.save"
    const val FORM_LABEL = "check.form.label"
    const val FORM_COORDINATES = "check.form.coordinates"
    const val FORM_CONFIRM = "check.form.confirm"
    fun layer(importId: String) = "check.layer:$importId"
    fun point(importId: String, index: Int) = "check.point:$importId:$index"
    fun unread(importId: String, index: Int) = "check.unread:$importId:$index"
}

/** Which point is being edited, or which line fixed. */
private class Editing(val importId: String, val index: Int)
private class Fixing(val importId: String, val line: UnreadLine)

/**
 * The check screen (spec §6.2), phone-sized: per file, the layer, "Found 7 POLE points" per type, a card for every
 * point (label, coordinates, description, flags; edit or remove) and every line the reader couldn't read (fix it into a
 * point, or ignore it explicitly). A new job also has its name and Field Notes link. Save is enabled only when every
 * point has coordinates and every unread line is fixed or ignored; until then the bar says what's left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckScreen(model: NewJobModel, api: Api, onBack: () -> Unit) {
    val s = model.state ?: return
    var editing by remember { mutableStateOf<Editing?>(null) }
    var fixing by remember { mutableStateOf<Fixing?>(null) }
    var askNoLink by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (model.isNew) "New job" else "Add files") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = fmxBarColors(),
            )
        },
        bottomBar = { SaveBar(model, s, onSave = { if (model.isNew && model.link.isBlank()) askNoLink = true else model.save() }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding().testTag(CheckTags.LIST),
            state = list,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (model.isNew) {
                item(key = "name") {
                    OutlinedTextField(
                        model.name, model::rename, label = { Text("Job name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(CheckTags.NAME),
                    )
                }
            }
            if (model.failed.isNotEmpty()) {
                item(key = "failed") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FailedFiles(model.failed)
                        TextButton(onClick = model::retryFailed, enabled = model.reading == null) { Text("Try again") }
                    }
                }
            }
            for (preview in s.previews) {
                val id = preview.importId
                val points = pointsOf(s, id)
                item(key = "file:$id") {
                    FileHeader(preview, layer = s.layers[id] ?: preview.layerSuggestion, points = points, onLayer = { l -> model.update { setLayer(it, id, l) } })
                }
                itemsIndexed(points, key = { i, _ -> "point:$id:$i" }) { i, point ->
                    PointCard(
                        point,
                        onEdit = { editing = Editing(id, i) },
                        onRemove = { model.update { removePoint(it, id, i) } },
                        modifier = Modifier.testTag(CheckTags.point(id, i)),
                    )
                }
                if (preview.unread.isNotEmpty()) {
                    item(key = "unread:$id") {
                        Text(
                            "Couldn't read (${preview.unread.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = FmxColors.Warning,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    itemsIndexed(preview.unread, key = { i, _ -> "unread:$id:$i" }) { i, line ->
                        UnreadCard(
                            line,
                            ignored = isIgnored(s, id, line),
                            onFix = { fixing = Fixing(id, line) },
                            onIgnore = { ignore -> model.update { ignoreUnread(it, id, line, ignore) } },
                            modifier = Modifier.testTag(CheckTags.unread(id, i)),
                        )
                    }
                }
            }
            if (model.isNew) {
                item(key = "link") { FieldNotesLinkField(model.link, { model.link = it }, api, Modifier.padding(top = 8.dp)) }
            }
        }
    }

    editing?.let { e ->
        val point = pointsOf(s, e.importId).getOrNull(e.index) ?: return@let
        PointDialog(
            title = "Edit ${point.label}",
            line = null,
            label = point.label,
            coordinates = coordinatesText(point),
            description = point.description,
            confirm = "Save point",
            onConfirm = { label, coordinates, description ->
                model.update { editPoint(it, e.importId, e.index, label, coordinates, description) }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    fixing?.let { f ->
        PointDialog(
            title = "Fix this line",
            line = f.line.text,
            label = suggestedLabel(f.line),
            coordinates = "",
            description = null,
            confirm = "Add point",
            onConfirm = { label, coordinates, _ ->
                model.update { fixUnread(it, f.importId, f.line, label, coordinates) }
                fixing = null
            },
            onDismiss = { fixing = null },
        )
    }
    if (askNoLink) {
        AlertDialog(
            onDismissRequest = { askNoLink = false },
            title = { Text("No Field Notes link") },
            text = { Text(PHOTOS_WILL_WAIT) },
            confirmButton = {
                TextButton(onClick = {
                    askNoLink = false
                    model.save()
                }) { Text("Save without link") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askNoLink = false
                    scope.launch { list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1) }
                }) { Text("Add link") }
            },
        )
    }
}

@Composable
private fun FileHeader(preview: ImportPreview, layer: String, points: List<DraftPoint>, onLayer: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(preview.fileName, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            layer, onLayer, label = { Text("Layer") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(CheckTags.layer(preview.importId)),
        )
        val lines = summary(points)
        val missing = points.any { it.lat == null || it.lon == null }
        if (lines.isEmpty()) Text("No points found in this file", color = MaterialTheme.colorScheme.onSurfaceVariant)
        lines.forEachIndexed { i, line ->
            Text(line, style = MaterialTheme.typography.bodyLarge, color = if (missing && i == lines.lastIndex) FmxColors.Warning else LocalContentColor.current)
        }
        if (preview.notes.isNotBlank()) {
            Text("Job notes from this file", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(preview.notes, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PointCard(point: DraftPoint, onEdit: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(point.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
            val lat = point.lat
            val lon = point.lon
            if (lat != null && lon != null) {
                Text(String.format(Locale.US, "%.6f, %.6f", lat, lon), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("No coordinates: add them, or remove the point", color = FmxColors.Warning)
            }
            if (point.description.isNotBlank()) {
                Text(point.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (point.flags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    point.flags.forEach { FlagBadge(flagText(it)) }
                }
            }
        }
    }
}

@Composable
private fun FlagBadge(text: String) {
    Surface(shape = RoundedCornerShape(50), color = FmxColors.Warning.copy(alpha = 0.12f), border = BorderStroke(1.dp, FmxColors.Warning)) {
        Text(text, color = FmxColors.Warning, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun UnreadCard(line: UnreadLine, ignored: Boolean, onFix: () -> Unit, onIgnore: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.alpha(if (ignored) 0.6f else 1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(line.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(line.sourcePage?.let { "Page $it" }, line.reason).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ignored) {
                    Text("Ignored", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onIgnore(false) }) { Text("Undo") }
                } else {
                    Text("Not on the map yet", color = FmxColors.Warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onFix) { Text("Fix") }
                    TextButton(onClick = { onIgnore(true) }) { Text("Ignore") }
                }
            }
        }
    }
}

@Composable
private fun SaveBar(model: NewJobModel, s: CheckState, onSave: () -> Unit) {
    Surface(color = FmxColors.Bar) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val missing = pointsWithoutCoordinates(s)
            val unread = unreadLeft(s)
            if (missing > 0) Text(if (missing == 1) "Fix or remove 1 point without coordinates." else "Fix or remove $missing points without coordinates.", color = FmxColors.Warning)
            if (unread > 0) Text(if (unread == 1) "Fix or ignore 1 line that couldn't be read." else "Fix or ignore $unread lines that couldn't be read.", color = FmxColors.Warning)
            if (model.isNew && model.name.isBlank()) Text("Enter the job name.", color = FmxColors.Warning)
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onSave, enabled = model.saveEnabled, modifier = Modifier.fillMaxWidth().testTag(CheckTags.SAVE)) {
                if (model.saving) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                } else {
                    Text(if (model.isNew) "Save job" else "Add to job")
                }
            }
        }
    }
}

/**
 * Label and coordinates (and the description, when [description] isn't null) for an edited point or a fixed line.
 * [onConfirm] throws [IllegalArgumentException] with a readable reason when the form can't be taken.
 */
@Composable
private fun PointDialog(
    title: String,
    line: String?,
    label: String,
    coordinates: String,
    description: String?,
    confirm: String,
    onConfirm: (label: String, coordinates: String, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var l by rememberSaveable { mutableStateOf(label) }
    var c by rememberSaveable { mutableStateOf(coordinates) }
    var d by rememberSaveable { mutableStateOf(description.orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                line?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    l, { l = it; problem = null }, label = { Text("Label") }, placeholder = { Text("POLE 9") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(CheckTags.FORM_LABEL),
                )
                OutlinedTextField(
                    c, { c = it; problem = null }, label = { Text("Coordinates") }, placeholder = { Text("39.636130, -119.691104") },
                    singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().testTag(CheckTags.FORM_COORDINATES),
                )
                if (description != null) {
                    OutlinedTextField(d, { d = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    try {
                        onConfirm(l, c, d)
                    } catch (e: IllegalArgumentException) {
                        problem = e.message
                    }
                },
                modifier = Modifier.testTag(CheckTags.FORM_CONFIRM),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
