package com.fieldmaxx.app.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.fieldmaxx.app.AppGraph
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.photos.deletedPhotoIds
import com.fieldmaxx.app.platform.Fix
import com.fieldmaxx.app.sync.SyncStatus
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.model.AppConfig
import com.fieldmaxx.core.model.CrewPosition
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.OpResult
import com.fieldmaxx.core.model.Point
import java.io.IOException
import java.time.ZoneId
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Center on me" goes at least this close; a search or "Left" pick at least [POINT_ZOOM]. */
private const val ME_ZOOM = 17.0
private const val POINT_ZOOM = 18.0

/** A view is saved once the map has held it this long, so a pan isn't written frame by frame. */
internal const val SAVE_QUIET_MS = 500L

/** How often crew ages and the sunset countdown are worked out again. */
private const val TICK_MS = 30_000L
private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
private val MapBackground = Color(0xFF020617) // what shows where no imagery has loaded (spec §6.8)

object MapTags {
    const val PILL = "map.pill"
    const val BANNER = "map.banner"
    const val HIDE_SHOT = "map.hideShot"
    const val LEFT = "map.left"
    const val LOCATION_OFF = "map.locationOff"
    const val CENTER_ON_ME = "map.centerOnMe"
    const val LABELS = "map.labels"
    const val HOUSES = "map.houseNumbers"
    const val ZOOM_IN = "map.zoomIn"
    const val ZOOM_OUT = "map.zoomOut"
    const val ADD = "map.add"
    const val MENU = "map.menu"
    const val SEARCH = "map.search"
    const val SEARCH_FIELD = "map.search.field"
    fun leftItem(pointId: String) = "map.left:$pointId"
    fun searchResult(pointId: String) = "map.search:$pointId"
}

/** What the map screen reads and does; [GraphMapDeps] in the app, a fake in tests. */
interface MapDeps {
    fun job(jobId: String): Flow<Job?>

    /** The job's points, deleted ones left out. */
    fun points(jobId: String): Flow<List<Point>>

    /** The job's points with a photo not in OneDrive yet. */
    fun pendingPhotoPointIds(jobId: String): Flow<Set<String>>
    val crew: StateFlow<List<CrewPosition>>
    val status: StateFlow<SyncStatus>

    /** This phone's photos not in OneDrive yet, across all jobs (R40). */
    val photosWaiting: Flow<Int>
    val rejected: SharedFlow<OpResult>
    val fixes: StateFlow<Fix?>
    val config: StateFlow<AppConfig>
    val views: ViewMemory

    /** The time now and then every [TICK_MS]. */
    val clock: Flow<Long>
    fun now(): Long
    suspend fun userId(): String

    /** Whether the house-number overlay is on: off until the tech turns it on, then remembered (R75). */
    val houseNumbersOn: Flow<Boolean>
    suspend fun setHouseNumbersOn(on: Boolean)

    /** The address numbers inside [box]; throws [IOException] with no signal or while Overpass asks for patience (R74). */
    suspend fun houseNumbers(box: BBox): List<HouseNumber>

    /** Shows [op] on the phone at once and queues it for the server; it waits there while there is no signal. */
    suspend fun enqueue(op: Op)

    /** Refreshes the stored job; throws [IOException] or [ApiException]. */
    suspend fun openJob(jobId: String)

    /** After the location permission answer: location starts if it was allowed (R41). */
    fun permissionChanged()
}

/** [MapDeps] from the app's graph. */
class GraphMapDeps(private val graph: AppGraph) : MapDeps {
    override fun job(jobId: String): Flow<Job?> = graph.sync.job(jobId)
    override fun points(jobId: String): Flow<List<Point>> = graph.sync.points(jobId)
    override fun pendingPhotoPointIds(jobId: String): Flow<Set<String>> =
        combine(
            graph.sync.jobPhotos(jobId),
            graph.db.localPhotos().observeWaitingPointIds(jobId),
            deletedPhotoIds(graph.db),
        ) { server, local, deleted -> pendingPointIds(server, local, deleted) }
    override val crew: StateFlow<List<CrewPosition>> get() = graph.sync.crew
    override val status: StateFlow<SyncStatus> get() = graph.sync.status
    override val photosWaiting: Flow<Int> get() = graph.db.localPhotos().observeWaitingCount()
    override val rejected: SharedFlow<OpResult> get() = graph.sync.rejected
    override val fixes: StateFlow<Fix?> get() = graph.location.fixes
    override val config: StateFlow<AppConfig> get() = graph.config
    override val views: ViewMemory get() = graph.mapViews
    override val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }
    override fun now(): Long = System.currentTimeMillis()
    override suspend fun userId(): String = graph.sync.savedUser()?.id.orEmpty()
    override val houseNumbersOn: Flow<Boolean> get() = graph.houseNumberSetting.on
    override suspend fun setHouseNumbersOn(on: Boolean) = graph.houseNumberSetting.set(on)
    override suspend fun houseNumbers(box: BBox): List<HouseNumber> = graph.houseNumbers.load(box)
    override suspend fun enqueue(op: Op) = graph.sync.enqueue(op)
    override suspend fun openJob(jobId: String) = graph.sync.openJob(jobId)
    override fun permissionChanged() = graph.tracker.permissionChanged()
}

