package com.fieldmaxx.app.jobs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.MetaEntity
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.sync.SyncStatus
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.app.ui.fmxBarColors
import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.JobListItem
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

internal const val FINISHED = "finished"
internal const val OFFLINE_CACHED = "Offline · showing the list from your last connection"
internal const val OFFLINE_EMPTY = "Offline · the job list needs signal the first time"
private const val NO_SIGNAL_DELETE = "Can't reach the server. Deleting a job needs signal."
private const val DELETE_INFO = "The job disappears for everyone. An admin can restore it for 30 days; after that it is purged, " +
    "and the server's backup copies of its photos are removed. Photos already in OneDrive are untouched."
private const val CACHE_KEY = "jobList:"
private val LIST = ListSerializer(JobListItem.serializer())

/** This phone's photo steps before the server knows the photo (LocalPhotoEntity.state). */
private val NOT_REGISTERED = listOf("saved", "stamped")

object JobListTags {
    const val NEW = "jobs.new"
    const val OFFLINE = "jobs.offline"
    const val DELETE_CONFIRM = "jobs.delete.confirm"
    const val DELETE_WARNING = "jobs.delete.warning"
    fun menu(jobId: String) = "jobs.menu:$jobId"
}

/** What a job holds, for the delete confirmation. [undelivered] of its [photos] haven't reached OneDrive. */
data class JobContents(val points: Int, val photos: Int, val undelivered: Int)

/**
 * The server's jobs as the job screens use them. The last list the server sent is kept on the phone per server
 * address, so the list still shows without signal (R31, spec §6.8).
 */
class JobList(private val api: Api, private val db: AppDatabase, private val serverUrl: () -> String) {
    /** The last list [fetch] got from the current server; null when there is none. */
    suspend fun cached(): List<JobListItem>? {
        val json = db.meta().get(CACHE_KEY + serverUrl()) ?: return null
        return try {
            FmxJson.decodeFromString(LIST, json)
        } catch (e: IllegalArgumentException) { // written by another version of the app: fetch it again
            null
        }
    }

    /** Active and finished jobs, kept for the next time without signal. Throws [IOException] or [ApiException]. */
    suspend fun fetch(): List<JobListItem> {
        val server = serverUrl()
        val items = api.jobs(include = FINISHED)
        db.meta().put(MetaEntity(CACHE_KEY + server, FmxJson.encodeToString(LIST, items)))
        return items
    }

    /**
     * Counted from the server's snapshot and this phone's own photos. Photos not in OneDrive yet are the server's photo
     * infos not confirmed, and this phone's photos of the job the server doesn't know about yet (R33).
     */
    suspend fun contents(jobId: String): JobContents {
        val snapshot = api.snapshot(jobId)
        val photos = snapshot.photos.filter { it.status != PhotoStatus.removed }
        val known = photos.mapTo(HashSet()) { it.id }
        val local = db.localPhotos().inStates(NOT_REGISTERED).filter { it.jobId == jobId && it.id !in known }
        return JobContents(
            points = snapshot.points.count { it.deletedAt == null },
            photos = photos.size + local.size,
            undelivered = photos.count { it.status != PhotoStatus.confirmed } + local.size,
        )
    }

    suspend fun delete(jobId: String) = api.deleteJob(jobId)
}

internal fun progressText(item: JobListItem) = "${item.pointsDone}/${item.pointsTotal} done"

private fun progressOf(item: JobListItem) = if (item.pointsTotal == 0) 0f else item.pointsDone.toFloat() / item.pointsTotal

/** Spec §6.2: the tech who created the job, or an admin. */
private fun mayDelete(user: User?, job: Job) = user != null && (user.role == Role.admin || user.id == job.createdBy)

internal fun contentsText(c: JobContents) = "${count(c.points, "point")} · ${count(c.photos, "photo")}"

internal fun undeliveredWarning(n: Int) =
    if (n == 1) "1 photo hasn't reached OneDrive yet. It will stay undelivered (it remains on the phone that took it)."
    else "$n photos haven't reached OneDrive yet. They will stay undelivered (they remain on the phone that took them)."

private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

