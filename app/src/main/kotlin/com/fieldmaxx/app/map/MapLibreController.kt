package com.fieldmaxx.app.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fieldmaxx.app.platform.Fix
import com.fieldmaxx.app.platform.Heading
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.model.AppConfig
import com.fieldmaxx.core.model.CrewPosition
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.naming.markerCode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.all
import org.maplibre.android.style.expressions.Expression.concat
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.exponential
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.product
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOffset
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point as GeoPoint

// Sources and layers, bottom to top. Text is drawn into images here (see drawImage): the raster-only style has no glyphs.
private const val L_BACKGROUND = "background"
private const val SRC_IMAGERY = "imagery"
private const val L_IMAGERY = "imagery"
private const val SRC_LABELS = "labels"
private const val L_LABELS = "labels"
private const val SRC_HOUSES = "houses"
private const val L_HOUSES = "houses"
private const val SRC_ME = "me"
private const val L_ME_ACCURACY = "me-accuracy"
private const val SRC_POINTS = "points"
private const val L_CLUSTERS = "clusters"
private const val L_CLUSTER_COUNTS = "cluster-counts"
private const val L_POINTS = "points"
private const val L_CODES = "codes"
private const val L_BADGES = "badges"
private const val SRC_DRAG = "drag"
private const val L_DRAG = "drag"
private const val L_DRAG_CODE = "drag-code"
private const val SRC_CREW = "crew"
private const val L_CREW = "crew"
private const val L_CREW_LABELS = "crew-labels"
private const val L_ME_PULSE = "me-pulse"
private const val L_ME_HEADING = "me-heading"
private const val L_ME_DOT = "me-dot"

// Feature properties.
private const val P_ID = "id"
private const val P_COLOR = "color"
private const val P_OPACITY = "opacity"
private const val P_ICON = "icon"
private const val P_PENDING = "pending"
private const val P_PAUSED = "paused"
private const val P_STATE = "state"
private const val P_ACCURACY = "accuracy"
private const val P_HEADING = "heading"
private const val P_BEAM = "beam"
private const val CLUSTER_COUNT = "point_count"

// Images, drawn on first use. A code, count or label image's id ends with its text.
private const val IMG_CODE = "fmx-code:"
private const val IMG_SHOT = "fmx-shot:"
private const val IMG_COUNT = "fmx-count:"
private const val IMG_LABEL = "fmx-label:"
private const val IMG_HOUSE = "fmx-house:"
private const val IMG_BADGE = "fmx-badge"
private const val IMG_BEAM = "fmx-beam"

// The binding colours come from FmxColors (ui/Theme.kt); MapLibre takes them as "#rrggbb" and an opacity.
private val ACCENT = FmxColors.Accent.hex()
private val INK = FmxColors.Bar.hex() // the bars' colour, opaque
private val SHOT = FmxColors.ShotMarker.hex()
private val SHOT_OPACITY = FmxColors.ShotMarker.alpha
private const val CREW_COLOR = "#f8fafc"
private const val LABELS_OPACITY = 0.9f
private const val TILE_SIZE = 256
private const val SOURCE_MAX_ZOOM = 19f // Esri's imagery stops around here; MapLibre over-zooms the last tiles beyond it
private const val CLUSTER_MAX_ZOOM = 17
private const val FIT_MAX_ZOOM = 18.0
private const val MARKER_RADIUS = 12f
private const val ANIMATE_MS = 400
private const val PULSE_MS = 2_000L
private const val PULSE_FRAME_MS = 80L
private const val TAP_DP = 16f
private const val GRAB_DP = 36f
private const val NO_ACCURACY_M = 2_000.0
private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
private const val TILE_WORLD_PX = 512.0 // MapLibre's world is 512 px wide at zoom 0
private const val AMBIENT_CACHE_BYTES = 256L * 1024 * 1024
private const val ESRI_IMAGERY_CREDIT = "Esri, Maxar, Earthstar Geographics, and the GIS User Community"
private const val ESRI_CREDIT = "Esri"

