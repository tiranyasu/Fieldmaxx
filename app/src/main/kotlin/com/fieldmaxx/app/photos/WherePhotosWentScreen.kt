package com.fieldmaxx.app.photos

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.jobs.PHOTOS_WILL_WAIT
import com.fieldmaxx.app.point.folderLineText
import com.fieldmaxx.app.point.plannedFolder
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.app.ui.fmxBarColors
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

private const val NO_LINK_APP = "No app on this phone can open the link."

object WhereTags {
    const val LIST = "where.list"
    const val SUMMARY = "where.summary"
    const val OPEN_FIELD_NOTES = "where.openFieldNotes"
    fun point(id: String) = "where.point:$id"
    fun photo(id: String) = "where.photo:$id"
}

/**
 * Where a photo is inside the job's Field Notes folder once in OneDrive (`Field Notes/POLE/POLE_18/POLE_18_…_7.jpg`), or
 * where it goes (`→ Field Notes/…`): its point's folder, or the one that would be made (§6.6.1), and the name the server
 * gave it, if any yet.
 */
internal fun photoPlace(point: Point, p: PhotoView): String {
    val folder = point.folderPath ?: plannedFolder(point)
    return when {
        p.status == PhotoUiStatus.IN_ONEDRIVE -> "Field Notes/" + (p.destPath ?: "$folder/${p.fileName.orEmpty()}")
        p.fileName != null -> "→ Field Notes/$folder/${p.fileName}"
        else -> "→ Field Notes/$folder/ (named once the server has it)"
    }
}

/** "3 photos not in OneDrive yet"; [any]: the job has photos at all. */
internal fun pendingText(pending: Int, any: Boolean): String = when {
    !any -> "No photos in this job yet"
    pending == 0 -> "All photos are in OneDrive"
    pending == 1 -> "1 photo not in OneDrive yet"
    else -> "$pending photos not in OneDrive yet"
}

/**
 * Menu → "Where the photos went" (spec §6.6.2): per point, each photo's OneDrive path and status, and a button that opens
 * the job's Field Notes folder through its link (R65), or, while the job has none, the check screen's sentence that the
 * photos wait for it. [photos] is [jobPhotoViewsFor]; points come in the job's order, deleted ones left out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WherePhotosWentScreen(job: Flow<Job?>, points: Flow<List<Point>>, photos: Flow<JobPhotoViews>, onBack: () -> Unit) {
    // Main-confined, as on the other screens: the state is written on the main thread wherever a value comes from.
    val main = Dispatchers.Main.immediate
    val stored by job.collectAsState(null, main)
    val all by points.collectAsState(emptyList(), main)
    val views by photos.collectAsState(null, main)
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    val loaded = views
    val shown = if (loaded == null) emptyList() else all.filter { it.id in loaded.byPoint }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Where the photos went") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = fmxBarColors(),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag(WhereTags.LIST),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val text = if (loaded == null) "Loading…" else pendingText(shown.sumOf { loaded.pending[it.id] ?: 0 }, shown.isNotEmpty())
                Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(WhereTags.SUMMARY))
            }
            item {
                val link = stored?.fieldNotesUrl?.trim().orEmpty()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (link.isNotEmpty()) {
                        Button(onClick = { problem = openLink(context, link) }, modifier = Modifier.testTag(WhereTags.OPEN_FIELD_NOTES)) {
                            Text("Open the Field Notes folder")
                        }
                    } else if (stored != null) {
                        Text(PHOTOS_WILL_WAIT, color = FmxColors.Warning)
                    }
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (loaded != null) {
                for (point in shown) {
                    item(key = "point:${point.id}") { PointHeading(point, loaded.pending[point.id] ?: 0) }
                    items(loaded.byPoint.getValue(point.id), key = { "photo:${it.id}" }) { p -> PhotoLine(point, p) }
                }
            }
        }
    }
}

@Composable
private fun PointHeading(point: Point, pending: Int) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag(WhereTags.point(point.id))) {
        Text(point.label, style = MaterialTheme.typography.titleSmall)
        Text(folderLineText(point.folderPath ?: plannedFolder(point)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (pending > 0) Text(if (pending == 1) "1 not in OneDrive yet" else "$pending not in OneDrive yet", style = MaterialTheme.typography.bodySmall, color = FmxColors.Warning)
    }
}

@Composable
private fun PhotoLine(point: Point, p: PhotoView) {
    Row(Modifier.fillMaxWidth().testTag(WhereTags.photo(p.id)), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(p.status.glyph())
        Column {
            val status = if (p.status == PhotoUiStatus.STUCK) "Stuck: ${p.error ?: "no reason was recorded"}" else p.status.words()
            Text(status, color = if (p.status == PhotoUiStatus.STUCK) FmxColors.Warning else MaterialTheme.colorScheme.onSurface)
            Text(photoPlace(point, p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The Field Notes folder in the phone's browser or OneDrive app; what to tell the tech when no app can open it. */
private fun openLink(context: Context, link: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
    null
} catch (e: ActivityNotFoundException) {
    NO_LINK_APP
}
