package com.fieldmaxx.app.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.fieldmaxx.app.AppGraph
import com.fieldmaxx.app.data.MetaDao
import com.fieldmaxx.app.data.MetaEntity
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.app.ui.fmxBarColors
import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.ReuseCandidate
import com.fieldmaxx.core.naming.normalizeFolderName
import java.io.IOException
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

const val NEARBY_NEEDS_SIGNAL = "No signal. Nearby photos come from the server: they need signal."
const val SEARCH_OFFLINE = "No signal: only this job's photos are searched. Other jobs' photos need signal."
private const val NO_NEARBY = "No photos within 150 m of this point."
private const val NO_JOB_PHOTOS = "No other photos in this job yet."
private const val NO_MATCH = "No photos of a point with that label."
private const val TYPE_A_LABEL = "Type a point label, like POLE 5."
private const val NOT_ADDED = "The photos couldn't be added on this phone. Try again."

object ReuseTags {
    const val NEARBY = "reuse.nearby"
    const val THIS_JOB = "reuse.thisJob"
    const val SEARCH = "reuse.search"
    const val SEARCH_FIELD = "reuse.search.field"
    const val USE = "reuse.use"
    const val TRY_AGAIN = "reuse.tryAgain"
    fun candidate(photoId: String) = "reuse.candidate:$photoId"
    fun line(photoId: String) = "reuse.line:$photoId"
    fun distance(photoId: String) = "reuse.distance:$photoId"
    fun group(pointId: String) = "reuse.group:$pointId"
}

private enum class ReuseTab(val title: String, val tag: String) {
    NEARBY("Nearby", ReuseTags.NEARBY),
    THIS_JOB("This job", ReuseTags.THIS_JOB),
    SEARCH("Search", ReuseTags.SEARCH),
}

/** Where a reused photo came from, as the picking phone saw it: "reused from POLE 5 · A05J9XE · 11/05/26" (R66). */
@Serializable
data class ReuseSource(val pointLabel: String, val jobName: String)

/**
 * The source of each photo this phone reused, kept on the phone by the copy's id: the server's photo carries only its
 * source's id, and a source from another job isn't on this phone. Another phone's reuse is named from its source's rows,
 * where this phone has them ([photoViewsFor]).
 */
class ReuseSources(private val meta: MetaDao) {
    suspend fun put(photoId: String, source: ReuseSource) {
        meta.put(MetaEntity(KEY + photoId, FmxJson.encodeToString(ReuseSource.serializer(), source)))
    }

    suspend fun get(photoId: String): ReuseSource? {
        val json = meta.get(KEY + photoId) ?: return null
        return try {
            FmxJson.decodeFromString(ReuseSource.serializer(), json)
        } catch (e: IllegalArgumentException) { // kotlinx.serialization's SerializationException
            null
        }
    }

    private companion object {
        const val KEY = "reuseSource:"
    }
}

/** What the picker reads and does; [GraphReuseDeps] in the app, a fake in tests. */
interface ReuseDeps {
    /** The app's Coil loader: the server's thumbnails through the signed-in client. */
    val imageLoader: ImageLoader

    /** The server's address, where the thumbnails come from. */
    fun server(): String

    fun job(jobId: String): Flow<Job?>

    /** The job's points, deleted ones left out. */
    fun points(jobId: String): Flow<List<Point>>

    /** The job's photos on this phone (the sync engine's copy), removed ones left out: "This job" without signal. */
    fun jobPhotos(jobId: String): Flow<List<PhotoInfo>>

    /** Photos within 150 m of the spot from any job, nearest first (`Api.nearby`); needs signal. */
    suspend fun nearby(lat: Double, lon: Double, excludePointId: String?): List<ReuseCandidate>

    /** The server's list of the job's photos, newest first (`Api.jobPhotos`); needs signal. */
    suspend fun serverJobPhotos(jobId: String): List<ReuseCandidate>

    /** Shows [op] on the phone at once and queues it for the server. */
    suspend fun enqueue(op: Op)

