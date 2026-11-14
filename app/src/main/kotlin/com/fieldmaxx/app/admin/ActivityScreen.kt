package com.fieldmaxx.app.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.point.newOpId
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.model.ACTIVITY_LIMIT
import com.fieldmaxx.core.model.ActivityItem
import com.fieldmaxx.core.model.CantShootReason
import com.fieldmaxx.core.model.Op
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val POINT_DELETE = "point.delete"
private const val POINT_RESTORE = "point.restore"

/**
 * Deliveries are logged too; they belong on Delivery health, not among who did what. The server leaves them out (I1);
 * this is the backstop for a server from before that.
 */
private val DELIVERY_EVENTS = setOf("photo.confirmed", "photo.failed")

/**
 * What each list shows ([kinds], null: everything) and what it asks the server for ([asks]): only its own kinds, so a
 * busy job's newer edits can't push an older can't-shoot or delete past the server's newest [ACTIVITY_LIMIT] (I1).
 * Deleted asks for the restores too: Restore is offered only while a point's newest of the two is a delete (R68).
 */
enum class ActivityFilter(val label: String, internal val kinds: Set<String>?, internal val asks: Set<String>?) {
    ALL("All", null, null),
    SHOT("Shot", setOf("point.close"), setOf("point.close")),
    CANT_SHOOT("Can't shoot", setOf("point.cantShoot"), setOf("point.cantShoot")),
    DELETED("Deleted", setOf(POINT_DELETE), setOf(POINT_DELETE, POINT_RESTORE)),
}

/** What a list says when the server's answer is full, so it is never silently partial. */
internal fun cappedText(f: ActivityFilter): String = "Showing the newest $ACTIVITY_LIMIT." +
    if (f == ActivityFilter.ALL) " Shot, Can't shoot and Deleted each reach further back." else ""

/** The server's answer for [filter]: [capped] when it holds the most the server sends, so older ones exist. */
private class Feed(val filter: ActivityFilter, val items: List<ActivityItem>, val capped: Boolean)

object ActivityTags {
    const val CAPPED = "activity.capped"
    fun filter(f: ActivityFilter) = "activity.filter:${f.name}"
    fun restore(pointId: String) = "activity.restore:$pointId"
    fun restoreSent(pointId: String) = "activity.restoreSent:$pointId"
}

private fun kindText(kind: String) = when (kind) {
    "point.close" -> "Shot"
    "point.cantShoot" -> "Can't shoot"
    "point.reopen" -> "Reopened"
    "point.add" -> "Added"
    POINT_DELETE -> "Deleted"
    POINT_RESTORE -> "Restored"
    "point.move" -> "Moved"
    "point.update" -> "Pole data or comment edited"
    "point.setFolder" -> "Folder chosen"
    "photo.reuse" -> "Photo reused"
    "photo.remove" -> "Photo removed"
    else -> kind
}

/** The server's "no_access" or "other: gate locked" (admin/AdminRoutes.kt) as the point sheet words it. */
internal fun cantShootText(detail: String): String {
    val reason = detail.substringBefore(": ")
    val words = when (runCatching { CantShootReason.valueOf(reason) }.getOrNull()) {
        CantShootReason.not_found -> "Not found"
        CantShootReason.no_access -> "No access"
        CantShootReason.not_applicable -> "Not applicable"
        CantShootReason.other -> "Other"
        null -> reason
    }
    return if (": " in detail) "$words: ${detail.substringAfter(": ")}" else words
}

/**
 * The points still deleted: those whose newest delete-or-restore event, in this newest-first feed, is a delete (R68).
 * Only that event offers Restore.
 */
internal fun stillDeleted(items: List<ActivityItem>): Map<String, ActivityItem> {
    val seen = HashSet<String>()
    val deleted = HashMap<String, ActivityItem>()
    for (item in items) {
        val id = item.pointId ?: continue
        if (item.kind != POINT_DELETE && item.kind != POINT_RESTORE) continue
        if (!seen.add(id)) continue
        if (item.kind == POINT_DELETE) deleted[id] = item
    }
    return deleted
}

