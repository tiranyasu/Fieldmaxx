package com.fieldmaxx.app.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import com.fieldmaxx.app.photos.PickedMedia
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one-line reason given before the app asks to read photo locations (R52). */
const val MEDIA_LOCATION_REASON = "so gallery photos keep the spot they were taken"

object GalleryTags {
    const val REASON = "gallery.reason"
    const val REASON_OK = "gallery.reason.ok"
}

/** "Pick from gallery" for a point (spec §6.4): the Android photo picker, then the photos copied into app storage. */
fun interface GalleryPick {
    fun pick(jobId: String, pointId: String)
}

/**
 * The photo picker, images only and several at once ([PickPhotos]); each picked photo is copied into app storage as the
 * point's ([com.fieldmaxx.app.photos.LocalPhotos.saveFromGallery]) and a short message says how it went. The copies finish
 * even if the screen goes meanwhile.
 *
 * R52: before the first pick ever, the app asks once to read the locations stored in photos, after a one-line reason;
 * whatever the answer, the picker opens next, and it isn't asked again. Without that permission the photos' locations are
 * hidden and the phone's fix stands in.
 */
@Composable
fun rememberGalleryPick(deps: CameraDeps): GalleryPick {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentDeps by rememberUpdatedState(deps)
    // The point being picked for: kept across a rotation or the process going while the picker is open.
    var forJob by rememberSaveable { mutableStateOf<String?>(null) }
    var forPoint by rememberSaveable { mutableStateOf<String?>(null) }
    var explaining by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(PickPhotos { hasMediaLocation(context) }) { uris ->
        val jobId = forJob
        val pointId = forPoint
        if (jobId != null && pointId != null && uris.isNotEmpty()) {
            scope.launch(Dispatchers.Main.immediate) { withContext(NonCancellable) { addPicked(context.applicationContext, currentDeps, jobId, pointId, uris) } }
        }
    }
    val openPicker = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { openPicker() }

    if (explaining) {
        AlertDialog(
            onDismissRequest = {
                explaining = false
                openPicker() // not asked: the photos keep the phone's position this time, and the next pick asks
            },
            text = { Text("Fieldmaxx will ask to read photo locations, $MEDIA_LOCATION_REASON.", modifier = Modifier.testTag(GalleryTags.REASON)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        explaining = false
                        markMediaLocationAsked(context)
                        permission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    },
                    modifier = Modifier.testTag(GalleryTags.REASON_OK),
                ) { Text("OK") }
            },
        )
    }

    return remember(picker) {
        GalleryPick { jobId, pointId ->
            forJob = jobId
            forPoint = pointId
            if (shouldAskMediaLocation(context)) explaining = true else picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        }
    }
}

/**
 * [PickMultipleVisualMedia][ActivityResultContracts.PickMultipleVisualMedia]; while the app may read photo locations it
 * also asks the picker to keep them. Newer pickers hide them unless asked (MediaStore's
 * EXTRA_REQUEST_LOCATION_METADATA_ACCESS, newer than compileSdk 35); older ones ignore the extra.
 */
private class PickPhotos(private val withLocation: () -> Boolean) : ActivityResultContracts.PickMultipleVisualMedia() {
    override fun createIntent(context: Context, input: PickVisualMediaRequest): Intent =
        super.createIntent(context, input).apply { if (withLocation()) putExtra(EXTRA_REQUEST_LOCATION_METADATA_ACCESS, true) }
}

private const val EXTRA_REQUEST_LOCATION_METADATA_ACCESS = "android.provider.extra.REQUEST_LOCATION_METADATA_ACCESS"

private const val PREFS = "gallery_pick"
private const val ASKED = "media_location_asked"
private const val TAG = "GalleryPick"

private fun mediaLocationGranted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Android 10+ hides the locations in photos from an app without ACCESS_MEDIA_LOCATION; Android 9 hides nothing. */
private fun hasMediaLocation(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mediaLocationGranted(context)

private fun shouldAskMediaLocation(context: Context) =
    !hasMediaLocation(context) && !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

private fun markMediaLocationAsked(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
}

private suspend fun addPicked(context: Context, deps: CameraDeps, jobId: String, pointId: String, uris: List<Uri>) {
    val target = deps.target(jobId, pointId).first()
    if (target == null) {
        Toast.makeText(context, "This point was deleted: the photos weren't added.", Toast.LENGTH_LONG).show()
        return
    }
    var added = 0
    for (uri in uris) {
        try {
            deps.photos.saveFromGallery(jobId, pointId, uri, target.jobName, target.pointLabel, deps.fixes.value)
            added++
        } catch (e: Exception) {
            Log.w(TAG, "A picked photo couldn't be added to ${target.pointLabel}", e)
        }
    }
    Toast.makeText(context, pickedMessage(target.pointLabel, added, failed = uris.size - added), Toast.LENGTH_LONG).show()
}

private fun photos(n: Int) = if (n == 1) "1 photo" else "$n photos"

private fun pickedMessage(label: String, added: Int, failed: Int): String = when {
    failed == 0 -> "Added ${photos(added)} to $label."
    added == 0 -> "The ${if (failed == 1) "photo" else "photos"} couldn't be read: nothing was added."
    else -> "Added ${photos(added)} to $label. ${photos(failed).replaceFirstChar(Char::uppercase)} couldn't be read."
}

/**
 * [PickedMedia] through the phone's content resolver. With the permission (R52), a media uri is opened as its original,
 * location included ([MediaStore.setRequireOriginal]); a uri that can't give its original gives the photo as it is.
 */
class ContentPickedMedia internal constructor(
    private val sdk: Int,
    private val hasPermission: () -> Boolean,
    private val openStream: (Uri) -> InputStream?,
) : PickedMedia {
    constructor(context: Context) : this(context.applicationContext, Build.VERSION.SDK_INT)

    private constructor(app: Context, sdk: Int) : this(sdk, { mediaLocationGranted(app) }, app.contentResolver::openInputStream)

    override fun locationAllowed(): Boolean = sdk < Build.VERSION_CODES.Q || hasPermission()

    @SuppressLint("NewApi") // sdk is Build.VERSION.SDK_INT, as the public constructor gives it
    override fun open(uri: Uri): InputStream {
        if (sdk >= Build.VERSION_CODES.Q && hasPermission() && uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
            try {
                openStream(MediaStore.setRequireOriginal(uri))?.let { return it }
            } catch (e: UnsupportedOperationException) {
                Log.i(TAG, "No original for $uri", e)
            } catch (e: SecurityException) {
                Log.i(TAG, "No original for $uri", e)
            } catch (e: FileNotFoundException) {
                Log.i(TAG, "No original for $uri", e)
            }
        }
        return openStream(uri) ?: throw FileNotFoundException("The photo can't be read")
    }
}