/**
 * The job's map (spec §6.3): imagery with the street-name overlay, the points as markers by type (grey once shot), the
 * chip row, the progress strip, the crew and your own position. [mapView] draws [controller]'s map underneath.
 *
 * The camera never moves by itself (R38): on open it goes to the view saved for this user and job, or, with none saved,
 * fits all points once; after that only the user's own actions move it. Each view the map holds is saved. Relative
 * moves (zoom +/−) start from where the map is now, also mid-glide or mid-zoom.
 *
 * Opening refreshes the job in the background; without signal the stored job stays, and a job that is gone goes back
 * with [onJobGone] ("This job was deleted", R42). Refused changes show as a snackbar while the map is open.
 *
 * "+" starts the add-point flow ([AddPointFlow]); the new point opens in [pointSheet]. [initialPointId] opens that point's
 * sheet at once (from "Points I added"). Hooks (R43): [onMenu] is the ☰ (Task 30), and [pointSheet] shows the point a
 * marker tap (or a search or "Left" pick) selected, with a way to close it (Task 25). [banner] shows under the top bar,
 * after the delivery banner: the update offer (R88).
 */
@Composable
fun MapScreen(
    jobId: String,
    deps: MapDeps,
    controller: MapController,
    onBack: () -> Unit,
    onJobGone: (message: String) -> Unit,
    mapView: @Composable (Modifier) -> Unit = {},
    initialPointId: String? = null,
    onMenu: () -> Unit = {},
    banner: @Composable () -> Unit = {},
    pointSheet: @Composable (pointId: String, close: () -> Unit) -> Unit = { _, _ -> },
) {
    // Main-confined, as on the other screens: the screen's state is written on the main thread wherever a value comes from.
    val main = Dispatchers.Main.immediate
    val job by remember(deps, jobId) { deps.job(jobId) }.collectAsState(null, main)
    val points by remember(deps, jobId) { deps.points(jobId) }.collectAsState(emptyList(), main)
    val pending by remember(deps, jobId) { deps.pendingPhotoPointIds(jobId) }.collectAsState(emptySet(), main)
    val waiting by remember(deps) { deps.photosWaiting }.collectAsState(0, main)
    val now by remember(deps) { deps.clock }.collectAsState(remember(deps) { deps.now() }, main)
    val crew by deps.crew.collectAsState(main)
    val status by deps.status.collectAsState(main)
    val fix by deps.fixes.collectAsState(main)
    val config by deps.config.collectAsState(main)
    val locationAllowed = rememberLocationPermission(deps::permissionChanged)
    val goneCallback by rememberUpdatedState(onJobGone)

    var typeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var hideShot by rememberSaveable { mutableStateOf(false) }
    var labels by rememberSaveable { mutableStateOf(true) }
    val houses by remember(deps) { deps.houseNumbersOn }.collectAsState(false, main)
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var showLeft by rememberSaveable { mutableStateOf(false) }
    var selectedPointId by rememberSaveable { mutableStateOf(initialPointId) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val progress = remember(points) { progress(points) }
    val type = typeFilter?.takeIf { t -> progress.byType.any { it.type == t } } // a type no longer in the job shows all
    val visible = remember(points, type, hideShot) { visiblePoints(points, type, hideShot) }
    val me = if (locationAllowed) fix else null
    val here = me?.let { LatLon(it.lat, it.lon) }
    val left = remember(points, here) { remainingByDistance(points, here) }
    val sunsetAt = here ?: points.firstOrNull()?.let { LatLon(it.lat, it.lon) }
    val sunset = remember(now, sunsetAt) { sunsetAt?.let { sunsetText(now, it.lat, it.lon, ZoneId.systemDefault()) } }

    // What the map draws follows the data; none of it moves the camera.
    LaunchedEffect(controller, config) { controller.setConfig(config) }
    LaunchedEffect(controller, visible, pending) { controller.setPoints(visible, pending) }
    LaunchedEffect(controller, crew, now) { controller.setCrew(crew, now) }
    LaunchedEffect(controller, me) { controller.setMyFix(me) }
    LaunchedEffect(controller, labels) { controller.setLabelsVisible(labels) }
    val screen = LocalConfiguration.current
    LaunchedEffect(controller, deps, houses, screen.screenWidthDp, screen.screenHeightDp) {
        withContext(main) { runHouseNumbers(houses, controller, deps::houseNumbers, screen.screenWidthDp, screen.screenHeightDp) }
    }
    val addingNow by rememberUpdatedState(adding)
    LaunchedEffect(controller) { controller.pointTaps.collect { if (!addingNow) selectedPointId = it } }
    LaunchedEffect(controller, deps, jobId) { withContext(main) { placeCamera(jobId, deps, controller) } }

    LaunchedEffect(deps, jobId) {
        withContext(main) {
            try {
                deps.openJob(jobId)
            } catch (e: IOException) {
                // No signal: the stored job stays on screen.
            } catch (e: ApiException) {
                if (e.status == 404) goneCallback(JOB_DELETED) // anything else: the stored job stays
            }
        }
    }
    LaunchedEffect(deps) {
        withContext(main) { deps.rejected.collectLatest { snackbar.showSnackbar(rejectionText(it.error)) } }
    }

    // The user's own actions: the only other camera moves.
    fun zoomed(least: Double) = max(controller.camera.value?.zoom ?: least, least)
    fun focus(p: Point) {
        controller.moveTo(CameraView(p.lat, p.lon, zoomed(POINT_ZOOM)))
        selectedPointId = p.id
    }
    fun zoomBy(delta: Double) {
        controller.camera.value?.let { controller.moveTo(it.copy(zoom = (it.zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM))) }
    }

    Box(Modifier.fillMaxSize().background(MapBackground)) {
        mapView(Modifier.fillMaxSize())
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            TopBar(job?.name.orEmpty(), status, waiting, onBack = onBack, onSearch = { searching = true }, onMenu = onMenu)
            job?.deliveryProblem?.let { DeliveryBanner(it) }
            banner()
            ChipRow(progress, type, hideShot, onType = { typeFilter = it }, onHideShot = { hideShot = !hideShot })
        }
        MapButtons(
            labels = labels,
            onLabels = { labels = !labels },
            houses = houses,
            onHouses = { scope.launch { deps.setHouseNumbersOn(!houses) } },
            onZoomIn = { zoomBy(1.0) },
            onZoomOut = { zoomBy(-1.0) },
            onCenter = me?.let { f -> { controller.moveTo(CameraView(f.lat, f.lon, zoomed(ME_ZOOM))) } },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        )
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
            Box(Modifier.fillMaxWidth().padding(12.dp)) {
                if (!adding) FloatingActionButton(
                    onClick = { adding = true; selectedPointId = null },
                    containerColor = FmxColors.Accent,
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).testTag(MapTags.ADD),
                ) { Icon(Icons.Default.Add, contentDescription = "Add a point here") }
                if (!locationAllowed) {
                    Text(
                        LOCATION_OFF,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.BottomEnd).background(FmxColors.Bar, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp).testTag(MapTags.LOCATION_OFF),
                    )
                }
            }
            SnackbarHost(snackbar)
            ProgressStrip(progress, left.size, sunset, onLeft = { showLeft = true })
        }
    }

    if (adding) {
        AddPointFlow(
            jobId, points, controller, start = here, enqueue = deps::enqueue,
            onAdded = { id -> adding = false; selectedPointId = id },
            onCancel = { adding = false },
        )
    }
    if (searching) SearchSheet(points, onPick = { searching = false; focus(it) }, onDismiss = { searching = false })
    if (showLeft) LeftList(left, here, onPick = { showLeft = false; focus(it) }, onDismiss = { showLeft = false })

    // ---- Task 25: the point sheet attaches here (R43), for the selected point; closing it clears the selection. ----
    selectedPointId?.let { id -> pointSheet(id) { selectedPointId = null } }
}

