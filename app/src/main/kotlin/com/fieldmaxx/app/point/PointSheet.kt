package com.fieldmaxx.app.point

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.fieldmaxx.app.AppGraph
import com.fieldmaxx.app.map.MapController
import com.fieldmaxx.app.photos.PhotoView
import com.fieldmaxx.app.photos.photoViewsFor
import com.fieldmaxx.app.platform.Fix
import com.fieldmaxx.app.sync.refImageUrl
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.model.Attachment
import com.fieldmaxx.core.model.CantShootReason
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.PoleData
import com.fieldmaxx.core.model.User
import com.fieldmaxx.core.naming.isQueryLayer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Comment and pole data edits are saved once they have been quiet this long. */
internal const val EDIT_QUIET_MS = 500L

private const val NO_MAPS_APP = "No app on this phone can show directions."

/** What to photograph, by point type (spec §6.4). A type without an entry shows only its description from the file. */
val TYPE_INSTRUCTIONS: Map<String, String> = mapOf(POLE_TYPE to "Photograph the pole and the carrier equipment on it.")

/** Google Maps directions to the point (spec §6.4); 6 decimals, about 10 cm. */
fun navigateUri(lat: Double, lon: Double): Uri =
    Uri.parse(String.format(Locale.US, "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f", lat, lon))

internal fun newOpId(): String = UUID.randomUUID().toString()

object PointTags {
    const val TITLE = "point.title"
    const val CLOSE = "point.close"
    const val NAVIGATE = "point.navigate"
    const val MOVE = "point.move"
    const val MOVE_CONFIRM = "point.move.confirm"
    const val MOVE_CANCEL = "point.move.cancel"
    const val UNDO_MOVE = "point.undoMove"
    const val GPS = "point.gps"
    const val LINE_HEIGHT = "point.lineHeight"
    const val ANCHOR = "point.anchor"
    fun attachment(a: Attachment) = "point.attachment:${a.name}"
    fun tag(t: String) = "point.tag:$t"
    const val REFERENCE = "point.reference"
    fun refImage(refId: String) = "point.ref:$refId"
    const val USE_EXISTING = "point.useExisting"
    const val COMMENT = "point.comment"
    const val TAKE_PHOTO = "point.takePhoto"
    const val PICK_GALLERY = "point.pickGallery"
    const val FOLDER = "point.folder"
    fun folderChoice(path: String) = "point.folder:$path"
    const val DONE = "point.done"
    const val CANT_SHOOT = "point.cantShoot"
    fun reason(r: CantShootReason) = "point.reason:${r.name}"
    const val REASON_TEXT = "point.reason.text"
    const val CANT_SHOOT_SAVE = "point.cantShoot.save"
    const val REOPEN = "point.reopen"
    const val DELETE = "point.delete"
    const val DELETE_CONFIRM = "point.delete.confirm"
}

/** What the point sheet reads and does; [GraphPointDeps] in the app, a fake in tests. */
interface PointDeps {
    /** The job's points, deleted ones left out. */
    fun points(jobId: String): Flow<List<Point>>

    /** The point's photos as the sheet lists them: [photoViewsFor]. */
    fun photos(pointId: String): Flow<List<PhotoView>>

    /** How many of the point's photos are still only on this phone: the delete warning's count. */
    fun photosOnPhone(pointId: String): Flow<Int>

    val fixes: StateFlow<Fix?>

    /** The app's Coil loader: the signed-in client, and the disk cache that keeps reference images for work without signal. */
    val imageLoader: ImageLoader

    /** The server's address, where reference images come from. */
    fun server(): String

    suspend fun user(): User?

    /** Shows [op] on the phone at once and queues it for the server; it waits there while there is no signal. */
    suspend fun enqueue(op: Op)
}

