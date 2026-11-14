package com.fieldmaxx.app.jobs

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.sync.SyncEngine
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.app.ui.fmxBarColors
import com.fieldmaxx.core.model.JobFileInfo
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NO_SIGNAL_INFO = "Can't reach the server. Job info shows once the job has been opened with signal."
private const val NO_SIGNAL_FILE = "Can't reach the server. Opening a file the first time needs signal."
private const val NO_VIEWER = "No app on this phone can open this file."

object JobInfoTags {
    const val SAVE_LINK = "jobInfo.saveLink"
    fun file(fileId: String) = "jobInfo.file:$fileId"
}

/**
 * Spec §6.2: the job's notes, its source files (they open in the phone's viewer), its Field Notes link (checked and
 * saved here, or added later when the job was saved without one) and the crew who has worked it. It shows what the
 * phone has kept and refreshes it from the server. [sharedLink], from "Share → Fieldmaxx", starts the link field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobInfoScreen(jobId: String, api: Api, sync: SyncEngine, sharedLink: String?, onBack: () -> Unit) {
    val job by remember(jobId) { sync.job(jobId) }.collectAsState(null)
    val extras by remember(jobId) { sync.extras(jobId) }.collectAsState(null)
    var offline by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<String?>(null) }
    var typedLink by rememberSaveable { mutableStateOf(sharedLink) } // null: the job's own link
    var linkNote by remember { mutableStateOf<String?>(null) }
    var savingLink by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<String?>(null) }
    var fileProblem by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(jobId) {
        try {
            sync.openJob(jobId)
        } catch (e: IOException) {
            offline = true // what the phone kept still shows
        } catch (e: ApiException) {
            refused = e.message // gone, say, or no longer readable
        }
    }
    val savedLink = job?.fieldNotesUrl.orEmpty()
    val link = typedLink ?: savedLink

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(job?.name ?: "Job info") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = fmxBarColors(),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            refused?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (offline && extras == null) Text(NO_SIGNAL_INFO, color = FmxColors.Warning)

            Section("Notes") {
                Text(job?.notes?.ifBlank { null } ?: "No notes", color = if (job?.notes.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
            }

            Section("Files") {
                val files = extras?.files.orEmpty()
                if (extras != null && files.isEmpty()) Text("No files", color = MaterialTheme.colorScheme.onSurfaceVariant)
                files.forEach { f ->
                    ListItem(
                        headlineContent = { Text(f.name) },
                        supportingContent = if (f.layer.isNotBlank()) ({ Text(f.layer) }) else null,
                        trailingContent = if (opening == f.id) ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }) else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.testTag(JobInfoTags.file(f.id)).clickable(enabled = opening == null) {
                            opening = f.id
                            fileProblem = null
                            scope.launch {
                                fileProblem = try {
                                    openJobFile(context, api, jobId, f)
                                    null
                                } catch (e: IOException) {
                                    NO_SIGNAL_FILE
                                } catch (e: ApiException) {
                                    e.message
                                } catch (e: ActivityNotFoundException) {
                                    NO_VIEWER
                                } finally {
                                    opening = null
                                }
                            }
                        },
                    )
                }
                fileProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            Section("Field Notes link") {
                FieldNotesLinkField(
                    link,
                    {
                        typedLink = it
                        linkNote = null
                    },
                    api,
                )
                if (link.isBlank() && savedLink.isBlank()) {
                    Text("Photos wait until the Field Notes link is added.", color = FmxColors.Warning)
                }
                Button(
                    onClick = {
                        savingLink = true
                        scope.launch {
                            linkNote = try {
                                api.patchJob(jobId, fieldNotesUrl = link.trim())
                                if (link.isBlank()) "Link removed" else "Link saved"
                            } catch (e: IOException) {
                                NO_SIGNAL
                            } catch (e: ApiException) {
                                e.message
                            } finally {
                                savingLink = false
                            }
                        }
                    },
                    enabled = !savingLink && job != null && link.trim() != savedLink,
                    modifier = Modifier.testTag(JobInfoTags.SAVE_LINK),
                ) { Text("Save link") }
                linkNote?.let { Text(it) }
            }

            Section("Crew") {
                val crew = extras?.crew.orEmpty()
                if (extras != null && crew.isEmpty()) Text("No one has worked it yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                crew.forEach { Text(it.displayName) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

/**
 * Downloads the job's source file once into `cacheDir/shared/` (what the FileProvider serves) and opens it in the
 * phone's viewer. A file already downloaded opens without signal.
 */
internal suspend fun openJobFile(context: Context, api: Api, jobId: String, file: JobFileInfo) {
    val target = File(context.cacheDir, "shared/job-files/${safeName(file.id)}/${safeName(file.name)}")
    if (!withContext(Dispatchers.IO) { target.isFile }) {
        api.download("/api/jobs/${Uri.encode(jobId)}/files/${Uri.encode(file.id)}", target)
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, typeOf(target.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(view)
}

private fun typeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> JOB_FILE_TYPES[0]
    "docx" -> JOB_FILE_TYPES[1]
    else -> "application/octet-stream"
}

/** One path segment: no separators, never `.` or `..`. */
private fun safeName(name: String): String = name.replace(Regex("""[/\\:\u0000]"""), "_").trim().let { if (it.isEmpty() || it == "." || it == "..") "file" else it }
