package com.fieldmaxx.app.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.point.NEW_TAG_MAX
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.model.AdminSettings
import com.fieldmaxx.core.model.AppConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Global constraints: backups are kept at least this many days after they arrive (the server refuses fewer). */
private const val MIN_BACKUP_DAYS = 3

/** The server's own words for too few days (admin/AdminRoutes.kt). */
const val BACKUP_DAYS_TOO_FEW = "Backups must be kept at least $MIN_BACKUP_DAYS days"

private const val STANDARD = 85
private const val MAXIMUM = 95

object AdminSettingsTags {
    const val STANDARD = "adminSettings.standard"
    const val MAXIMUM = "adminSettings.maximum"
    const val BACKUP_DAYS = "adminSettings.backupDays"
    const val IMAGERY = "adminSettings.imagery"
    const val LABELS = "adminSettings.labels"
    const val KEY = "adminSettings.key"
    const val SAVE = "adminSettings.save"
    const val MESSAGE = "adminSettings.message"
    fun tag(t: String) = "adminSettings.tag:$t"
    fun tagUp(t: String) = "adminSettings.tag.up:$t"
    fun tagDown(t: String) = "adminSettings.tag.down:$t"
    fun tagRename(t: String) = "adminSettings.tag.rename:$t"
    fun tagDelete(t: String) = "adminSettings.tag.delete:$t"
    const val TAG_NAME = "adminSettings.tag.name"
    const val TAG_SAVE = "adminSettings.tag.save"
    const val TAG_DELETE_CONFIRM = "adminSettings.tag.deleteConfirm"
}

/** [list] with the item at [from] moved to [to]. */
private fun <T> moved(list: List<T>, from: Int, to: Int): List<T> = list.toMutableList().apply { add(to, removeAt(from)) }

/** The form as typed: what [changes] compares with the settings it started from. */
private data class Form(val quality: Int, val days: String, val imagery: String, val labels: String, val key: String) {
    constructor(c: AppConfig) : this(c.photoQuality, c.backupDays.toString(), c.imageryUrl, c.labelsUrl, c.imageryKey.orEmpty())
}

/**
 * Only what differs from [base]: a field left as it was isn't sent, so a server default stays a default. A map field
 * emptied clears the server's own (the default comes back). Null with the reason when the days aren't valid.
 */
private fun changes(form: Form, base: Form): Pair<AdminSettings?, String?> {
    val days = form.days.trim().toIntOrNull() ?: return null to "Enter the number of days"
    if (days < MIN_BACKUP_DAYS) return null to BACKUP_DAYS_TOO_FEW
    return AdminSettings(
        photoQuality = form.quality.takeIf { it != base.quality },
        backupDays = days.takeIf { it.toString() != base.days.trim() },
        imageryUrl = form.imagery.trim().takeIf { it != base.imagery },
        labelsUrl = form.labels.trim().takeIf { it != base.labels },
        imageryKey = form.key.trim().takeIf { it != base.key },
    ) to null
}

/**
 * Admin → Settings (spec §6.9): photo quality (Standard 85% / Maximum 95%), how long the server keeps its backups
 * (at least 3 days, global constraints) and the map's imagery (address, street names, optional key, R39). It starts from
 * the settings kept on the phone ([kept]) and the server's latest. Saved, [onSaved] fetches the phone's settings again,
 * so the map and the photo quality follow at once.
 *
 * Tags: the crew's one tag list, which every point screen shows. Rename ([Api.renameTag]: the server renames it on the
 * points too), delete and reorder ([Api.putTags]: points keep a deleted tag) are each saved at once, then [onSaved].
 */