/** The plain style everything is added to: a dark background where no imagery has loaded (spec §6.8). */
private const val BASE_STYLE =
    """{"version":8,"sources":{},"layers":[{"id":"$L_BACKGROUND","type":"background","paint":{"background-color":"#020617"}}]}"""

/**
 * [MapController] on MapLibre Native, built by [rememberMapLibreController] and shown with [View]:
 * - the server's raster imagery, with the street-name overlay at 90% opacity above it;
 * - the points as one GeoJSON source, clustered up to zoom 17: circles in the type's colour (grey at 55% once shot,
 *   [FmxColors.ShotMarker]), the type's code on each (struck through once shot), and a badge on points with photos not in
 *   OneDrive yet;
 * - the crew as their own source, your position as a pulsing blue dot with its accuracy and heading.
 * Tiles once viewed stay in MapLibre's ambient cache, so viewed areas show without signal (spec §6.8).
 *
 * The camera moves only through [moveTo], [fitAll], a cluster tap (zooms into it) and the user's gestures (R38).
 * [camera] comes from a [CameraTracker] fed by MapLibre's camera listeners and each move's end; it is null until the
 * first move asked for is carried out, so the map's starting view (and gestures on it) never count (R61).
 */
@SuppressLint("ClickableViewAccessibility") // dragging a marker is a map gesture; the point sheet has the accessible move
class MapLibreController(context: Context) : MapController {
    private val density = context.resources.displayMetrics.density
    private val mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var shown = Lifecycle.State.INITIALIZED // how far the MapView has been brought along its lifecycle

    // The latest of everything, drawn once the style has loaded and on each change after.
    private var config: AppConfig? = null
    private var drawnConfig: AppConfig? = null
    private var points: List<Point> = emptyList()
    private var pending: Set<String> = emptySet()
    private var crew: List<CrewPosition> = emptyList()
    private var crewNow = 0L
    private var fix: Fix? = null
    private var heading: Heading? = null
    private var labelsVisible = true
    private var houseNumbers: List<HouseNumber> = emptyList()

    /** The latest camera move asked for while the map wasn't ready; it runs once the style is there. */
    private var waitingMove: ((MapLibreMap) -> Unit)? = null

    private val tracker = CameraTracker()
    override val camera: StateFlow<CameraView?> = tracker.camera

    /** The first move jumps there (the map was just opened); the ones after glide. */
    private var jumped = false
    private val taps = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val pointTaps: SharedFlow<String> = taps.asSharedFlow()

    private var drag: Drag? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pulsing = false
    private var pulseStart = 0L
    private val pulse = object : Runnable {
        override fun run() {
            val layer = style?.getLayerAs<CircleLayer>(L_ME_PULSE) ?: return
            val t = ((SystemClock.uptimeMillis() - pulseStart) % PULSE_MS) / PULSE_MS.toFloat()
            layer.setProperties(circleRadius(8f + 20f * t), circleOpacity(0.45f * (1f - t)))
            handler.postDelayed(this, PULSE_FRAME_MS)
        }
    }

