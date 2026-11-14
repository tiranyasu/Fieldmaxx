package com.fieldmaxx.app.point

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.photos.PhotoUiStatus
import com.fieldmaxx.app.photos.PhotoView
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Spec §6.5a, as the server decides it (ops/Ops.kt): anyone while none of the point's photos reached OneDrive, then admins only. */
fun canDeletePoint(user: User, photos: List<PhotoView>): Boolean =
    user.role == Role.admin || photos.none { it.status == PhotoUiStatus.IN_ONEDRIVE }

/** "2 photos on this phone will be discarded"; null when there are none to discard. */
internal fun discardWarning(onPhone: Int): String? = when {
    onPhone <= 0 -> null
    onPhone == 1 -> "1 photo on this phone will be discarded"
    else -> "$onPhone photos on this phone will be discarded"
}

/**
 * R46: deleting a point discards its photos still on this phone, their rows and then their files. Photos OneDrive has
 * confirmed are left alone. Rows go first, in one transaction with the read, so the photo pipeline finds a vanished row
 * (and treats it as finished) rather than a row whose file is gone.
 */
suspend fun discardPointPhotos(db: AppDatabase, pointId: String) {
    val gone = db.withTransaction {
        db.localPhotos().onPhoneForPoint(pointId).also { rows -> if (rows.isNotEmpty()) db.localPhotos().delete(rows.map { it.id }) }
    }
    withContext(Dispatchers.IO) {
        for (p in gone) {
            File(p.originalPath).delete()
            p.stampedPath?.let { File(it).delete() }
        }
    }
}

/**
 * "Delete point" with its warning (spec §6.5a): the point goes for the whole crew (soft: an admin can restore it), and
 * [onPhone] photos still on this phone are discarded with it. The caller shows it only where [canDeletePoint].
 */
@Composable
internal fun DeletePoint(label: String, onPhone: Int, onConfirm: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    TextButton(
        onClick = { asking = true },
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.testTag(PointTags.DELETE),
    ) {
        Icon(Icons.Default.Delete, contentDescription = null)
        Text("Delete point")
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Delete $label?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("It goes for the whole crew. An admin can restore it.")
                    discardWarning(onPhone)?.let { Text(it, color = FmxColors.Warning) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        onConfirm()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag(PointTags.DELETE_CONFIRM),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}
