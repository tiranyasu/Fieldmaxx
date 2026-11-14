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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
}

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
 */
@Composable
fun AdminSettingsScreen(api: Api, kept: suspend () -> AppConfig, onSaved: () -> Unit, onBack: () -> Unit) {
    var base by remember { mutableStateOf<Form?>(null) }
    var form by remember { mutableStateOf<Form?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Main.immediate) {
            val local = Form(kept())
            base = local
            form = local
            try {
                val latest = Form(api.config())
                if (form == base) form = latest // not edited meanwhile
                base = latest
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
        }
    }
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