    init {
        MapLibre.getInstance(context.applicationContext) // must come before any MapView
        keepViewedTiles(context.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .rotateGesturesEnabled(false) // north up: the saved view is centre and zoom only
            .tiltGesturesEnabled(false)
            .compassEnabled(false)
            .logoEnabled(false)
            .attributionGravity(Gravity.BOTTOM or Gravity.END)
            .attributionMargins(intArrayOf(0, 0, px(8f), px(120f))) // above the progress strip
            .minZoomPreference(MIN_ZOOM)
            .maxZoomPreference(MAX_ZOOM)
        mapView = MapView(context, options)
        mapView.addOnStyleImageMissingListener { id -> drawImage(id)?.let { style?.addImage(id, it) } }
        mapView.addOnCanRemoveUnusedStyleImageListener { id -> id.startsWith(IMG_LABEL) || id.startsWith(IMG_COUNT) || id.startsWith(IMG_HOUSE) }
        mapView.setOnTouchListener { _, e -> onTouch(e) }
        mapView.getMapAsync(::onMapReady)
    }

    /** The map, filling [modifier]. */
    @Composable
    fun View(modifier: Modifier = Modifier) {
        AndroidView(factory = { (mapView.parent as? ViewGroup)?.removeView(mapView); mapView }, modifier = modifier)
    }

    /** Brings the MapView to [target] one lifecycle step at a time; it is destroyed for good once it reaches DESTROYED. */
    fun lifecycleTo(target: Lifecycle.State) {
        while (shown != Lifecycle.State.DESTROYED && shown < target) {
            shown = when (shown) {
                Lifecycle.State.INITIALIZED -> Lifecycle.State.CREATED.also { mapView.onCreate(null) }
                Lifecycle.State.CREATED -> Lifecycle.State.STARTED.also { mapView.onStart() }
                else -> Lifecycle.State.RESUMED.also { mapView.onResume() }
            }
        }
        while (shown != Lifecycle.State.DESTROYED && shown > target) {
            shown = when (shown) {
                Lifecycle.State.RESUMED -> Lifecycle.State.STARTED.also { mapView.onPause() }
                Lifecycle.State.STARTED -> Lifecycle.State.CREATED.also { mapView.onStop() }
                Lifecycle.State.CREATED -> Lifecycle.State.DESTROYED.also { destroy(created = true) }
                else -> Lifecycle.State.DESTROYED.also { destroy(created = false) }
            }
        }
        updatePulse()
    }

    private fun destroy(created: Boolean) {
        handler.removeCallbacks(pulse)
        drag?.close?.invoke()
        if (created) mapView.onDestroy()
        map = null
        style = null
    }

    /** Your heading (true north once a fix is known), drawn as a beam from the blue dot. */
    fun setMyHeading(h: Heading?) {
        val old = heading
        // The sensor reports often: only a turn of a couple of degrees, or a change in its reliability, redraws.
        if (h != null && old != null && h.reliable == old.reliable && angleBetween(h.deg, old.deg) < 2.0) return
        heading = h
        style?.let(::drawMe)
    }

    override fun setConfig(c: AppConfig) {
        config = c
        style?.let(::drawConfig)
    }

    override fun setPoints(points: List<Point>, pendingPhotoPointIds: Set<String>) {
        this.points = points
        pending = pendingPhotoPointIds
        style?.let(::drawPoints)
    }

    override fun setCrew(crew: List<CrewPosition>, now: Long) {
        this.crew = crew
        crewNow = now
        style?.let(::drawCrew)
    }

    override fun setMyFix(f: Fix?) {
        fix = f
        style?.let(::drawMe)
        updatePulse()
    }

    override fun setLabelsVisible(v: Boolean) {
        labelsVisible = v
        style?.getLayer(L_LABELS)?.setProperties(visibility(if (v) Property.VISIBLE else Property.NONE))
    }

    override fun setHouseNumbers(items: List<HouseNumber>) {
        houseNumbers = items
        style?.let(::drawHouses)
    }

    override fun moveTo(v: CameraView) {
        tracker.asked(v) // at once, even while the map isn't ready: a second zoom step builds on this one
        whenPlaced { m -> go(m, CameraUpdateFactory.newLatLngZoom(LatLng(v.lat, v.lon), v.zoom), v) }
    }

    override fun fitAll(points: List<Point>) {
        if (points.isEmpty()) return
        whenPlaced { m ->
            val targets = points.map { LatLng(it.lat, it.lon) }
            val fitted = if (targets.size < 2) null else m.getCameraForLatLngBounds(LatLngBounds.Builder().includes(targets).build(), fitPadding())
            val target = fitted?.target ?: targets.first()
            val zoom = min(fitted?.zoom ?: FIT_MAX_ZOOM, FIT_MAX_ZOOM) // a tight group of points isn't shown closer than this
            val destination = CameraView(target.latitude, target.longitude, zoom)
            tracker.asked(destination)
            go(m, CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(target).zoom(zoom).build()), destination)
        }
    }

