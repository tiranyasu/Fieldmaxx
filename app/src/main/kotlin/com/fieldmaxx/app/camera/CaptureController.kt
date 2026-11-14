package com.fieldmaxx.app.camera

import java.io.File

/** Takes one full-resolution photo into [to]: [CameraXCaptureController] in the app (ImageCapture, CAPTURE_MODE_MAXIMIZE_QUALITY), a fake in tests. */
interface CaptureController {
    suspend fun capture(to: File)
}
