package com.fieldmaxx.app.jobs

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.app.ui.fmxBarColors
import com.fieldmaxx.core.model.JobListItem
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The server's own wording for any other file (HTTP 415). */
const val ONLY_JOB_FILES = "Only PDF and Word (.docx) files"
const val FILE_TOO_LARGE = "File is too large (max 50 MB)"
const val MAX_JOB_FILE_BYTES = 50L * 1024 * 1024
private const val CANT_OPEN = "Couldn't open this file. Share or pick it again."

/** What the file picker offers: PDF and Word (.docx). */
val JOB_FILE_TYPES = arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")

/** What another app shared with Fieldmaxx: job files, or a Field Notes link. */
sealed interface SharedIntake {
    data class Files(val uris: List<Uri>) : SharedIntake
    data class Link(val url: String) : SharedIntake
}

/**
 * "Share → Fieldmaxx": the files of a SEND / SEND_MULTIPLE, else the OneDrive or SharePoint address in its text
 * (sharepoint.com, 1drv.ms, onedrive.live.com). Null for anything else, an ordinary launch included. Files aren't
 * looked at here: [readJobFile] decides whether each is a job file.
 */
fun parseShareIntent(intent: Intent): SharedIntake? {
    val streams = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> return null
    }
    val files = streams.ifEmpty { intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty() }
    if (files.isNotEmpty()) return SharedIntake.Files(files)
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return null
    return oneDriveLink(text)?.let { SharedIntake.Link(it) }
}

/** The first OneDrive or SharePoint address in [text], without the sentence's closing punctuation. */
fun oneDriveLink(text: String): String? = URL_IN_TEXT.findAll(text)
    .map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}') }
    .firstOrNull { url ->
        val host = url.toHttpUrlOrNull()?.host ?: return@firstOrNull false
        host == "sharepoint.com" || host.endsWith(".sharepoint.com") || host == "1drv.ms" || host == "onedrive.live.com"
    }

