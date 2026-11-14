package com.fieldmaxx.app.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.model.HealthReport
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoStatus
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Above this the admin is warned (spec §6.6; the server's Retention.DISK_WARN_PCT). */
private const val DISK_WARN_PCT = 80

/** The red of the stuck count, on the menu and here. */
internal val StuckRed = Color(0xFFDC2626)

object HealthTags {
    const val STUCK_COUNT = "health.stuck"
    const val SUMMARY = "health.summary"
    const val DISK = "health.disk"
    const val TIMES = "health.times"
    fun photo(id: String) = "health.photo:$id"
    fun retry(id: String) = "health.retry:$id"
}

/** The photos that ran out of attempts (spec §6.6): the menu's red count (R71). */
fun stuckCount(r: HealthReport): Int = r.photos.count { it.status == PhotoStatus.stuck }

/** The server's disk, with the warning above 80%: the server never deletes a backup early to make room. */
internal fun diskText(pct: Int): String =
    if (pct > DISK_WARN_PCT) "Server disk $pct% full. Backups are still kept for their full time: add disk space."
    else "Server disk $pct% used"

/** "40 s", "2 min 5 s", "1 h 5 min". */
internal fun durationText(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min" + (seconds % 60).let { if (it > 0) " $it s" else "" }
    else -> "${seconds / 3600} h" + (seconds % 3600 / 60).let { if (it > 0) " $it min" else "" }
}

/** The typical delivery times of the last 24 hours (spec §6.6 "Speed"), from the shutter and for the upload itself. */
internal fun typicalTimesText(r: HealthReport): String {
    val parts = listOfNotNull(
        r.phoneToConfirmedMedianS?.let { "${durationText(it)} from shutter to OneDrive" },
        r.uploadMedianS?.let { "${durationText(it)} upload" },
    )
    return if (parts.isEmpty()) "No photos reached OneDrive in the last 24 hours" else "Typical over the last 24 hours: " + parts.joinToString(", ")
}

private fun pendingText(n: Int) = when (n) {
    0 -> "Every photo is in OneDrive"
    1 -> "1 photo not in OneDrive yet"
    else -> "$n photos not in OneDrive yet"
}

/** The report, with the names of the techs and jobs it refers to by id (empty when those couldn't be read). */
private class Health(val report: HealthReport, val techs: Map<String, String>, val jobs: Map<String, String>)

/**
 * Admin → Delivery health (spec §6.9): every photo not yet confirmed across the crew, stuck ones first (as the server
 * sends them), each with its phone's last reported reason, who took it and when; the server's disk; the typical
 * delivery times. Stuck photos have "Retry now". Each report goes to [onReport], which keeps the menu's count (R71).
 */
@Composable
fun DeliveryHealthScreen(api: Api, onReport: (HealthReport) -> Unit, onBack: () -> Unit, zone: ZoneId = ZoneId.systemDefault()) {
    var health by remember { mutableStateOf<Health?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val reported by rememberUpdatedState(onReport)
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        withContext(Dispatchers.Main.immediate) {
            try {
                health = loadHealth(api).also { reported(it.report) }
                problem = null
            } catch (e: IOException) {
                problem = ADMIN_NEEDS_SIGNAL
            } catch (e: ApiException) {
                problem = e.message
            }
        }
    }

    AdminScaffold("Delivery health", onBack, onRefresh = { reload++ }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            problem?.let { item(key = "problem") { AdminNote(it) } }
            val h = health ?: return@LazyColumn
            val r = h.report
            item(key = "summary") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val stuck = stuckCount(r)
                        if (stuck > 0) {
                            Text(
                                "$stuck stuck",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(StuckRed, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp)
                                    .testTag(HealthTags.STUCK_COUNT),
                            )
                        }
                        Text(pendingText(r.photos.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(HealthTags.SUMMARY))
                    }
                    Text(
                        diskText(r.diskUsedPct),
                        color = if (r.diskUsedPct > DISK_WARN_PCT) FmxColors.Warning else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag(HealthTags.DISK),
                    )
                    Text(typicalTimesText(r), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(HealthTags.TIMES))
                }
                HorizontalDivider()
            }
            items(r.photos, key = { it.id }) { p ->
                PhotoRow(p, h, zone, onRetry = {
                    scope.launch(Dispatchers.Main.immediate) {
                        try {
                            api.retryPhoto(p.id)
                            problem = null
                        } catch (e: IOException) {
                            problem = ADMIN_NEEDS_SIGNAL
                        } catch (e: ApiException) {
                            problem = e.message
                        }
                        reload++
                    }
                })
            }
        }
    }
}

/** The report, and the names for it, read side by side; only the report itself must come. */
private suspend fun loadHealth(api: Api): Health = coroutineScope {
    val techs = async { namesOrNone { api.adminUsers().associate { it.id to it.displayName } } }
    val jobs = async { namesOrNone { api.jobs(include = "finished,deleted").associate { it.job.id to it.job.name } } }
    val report = api.health()
    Health(report, techs.await(), jobs.await())
}

private suspend fun namesOrNone(read: suspend () -> Map<String, String>): Map<String, String> = try {
    read()
} catch (e: IOException) {
    emptyMap()
} catch (e: ApiException) {
    emptyMap()
}

@Composable
private fun PhotoRow(p: PhotoInfo, h: Health, zone: ZoneId, onRetry: () -> Unit) {
    val stuck = p.status == PhotoStatus.stuck
    ListItem(
        headlineContent = { Text(p.fileName ?: "Not named yet") },
        supportingContent = {
            Column {
                Text(
                    if (stuck) "Stuck: ${p.lastError ?: "no reason was reported"}" else "On its way" + (p.lastError?.let { " · last try: $it" } ?: ""),
                    color = if (stuck) FmxColors.Warning else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    listOf(h.techs[p.userId] ?: "Unknown tech", h.jobs[p.jobId] ?: "Unknown job", "taken ${dayTimeText(p.takenAt, zone)}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailingContent = if (stuck) ({ TextButton(onClick = onRetry, modifier = Modifier.testTag(HealthTags.retry(p.id))) { Text("Retry now") } }) else null,
        modifier = Modifier.testTag(HealthTags.photo(p.id)),
    )
    HorizontalDivider()
}
