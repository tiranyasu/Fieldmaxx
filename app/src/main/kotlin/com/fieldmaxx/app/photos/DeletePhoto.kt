package com.fieldmaxx.app.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.LocalPhotoEntity
import com.fieldmaxx.app.data.toPhoto
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What removing a photo does (R64): before OneDrive it is deleted; once there it is only hidden, and stays in OneDrive. */
enum class Removal { DELETE, HIDE }

/**
 * Who may remove a photo (spec §6.6, R64, ledger R14): the tech who took it, before OneDrive (a delete) and after (it only
 * hides); an admin, always. A photo whose tech isn't known is for admins only. The server checks the same (photos/PhotoService.kt).
 */
fun canDeletePhoto(user: User, p: PhotoView): Boolean = user.role == Role.admin || (p.ownerId.isNotEmpty() && p.ownerId == user.id)

fun removalOf(p: PhotoView): Removal = if (p.status == PhotoUiStatus.IN_ONEDRIVE) Removal.HIDE else Removal.DELETE

/** The confirmation's title, its lines and its button. */
data class RemovalPrompt(val title: String, val lines: List<String>, val confirm: String)

/**
 * What the tech is told before a photo goes. Hidden, its file stays in OneDrive (R64: nothing there is ever deleted). Deleted
 * once the server named it (R84), either side's chunked upload may already have made SharePoint's empty file under that
 * name, which the link can't delete.
 */
fun removalPrompt(p: PhotoView): RemovalPrompt = when (removalOf(p)) {
    Removal.HIDE -> RemovalPrompt(
        "Hide this photo?",
        listOf(
            "The file stays in OneDrive: the app never deletes anything there. Remove it there by hand if it shouldn't be there.",
            "The point no longer lists it, and who hid it and when is recorded.",
        ),
        "Hide",
    )
    Removal.DELETE -> RemovalPrompt(
        "Delete this photo?",
        listOfNotNull(
            if (p.onThisPhone) "It is deleted from this phone and won't go to OneDrive." else "It won't go to OneDrive.",
            p.fileName?.let { "An empty file named $it may stay in OneDrive. Remove it there by hand." },
        ),
        "Delete",
    )
}

/**
 * Removes a photo as [removalOf] its current rows says (R64), never touching OneDrive:
 * - in OneDrive (this phone saw it there, or the server did): only hidden, by [Op.PhotoRemove]; this phone's copies go
 *   with the usual cleanup;
 * - before OneDrive: [Op.PhotoRemove] first when the server knows the photo, so that it never delivers it from its backup,
 *   then this phone's copy goes through [discard] (the pipeline's: its work stopped, `meta.json` first, then its folder,
 *   then its row, so that a kill in between can't let the orphan sweep bring it back, Task 28).
 *
 * Once asked, it finishes even if the screen goes. Null when the photo is gone already.
 */
class PhotoRemover(
    private val db: AppDatabase,
    private val enqueue: suspend (Op) -> Unit,
    private val discard: suspend (LocalPhotoEntity) -> Unit,
) {
    suspend fun remove(photoId: String): Removal? = withContext(NonCancellable) {
        val local = db.localPhotos().get(photoId)
        val server = db.photos().get(photoId)?.toPhoto()
        val hidden = server?.status == PhotoStatus.removed
        if (local == null && (server == null || hidden)) return@withContext null
        val known = server != null || local?.state == REGISTERED || local?.state == CONFIRMED
        if (known && !hidden) enqueue(Op.PhotoRemove(UUID.randomUUID().toString(), photoId))
        if (local?.state == CONFIRMED || server?.delivered() == true) return@withContext Removal.HIDE
        local?.let { discard(it) }
        Removal.DELETE
    }
}

/** "Delete photo" (or "Hide photo" once in OneDrive) and its confirmation; [onConfirm] removes it. */
@Composable
internal fun RemovePhoto(p: PhotoView, onConfirm: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    val danger = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
    TextButton(onClick = { asking = true }, colors = danger, modifier = Modifier.testTag(PhotoTags.REMOVE)) {
        Icon(Icons.Default.Delete, contentDescription = null)
        Text(if (removalOf(p) == Removal.HIDE) "Hide photo" else "Delete photo")
    }
    if (asking) {
        val prompt = removalPrompt(p)
        val deleting = removalOf(p) == Removal.DELETE
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(prompt.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // R84's empty-file sentence stands out.
                    prompt.lines.forEachIndexed { i, line -> Text(line, color = if (deleting && i > 0) FmxColors.Warning else Color.Unspecified) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        onConfirm()
                    },
                    colors = danger,
                    modifier = Modifier.testTag(PhotoTags.REMOVE_CONFIRM),
                ) { Text(prompt.confirm) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}