/**
 * The only camera moves that aren't the user's (R38): on open, to the view saved for this user and job, or with none
 * saved, a fit of all points once they are stored. Only the user's explicit moves ("center on me", a search result, a
 * "Left" item) cancel that fit; pans and pinches on the map before it don't (R61). Then each view the map holds is
 * saved, and the last one when the screen goes. Nothing is saved before the first placement: the camera is null till then.
 */
@OptIn(FlowPreview::class)
private suspend fun placeCamera(jobId: String, deps: MapDeps, controller: MapController) {
    val userId = deps.userId()
    val saved = deps.views.load(userId, jobId)
    if (controller.camera.value == null) {
        if (saved != null) {
            controller.moveTo(saved)
        } else {
            val first = merge(deps.points(jobId).filter { it.isNotEmpty() }, controller.camera.filterNotNull().map { emptyList() }).first()
            if (first.isNotEmpty()) controller.fitAll(first)
        }
    }
    try {
        // Off the main thread: the camera changes with every frame of a drag.
        withContext(Dispatchers.Default) {
            controller.camera.filterNotNull().debounce(SAVE_QUIET_MS).collect { deps.views.save(userId, jobId, it) }
        }
    } finally {
        // Wherever the map was left, even mid-move.
        controller.camera.value?.let { withContext(NonCancellable) { deps.views.save(userId, jobId, it) } }
    }
}

