package com.fieldmaxx.app.camera

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fieldmaxx.app.photos.CaptureMeta
import com.fieldmaxx.app.photos.LocalPhotos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The camera screen's shutter for one point: several photos in a row, each saved through [photos]
 * ([LocalPhotos.saveCaptured]) with the meta read as it was pressed and the signed-in tech ([techId], R78). Once pressed,
 * a photo is saved even if the screen goes meanwhile. It lives as long as the camera screen, so turning the phone keeps
 * it all; [taken] also survives Android ending the app.
 *
 * [taken] counts the photos saved here and [saving] those still being written. [problem] says why a photo wasn't saved
 * (R79): it stays until the tech dismisses it ([dismissProblem]) or presses again; a later photo saved doesn't hide it.
 */
class Shutter(private val handle: SavedStateHandle, private val photos: LocalPhotos, techId: suspend () -> String?) : ViewModel() {
    var taken by mutableIntStateOf(handle[TAKEN] ?: 0)
        private set
    var saving by mutableIntStateOf(0)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    /** Read once as the camera opens, so that a press needn't wait for it. A photo without its tech beats no photo. */
    private val tech: Deferred<String?> = viewModelScope.async {
        try {
            techId()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The signed-in tech couldn't be read", e)
            null
        }
    }

    /**
     * Saves the photo [controller] takes now, with [meta]: what the phone read at the press ([com.fieldmaxx.app.photos.cameraMeta]).
     * [saving] counts it before this returns; pressing is reading the last failure, so [problem] goes.
     */
    fun press(jobId: String, pointId: String, meta: CaptureMeta, controller: CaptureController) {
        problem = null
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            saving++
            try {
                withContext(NonCancellable) {
                    photos.saveCaptured(jobId, pointId, meta.copy(techId = tech.await())) { controller.capture(it) }
                }
                taken++
                handle[TAKEN] = taken
            } catch (e: CancellationException) {
                throw e // the camera screen is gone; the photo was saved all the same
            } catch (e: Exception) {
                problem = "Photo not saved: ${e.message ?: "the camera failed"}. Try again."
            } finally {
                saving--
            }
        }
    }

    /** The tech has read [problem]. */
    fun dismissProblem() {
        problem = null
    }

    private companion object {
        const val TAG = "Shutter"
        const val TAKEN = "taken"
    }
}
