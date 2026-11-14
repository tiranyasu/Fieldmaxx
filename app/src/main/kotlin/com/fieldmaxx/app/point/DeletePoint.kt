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
import com.fieldmaxx.app.photos.PhotoUiStatus
import com.fieldmaxx.app.photos.PhotoView
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User

/**
 * Spec §6.5a, as the server decides it (ops/Ops.kt): anyone while none of the point's photos reached OneDrive, then admins
 * only. [hiddenDelivered]: one that reached OneDrive was hidden since, so [photos] no longer lists it, and it still counts.
 */
fun canDeletePoint(user: User, photos: List<PhotoView>, hiddenDelivered: Boolean = false): Boolean =
    user.role == Role.admin || (!hiddenDelivered && photos.none { it.status == PhotoUiStatus.IN_ONEDRIVE })

/** "2 photos on this phone will be discarded"; null when there are none to discard. */
internal fun discardWarning(onPhone: Int): String? = when {
    onPhone <= 0 -> null
    onPhone == 1 -> "1 photo on this phone will be discarded"
    else -> "$onPhone photos on this phone will be discarded"
}

/**
 * "Delete point" with its warning (spec §6.5a): the point goes for the whole crew (soft: an admin can restore it), and
 * the [onPhone] photos still on this phone are discarded with it. The caller shows it only where [canDeletePoint].
 *
 * Confirming only queues the delete (R67). The photos stay on the phone until the server accepts it: the server may
 * still refuse, since a photo of the point from another phone can have reached OneDrive, and then the point comes back
 * with them. The photo pipeline holds them meanwhile and discards them once the delete is settled (Task 28).
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
