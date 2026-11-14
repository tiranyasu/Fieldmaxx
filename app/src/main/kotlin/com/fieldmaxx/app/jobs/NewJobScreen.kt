package com.fieldmaxx.app.jobs

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.ui.fmxBarColors
import com.fieldmaxx.core.model.CreateJobInput
import com.fieldmaxx.core.model.ImportPreview
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NO_SIGNAL_IMPORT = "No signal. Reading a job file needs signal: the server reads it."
private const val NO_SIGNAL_SAVE = "Can't reach the server. Check your signal and try again."
private const val CANT_READ_FILE = "Couldn't open this file on the phone. Pick or share it again."

/** A file that couldn't be read, and why. "Try again" reads it again from [uri]. */
class FailedFile(val uri: Uri, val name: String, val reason: String)

/**
 * A new job (or more files for [addToJobId]): each picked or shared file is read by the server, the tech checks the
 * result ([state], the check screen) and saves. It lives as long as its screen, so turning the phone keeps the work.
 */
class NewJobModel(
    private val api: Api,
    private val resolver: ContentResolver,
    private val openJob: suspend (jobId: String) -> Unit,
    val addToJobId: String?,
    sharedLink: String?,
) : ViewModel() {
    var state by mutableStateOf<CheckState?>(null)
        private set
    var failed by mutableStateOf<List<FailedFile>>(emptyList())
        private set

    /** The file being read, while files are read. */
    var reading by mutableStateOf<String?>(null)
        private set
    var name by mutableStateOf("")
        private set
    var link by mutableStateOf(sharedLink.orEmpty())
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The job saved to, once saving is done. */
    var savedJobId by mutableStateOf<String?>(null)
        private set

    /** Files were picked or taken from the share; a rotation doesn't ask again. */
    var started = false
    private var nameEdited = false

    val isNew: Boolean get() = addToJobId == null
    val saveEnabled: Boolean
        get() = state?.let(::canSave) == true && (!isNew || name.isNotBlank()) && !saving && reading == null

    fun rename(text: String) {
        name = text
        nameEdited = true
    }

    /** A change from the check screen, applied to the state as it is now. Whatever [change] throws leaves it as it was. */
    fun update(change: (CheckState) -> CheckState) {
        state = state?.let(change)
    }

    /** Reads each file on the server in turn; a file that fails is listed with the reason and doesn't stop the rest. */
    fun read(uris: List<Uri>) {
        if (uris.isEmpty() || reading != null) return
        reading = ""
        viewModelScope.launch {
            val got = ArrayList<ImportPreview>()
            val bad = ArrayList<FailedFile>()
            for (uri in uris) {
                val shown = withContext(Dispatchers.IO) { runCatching { fileInfo(resolver, uri).name }.getOrDefault("file") }
                reading = shown
                val file = try {
                    withContext(Dispatchers.IO) { readJobFile(resolver, uri) }
                } catch (e: IllegalArgumentException) { // not a PDF or a Word file, or too large
                    bad += FailedFile(uri, shown, e.message ?: ONLY_JOB_FILES)
                    continue
                } catch (e: IOException) {
                    bad += FailedFile(uri, shown, CANT_READ_FILE)
                    continue
                } catch (e: SecurityException) { // the sharing app took its permission back
                    bad += FailedFile(uri, shown, CANT_READ_FILE)
                    continue
                }
                try {
                    got += api.importFile(file.name, file.bytes)
                } catch (e: ApiException) {
                    bad += FailedFile(uri, file.name, e.message)
                } catch (e: IOException) {
                    bad += FailedFile(uri, file.name, NO_SIGNAL_IMPORT)
                }
            }
            val before = state
            state = when {
                got.isEmpty() -> before
                before == null -> checkStateOf(got)
                else -> before.withPreviews(got)
            }
            if (!nameEdited && name.isBlank()) state?.previews?.firstOrNull()?.let { name = jobNameFor(it.fileName) }
            failed = failed.filter { f -> uris.none { it == f.uri } } + bad
            reading = null
        }
    }

    fun retryFailed() = read(failed.map { it.uri })

    /**
     * Creates the job, or adds each file to [addToJobId]; a file added is dropped from the screen, so trying again
     * after a failure sends only the rest. Then the job is opened on the phone (failures there don't matter: the map
     * opens it again) and [savedJobId] is set.
     */
    fun save() {
        val s = state ?: return
        if (!saveEnabled) return
        saving = true
        error = null
        viewModelScope.launch {
            try {
                val jobId = addToJobId?.also { id ->
                    for (imp in toJobImports(s)) {
                        api.addImport(id, imp)
                        update { it.without(imp.importId) }
                    }
                } ?: api.createJob(CreateJobInput(name.trim(), link.trim().ifEmpty { null }, toJobImports(s))).id
                try {
                    openJob(jobId)
                } catch (e: IOException) {
                    // The map opens it again; what was saved is on the server.
                } catch (e: ApiException) {
                    // As above.
                }
                savedJobId = jobId
            } catch (e: ApiException) {
                error = e.message
            } catch (e: IOException) {
                error = NO_SIGNAL_SAVE
            } finally {
                saving = false
            }
        }
    }
}

/**
 * "+ New job" (or "Add files" for [addToJobId]): Android's file picker for PDF and Word files, unless files came from
 * a share; then each file is read by the server and the check screen follows. [onDone] gets the job once it is saved
 * and opened.
 */
@Composable
fun NewJobScreen(
    api: Api,
    openJob: suspend (jobId: String) -> Unit,
    addToJobId: String?,
    sharedFiles: List<Uri>,
    sharedLink: String?,
    onDone: (jobId: String) -> Unit,
    onBack: () -> Unit,
) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val model = viewModel { NewJobModel(api, resolver, openJob, addToJobId, sharedLink) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty() && model.state == null && model.failed.isEmpty()) onBack() else model.read(uris)
    }
    val pick = {
        try {
            picker.launch(JOB_FILE_TYPES)
        } catch (e: ActivityNotFoundException) {
            // No document picker on this phone: the screen's "Pick" button stays, and shares still work.
        }
    }
    LaunchedEffect(model) {
        if (model.started) return@LaunchedEffect
        model.started = true
        if (sharedFiles.isNotEmpty()) model.read(sharedFiles) else pick()
    }
    val saved = model.savedJobId
    LaunchedEffect(saved) { if (saved != null) onDone(saved) }

    if (model.state != null) {
        CheckScreen(model, api, onBack)
    } else {
        Reading(model, onPick = pick, onBack = onBack)
    }
}

/** Before the check screen: the file being read, or the files that couldn't be. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Reading(model: NewJobModel, onPick: () -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (model.isNew) "New job" else "Add files") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = fmxBarColors(),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val reading = model.reading
            when {
                reading != null -> {
                    CircularProgressIndicator()
                    Text(if (reading.isEmpty()) "Reading…" else "Reading $reading…")
                    Text("The server reads the files, so this needs signal.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                model.failed.isNotEmpty() -> {
                    FailedFiles(model.failed)
                    Button(onClick = model::retryFailed, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                    OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Pick other files") }
                }
                else -> OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Pick PDF or Word files") }
            }
        }
    }
}

/** Every file that couldn't be read, with the reason: none is dropped without a word. */
@Composable
internal fun FailedFiles(failed: List<FailedFile>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Couldn't read", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        failed.forEach { Text("${it.name}: ${it.reason}") }
    }
}