    /** Carries out a move asked for; the tracker hears how it ends (finished, or given up for a newer move or a gesture). */
    private fun go(m: MapLibreMap, update: CameraUpdate, destination: CameraView) {
        tracker.applied()
        val ended = object : MapLibreMap.CancelableCallback {
            override fun onFinish() = tracker.ended(destination, m.view() ?: destination)
            override fun onCancel() = tracker.ended(destination, m.view() ?: destination)
        }
        if (jumped) m.animateCamera(update, ANIMATE_MS, ended) else m.moveCamera(update, ended)
        jumped = true
    }

    override fun beginDrag(pointId: String): Flow<LatLon> = callbackFlow {
        val p = points.firstOrNull { it.id == pointId }
        if (p == null) {
            close()
            return@callbackFlow
        }
        drag?.close?.invoke() // one drag at a time
        val d = Drag(pointId, p.type, LatLon(p.lat, p.lon), emit = { trySend(it) }, close = { channel.close() })
        drag = d
        trySend(d.at)
        style?.let { drawPoints(it); drawDrag(it) }
        awaitClose {
            if (drag === d) {
                drag = null
                style?.let { drawPoints(it); drawDrag(it) }
            }
        }
    }

    override fun endDrag() {
        drag?.close?.invoke()
    }

    // ---- map setup ----