/**
 * The house-number overlay (R74, R75). Off: nothing drawn. On: once the camera has rested [HOUSE_NUMBER_DEBOUNCE_MS]
 * at zoom [HOUSE_NUMBER_MIN_ZOOM] or closer, the numbers of the screen's box are drawn; below that zoom they clear at
 * once. A failed or refused request leaves what was drawn as it is (no signal, or Overpass asking for patience).
 */
private suspend fun runHouseNumbers(
    on: Boolean,
    controller: MapController,
    load: suspend (BBox) -> List<HouseNumber>,
    widthDp: Int,
    heightDp: Int,
) {
    if (!on) {
        controller.setHouseNumbers(emptyList()) // called on the main thread
        return
    }
    // Waiting and asking happen off the main thread; the map is only touched on it.
    withContext(Dispatchers.Default) {
        controller.camera.filterNotNull().collectLatest { view ->
            if (view.zoom < HOUSE_NUMBER_MIN_ZOOM) {
                withContext(Dispatchers.Main.immediate) { controller.setHouseNumbers(emptyList()) }
            } else {
                delay(HOUSE_NUMBER_DEBOUNCE_MS)
                try {
                    val numbers = load(viewBBox(view, widthDp, heightDp))
                    withContext(Dispatchers.Main.immediate) { controller.setHouseNumbers(numbers) }
                } catch (e: IOException) {
                    // The labels stay as they were.
                }
            }
        }
    }
}

/**
 * R41: only while-in-use location, asked each time the map opens without it (not again on a rotation). The answer
 * restarts location; refused, the map works without the blue dot. A change in the system settings shows on return.
 */
@Composable
private fun rememberLocationPermission(onAnswered: () -> Unit): Boolean {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(hasLocationPermission(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val answered by rememberUpdatedState(onAnswered)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = hasLocationPermission(context)
        answered()
    }
    LaunchedEffect(Unit) {
        if (!allowed && !asked) {
            asked = true
            launcher.launch(LOCATION_PERMISSIONS)
        }
    }
    LifecycleResumeEffect(context) {
        allowed = hasLocationPermission(context)
        onPauseOrDispose { }
    }
    return allowed
}

private fun hasLocationPermission(context: Context) =
    LOCATION_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** The map's own buttons on the right: street names ("Aa"), zoom, and "center on me" (only with a position). */
@Composable
private fun MapButtons(
    labels: Boolean,
    onLabels: () -> Unit,
    houses: Boolean,
    onHouses: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onCenter: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MapButton(onLabels, if (labels) "Hide street names" else "Show street names", selected = labels, modifier = Modifier.testTag(MapTags.LABELS)) {
            Text("Aa", fontWeight = FontWeight.Bold)
        }
        MapButton(onHouses, if (houses) "Hide house numbers" else "Show house numbers", selected = houses, modifier = Modifier.testTag(MapTags.HOUSES)) {
            Text("123", fontWeight = FontWeight.Bold)
        }
        MapButton(onZoomIn, "Zoom in", modifier = Modifier.testTag(MapTags.ZOOM_IN)) { Text("+", style = MaterialTheme.typography.titleLarge) }
        MapButton(onZoomOut, "Zoom out", modifier = Modifier.testTag(MapTags.ZOOM_OUT)) { Text("−", style = MaterialTheme.typography.titleLarge) }
        MapButton(onCenter ?: {}, "Center on me", enabled = onCenter != null, modifier = Modifier.testTag(MapTags.CENTER_ON_ME)) {
            Icon(MyLocation, contentDescription = null)
        }
    }
}

@Composable
private fun MapButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (selected) FmxColors.Accent else FmxColors.Bar,
        contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
        modifier = modifier.size(44.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** Material's "my location" crosshair (the core icon set doesn't have it). */
private val MyLocation: ImageVector = ImageVector.Builder("MyLocation", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes(
            "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4zM20.94,11c-0.46,-4.17 -3.77,-7.48 -7.94,-7.94L13,1h-2v2.06" +
                "C6.83,3.52 3.52,6.83 3.06,11L1,11v2h2.06c0.46,4.17 3.77,7.48 7.94,7.94L11,23h2v-2.06c4.17,-0.46 7.48,-3.77 7.94,-7.94L23,13v-2h-2.06z" +
                "M12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z",
        ),
        fill = SolidColor(Color.Black),
    )
    .build()
