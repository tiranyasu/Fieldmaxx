package com.fieldmaxx.app.photos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * A photo picked from the gallery, as the phone's storage hands it over. Android may have hidden its location (zeroed
 * it), unless the app may read photo locations or the photo picker asked the tech (R52a); [LocalPhotos] takes whatever
 * valid position it carries.
 */
fun interface PickedMedia {
    fun open(uri: Uri): InputStream
}

/** R52: Android 9 hides no locations in photos; Android 10+ hides them unless the app holds ACCESS_MEDIA_LOCATION. */
fun mediaLocationAllowed(context: Context): Boolean = mediaLocationAllowed(Build.VERSION.SDK_INT, mediaLocationGranted(context))

internal fun mediaLocationAllowed(sdk: Int, granted: Boolean): Boolean = sdk < Build.VERSION_CODES.Q || granted

private fun mediaLocationGranted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * [PickedMedia] through the phone's content resolver. Where Android 10+ would hide the location but the app may read it,
 * a media uri (the photo picker's, or MediaStore's) is opened as its original, location included
 * ([MediaStore.setRequireOriginal]); a uri that can't give its original gives the photo as it is.
 */
class ContentPickedMedia internal constructor(
    private val sdk: Int,
    private val hasPermission: () -> Boolean,
    private val openStream: (Uri) -> InputStream?,
) : PickedMedia {
    constructor(context: Context) : this(context.applicationContext, Build.VERSION.SDK_INT)

    private constructor(app: Context, sdk: Int) : this(sdk, { mediaLocationGranted(app) }, app.contentResolver::openInputStream)

    @SuppressLint("NewApi") // the original exists from Android 10 (sdk is Build.VERSION.SDK_INT, as the public constructor gives it)
    override fun open(uri: Uri): InputStream {
        val hidden = sdk >= Build.VERSION_CODES.Q
        if (hidden && mediaLocationAllowed(sdk, hasPermission()) && uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
            // Refused with UnsupportedOperationException or SecurityException where the uri can't give it, or not found.
            try {
                openStream(MediaStore.setRequireOriginal(uri))?.let { return it }
            } catch (e: RuntimeException) {
                Log.i(TAG, "No original for $uri", e)
            } catch (e: FileNotFoundException) {
                Log.i(TAG, "No original for $uri", e)
            }
        }
        return openStream(uri) ?: throw FileNotFoundException("The photo can't be read")
    }

    private companion object {
        const val TAG = "PickedMedia"
    }
}