/** [PointDeps] from the app's graph. */
class GraphPointDeps(private val graph: AppGraph) : PointDeps {
    override fun points(jobId: String): Flow<List<Point>> = graph.sync.points(jobId)
    override fun photos(pointId: String): Flow<List<PhotoView>> = photoViewsFor(pointId)
    override fun photosOnPhone(pointId: String): Flow<Int> = graph.db.localPhotos().observeOnPhoneCount(pointId)
    override val fixes: StateFlow<Fix?> get() = graph.location.fixes
    override val imageLoader: ImageLoader get() = graph.imageLoader
    override fun server(): String = graph.server.get()
    override suspend fun user(): User? = graph.sync.savedUser()
    override suspend fun enqueue(op: Op) = graph.sync.enqueue(op)
}

/**
 * The sheet's writes for one point, in the order they were asked (spec §6.4: every edit saves as you go). Comment and
 * pole data edits are written once they have been quiet for [EDIT_QUIET_MS], as one [Op.PointUpdate] holding only the
 * fields edited, so the crew's edits of the other field survive; the pole data goes whole (it is one field for
 * last-write-wins). Any other op is written at once, after the edits that wait. When the sheet goes, whatever still
 * waits is written at once: nothing typed is lost.
 */
internal class PointEditor(private val pointId: String) {
    private val lock = Any()
    private var pole: PoleData? = null // guarded by lock: not written yet
    private var comment: String? = null // guarded by lock
    private val ops = ArrayDeque<Op>() // guarded by lock
    private val wake = Channel<Unit>(Channel.CONFLATED)

    val poleWaiting: Boolean get() = synchronized(lock) { pole != null }
    val commentWaiting: Boolean get() = synchronized(lock) { comment != null }

    fun editPole(p: PoleData) = ask { pole = p }

    fun editComment(c: String) = ask { comment = c }

    /** Writes [op] once the edits before it are written. */
    fun send(op: Op) = ask { ops.addLast(op) }

    private inline fun ask(change: () -> Unit) {
        synchronized(lock) { change() }
        wake.trySend(Unit)
    }

    /** Writes through [enqueue] while the sheet is open; when it goes, what is left. */
    suspend fun run(enqueue: suspend (Op) -> Unit) {
        try {
            while (true) {
                wake.receive()
                // Edits alone wait until they've been quiet; an op goes at once.
                while (!opsWaiting()) {
                    withTimeoutOrNull(EDIT_QUIET_MS) { wake.receive() } ?: break
                }
                writeAll(enqueue)
            }
        } finally {
            withContext(NonCancellable) { writeAll(enqueue) }
        }
    }

    private fun opsWaiting(): Boolean = synchronized(lock) { ops.isNotEmpty() }

    /** Each write completes, even when the sheet goes while it is under way. */
    private suspend fun writeAll(enqueue: suspend (Op) -> Unit) {
        while (true) {
            val (update, next) = synchronized(lock) {
                val u = if (pole != null || comment != null) Op.PointUpdate(newOpId(), pointId, pole, comment) else null
                pole = null
                comment = null
                u to ops.removeFirstOrNull()
            }
            if (update == null && next == null) return
            withContext(NonCancellable) {
                update?.let { enqueue(it) }
                next?.let { enqueue(it) }
            }
        }
    }
}

private class Loaded(val point: Point?)

/**
 * The point screen (spec §6.4): a bottom sheet over the map for the point a marker tap selected. Top to bottom: the name
 * with Navigate, Move point and close; what to photograph and the point's description from the file; the GPS accuracy;
 * pole data (poles); the client query's reference images (query points); the comment; the folder its photos go to;
 * Done, Can't shoot or Reopen; and Delete where the user may (§6.5a). [onClose] runs once the point is gone too
 * (deleted here or by the crew).
 *
 * Every edit saves as you go ([PointEditor]): it shows at once, is queued for the crew, and waits on the phone while
 * there is no signal. The sheet never moves the camera (R38); Move point drags the marker through [controller].
 *
 * Hooks for later tasks (R45), hidden until set: [onTakePhoto] and [onPickFromGallery] (Task 27), [photoStrip] and
 * [onUseExistingPhotos] (Task 29). Use existing photos shows on query points, as §6.4 and R45 place it (§6.6.2 would
 * allow it on any point).
 */
