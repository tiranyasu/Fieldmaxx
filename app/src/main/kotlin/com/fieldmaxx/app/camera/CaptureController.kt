package com.fieldmaxx.app.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fieldmaxx.app.photos.CaptureMeta
import com.fieldmaxx.app.photos.LocalPhotos
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Takes one full-resolution photo into [to]: [CameraXCaptureController] in the app (ImageCapture, CAPTURE_MODE_MAXIMIZE_QUALITY), a fake in tests. */
interface CaptureController {
    suspend fun capture(to: File)
}

/**
 * The camera screen's shutter for one point: several photos in a row, each saved through [photos] ([LocalPhotos.saveCaptured])
 * with the meta read as it was pressed. Once pressed, a photo is saved even if the screen goes meanwhile. [taken] counts
 * the photos saved here, [saving] those still being written, and [problem] says why the last one wasn't saved.
 */
class Shutter(private val photos: LocalPhotos, private val controller: CaptureController) {
    var taken by mutableIntStateOf(0)
        private set
    var saving by mutableIntStateOf(0)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    /** [meta] is what the phone read at the press ([com.fieldmaxx.app.photos.cameraMeta]). */
    suspend fun take(jobId: String, pointId: String, meta: CaptureMeta) {
        saving++
        try {
            withContext(NonCancellable) { photos.saveCaptured(jobId, pointId, meta) { controller.capture(it) } }
            taken++
            problem = null
        } catch (e: CancellationException) {
            throw e // the screen went; the photo was saved all the same
        } catch (e: Exception) {
            problem = "Photo not saved: ${e.message ?: "the camera failed"}. Try again."
        } finally {
            saving--
        }
    }
}
