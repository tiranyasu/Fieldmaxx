package com.fieldmaxx.app.camera

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The photo's whole frame: 4:3, the sensor's own shape, at the highest resolution the camera gives. */
private val FULL_RESOLUTION = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
    .build()

/** The preview in the photo's shape, so that it shows the whole frame the photo will have. */
private val PREVIEW_4_3 = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()

private val FLASH_MODES = listOf(ImageCapture.FLASH_MODE_AUTO, ImageCapture.FLASH_MODE_ON, ImageCapture.FLASH_MODE_OFF)

/**
 * The back camera through CameraX: a Preview on screen and a full-resolution ImageCapture in CAPTURE_MODE_MAXIMIZE_QUALITY,
 * with flash (auto, on, off), pinch zoom and tap to focus. [Preview] shows it and binds it to the screen's lifecycle;
 * [capture] writes a JPEG whose EXIF orientation follows how the phone is held.
 */
class CameraXCaptureController(context: Context) : CaptureController {
    private val app = context.applicationContext
    private val camera = LifecycleCameraController(app).apply {
        setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        imageCaptureResolutionSelector = FULL_RESOLUTION
        previewResolutionSelector = PREVIEW_4_3
        isPinchToZoomEnabled = true
        isTapToFocusEnabled = true
        imageCaptureFlashMode = ImageCapture.FLASH_MODE_AUTO
    }

    override suspend fun capture(to: File): Unit = withContext(Dispatchers.Main.immediate) {
        awaitCamera()
        suspendCancellableCoroutine { done ->
            camera.takePicture(
                ImageCapture.OutputFileOptions.Builder(to).build(),
                ContextCompat.getMainExecutor(app),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) = done.resume(Unit)
                    override fun onError(exception: ImageCaptureException) = done.resumeWithException(IOException(reason(exception), exception))
                },
            )
        }
    }

    /** Until CameraX is set up: the first press can come as the screen opens. */
    private suspend fun awaitCamera() = suspendCancellableCoroutine { ready ->
        val future = camera.initializationFuture
        future.addListener(
            {
                try {
                    future.get()
                    ready.resume(Unit)
                } catch (e: ExecutionException) {
                    ready.resumeWithException(IOException("the camera couldn't start", e.cause ?: e))
                }
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    /** The preview, bound to the screen's lifecycle while shown, with the flash button over its top-right corner. */
    @Composable
    fun Preview(modifier: Modifier = Modifier) {
        val owner = LocalLifecycleOwner.current
        var flash by rememberSaveable { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
        DisposableEffect(owner) {
            camera.bindToLifecycle(owner)
            onDispose { camera.unbind() }
        }
        LaunchedEffect(flash) { camera.imageCaptureFlashMode = flash }
        Box(modifier) {
            AndroidView(
                factory = { context ->
                    PreviewView(context).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        controller = camera
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            TextButton(
                onClick = { flash = FLASH_MODES[(FLASH_MODES.indexOf(flash) + 1) % FLASH_MODES.size] },
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
            ) { Text(flashLabel(flash), color = Color.White) }
        }
    }
}

@Composable
fun rememberCameraXCaptureController(): CameraXCaptureController {
    val context = LocalContext.current
    return remember { CameraXCaptureController(context) }
}

private fun flashLabel(mode: Int) = when (mode) {
    ImageCapture.FLASH_MODE_ON -> "Flash on"
    ImageCapture.FLASH_MODE_OFF -> "Flash off"
    else -> "Flash auto"
}

private fun reason(e: ImageCaptureException): String = when (e.imageCaptureError) {
    ImageCapture.ERROR_CAMERA_CLOSED -> "the camera closed"
    ImageCapture.ERROR_FILE_IO -> "the phone couldn't write it"
    ImageCapture.ERROR_CAPTURE_FAILED -> "the camera couldn't take it"
    else -> "the camera failed"
}