/**
 * The jobs with their progress, finished ones in their own section. It shows the kept list at once and refreshes on
 * every start, on pull-to-refresh and when sync comes online; with no signal the kept list stays, with an offline note.
 * Tapping a job opens it. Each job's menu has its info, adding files (active jobs) and, for its creator or an admin,
 * delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobListScreen(
    jobs: JobList,
    status: StateFlow<SyncStatus>,
    currentUser: suspend () -> User?,
    onOpenJob: (jobId: String) -> Unit,
    onNewJob: () -> Unit,
    onAddFiles: (jobId: String) -> Unit,
    onJobInfo: (jobId: String) -> Unit,
) {
    var items by remember { mutableStateOf<List<JobListItem>?>(null) } // null until the kept list or the server's
    var offline by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var me by remember { mutableStateOf<User?>(null) }
    var deleting by remember { mutableStateOf<Job?>(null) }
    val refreshAsked = remember { Channel<Unit>(Channel.CONFLATED) }

    // Main-confined, as on the login screen: the screen's state is written on the main thread wherever a call finishes.
    LaunchedEffect(jobs) {
        withContext(Dispatchers.Main.immediate) {
            me = currentUser()
            if (items == null) items = jobs.cached()
            while (true) {
                refreshAsked.receive()
                refreshing = true
                try {
                    items = jobs.fetch()
                    offline = false
                    problem = null
                } catch (e: IOException) {
                    offline = true
                } catch (e: ApiException) {
                    offline = false
                    problem = e.message
                } finally {
                    refreshing = false
                }
                me = currentUser() // signing in saves the user while the first list loads
            }
        }
    }
    LifecycleStartEffect(refreshAsked) {
        refreshAsked.trySend(Unit)
        onStopOrDispose { }
    }
    LaunchedEffect(status) {
        status.map { it == SyncStatus.ONLINE }.distinctUntilChanged().drop(1).filter { it }.collect { refreshAsked.trySend(Unit) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Jobs") }, colors = fmxBarColors()) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewJob,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New job") },
                modifier = Modifier.testTag(JobListTags.NEW),
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshAsked.trySend(Unit) }, modifier = Modifier.fillMaxSize().padding(padding)) {
            val list = items.orEmpty()
            val active = list.filter { it.job.status != FINISHED }
            val finished = list.filter { it.job.status == FINISHED }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (offline) {
                    item(key = "offline") { Note(if (items == null) OFFLINE_EMPTY else OFFLINE_CACHED, Modifier.testTag(JobListTags.OFFLINE)) }
                }
                problem?.let { item(key = "problem") { Note(it) } }
                if (items != null && list.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No jobs yet. Tap New job to read a job file.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(active, key = { "job:" + it.job.id }) { item ->
                    JobRow(
                        item,
                        onOpen = { onOpenJob(item.job.id) },
                        onInfo = { onJobInfo(item.job.id) },
                        onAddFiles = { onAddFiles(item.job.id) },
                        onDelete = if (mayDelete(me, item.job)) ({ deleting = item.job }) else null,
                    )
                }
                if (finished.isNotEmpty()) {
                    item(key = "finished") {
                        Text(
                            "Finished",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
                        )
                    }
                    items(finished, key = { "job:" + it.job.id }) { item ->
                        JobRow(
                            item,
                            onOpen = { onOpenJob(item.job.id) },
                            onInfo = { onJobInfo(item.job.id) },
                            onAddFiles = null,
                            onDelete = if (mayDelete(me, item.job)) ({ deleting = item.job }) else null,
                        )
                    }
                }
            }
        }
    }

    deleting?.let { job ->
        DeleteJobDialog(
            job, jobs,
            onDismiss = { deleting = null },
            onDeleted = {
                deleting = null
                refreshAsked.trySend(Unit)
            },
        )
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, color = FmxColors.Warning, style = MaterialTheme.typography.bodyMedium, modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun JobRow(item: JobListItem, onOpen: () -> Unit, onInfo: () -> Unit, onAddFiles: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(item.job.name) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                Text(progressText(item))
                LinearProgressIndicator(
                    progress = { progressOf(item) },
                    color = if (item.pointsTotal > 0 && item.pointsDone >= item.pointsTotal) FmxColors.Done else FmxColors.Accent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(JobListTags.menu(item.job.id))) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More for ${item.job.name}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Job info") }, onClick = { menu = false; onInfo() })
                    onAddFiles?.let { add -> DropdownMenuItem(text = { Text("Add files") }, onClick = { menu = false; add() }) }
                    onDelete?.let { delete -> DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; delete() }) }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
    HorizontalDivider()
}

/**
 * Confirmation before a delete (spec §6.2, R33): it names the job, shows what's in it, warns when photos haven't reached
 * OneDrive (they stay undelivered, on the phone that took them), and says what a delete means. It can only be confirmed
 * once the contents are known.
 */
@Composable
private fun DeleteJobDialog(job: Job, jobs: JobList, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    var contents by remember { mutableStateOf<JobContents?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(job.id) {
        withContext(Dispatchers.Main.immediate) {
            try {
                contents = jobs.contents(job.id)
            } catch (e: IOException) {
                problem = NO_SIGNAL_DELETE
            } catch (e: ApiException) {
                problem = e.message
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Delete ${job.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val c = contents
                if (c != null) {
                    Text(contentsText(c), style = MaterialTheme.typography.titleSmall)
                    if (c.undelivered > 0) {
                        Text(undeliveredWarning(c.undelivered), color = FmxColors.Warning, modifier = Modifier.testTag(JobListTags.DELETE_WARNING))
                    }
                    Text(DELETE_INFO)
                } else if (problem == null) {
                    Text("Checking what's in the job…")
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch(Dispatchers.Main.immediate) {
                        busy = true
                        try {
                            jobs.delete(job.id)
                            onDeleted()
                        } catch (e: IOException) {
                            problem = NO_SIGNAL_DELETE
                        } catch (e: ApiException) {
                            problem = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = contents != null && !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag(JobListTags.DELETE_CONFIRM),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
