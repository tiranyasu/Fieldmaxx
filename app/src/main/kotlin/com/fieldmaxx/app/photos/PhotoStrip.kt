package com.fieldmaxx.app.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.fieldmaxx.app.AppGraph
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.User
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

private val TILE = 96.dp
private val TILE_SHAPE = RoundedCornerShape(8.dp)

object PhotoTags {
    fun tile(id: String) = "photo.tile:$id"
    fun status(id: String) = "photo.status:$id"
    fun shield(id: String) = "photo.shield:$id"
    fun reuse(id: String) = "photo.reuse:$id"
    const val DETAIL = "photo.detail"
    const val REASON = "photo.reason"
    const val RETRY = "photo.retry"
    const val REMOVE = "photo.remove"
    const val REMOVE_CONFIRM = "photo.remove.confirm"
}

/** What the strip reads and does; [GraphPhotoStripDeps] in the app, a fake in tests. */
interface PhotoStripDeps {
    /** The app's Coil loader: the server's thumbnails come through the signed-in client. */
    val imageLoader: ImageLoader

    /** The signed-in user, who may remove their own photos ([canDeletePhoto]). */
    suspend fun user(): User?

    /** "Retry now" (spec §6.6): this phone's stuck photo is tried again at once. */
    fun retryNow(localPhotoId: String)

    /** Deletes the photo, or once it is in OneDrive hides it (R64). */
    suspend fun remove(photoId: String)
}

/** [PhotoStripDeps] from the app's graph: the photo pipeline's "Retry now", and [PhotoRemover] with the pipeline's discard. */
class GraphPhotoStripDeps(private val graph: AppGraph) : PhotoStripDeps {
    override val imageLoader: ImageLoader get() = graph.imageLoader

    override suspend fun user(): User? = graph.sync.savedUser()

    override fun retryNow(localPhotoId: String) {
        graph.photos.retryNow(localPhotoId)
    }

    override suspend fun remove(photoId: String) {
        PhotoRemover(graph.db, graph.sync::enqueue, graph.photos::discard).remove(photoId)
    }
}

/**
 * The point's photo strip (spec §6.4, §6.6.2), the point sheet's hook (R45): a thumbnail per photo with its status, ⏳ on
 * this phone → ⬆ uploading → ✓ in OneDrive, or ⚠ stuck, and a small shield once the server's backup is in place. A
 * reused photo says where it came from. Tapping a photo shows its details: a stuck one's reason, with "Retry now" when
 * this phone holds it; where it is in OneDrive; and Delete (or Hide, once in OneDrive) for whoever may ([canDeletePhoto]).
 */
@Composable
fun PhotoStrip(point: Point, photos: List<PhotoView>, deps: PhotoStripDeps) {
    if (photos.isEmpty()) return
    var selected by remember(point.id) { mutableStateOf<String?>(null) }
    val user by produceState<User?>(null, deps) { value = deps.user() }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in photos) {
                key(p.id) { Tile(p, p.id == selected, deps.imageLoader) { selected = if (selected == p.id) null else p.id } }
            }
        }
        photos.firstOrNull { it.id == selected }?.let { p ->
            key(p.id) {
                Details(p, user, onRetry = { deps.retryNow(p.id) }) {
                    selected = null
                    // Started at once, and it finishes even if the strip goes meanwhile ([PhotoRemover]).
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { deps.remove(p.id) }
                }
            }
        }
    }
}

@Composable
private fun Tile(p: PhotoView, selected: Boolean, imageLoader: ImageLoader, onClick: () -> Unit) {
    Column(Modifier.width(TILE), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(TILE)) {
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant, TILE_SHAPE)
                    .border(2.dp, if (selected) FmxColors.Accent else Color.Transparent, TILE_SHAPE)
                    .clickable(onClick = onClick)
                    .testTag(PhotoTags.tile(p.id)),
            ) {
                AsyncImage(
                    model = p.thumb,
                    contentDescription = "Photo",
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                )
            }
            // Marks over the photo, outside its clickable box so each keeps its own semantics; taps go through them.
            if (p.backedUp) Mark(SHIELD, "Backed up on the server", FmxColors.Bar, Modifier.align(Alignment.TopStart).testTag(PhotoTags.shield(p.id)))
            Mark(p.status.glyph(), p.status.words(), statusColor(p.status), Modifier.align(Alignment.BottomEnd).testTag(PhotoTags.status(p.id)))
        }
        p.reuseNote?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(PhotoTags.reuse(p.id)),
            )
        }
    }
}

@Composable
private fun Mark(text: String, description: String, background: Color, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.padding(4.dp).background(background, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
            .semantics { contentDescription = description },
    )
}

private fun statusColor(s: PhotoUiStatus): Color = when (s) {
    PhotoUiStatus.ON_PHONE -> FmxColors.Bar
    PhotoUiStatus.UPLOADING -> FmxColors.Accent
    PhotoUiStatus.IN_ONEDRIVE -> FmxColors.Done.copy(alpha = 0.9f)
    PhotoUiStatus.STUCK -> FmxColors.Warning.copy(alpha = 0.9f)
}

/** The tapped photo: its status and, when stuck, why (spec §6.6.2: "tap for reason + Retry now"). */
@Composable
private fun Details(p: PhotoView, user: User?, onRetry: () -> Unit, onRemove: () -> Unit) {
    var retried by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, TILE_SHAPE).padding(12.dp).testTag(PhotoTags.DETAIL),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("${p.status.glyph()} ${p.status.words()}", style = MaterialTheme.typography.titleSmall)
        if (p.status == PhotoUiStatus.STUCK) {
            Text(p.error ?: "No reason was recorded", color = FmxColors.Warning, modifier = Modifier.testTag(PhotoTags.REASON))
            Text("It is tried again every 30 minutes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p.onThisPhone) {
                OutlinedButton(
                    onClick = {
                        onRetry()
                        retried = true
                    },
                    modifier = Modifier.testTag(PhotoTags.RETRY),
                ) { Text("Retry now") }
                if (retried) Text("Trying again now", style = MaterialTheme.typography.bodySmall)
            }
        }
        p.reuseNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (p.backedUp) Text("$SHIELD Backed up on the server", style = MaterialTheme.typography.bodySmall)
        p.destPath?.let { Text("Field Notes/$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (user != null && canDeletePhoto(user, p)) RemovePhoto(p, onConfirm = onRemove)
    }
}