    suspend fun saveSource(photoId: String, source: ReuseSource)

    /** Where the picker's dates are told (the stamp's zone). */
    fun zone(): ZoneId
}

/** [ReuseDeps] from the app's graph. */
class GraphReuseDeps(private val graph: AppGraph) : ReuseDeps {
    override val imageLoader: ImageLoader get() = graph.imageLoader
    override fun server(): String = graph.server.get()
    override fun job(jobId: String): Flow<Job?> = graph.sync.job(jobId)
    override fun points(jobId: String): Flow<List<Point>> = graph.sync.points(jobId)
    override fun jobPhotos(jobId: String): Flow<List<PhotoInfo>> = graph.sync.jobPhotos(jobId)
    override suspend fun nearby(lat: Double, lon: Double, excludePointId: String?): List<ReuseCandidate> = graph.api.nearby(lat, lon, excludePointId)
    override suspend fun serverJobPhotos(jobId: String): List<ReuseCandidate> = graph.api.jobPhotos(jobId)
    override suspend fun enqueue(op: Op) = graph.sync.enqueue(op)
    override suspend fun saveSource(photoId: String, source: ReuseSource) = ReuseSources(graph.db.meta()).put(photoId, source)
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/** "80 m". */
internal fun distanceText(m: Double): String = "${m.roundToInt()} m"

internal fun useText(n: Int): String = when (n) {
    0 -> "Use photos"
    1 -> "Use 1 photo"
    else -> "Use $n photos"
}

/** A typed label finds a point however its label is written: "pole 5" finds "POLE 5" and "POLE_5". */
internal fun labelMatches(label: String, query: String): Boolean = normalizeFolderName(label).contains(normalizeFolderName(query.trim()))

/**
 * "This job": the job's photos the server knows, from its list when it could be fetched and from this phone's copy of the
 * job in any case (R66), newest first; the point's own photos left out. A copy this phone made a moment ago has no name
 * until the server has it, so it can't be a source yet.
 */
internal fun thisJobCandidates(stored: List<PhotoInfo>, points: List<Point>, jobName: String, fromServer: List<ReuseCandidate>, pointId: String): List<ReuseCandidate> {
    val labels = points.associate { it.id to it.label }
    val here = stored.filter { it.fileName != null }.mapNotNull { ph -> labels[ph.pointId]?.let { ReuseCandidate(ph, it, jobName, null) } }
    return (fromServer + here).distinctBy { it.photo.id }.filter { it.photo.pointId != pointId }.sortedByDescending { it.photo.takenAt }
}

/** Search: [pool]'s photos of points whose label matches [query], newest first. */
internal fun searchCandidates(query: String, pool: List<ReuseCandidate>): List<ReuseCandidate> =
    if (query.isBlank()) emptyList() else pool.distinctBy { it.photo.id }.filter { labelMatches(it.pointLabel, query) }.sortedByDescending { it.photo.takenAt }

/**
 * Copies the [picks] to the point (R66): one [Op.PhotoReuse] each, under a fresh id of its own. The server makes the copy
 * (from its backup, or from OneDrive through the source job's link) and delivers it to this point's folder. Each source's
 * label and job are kept for the copy's "reused from" line. Each pick is queued whole even if the screen goes; [queued]
 * hears each one.
 */
internal suspend fun reusePhotos(picks: List<ReuseCandidate>, pointId: String, deps: ReuseDeps, queued: (ReuseCandidate) -> Unit = {}) =
    withContext(NonCancellable) {
        for (c in picks) {
            val copyId = UUID.randomUUID().toString()
            deps.saveSource(copyId, ReuseSource(c.pointLabel, c.jobName))
            deps.enqueue(Op.PhotoReuse(UUID.randomUUID().toString(), copyId, c.photo.id, pointId))
            queued(c)
        }
    }

/** A list fetched from the server: still coming, here, or not (signal or a refusal). */
private sealed interface Fetch {
    data object Loading : Fetch
    data class Done(val photos: List<ReuseCandidate>) : Fetch
    data class Failed(val message: String, val offline: Boolean) : Fetch
}

private suspend fun fetch(call: suspend () -> List<ReuseCandidate>): Fetch = try {
    Fetch.Done(call())
} catch (e: IOException) {
    Fetch.Failed(NEARBY_NEEDS_SIGNAL, offline = true)
} catch (e: ApiException) {
    Fetch.Failed(e.message, offline = false)
}

/**
 * "Use existing photos" (spec §6.6.2) for one point, from its sheet: photos already taken, with thumbnail, point label,
 * job and date, to copy into this point's folder. **Nearby**: within 150 m of the point, from any job, with the distance
 * (this point's own left out); **This job**: all the job's photos, grouped by point, newest first; **Search**: by point
 * label, over both. This job works without signal, from the phone's copy of the job; Nearby and searching other jobs
 * need it, and say so (R66). Each picked photo becomes one [Op.PhotoReuse]; [onDone] closes the picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReusePicker(jobId: String, pointId: String, deps: ReuseDeps, onDone: () -> Unit) {
    // Main-confined, as on the other screens: the state is written on the main thread wherever a value comes from.
    val main = Dispatchers.Main.immediate
    val job by remember(deps, jobId) { deps.job(jobId) }.collectAsState(null, main)
    val points by remember(deps, jobId) { deps.points(jobId) }.collectAsState(null, main)
    val stored by remember(deps, jobId) { deps.jobPhotos(jobId) }.collectAsState(emptyList(), main)
    val done by rememberUpdatedState(onDone)
    val point = points?.firstOrNull { it.id == pointId }
    if (points != null && point == null) {
        LaunchedEffect(Unit) { done() } // deleted meanwhile
        return
    }

    var tab by rememberSaveable { mutableStateOf(ReuseTab.NEARBY) }
    var query by rememberSaveable { mutableStateOf("") }
    val picked = remember { mutableStateListOf<ReuseCandidate>() }
    var attempt by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val zone = remember(deps) { deps.zone() }
    val server = remember(deps) { deps.server() }

    // Asked once the point is known, and again on "Try again".
    val nearby by produceState<Fetch>(Fetch.Loading, point?.lat, point?.lon, attempt) {
        val here = point ?: return@produceState
        value = Fetch.Loading
        value = fetch { deps.nearby(here.lat, here.lon, here.id) }
    }
    val fromServer by produceState<Fetch>(Fetch.Loading, jobId, attempt) { value = fetch { deps.serverJobPhotos(jobId) } }

    val reusedHere = remember(stored, pointId) { stored.filter { it.pointId == pointId }.mapNotNullTo(HashSet()) { it.reusedFrom } }
    val thisJob = remember(stored, points, job, fromServer, pointId) {
        thisJobCandidates(stored, points.orEmpty(), job?.name.orEmpty(), (fromServer as? Fetch.Done)?.photos.orEmpty(), pointId)
    }
    val nearbyPhotos = (nearby as? Fetch.Done)?.photos.orEmpty()
    val results = remember(query, nearbyPhotos, thisJob) { searchCandidates(query, nearbyPhotos + thisJob) }
    val choice = Choice(picked.mapTo(HashSet()) { it.photo.id }, reusedHere, server, zone, deps.imageLoader) { c ->
        val i = picked.indexOfFirst { it.photo.id == c.photo.id }
        if (i >= 0) picked.removeAt(i) else picked.add(c)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Use existing photos")
                        point?.let { Text(it.label, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = { IconButton(onClick = { done() }) { Icon(Icons.Default.Close, contentDescription = "Close") } },
                colors = fmxBarColors(),
            )
        },
        bottomBar = {
            Surface(color = FmxColors.Bar) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = {
                            saving = true
                            problem = null
                            val chosen = picked.toList()
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    reusePhotos(chosen, pointId, deps) { c -> picked.removeAll { it.photo.id == c.photo.id } }
                                    done()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    problem = NOT_ADDED
                                    saving = false
                                }
                            }
                        },
                        enabled = picked.isNotEmpty() && !saving,
                        modifier = Modifier.fillMaxWidth().testTag(ReuseTags.USE),
                    ) { Text(useText(picked.size)) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab.ordinal) {
                for (t in ReuseTab.entries) {
                    Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.title) }, modifier = Modifier.testTag(t.tag))
                }
            }
            when (tab) {
                ReuseTab.NEARBY -> when (val n = nearby) {
                    Fetch.Loading -> Loading()
                    is Fetch.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(n.message, color = FmxColors.Warning)
                        OutlinedButton(onClick = { attempt++ }, modifier = Modifier.testTag(ReuseTags.TRY_AGAIN)) { Text("Try again") }
                    }
                    is Fetch.Done -> if (n.photos.isEmpty()) Message(NO_NEARBY) else Candidates(n.photos, choice, grouped = false)
                }
                ReuseTab.THIS_JOB -> when {
                    thisJob.isNotEmpty() -> Candidates(thisJob, choice, grouped = true)
                    fromServer == Fetch.Loading && points == null -> Loading()
                    else -> Message(NO_JOB_PHOTOS)
                }
                ReuseTab.SEARCH -> Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Point label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(ReuseTags.SEARCH_FIELD),
                    )
                    (nearby as? Fetch.Failed)?.let { f ->
                        Text(
                            if (f.offline) SEARCH_OFFLINE else "Only this job's photos are searched: ${f.message}",
                            color = FmxColors.Warning,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    when {
                        query.isBlank() -> Message(TYPE_A_LABEL)
                        results.isEmpty() -> Message(NO_MATCH)
                        else -> Candidates(results, choice, grouped = false)
                    }
                }
            }
        }
    }
}

/** What each row needs: which photos are picked, which are on this point already, and how to show and pick one. */
private class Choice(
    val picked: Set<String>,
    val reusedHere: Set<String>,
    val server: String,
    val zone: ZoneId,
    val imageLoader: ImageLoader,
    val toggle: (ReuseCandidate) -> Unit,
)

/** The photos as rows; [grouped]: under their point's label, in the order the list has them. */
@Composable
private fun Candidates(candidates: List<ReuseCandidate>, choice: Choice, grouped: Boolean) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (grouped) {
            for ((pointId, group) in candidates.groupBy { it.photo.pointId }) {
                item(key = "group:$pointId") {
                    Text(
                        group.first().pointLabel,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag(ReuseTags.group(pointId)),
                    )
                }
                items(group, key = { "photo:${it.photo.id}" }) { CandidateRow(it, choice) }
            }
        } else {
            items(candidates, key = { "photo:${it.photo.id}" }) { CandidateRow(it, choice) }
        }
    }
}

@Composable
private fun CandidateRow(c: ReuseCandidate, choice: Choice) {
    val id = c.photo.id
    val picked = id in choice.picked
    val alreadyHere = id in choice.reusedHere
    val distance = c.distanceM
    ListItem(
        leadingContent = {
            AsyncImage(
                model = photoThumbUrl(choice.server, id),
                contentDescription = null,
                imageLoader = choice.imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            )
        },
        headlineContent = { Text(photoSource(c.pointLabel, c.jobName, c.photo.takenAt, choice.zone), modifier = Modifier.testTag(ReuseTags.line(id))) },
        supportingContent = when {
            alreadyHere -> ({ Text("Already on this point") })
            distance != null -> ({ Text(distanceText(distance), modifier = Modifier.testTag(ReuseTags.distance(id))) })
            else -> null
        },
        trailingContent = { Checkbox(checked = picked || alreadyHere, onCheckedChange = null, enabled = !alreadyHere) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = picked, enabled = !alreadyHere, role = Role.Checkbox, onValueChange = { choice.toggle(c) })
            .testTag(ReuseTags.candidate(id)),
    )
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun Message(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
}