@Composable
fun AdminSettingsScreen(api: Api, kept: suspend () -> AppConfig, onSaved: () -> Unit, onBack: () -> Unit) {
    var base by remember { mutableStateOf<Form?>(null) }
    var form by remember { mutableStateOf<Form?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var tagBusy by remember { mutableStateOf(false) }
    var renamingTag by remember { mutableStateOf<String?>(null) }
    var deletingTag by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Main.immediate) {
            val keptConfig = kept()
            val local = Form(keptConfig)
            base = local
            form = local
            tags = keptConfig.tags
            try {
                val latestConfig = api.config()
                val latest = Form(latestConfig)
                if (form == base) form = latest // not edited meanwhile
                base = latest
                tags = latestConfig.tags
            } catch (e: IOException) {
                message = "$ADMIN_NEEDS_SIGNAL Showing the settings this phone has."
                warning = true
            } catch (e: ApiException) {
                message = e.message
                warning = true
            }
        }
    }

    fun say(text: String?, isWarning: Boolean) {
        message = text
        warning = isWarning
    }

    /**
     * Makes one tag change ([change], which returns the list it expects the server to hold, or null when there was nothing
     * to send), then shows the list the server holds, read back (R8), and the phone's settings follow.
     */
    fun changeTags(done: String, change: suspend () -> List<String>?) {
        tagBusy = true
        scope.launch(Dispatchers.Main.immediate) {
            try {
                val expected = change()
                if (expected != null) {
                    onSaved()
                    say(done, isWarning = false)
                }
                tags = try {
                    api.config().tags
                } catch (e: IOException) {
                    expected ?: tags
                } catch (e: ApiException) {
                    expected ?: tags
                }
            } catch (e: IOException) {
                say(ADMIN_NEEDS_SIGNAL, isWarning = true)
            } catch (e: ApiException) {
                say(e.message, isWarning = true)
            } finally {
                tagBusy = false
            }
        }
    }

    /**
     * R8: a delete or a move is made to the server's list as it is now, not as it was when the screen opened, so tags the
     * crew added meanwhile stay. [edit] gets that list and returns the one to send (null: nothing to change).
     */
    fun editTags(done: String, edit: (List<String>) -> List<String>?) = changeTags(done) {
        val fresh = api.config().tags
        edit(fresh)?.takeIf { it != fresh }?.also { api.putTags(it) }
    }

    /** [t] moved [by] places in [list]; null when it isn't listed any more or can't move further. */
    fun movedTag(list: List<String>, t: String, by: Int): List<String>? {
        val i = list.indexOf(t)
        val j = i + by
        return if (i < 0 || j !in list.indices) null else moved(list, i, j)
    }

    AdminScaffold("Admin settings", onBack) { padding ->
        val f = form ?: return@AdminScaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Photo quality", style = MaterialTheme.typography.titleMedium)
            for ((quality, label, tag) in listOf(
                Triple(STANDARD, "Standard (85%)", AdminSettingsTags.STANDARD),
                Triple(MAXIMUM, "Maximum (95%)", AdminSettingsTags.MAXIMUM),
            )) {
                Row(
                    Modifier.fillMaxWidth().selectable(selected = f.quality == quality, role = Role.RadioButton) { form = form?.copy(quality = quality) }
                        .testTag(tag),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = f.quality == quality, onClick = null)
                    Text(label)
                }
            }

            Text("Server backups", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = f.days,
                onValueChange = { form = form?.copy(days = it) },
                label = { Text("Keep backups for (days)") },
                supportingText = { Text("At least $MIN_BACKUP_DAYS. A backup is never deleted before OneDrive has the photo.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag(AdminSettingsTags.BACKUP_DAYS),
            )

            Text("Map", style = MaterialTheme.typography.titleMedium)
            Text("Empty a field to go back to the default.", style = MaterialTheme.typography.bodySmall)
            MapField(f.imagery, "Imagery address", AdminSettingsTags.IMAGERY) { form = form?.copy(imagery = it) }
            MapField(f.labels, "Street names address", AdminSettingsTags.LABELS) { form = form?.copy(labels = it) }
            MapField(f.key, "Imagery key (optional)", AdminSettingsTags.KEY) { form = form?.copy(key = it) }

            Button(
                onClick = {
                    val start = base ?: return@Button
                    val typed = form ?: return@Button
                    val (settings, invalid) = changes(typed, start)
                    if (settings == null) {
                        say(invalid, isWarning = true)
                        return@Button
                    }
                    if (settings == AdminSettings()) {
                        say("Nothing changed", isWarning = false)
                        return@Button
                    }
                    saving = true
                    scope.launch(Dispatchers.Main.immediate) {
                        try {
                            api.putSettings(settings)
                            base = typed.copy(days = typed.days.trim(), imagery = typed.imagery.trim(), labels = typed.labels.trim(), key = typed.key.trim())
                            onSaved()
                            say("Settings saved", isWarning = false)
                        } catch (e: IOException) {
                            say(ADMIN_NEEDS_SIGNAL, isWarning = true)
                        } catch (e: ApiException) {
                            say(e.message, isWarning = true)
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving && base != null,
                modifier = Modifier.fillMaxWidth().testTag(AdminSettingsTags.SAVE),
            ) { Text("Save") }
            message?.let { Text(it, color = if (warning) FmxColors.Warning else MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag(AdminSettingsTags.MESSAGE)) }

            Text("Tags", style = MaterialTheme.typography.titleMedium)
            Text(
                "The crew's tag list, in the order the point screen shows it. Changes here are saved at once; points keep a deleted tag.",
                style = MaterialTheme.typography.bodySmall,
            )
            tags.forEachIndexed { i, t ->
                TagRow(
                    t,
                    canUp = i > 0,
                    canDown = i < tags.lastIndex,
                    enabled = !tagBusy,
                    onUp = { editTags("Tags saved") { fresh -> movedTag(fresh, t, -1) } },
                    onDown = { editTags("Tags saved") { fresh -> movedTag(fresh, t, +1) } },
                    onRename = { renamingTag = t },
                    onDelete = { deletingTag = t },
                )
            }
        }
    }

    renamingTag?.let { from ->
        RenameTagDialog(
            from,
            onDismiss = { renamingTag = null },
            onSave = { to ->
                renamingTag = null
                changeTags("Renamed \u201c$from\u201d to \u201c$to\u201d") {
                    api.renameTag(from, to)
                    tags.map { if (it == from) to else it }
                }
            },
        )
    }
    deletingTag?.let { t ->
        AlertDialog(
            onDismissRequest = { deletingTag = null },
            text = { Text("Delete the tag \u201c$t\u201d? Points that have it keep it.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingTag = null
                        editTags("Deleted \u201c$t\u201d") { fresh -> fresh - t }
                    },
                    modifier = Modifier.testTag(AdminSettingsTags.TAG_DELETE_CONFIRM),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingTag = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TagRow(
    tag: String,
    canUp: Boolean,
    canDown: Boolean,
    enabled: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tag, modifier = Modifier.weight(1f).testTag(AdminSettingsTags.tag(tag)))
        IconButton(onClick = onUp, enabled = enabled && canUp, modifier = Modifier.testTag(AdminSettingsTags.tagUp(tag))) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move $tag up")
        }
        IconButton(onClick = onDown, enabled = enabled && canDown, modifier = Modifier.testTag(AdminSettingsTags.tagDown(tag))) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move $tag down")
        }
        IconButton(onClick = onRename, enabled = enabled, modifier = Modifier.testTag(AdminSettingsTags.tagRename(tag))) {
            Icon(Icons.Default.Edit, contentDescription = "Rename $tag")
        }
        IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.testTag(AdminSettingsTags.tagDelete(tag))) {
            Icon(Icons.Default.Delete, contentDescription = "Delete $tag")
        }
    }
}

/** The new name, trimmed; the server refuses one already listed. */
@Composable
private fun RenameTagDialog(from: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(from) { mutableStateOf(from) }
    val to = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename tag") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.replace('\n', ' ').take(NEW_TAG_MAX) }, // as long as one typed on a point
                label = { Text("Tag") },
                supportingText = { Text("Every point that has it is renamed too.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(AdminSettingsTags.TAG_NAME),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(to) }, enabled = to.isNotEmpty() && to != from, modifier = Modifier.testTag(AdminSettingsTags.TAG_SAVE)) {
                Text("Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MapField(value: String, label: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
