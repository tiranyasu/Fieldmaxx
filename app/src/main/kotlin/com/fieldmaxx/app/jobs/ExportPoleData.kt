package com.fieldmaxx.app.jobs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.photos.UNSAFE
import com.fieldmaxx.app.ui.FmxColors
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val EXPORT_NEEDS_SIGNAL = "Exports need signal: the server makes them. Try again once you have signal."
private const val SAVE_FAILED = "Couldn't save it to Downloads. Share it instead."
private const val NO_SHARE_APP = "No app on this phone can take the file."

/** What the server exports for a job (`/api/jobs/{id}/…`, exports/ExportRoutes.kt), and how the phone names it. */
enum class ExportKind(val label: String, internal val route: String, internal val suffix: String, internal val mime: String) {
    POLE_XLSX("Excel (.xlsx)", "pole-data.xlsx", "_pole_data.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    POLE_CSV("CSV (.csv)", "pole-data.csv", "_pole_data.csv", "text/csv"),

    /** The server's backed-up photos laid out like Field Notes (spec §6.6, admins only). */
    PHOTOS_ZIP("The server's photos (.zip)", "photos.zip", "_server_photos.zip", "application/zip"),
}

/** Menu → Pole data export (spec §6.10): the tech picks the format. */
val POLE_DATA_EXPORTS = listOf(ExportKind.POLE_XLSX, ExportKind.POLE_CSV)

object ExportTags {
    const val SAVE = "export.save"
    const val SHARE = "export.share"
    const val STATUS = "export.status"
    fun choice(kind: ExportKind) = "export.choice:${kind.name}"
}

/**
 * Downloads the job's [kind] from the server into `cacheDir/shared/exports/` (what the FileProvider serves, R70), named
 * after the job: `A05J9XE_pole_data.xlsx`. A new export replaces the last one of that name. Throws [IOException]
 * without signal and [ApiException] when the server refuses.
 */
suspend fun downloadExport(context: Context, api: Api, jobId: String, jobName: String, kind: ExportKind): File {
    val base = jobName.replace(UNSAFE, "_").trim().ifEmpty { "job" }
    val target = File(context.cacheDir, "shared/exports/$base${kind.suffix}")
    api.download("/api/jobs/${Uri.encode(jobId)}/${kind.route}", target)
    return target
}

/** Android 10+ saves into Downloads through MediaStore with no permission; Android 9 only shares (R70). */
internal fun canSaveToDownloads(sdk: Int) = sdk >= Build.VERSION_CODES.Q

/**
 * Copies [file] into the phone's Downloads (MediaStore, Android 10+ only: see [canSaveToDownloads]); MediaStore picks
 * another name if that one is taken. False when it couldn't; nothing half-written is left there.
 */
@SuppressLint("NewApi", "InlinedApi") // MediaStore.Downloads and IS_PENDING: called on Android 10+ only (canSaveToDownloads)
fun saveToDownloads(resolver: ContentResolver, file: File, mime: String): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        val out = resolver.openOutputStream(uri) ?: throw IOException("Downloads gave no file to write")
        out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        true
    } catch (e: IOException) {
        resolver.delete(uri, null, null)
        false
    } catch (e: SecurityException) {
        resolver.delete(uri, null, null)
        false
    }
}

/** Android's share sheet with [file], through the app's FileProvider; the app picked may read it. */
fun shareFile(context: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri) // the read grant reaches whichever app is picked
    val chooser = Intent.createChooser(send, title)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

/**
 * An export of the job (spec §6.10, R70): with several [kinds] (pole data) the format is picked first; then the server's
 * file is downloaded and offered as "Save to Downloads" (Android 10+) or "Share". Exports need signal and say so.
 */
@Composable
fun ExportDialog(jobId: String, jobName: String, kinds: List<ExportKind>, api: Api, onDismiss: () -> Unit, sdk: Int = Build.VERSION.SDK_INT) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(kinds.singleOrNull()) }
    var attempt by remember { mutableIntStateOf(0) }
    var file by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(kind != null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(kind, attempt) {
        val k = kind ?: return@LaunchedEffect
        withContext(Dispatchers.Main.immediate) {
            busy = true
            problem = null
            try {
                file = downloadExport(context, api, jobId, jobName, k)
            } catch (e: IOException) {
                problem = EXPORT_NEEDS_SIGNAL
            } catch (e: ApiException) {
                problem = e.message
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kinds == POLE_DATA_EXPORTS) "Pole data export" else kinds.first().label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val chosen = kind
                val ready = file
                when {
                    chosen == null -> {
                        Text("Which format?")
                        kinds.forEach { k ->
                            OutlinedButton(
                                onClick = {
                                    kind = k
                                    busy = true
                                },
                                modifier = Modifier.fillMaxWidth().testTag(ExportTags.choice(k)),
                            ) { Text(k.label) }
                        }
                    }
                    busy -> {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text("Downloading from the server…")
                    }
                    ready != null -> {
                        Text("${ready.name} is ready.")
                        if (canSaveToDownloads(sdk)) {
                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.Main.immediate) {
                                        val saved = withContext(Dispatchers.IO) { saveToDownloads(context.contentResolver, ready, chosen.mime) }
                                        problem = if (saved) null else SAVE_FAILED
                                        done = if (saved) "Saved to Downloads: ${ready.name}" else null
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag(ExportTags.SAVE),
                            ) { Text("Save to Downloads") }
                        }
                        OutlinedButton(
                            onClick = {
                                problem = try {
                                    shareFile(context, ready, chosen.mime, "Share ${ready.name}")
                                    null
                                } catch (e: ActivityNotFoundException) {
                                    NO_SHARE_APP
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag(ExportTags.SHARE),
                        ) { Text("Share") }
                    }
                    else -> OutlinedButton(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }
                val note = problem ?: done
                if (note != null) {
                    Text(
                        note,
                        color = if (problem != null) FmxColors.Warning else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag(ExportTags.STATUS),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
