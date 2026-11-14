package com.fieldmaxx.app.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fieldmaxx.app.photos.CaptureMeta
import com.fieldmaxx.app.photos.LocalPhotos
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Takes one full-resolution photo into [to]: [CameraXCaptureController] in the app (ImageCapture, CAPTURE_MODE_MAXIMIZE_QUALITY), a fake in tests. */
interface CaptureController {
    suspend fun capture(to: File)
}

/**
 * The camera screen's shutter for one point: several photos in a row, each saved through [photos]
 * ([LocalPhotos.saveCaptured]) with the meta read as it was pressed. Once pressed, a photo is saved even if the screen
 * goes meanwhile. It lives as long as the camera screen, so turning the phone keeps it all; [taken] also survives
 * Android ending the app.
 *
 * [taken] counts the photos saved here, [saving] those still being written, and [problem] says why the last one wasn't
 * saved.
 */
class Shutter(private val handle: SavedStateHandle, private val photos: LocalPhotos) : ViewModel() {
    var taken by mutableIntStateOf(handle[TAKEN] ?: 0)
        private set
    var saving by mutableIntStateOf(0)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    /**
     * Saves the photo [controller] takes now, with [meta]: what the phone read at the press ([com.fieldmaxx.app.photos.cameraMeta]).
     * [saving] counts it before this returns.
     */
    fun press(jobId: String, pointId: String, meta: CaptureMeta, controller: CaptureController) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            saving++
            try {
                withContext(NonCancellable) { photos.saveCaptured(jobId, pointId, meta) { controller.capture(it) } }
                taken++
                handle[TAKEN] = taken
                problem = null
            } catch (e: CancellationException) {
                throw e // the camera screen is gone; the photo was saved all the same
            } catch (e: Exception) {
                problem = "Photo not saved: ${e.message ?: "the camera failed"}. Try again."
            } finally {
                saving--
            }
        }
    }

    private companion object {
        const val TAKEN = "taken"
    }
}