    private fun onMapReady(m: MapLibreMap) {
        if (shown == Lifecycle.State.DESTROYED) return
        map = m
        // Gestures before the first placement change nothing (R61): the opening move still runs, and nothing is reported.
        m.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) tracker.gestureStarted() }
        m.addOnCameraMoveListener { m.view()?.let(tracker::moved) }
        m.addOnCameraIdleListener { m.view()?.let(tracker::settled) }
        m.addOnMapClickListener(::onMapClick)
        m.setStyle(Style.Builder().fromJson(BASE_STYLE), ::onStyleLoaded)
    }

    private fun onStyleLoaded(s: Style) {
        if (shown == Lifecycle.State.DESTROYED) return
        style = s
        addSourcesAndLayers(s)
        drawConfig(s)
        drawHouses(s)
        drawPoints(s)
        drawDrag(s)
        drawCrew(s)
        drawMe(s)
        updatePulse()
        waitingMove?.let {
            waitingMove = null
            whenPlaced(it)
        }
    }

    /** Runs [move] once the style is there and the map has its size (fitting needs it); only the latest waits. */
    private fun whenPlaced(move: (MapLibreMap) -> Unit) {
        val m = map
        when {
            m == null || style == null -> waitingMove = move
            mapView.width == 0 || mapView.height == 0 -> mapView.doOnLayout { map?.let(move) }
            else -> move(m)
        }
    }

    private fun addSourcesAndLayers(s: Style) {
        val notCluster = not(has(CLUSTER_COUNT))
        val shotFade = get(P_OPACITY)
        s.addSource(GeoJsonSource(SRC_HOUSES))
        s.addSource(GeoJsonSource(SRC_ME))
        s.addSource(GeoJsonSource(SRC_POINTS, GeoJsonOptions().withCluster(true).withClusterMaxZoom(CLUSTER_MAX_ZOOM)))
        s.addSource(GeoJsonSource(SRC_DRAG))
        s.addSource(GeoJsonSource(SRC_CREW))

        s.addLayer(SymbolLayer(L_HOUSES, SRC_HOUSES).withProperties(iconImage(get(P_ICON))).also { it.minZoom = 17f })
        s.addLayer(
            CircleLayer(L_ME_ACCURACY, SRC_ME).withProperties(
                // P_ACCURACY is the radius at zoom 0; each zoom level doubles it, so the circle keeps its size on the ground.
                circleRadius(interpolate(exponential(2), zoom(), stop(0, product(get(P_ACCURACY), literal(1))), stop(24, product(get(P_ACCURACY), literal(2.0.pow(24)))))),
                circleColor(ACCENT),
                circleOpacity(0.15f),
                circleStrokeColor(ACCENT),
                circleStrokeOpacity(0.4f),
                circleStrokeWidth(1f),
            ),
        )
        s.addLayer(
            CircleLayer(L_CLUSTERS, SRC_POINTS).withFilter(has(CLUSTER_COUNT)).withProperties(
                circleColor(ACCENT),
                circleOpacity(0.9f),
                circleRadius(step(get(CLUSTER_COUNT), literal(16), stop(10, 20), stop(50, 24), stop(200, 28))),
                circleStrokeColor("#ffffff"),
                circleStrokeWidth(2f),
            ),
        )
        s.addLayer(
            SymbolLayer(L_CLUSTER_COUNTS, SRC_POINTS).withFilter(has(CLUSTER_COUNT)).withProperties(
                iconImage(concat(literal(IMG_COUNT), Expression.toString(get(CLUSTER_COUNT)))),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        s.addLayer(
            CircleLayer(L_POINTS, SRC_POINTS).withFilter(notCluster).withProperties(
                circleColor(toColor(get(P_COLOR))),
                circleOpacity(shotFade),
                circleRadius(MARKER_RADIUS),
                circleStrokeColor("#ffffff"),
                circleStrokeWidth(1.5f),
                circleStrokeOpacity(shotFade),
            ),
        )
        s.addLayer(
            SymbolLayer(L_CODES, SRC_POINTS).withFilter(notCluster).withProperties(
                iconImage(get(P_ICON)),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        s.addLayer(
            SymbolLayer(L_BADGES, SRC_POINTS).withFilter(all(notCluster, eq(get(P_PENDING), literal(true)))).withProperties(
                iconImage(IMG_BADGE),
                iconOffset(arrayOf(MARKER_RADIUS * 0.75f, -MARKER_RADIUS * 0.75f)),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        s.addLayer(
            CircleLayer(L_DRAG, SRC_DRAG).withProperties(
                circleColor(toColor(get(P_COLOR))),
                circleRadius(MARKER_RADIUS * 1.4f),
                circleStrokeColor(ACCENT),
                circleStrokeWidth(3f),
            ),
        )
        s.addLayer(SymbolLayer(L_DRAG_CODE, SRC_DRAG).withProperties(iconImage(get(P_ICON)), iconAllowOverlap(true), iconIgnorePlacement(true)))
        val crewFade = switchCase(eq(get(P_STATE), literal(CrewDot.FADED.name)), literal(0.4f), literal(1f))
        s.addLayer(
            CircleLayer(L_CREW, SRC_CREW).withProperties(
                circleColor(switchCase(eq(get(P_PAUSED), literal(true)), literal(SHOT), literal(CREW_COLOR))),
                circleOpacity(crewFade),
                circleStrokeOpacity(crewFade),
                circleRadius(7f),
                circleStrokeColor(INK),
                circleStrokeWidth(2f),
            ),
        )
        s.addLayer(
            SymbolLayer(L_CREW_LABELS, SRC_CREW).withProperties(
                iconImage(get(P_ICON)),
                iconAnchor(Property.ICON_ANCHOR_TOP),
                iconOffset(arrayOf(0f, 10f)),
                iconOpacity(crewFade),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        s.addLayer(CircleLayer(L_ME_PULSE, SRC_ME).withProperties(circleColor(ACCENT), circleRadius(8f), circleOpacity(0f)))
        s.addLayer(
            SymbolLayer(L_ME_HEADING, SRC_ME).withFilter(has(P_HEADING)).withProperties(
                iconImage(IMG_BEAM),
                iconRotate(get(P_HEADING)),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconOpacity(get(P_BEAM)),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        s.addLayer(
            CircleLayer(L_ME_DOT, SRC_ME).withProperties(
                circleColor(ACCENT),
                circleRadius(7f),
                circleStrokeColor("#ffffff"),
                circleStrokeWidth(2.5f),
            ),
        )
    }

    // ---- drawing ----

    /** The imagery and the street names on top; replaced only when the server's sources change. */
    private fun drawConfig(s: Style) {
        val c = config ?: return
        if (c == drawnConfig) return
        drawnConfig = c
        s.removeLayer(L_LABELS)
        s.removeLayer(L_IMAGERY)
        s.removeSource(SRC_LABELS)
        s.removeSource(SRC_IMAGERY)
        // The key belongs to the imagery service; the street names are a public Esri layer unless the admin changes it.
        val esri = { url: String, credit: String -> credit.takeIf { "arcgisonline.com" in url } }
        s.addSource(RasterSource(SRC_IMAGERY, tiles(tileUrl(c.imageryUrl, c.imageryKey), esri(c.imageryUrl, ESRI_IMAGERY_CREDIT)), TILE_SIZE))
        s.addSource(RasterSource(SRC_LABELS, tiles(c.labelsUrl, esri(c.labelsUrl, ESRI_CREDIT)), TILE_SIZE))
        s.addLayerAbove(RasterLayer(L_IMAGERY, SRC_IMAGERY), L_BACKGROUND)
        s.addLayerAbove(
            RasterLayer(L_LABELS, SRC_LABELS).withProperties(
                rasterOpacity(LABELS_OPACITY),
                visibility(if (labelsVisible) Property.VISIBLE else Property.NONE),
            ),
            L_IMAGERY,
        )
    }

    private fun tiles(url: String, credit: String?) = TileSet("2.1.0", url).apply {
        setMaxZoom(SOURCE_MAX_ZOOM)
        if (credit != null) attribution = credit
    }

    private fun drawPoints(s: Style) {
        val dragged = drag?.pointId
        val features = points.filter { it.id != dragged }.map { p ->
            val shot = isShot(p)
            feature(p.lat, p.lon) {
                addStringProperty(P_ID, p.id)
                addStringProperty(P_COLOR, if (shot) SHOT else typeColor(p.type))
                addNumberProperty(P_OPACITY, if (shot) SHOT_OPACITY else 1f)
                addStringProperty(P_ICON, (if (shot) IMG_SHOT else IMG_CODE) + markerCode(p.type))
                addBooleanProperty(P_PENDING, p.id in pending)
            }
        }
        s.getSourceAs<GeoJsonSource>(SRC_POINTS)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun drawDrag(s: Style) {
        val d = drag
        val features = if (d == null) emptyList() else listOf(
            feature(d.at.lat, d.at.lon) {
                addStringProperty(P_COLOR, typeColor(d.type))
                addStringProperty(P_ICON, IMG_CODE + markerCode(d.type))
            },
        )
        s.getSourceAs<GeoJsonSource>(SRC_DRAG)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun drawCrew(s: Style) {
        val features = crew.mapNotNull { c ->
            val state = crewDotState(c, crewNow)
            if (state == CrewDot.HIDDEN) return@mapNotNull null
            feature(c.lat, c.lon) {
                addStringProperty(P_STATE, state.name)
                addBooleanProperty(P_PAUSED, c.paused)
                addStringProperty(P_ICON, IMG_LABEL + crewLabel(c, crewNow))
            }
        }
        s.getSourceAs<GeoJsonSource>(SRC_CREW)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun drawMe(s: Style) {
        val f = fix
        val h = heading
        val features = if (f == null) emptyList() else listOf(
            feature(f.lat, f.lon) {
                val accuracy = if (f.accuracyM > NO_ACCURACY_M) 0.0 else f.accuracyM
                addNumberProperty(P_ACCURACY, accuracy * TILE_WORLD_PX / (EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(f.lat))))
                if (h != null) {
                    addNumberProperty(P_HEADING, h.deg)
                    addNumberProperty(P_BEAM, if (h.reliable) 0.6 else 0.25)
                }
            },
        )
        s.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun drawHouses(s: Style) {
        val features = houseNumbers.map { feature(it.lat, it.lon) { addStringProperty(P_ICON, IMG_HOUSE + it.text) } }
        s.getSourceAs<GeoJsonSource>(SRC_HOUSES)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private inline fun feature(lat: Double, lon: Double, properties: Feature.() -> Unit): Feature =
        Feature.fromGeometry(GeoPoint.fromLngLat(lon, lat)).apply(properties)

    /** The blue dot pulses while it shows and the map is on screen. */
    private fun updatePulse() {
        val want = fix != null && style != null && shown.isAtLeast(Lifecycle.State.STARTED)
        if (want == pulsing) return
        pulsing = want
        if (want) {
            pulseStart = SystemClock.uptimeMillis()
            handler.post(pulse)
        } else {
            handler.removeCallbacks(pulse)
            style?.getLayerAs<CircleLayer>(L_ME_PULSE)?.setProperties(circleOpacity(0f))
        }
    }

    // ---- touch ----

    /** A marker tap reports its point; a cluster tap zooms in until it splits (the user's own move). */
    private fun onMapClick(at: LatLng): Boolean {
        val m = map ?: return false
        val screen = m.projection.toScreenLocation(at)
        val r = TAP_DP * density
        val hits = m.queryRenderedFeatures(RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r), L_CODES, L_POINTS, L_CLUSTER_COUNTS, L_CLUSTERS)
        hits.firstOrNull { it.hasProperty(P_ID) }?.let {
            taps.tryEmit(it.getStringProperty(P_ID))
            return true
        }
        val cluster = hits.firstOrNull { it.hasProperty(CLUSTER_COUNT) } ?: return false
        val source = style?.getSourceAs<GeoJsonSource>(SRC_POINTS) ?: return false
        val center = (cluster.geometry() as? GeoPoint)?.let { LatLng(it.latitude(), it.longitude()) } ?: at
        val zoom = source.getClusterExpansionZoom(cluster).toDouble()
        val destination = CameraView(center.latitude, center.longitude, zoom)
        tracker.asked(destination)
        go(m, CameraUpdateFactory.newLatLngZoom(center, zoom), destination)
        return true
    }

    /** While a point is being moved, a touch on its marker drags it; anywhere else pans the map as usual. */
    private fun onTouch(e: MotionEvent): Boolean {
        val d = drag ?: return false
        val m = map ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val at = m.projection.toScreenLocation(LatLng(d.at.lat, d.at.lon))
                d.holding = hypot(e.x - at.x, e.y - at.y) <= GRAB_DP * density
            }
            MotionEvent.ACTION_MOVE -> if (d.holding) {
                val to = m.projection.fromScreenLocation(PointF(e.x, e.y))
                d.at = LatLon(to.latitude, to.longitude)
                style?.let(::drawDrag)
                d.emit(d.at)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (d.holding) {
                d.holding = false
                return true
            }
        }
        return d.holding
    }

    private class Drag(val pointId: String, val type: String, var at: LatLon, val emit: (LatLon) -> Unit, val close: () -> Unit) {
        var holding = false
    }

    // ---- images ----

    /** Draws the image a layer asked for by [id]; null for an id this map doesn't know. */
    private fun drawImage(id: String): Bitmap? = when {
        id.startsWith(IMG_CODE) -> textImage(id.removePrefix(IMG_CODE), 11f, android.graphics.Color.WHITE, struck = false, halo = true)
        id.startsWith(IMG_SHOT) -> textImage(id.removePrefix(IMG_SHOT), 11f, 0xFFE5E7EB.toInt(), struck = true, halo = false)
        id.startsWith(IMG_COUNT) -> textImage(id.removePrefix(IMG_COUNT), 12f, android.graphics.Color.WHITE, struck = false, halo = false)
        id.startsWith(IMG_LABEL) -> textImage(id.removePrefix(IMG_LABEL), 12f, android.graphics.Color.WHITE, struck = false, halo = true)
        id.startsWith(IMG_HOUSE) -> textImage(id.removePrefix(IMG_HOUSE), 10f, android.graphics.Color.WHITE, struck = false, halo = true)
        id == IMG_BADGE -> badgeImage()
        id == IMG_BEAM -> beamImage()
        else -> null
    }

    private fun textImage(text: String, sizeDp: Float, color: Int, struck: Boolean, halo: Boolean): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizeDp * density
            typeface = Typeface.DEFAULT_BOLD
            this.color = color
            textAlign = Paint.Align.CENTER
        }
        val pad = 3f * density
        val width = (paint.measureText(text) + 2 * pad).toInt().coerceAtLeast(1)
        val height = (paint.fontMetrics.let { it.descent - it.ascent } + 2 * pad).toInt().coerceAtLeast(1)
        val bitmap = newBitmap(width, height)
        val canvas = Canvas(bitmap)
        val baseline = pad - paint.fontMetrics.ascent
        if (halo) {
            val outline = Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.5f * density
                this.color = FmxColors.Bar.copy(alpha = 0.8f).toArgb()
            }
            canvas.drawText(text, width / 2f, baseline, outline)
        }
        canvas.drawText(text, width / 2f, baseline, paint)
        if (struck) {
            val y = baseline + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
            canvas.drawLine(pad / 2, y, width - pad / 2, y, Paint(paint).apply { strokeWidth = 1.5f * density })
        }
        return bitmap
    }

    private fun badgeImage(): Bitmap {
        val size = px(10f)
        val bitmap = newBitmap(size, size)
        val canvas = Canvas(bitmap)
        val r = size / 2f
        canvas.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FmxColors.Bar.copy(alpha = 1f).toArgb() })
        canvas.drawCircle(r, r, r - 1.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FmxColors.Warning.toArgb() })
        return bitmap
    }

    /** A wedge from the centre towards the top: rotated to the heading, the dot sits at its point. */
    private fun beamImage(): Bitmap {
        val size = px(64f)
        val bitmap = newBitmap(size, size)
        val c = size / 2f
        val path = android.graphics.Path().apply {
            moveTo(c, c)
            lineTo(c - size * 0.22f, 0f)
            lineTo(c + size * 0.22f, 0f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val accent = FmxColors.Accent.toArgb()
            shader = android.graphics.LinearGradient(c, c, c, 0f, accent, accent and 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawPath(path, paint)
        return bitmap
    }

    /** At the screen's density, so MapLibre shows it at the size it was drawn for. */
    private fun newBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { this.density = mapView.resources.displayMetrics.densityDpi }

    private fun px(dp: Float) = (dp * density).toInt()

    /** Leaves room for the top bar and chips, the progress strip and the side buttons, within what a landscape screen allows. */
    private fun fitPadding(): IntArray {
        val w = mapView.width
        val h = mapView.height
        return intArrayOf(min(px(32f), w / 10), min(px(170f), h * 3 / 10), min(px(64f), w / 6), min(px(150f), h / 4))
    }

    private fun angleBetween(a: Double, b: Double): Double = abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)

    private fun MapLibreMap.view(): CameraView? = cameraPosition.target?.let { CameraView(it.latitude, it.longitude, cameraPosition.zoom) }

    private companion object {
        const val LOG_TAG = "MapLibreController"

        /** Set once MapLibre took the cache size; until then each new map asks again. */
        @Volatile var cacheSet = false

        /** Viewed tiles are kept for work without signal (spec §6.8); bulk downloading isn't allowed for Esri imagery. */
        fun keepViewedTiles(context: Context) {
            if (cacheSet) return
            OfflineManager.getInstance(context).setMaximumAmbientCacheSize(
                AMBIENT_CACHE_BYTES,
                object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() {
                        cacheSet = true
                    }

                    override fun onError(message: String) {
                        Log.w(LOG_TAG, "Couldn't set the map's tile cache size (tried again with the next map): $message")
                    }
                },
            )
        }
    }
}

/**
 * A [MapLibreController] for this screen, following its lifecycle (and destroyed when the screen goes), with your
 * [heading] on the blue dot.
 */
@Composable
fun rememberMapLibreController(heading: StateFlow<Heading?>): MapLibreController {
    val context = LocalContext.current
    val controller = remember { MapLibreController(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller) {
        onDispose { controller.lifecycleTo(Lifecycle.State.DESTROYED) }
    }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event -> controller.lifecycleTo(event.targetState) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val h by heading.collectAsState()
    LaunchedEffect(controller, h) { controller.setMyHeading(h) }
    return controller
}

/** "#rrggbb", the colour without its alpha (an opacity goes separately). */
private fun Color.hex(): String = String.format(Locale.US, "#%06x", toArgb() and 0xFFFFFF)