private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""")

/** A job file read from the phone, named with the extension the server reads it by. */
class JobFile(val name: String, val bytes: ByteArray)

/**
 * Reads a picked or shared file for import. Its type comes from its name's extension, else from its first bytes:
 * `%PDF` is a PDF, a ZIP holding `word/document.xml` a Word file (and gets that extension, so the server reads it).
 * Anything else, or more than 50 MB, throws [IllegalArgumentException] with the server's wording; a file that can't
 * be read throws [IOException] or [SecurityException].
 */
fun readJobFile(resolver: ContentResolver, uri: Uri): JobFile {
    val info = fileInfo(resolver, uri)
    if (info.size != null && info.size > MAX_JOB_FILE_BYTES) throw IllegalArgumentException(FILE_TOO_LARGE)
    val kind = jobFileKind(info.name) { open(resolver, uri) } ?: throw IllegalArgumentException(ONLY_JOB_FILES)
    val bytes = open(resolver, uri).use(::readCapped)
    val name = if (info.name.substringAfterLast('.', "").equals(kind, ignoreCase = true)) info.name else "${info.name}.$kind"
    return JobFile(name, bytes)
}

/** Why [uri] can't be imported, or null when it is a PDF or a Word file. Reads no more of it than it must. */
internal fun refusalOf(resolver: ContentResolver, uri: Uri): String? = try {
    val info = fileInfo(resolver, uri)
    when {
        info.size != null && info.size > MAX_JOB_FILE_BYTES -> FILE_TOO_LARGE
        jobFileKind(info.name) { open(resolver, uri) } == null -> ONLY_JOB_FILES
        else -> null
    }
} catch (e: IOException) {
    CANT_OPEN
} catch (e: SecurityException) {
    CANT_OPEN
}

internal class FileInfo(val name: String, val size: Long?)

/** The name and size the sharing app reports, else the last part of the address. */
internal fun fileInfo(resolver: ContentResolver, uri: Uri): FileInfo {
    var name: String? = null
    var size: Long? = null
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
        }
    }
    val fallback = uri.lastPathSegment?.substringAfterLast('/')
    return FileInfo(name?.trim()?.ifEmpty { null } ?: fallback?.ifEmpty { null } ?: "file", size)
}

/** "pdf" or "docx" by [name]'s extension, else by the content [open] gives; null for anything else. */
internal fun jobFileKind(name: String, open: () -> InputStream): String? {
    when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> return "pdf"
        "docx" -> return "docx"
    }
    val head = open().use { readHead(it, 4) }
    if (head.size == 4 && String(head, Charsets.ISO_8859_1) == "%PDF") return "pdf"
    if (head.contentEquals(ZIP_MAGIC) && holdsWordDocument(open)) return "docx"
    return null
}

private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

private fun holdsWordDocument(open: () -> InputStream): Boolean = try {
    open().use { input ->
        ZipInputStream(input).use { zip -> generateSequence { zip.nextEntry }.any { it.name == "word/document.xml" } }
    }
} catch (e: ZipException) {
    false
}

private fun open(resolver: ContentResolver, uri: Uri): InputStream =
    resolver.openInputStream(uri) ?: throw FileNotFoundException("Nothing to read at $uri")

private fun readHead(input: InputStream, n: Int): ByteArray {
    val out = ByteArray(n)
    var got = 0
    while (got < n) {
        val r = input.read(out, got, n - got)
        if (r < 0) break
        got += r
    }
    return out.copyOf(got)
}

private fun readCapped(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return out.toByteArray()
        total += n
        if (total > MAX_JOB_FILE_BYTES) throw IllegalArgumentException(FILE_TOO_LARGE)
        out.write(buffer, 0, n)
    }
}

/** A shared file and, when it can't be imported, why. */
private class SharedFile(val uri: Uri, val name: String, val refusal: String?)

/**
 * "New job / Add to job" for what was shared. Each shared file is checked at once, so one the app can't take is refused
 * here with the server's wording. "Add to job" lists the active jobs; for a link it opens that job's info, where the link
 * is checked and saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareIntakeScreen(
    intake: SharedIntake,
    jobs: JobList,
    onNewJob: (files: List<Uri>, link: String?) -> Unit,
    onAddToJob: (jobId: String, files: List<Uri>, link: String?) -> Unit,
    onClose: () -> Unit,
) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val files by produceState<List<SharedFile>?>(null, intake) {
        val uris = (intake as? SharedIntake.Files)?.uris.orEmpty()
        value = withContext(Dispatchers.IO) {
            uris.map { uri -> SharedFile(uri, runCatching { fileInfo(resolver, uri).name }.getOrDefault("file"), refusalOf(resolver, uri)) }
        }
    }
    val link = (intake as? SharedIntake.Link)?.url
    val accepted = files.orEmpty().filter { it.refusal == null }.map { it.uri }
    val canGoOn = link != null || accepted.isNotEmpty()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var active by remember { mutableStateOf<List<JobListItem>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(choosing) {
        if (!choosing) return@LaunchedEffect
        active = jobs.cached()?.filter { it.job.status != FINISHED }
        try {
            active = jobs.fetch().filter { it.job.status != FINISHED }
            problem = null
        } catch (e: IOException) {
            problem = if (active == null) OFFLINE_EMPTY else OFFLINE_CACHED
        } catch (e: ApiException) {
            problem = e.message
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shared with Fieldmaxx") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") } },
                colors = fmxBarColors(),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (link != null) {
                item {
                    Column {
                        Text("Field Notes link", style = MaterialTheme.typography.titleMedium)
                        Text(link, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(files.orEmpty(), key = { it.uri.toString() }) { f ->
                if (f.refusal == null) Text(f.name) else Text("${f.name}: ${f.refusal}", color = MaterialTheme.colorScheme.error)
            }
            if (canGoOn) {
                item { Button(onClick = { onNewJob(accepted, link) }, modifier = Modifier.fillMaxWidth()) { Text("New job") } }
                item { OutlinedButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) { Text("Add to job") } }
            }
            if (canGoOn && choosing) {
                item {
                    Text(
                        if (link != null) "Which job is this link for?" else "Add the files to:",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                problem?.let { item { Text(it, color = FmxColors.Warning) } }
                items(active.orEmpty(), key = { it.job.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.job.name) },
                        supportingContent = { Text(progressText(item)) },
                        modifier = Modifier.clickable { onAddToJob(item.job.id, accepted, link) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