@Composable
fun PointSheet(
    jobId: String,
    pointId: String,
    deps: PointDeps,
    controller: MapController,
    onClose: () -> Unit,
    onTakePhoto: ((Point) -> Unit)? = null,
    onPickFromGallery: ((Point) -> Unit)? = null,
    onUseExistingPhotos: ((Point) -> Unit)? = null,
    photoStrip: @Composable (point: Point, photos: List<PhotoView>) -> Unit = { _, _ -> },
) {
    // All of it belongs to this one point: another marker tapped gets a fresh sheet, once this one's edits are written.
    key(pointId) {
        val editor = remember(deps) { PointEditor(pointId) }
        LaunchedEffect(editor) { editor.run(deps::enqueue) }
        val move = remember(controller) { MoveFlow(pointId, controller) }
        val loaded by remember(deps, jobId) {
            deps.points(jobId).map { points -> points.firstOrNull { it.id == pointId } }.distinctUntilChanged().map(::Loaded)
        }.collectAsState(null, Dispatchers.Main.immediate)
        val close by rememberUpdatedState(onClose)
        when (val point = loaded?.point) {
            null -> if (loaded != null) LaunchedEffect(Unit) { close() } // deleted, here or by the crew
            else -> OpenSheet(point, deps, editor, move, onClose, onTakePhoto, onPickFromGallery, onUseExistingPhotos, photoStrip)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenSheet(
    point: Point,
    deps: PointDeps,
    editor: PointEditor,
    move: MoveFlow,
    onClose: () -> Unit,
    onTakePhoto: ((Point) -> Unit)?,
    onPickFromGallery: ((Point) -> Unit)?,
    onUseExistingPhotos: ((Point) -> Unit)?,
    photoStrip: @Composable (point: Point, photos: List<PhotoView>) -> Unit,
) {
    // Main-confined, as on the map: the sheet's state is written on the main thread wherever a value comes from.
    val main = Dispatchers.Main.immediate
    val photos by remember(deps, point.id) { deps.photos(point.id) }.collectAsState(emptyList(), main)
    val onPhone by remember(deps, point.id) { deps.photosOnPhone(point.id) }.collectAsState(0, main)
    val fix by deps.fixes.collectAsState(main)
    val user by produceState<User?>(null, deps) { value = deps.user() }
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }

    // The fields as edited here. The stored values, the crew's edits among them, show whenever nothing here waits to be saved.
    var comment by remember { mutableStateOf(point.comment) }
    var pole by remember { mutableStateOf(point.pole ?: PoleData()) }
    var heightText by remember { mutableStateOf(formatLineHeight(point.pole?.lineHeightFt)) }
    LaunchedEffect(point.comment) { if (!editor.commentWaiting) comment = point.comment }
    LaunchedEffect(point.pole) {
        if (editor.poleWaiting) return@LaunchedEffect
        val stored = point.pole ?: PoleData()
        pole = stored
        if (parseLineHeight(heightText) != LineHeight.Valid(stored.lineHeightFt)) heightText = formatLineHeight(stored.lineHeightFt)
    }

    BackHandler(enabled = move.dragging, onBack = move::cancel)
    move.undo?.let { u ->
        LaunchedEffect(u) {
            delay(UNDO_MOVE_MS)
            move.expire(u)
        }
    }
    if (move.dragging) {
        LaunchedEffect(move) { move.follow() }
        MoveBar(move.canConfirm, onCancel = move::cancel, onConfirm = { move.confirm { editor.send(it) } })
        return
    }

    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hideThen: (() -> Unit) -> Unit = { after -> scope.launch { sheet.hide() }.invokeOnCompletion { after() } }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(
                point,
                onNavigate = { problem = navigate(context, point) },
                onMove = { hideThen(move::start) },
                onClose = { hideThen(onClose) },
            )
            move.undo?.let { UndoMoveRow(point.label) { move.undoMove { editor.send(it) } } }
            (move.problem ?: problem)?.let { Text(it, color = FmxColors.Warning) }

            TYPE_INSTRUCTIONS[point.type]?.let { Text(it) }
            if (point.description.isNotBlank()) {
                Text(point.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GpsBadgeView(gpsBadge(fix))

            if (point.type == POLE_TYPE) {
                PoleDataForm(
                    pole,
                    heightText,
                    onHeightText = { text ->
                        heightText = text
                        val h = parseLineHeight(text)
                        if (h is LineHeight.Valid && h.ft != pole.lineHeightFt) {
                            pole = pole.copy(lineHeightFt = h.ft)
                            editor.editPole(pole)
                        }
                    },
                    onChange = { p ->
                        pole = p
                        editor.editPole(p)
                    },
                )
            }
            if (isQueryLayer(point.layer)) {
                val server = deps.server()
                QueryReference(point.refImageIds.map { RefImage(it, refImageUrl(server, point.jobId, it)) }, deps.imageLoader)
                onUseExistingPhotos?.let { use ->
                    OutlinedButton(onClick = { use(point) }, modifier = Modifier.testTag(PointTags.USE_EXISTING)) { Text("Use existing photos") }
                }
            }

            OutlinedTextField(
                value = comment,
                onValueChange = {
                    comment = it
                    editor.editComment(it)
                },
                label = { Text("Comment") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag(PointTags.COMMENT),
            )

            if (onTakePhoto != null || onPickFromGallery != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onTakePhoto?.let { take -> Button(onClick = { take(point) }, modifier = Modifier.testTag(PointTags.TAKE_PHOTO)) { Text("Take photo") } }
                    onPickFromGallery?.let { pick ->
                        OutlinedButton(onClick = { pick(point) }, modifier = Modifier.testTag(PointTags.PICK_GALLERY)) { Text("Pick from gallery") }
                    }
                }
            }
            photoStrip(point, photos)

            FolderLine(point, onPick = { editor.send(Op.PointSetFolder(newOpId(), point.id, it)) })
            CloseControls(
                point,
                hasPhotos = photos.isNotEmpty(),
                onDone = { editor.send(Op.PointClose(newOpId(), point.id, photos.map { it.id })) }, // R48
                onCantShoot = { reason, text -> editor.send(Op.PointCantShoot(newOpId(), point.id, reason, text)) },
                onReopen = { editor.send(Op.PointReopen(newOpId(), point.id)) },
            )

            val me = user
            if (me != null && canDeletePoint(me, photos)) {
                DeletePoint(point.label, onPhone) {
                    // R67: only the delete is queued; its photos stay on the phone until the server accepts it.
                    editor.send(Op.PointDelete(newOpId(), point.id))
                    hideThen(onClose)
                }
            }
        }
    }
}

@Composable
private fun Header(point: Point, onNavigate: () -> Unit, onMove: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(point.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).testTag(PointTags.TITLE))
        IconButton(onClick = onClose, modifier = Modifier.testTag(PointTags.CLOSE)) { Icon(Icons.Default.Close, contentDescription = "Close") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onNavigate, modifier = Modifier.testTag(PointTags.NAVIGATE)) { Text("Navigate") }
        OutlinedButton(onClick = onMove, modifier = Modifier.testTag(PointTags.MOVE)) { Text("Move point") }
    }
}

/** Opens Google Maps' directions to [p]; what to tell the tech when no app on the phone can show them. */
private fun navigate(context: Context, p: Point): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, navigateUri(p.lat, p.lon)))
    null
} catch (e: ActivityNotFoundException) {
    NO_MAPS_APP
}
