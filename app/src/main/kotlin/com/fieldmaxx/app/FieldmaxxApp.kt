package com.fieldmaxx.app

import android.app.Application
import androidx.annotation.VisibleForTesting
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.fieldmaxx.app.photos.photosOnPhone

class FieldmaxxApp : Application(), SingletonImageLoader.Factory {
    @Volatile private var built: AppGraph? = null

    /**
     * Built on first use, so a process that never needs it (a Robolectric test, say) never builds it. A test may set its
     * own graph before anything reads it.
     */
    var graph: AppGraph
        get() = built ?: synchronized(this) { built ?: AppGraph(this).also { built = it } }
        @VisibleForTesting set(value) {
            built = value
        }

    override fun onCreate() {
        super.onCreate()
        graph.tracker // starts watching the process lifecycle: location and compass run only while the app is on screen
        // Photos go on from wherever a kill, a reboot or a dead battery left them (Task 28); a phone with none has nothing to resume.
        if (photosOnPhone(filesDir)) graph.photos.resumeAll()
    }

    /** Coil's singleton loader is the graph's: signed-in requests and the reference-image disk cache. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = graph.imageLoader
}
