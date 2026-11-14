package com.fieldmaxx.app.menu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.fieldmaxx.app.photos.PhotoSettings
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.app.ui.fmxBarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The one-line reason the gallery copy stays off on Android 9 (Task 28's carry). */
const val GALLERY_STORAGE_REFUSED = "Storage access was refused: without it, Android 9 can't copy photos to the gallery."

object SettingsTags {
    const val GALLERY = "settings.gallery"
    const val GALLERY_NOTE = "settings.gallery.note"
}

/**
 * Menu → Settings: this phone's own settings. "Copy photos to the phone's gallery" (spec §6.6 step 2, R60, on by
 * default) puts each stamped photo in the album Fieldmaxx as an extra backup. From Android 10 that needs no permission.
 * Android 9 needs storage access ([sdk] ≤ 28): switching it on asks for it, and refused, it stays off with the reason.
 * Without that access the switch shows off whatever is stored, since Android 9 copies nothing then.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: PhotoSettings, onBack: () -> Unit, sdk: Int = Build.VERSION.SDK_INT) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stored by settings.galleryCopy.collectAsState(null, Dispatchers.Main.immediate) // null until read
    val needsStorage = sdk <= Build.VERSION_CODES.P
    var storageOk by remember { mutableStateOf(!needsStorage || hasStorage(context)) }
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        storageOk = granted || hasStorage(context)
        refused = !storageOk
        if (storageOk) scope.launch { settings.setGalleryCopy(true) }
    }
    LifecycleResumeEffect(context) {
        storageOk = !needsStorage || hasStorage(context) // allowed or taken away in the system settings meanwhile
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = fmxBarColors(),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                headlineContent = { Text("Copy photos to the phone's gallery") },
                supportingContent = { Text("Each stamped photo also goes to the album Fieldmaxx, as an extra backup.") },
                trailingContent = {
                    Switch(
                        checked = stored == true && storageOk,
                        enabled = stored != null,
                        onCheckedChange = { on ->
                            when {
                                !on -> {
                                    refused = false
                                    scope.launch { settings.setGalleryCopy(false) }
                                }
                                storageOk -> scope.launch { settings.setGalleryCopy(true) }
                                else -> ask.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        },
                        modifier = Modifier.testTag(SettingsTags.GALLERY),
                    )
                },
            )
            if (refused && !storageOk) {
                Text(
                    GALLERY_STORAGE_REFUSED,
                    color = FmxColors.Warning,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp).testTag(SettingsTags.GALLERY_NOTE),
                )
            }
        }
    }
}

private fun hasStorage(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
