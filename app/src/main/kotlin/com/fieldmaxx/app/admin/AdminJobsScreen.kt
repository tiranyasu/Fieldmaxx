package com.fieldmaxx.app.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.jobs.ExportDialog
import com.fieldmaxx.app.jobs.ExportKind
import com.fieldmaxx.app.jobs.progressText
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.JobListItem
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A deleted job can be restored this long (spec §6.2); the server purges it after that. */
private const val RESTORE_WINDOW_MS = 30L * 24 * 3_600_000

object AdminJobsTags {
    fun row(jobId: String) = "adminJobs.row:$jobId"
    fun restore(jobId: String) = "adminJobs.restore:$jobId"
    fun crew(jobId: String) = "adminJobs.crew:$jobId"
    fun open(jobId: String) = "adminJobs.open:$jobId"
    fun activity(jobId: String) = "adminJobs.activity:$jobId"
    fun photos(jobId: String) = "adminJobs.photos:$jobId"
}

/** "Created 10/29/26 · Finished 11/09/26". */
private fun datesText(job: Job, zone: ZoneId) =
    listOfNotNull("Created ${dayText(job.createdAt, zone)}", job.finishedAt?.let { "Finished ${dayText(it, zone)}" }).joinToString(" · ")

/**
 * Admin → Jobs (spec §6.9): every job, active, finished and deleted, with its progress and its created and finished
 * dates. Tapping a job shows its crew and leads to its map, its activity (where its deleted points are restored, R68)
 * and the server's ZIP of its photos. A deleted job can be restored for 30 days.
 */
@Composable
fun AdminJobsScreen(
    api: Api,
    onOpenJob: (jobId: String) -> Unit,
    onActivity: (jobId: String, jobName: String) -> Unit,
    onBack: () -> Unit,
    now: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var jobs by remember { mutableStateOf<List<JobListItem>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var zipFor by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        withContext(Dispatchers.Main.immediate) {
            try {
                jobs = api.jobs(include = "finished,deleted")
                problem = null
            } catch (e: IOException) {
                problem = ADMIN_NEEDS_SIGNAL
            } catch (e: ApiException) {
                problem = e.message
            }
        }
    }

    fun restore(job: Job) {
        scope.launch(Dispatchers.Main.immediate) {
            try {
                api.restoreJob(job.id)
                note = "${job.name} restored"
                problem = null
            } catch (e: IOException) {
                problem = ADMIN_NEEDS_SIGNAL
            } catch (e: ApiException) {
                problem = e.message // past the 30 days, say
            }
            reload++
        }
    }

    val liveRow: @Composable (JobListItem) -> Unit = { item ->
        val job = item.job
        JobRow(item, zone, expanded = open == job.id, onToggle = { open = if (open == job.id) null else job.id }) {
            JobDetails(job, api, onOpen = { onOpenJob(job.id) }, onActivity = { onActivity(job.id, job.name) }, onPhotos = { zipFor = job })
        }
    }
    AdminScaffold("Jobs", onBack, onRefresh = { reload++ }) { padding ->
        val all = jobs.orEmpty()
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            problem?.let { item(key = "problem") { AdminNote(it) } }
            note?.let { item(key = "note") { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) } }
            if (jobs != null && all.isEmpty()) item(key = "empty") { Text("No jobs yet.", modifier = Modifier.padding(16.dp)) }
            val (gone, live) = all.partition { it.job.deletedAt != null }
            section("Active", live.filter { it.job.status != "finished" }, liveRow)
            section("Finished", live.filter { it.job.status == "finished" }, liveRow)
            section("Deleted", gone) { item -> DeletedJobRow(item.job, now(), zone, onRestore = { restore(item.job) }) }
        }
    }

    zipFor?.let { job -> ExportDialog(job.id, job.name, listOf(ExportKind.PHOTOS_ZIP), api, onDismiss = { zipFor = null }) }
}

private fun LazyListScope.section(title: String, jobs: List<JobListItem>, row: @Composable (JobListItem) -> Unit) {
    if (jobs.isEmpty()) return
    item(key = "section:$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        )
    }
    items(jobs, key = { "job:" + it.job.id }) { row(it) }
}

@Composable
private fun JobRow(item: JobListItem, zone: ZoneId, expanded: Boolean, onToggle: () -> Unit, details: @Composable () -> Unit) {
    ListItem(
        headlineContent = { Text(item.job.name) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                Text(progressText(item))
                LinearProgressIndicator(
                    progress = { if (item.pointsTotal == 0) 0f else item.pointsDone.toFloat() / item.pointsTotal },
                    color = if (item.pointsTotal > 0 && item.pointsDone >= item.pointsTotal) FmxColors.Done else FmxColors.Accent,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(datesText(item.job, zone), style = MaterialTheme.typography.bodySmall)
            }
        },
        modifier = Modifier.clickable(onClick = onToggle).testTag(AdminJobsTags.row(item.job.id)),
    )
    if (expanded) details()
    HorizontalDivider()
}

/** A job's crew (from its snapshot, read when it is opened here) and where to go from it. */
@Composable
private fun JobDetails(job: Job, api: Api, onOpen: () -> Unit, onActivity: () -> Unit, onPhotos: () -> Unit) {
    var crew by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(job.id) {
        withContext(Dispatchers.Main.immediate) {
            crew = try {
                api.snapshot(job.id).crew.joinToString(", ") { it.displayName }.ifEmpty { null }?.let { "Crew: $it" } ?: "No one has worked it yet"
            } catch (e: IOException) {
                "The crew shows with signal"
            } catch (e: ApiException) {
                e.message
            }
        }
    }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(crew ?: "Reading the crew…", modifier = Modifier.testTag(AdminJobsTags.crew(job.id)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpen, modifier = Modifier.testTag(AdminJobsTags.open(job.id))) { Text("Map") }
            OutlinedButton(onClick = onActivity, modifier = Modifier.testTag(AdminJobsTags.activity(job.id))) { Text("Activity") }
            OutlinedButton(onClick = onPhotos, modifier = Modifier.testTag(AdminJobsTags.photos(job.id))) { Text("Photos ZIP") }
        }
    }
}

@Composable
private fun DeletedJobRow(job: Job, now: Long, zone: ZoneId, onRestore: () -> Unit) {
    val deletedAt = job.deletedAt ?: return
    val until = deletedAt + RESTORE_WINDOW_MS
    ListItem(
        headlineContent = { Text(job.name) },
        supportingContent = {
            Text(
                if (now <= until) "Deleted ${dayText(deletedAt, zone)} · restore until ${dayText(until, zone)}"
                else "Deleted ${dayText(deletedAt, zone)} · past the 30 days, it is being purged",
            )
        },
        trailingContent = if (now <= until) ({ TextButton(onClick = onRestore, modifier = Modifier.testTag(AdminJobsTags.restore(job.id))) { Text("Restore") } }) else null,
    )
    HorizontalDivider()
}