/**
 * Admin → Activity (spec §6.9): who did what to which point and when, newest first, for one job ([jobId]) or all.
 * "Shot" is who shot which point and when, "Can't shoot" the list of points that couldn't be, with the reason. A
 * point's latest delete has Restore (R68), queued through the outbox ([enqueue]) like every other change. Each list
 * asks the server for its own kinds, and says when it shows only the newest [ACTIVITY_LIMIT] (I1).
 */
@Composable
fun ActivityScreen(api: Api, jobId: String?, title: String, enqueue: suspend (Op) -> Unit, onBack: () -> Unit, zone: ZoneId = ZoneId.systemDefault()) {
    var feed by remember { mutableStateOf<Feed?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }
    var sent by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()

    // Until the new list's answer is in, the last one shows, narrowed to the new list's kinds.
    LaunchedEffect(reload, filter) {
        withContext(Dispatchers.Main.immediate) {
            try {
                val answer = api.activity(jobId, filter.asks)
                feed = Feed(filter, answer.filter { it.kind !in DELIVERY_EVENTS }, capped = answer.size >= ACTIVITY_LIMIT)
                problem = null
            } catch (e: IOException) {
                problem = ADMIN_NEEDS_SIGNAL
            } catch (e: ApiException) {
                problem = e.message
            }
        }
    }

    AdminScaffold(title, onBack, onRefresh = { reload++ }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }, modifier = Modifier.testTag(ActivityTags.filter(f)))
                }
            }
            problem?.let { AdminNote(it) }
            val current = feed
            val all = current?.items.orEmpty()
            val restorable = remember(all) { stillDeleted(all) }
            val shown = filter.kinds?.let { kinds -> all.filter { it.kind in kinds } } ?: all
            if (current?.capped == true && current.filter == filter) {
                Text(
                    cappedText(filter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(ActivityTags.CAPPED),
                )
            }
            if (current != null && shown.isEmpty()) {
                Text("Nothing here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(shown) { _, item ->
                    val pointId = item.pointId
                    val restore = pointId != null && restorable[pointId] === item
                    ActivityRow(
                        item, zone,
                        restore = when {
                            !restore -> RestoreState.NONE
                            pointId in sent -> RestoreState.SENT
                            else -> RestoreState.OFFERED
                        },
                        onRestore = {
                            val id = pointId ?: return@ActivityRow
                            scope.launch(Dispatchers.Main.immediate) {
                                try {
                                    enqueue(Op.PointRestore(newOpId(), id))
                                    sent = sent + id
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) { // the phone's database: rare, but the restore isn't queued
                                    problem = "Couldn't save the restore. Try again."
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private enum class RestoreState { NONE, OFFERED, SENT }

@Composable
private fun ActivityRow(item: ActivityItem, zone: ZoneId, restore: RestoreState, onRestore: () -> Unit) {
    val pointId = item.pointId.orEmpty()
    ListItem(
        headlineContent = { Text(item.label ?: "—") },
        supportingContent = {
            Column {
                Text("${kindText(item.kind)} by ${item.user.ifBlank { "someone" }} · ${dayTimeText(item.at, zone)}")
                if (item.kind == "point.cantShoot" && item.detail.isNotBlank()) Text(cantShootText(item.detail), color = FmxColors.Warning)
            }
        },
        trailingContent = when (restore) {
            RestoreState.NONE -> null
            RestoreState.OFFERED -> ({ TextButton(onClick = onRestore, modifier = Modifier.testTag(ActivityTags.restore(pointId))) { Text("Restore") } })
            RestoreState.SENT -> ({
                Text("Restore sent", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(ActivityTags.restoreSent(pointId)))
            })
        },
    )
    HorizontalDivider()
}
