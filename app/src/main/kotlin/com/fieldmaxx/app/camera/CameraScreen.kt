package com.fieldmaxx.app.camera

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fieldmaxx.app.AppGraph
import com.fieldmaxx.app.photos.LocalPhotos
import com.fieldmaxx.app.photos.cameraMeta
import com.fieldmaxx.app.platform.Fix
import com.fieldmaxx.app.platform.Heading
import com.fieldmaxx.app.ui.FmxColors
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The job and point as the stamp names them: "A05J9XE · POLE 1". */
data class CameraTarget(val jobName: String, val pointLabel: String)

/** What the camera and the gallery pick read and save through; [GraphCameraDeps] in the app, a fake in tests. */
interface CameraDeps {
    /** The job's name and the point's label; null while either isn't stored on the phone, or once the point is deleted. */
    fun target(jobId: String, pointId: String): Flow<CameraTarget?>

    val fixes: StateFlow<Fix?>

    /** Where the back camera points ([com.fieldmaxx.app.platform.CompassSource.cameraHeading], R35a). */
    val heading: StateFlow<Heading?>
    val photos: LocalPhotos
    fun now(): Long
    fun zone(): ZoneId
}

/** [CameraDeps] from the app's graph. */
class GraphCameraDeps(private val graph: AppGraph) : CameraDeps {
    override fun target(jobId: String, pointId: String): Flow<CameraTarget?> =
        combine(graph.sync.job(jobId), graph.sync.points(jobId)) { job, points ->
            val point = points.firstOrNull { it.id == pointId }
            if (job != null && point != null) CameraTarget(job.name, point.label) else null
        }.distinctUntilChanged()

    override val fixes: StateFlow<Fix?> get() = graph.location.fixes
    override val heading: StateFlow<Heading?> get() = graph.compass.cameraHeading
    override val photos: LocalPhotos get() = graph.localPhotos
    override fun now(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

object CameraTags {
    const val SHUTTER = "camera.shutter"
    const val COUNT = "camera.count"
    const val SAVING = "camera.saving"
    const val PROBLEM = "camera.problem"
    const val GONE = "camera.gone"
    const val CLOSE = "camera.close"
    const val OVERLAY = "camera.overlay"
    const val HINT = "camera.hint"
    const val REFUSED = "camera.refused"
    const val SETTINGS = "camera.settings"
    const val GALLERY = "camera.gallery"
}

private const val REFUSED_TEXT =
    "Fieldmaxx isn't allowed to use the camera. Allow it in the app's settings to take photos here, or pick them from the gallery."

private class Loaded(val target: CameraTarget?)

private enum class CameraAccess { ASKING, ALLOWED, REFUSED }

/**
 * The built-in camera for one point (spec §6.6): [preview] (the CameraX preview, with flash and pinch zoom) under a live
 * overlay of exactly what will be stamped, and a shutter for several photos in a row, with a count of those taken here.
 * Each press saves the fix and compass reading of that moment ([cameraMeta]). Once the camera has written a photo it is
 * saved even if the screen goes; Back waits for the photos being taken, and a photo that couldn't be taken says why.
 *
 * The camera permission is asked on entering (R53). Refused, the screen says so, with a way to the app's settings, and
 * still offers "Pick from gallery".
 */
@Composable
fun CameraScreen(
    jobId: String,
    pointId: String,
    deps: CameraDeps,
    controller: CaptureController,
    onBack: () -> Unit,
    preview: @Composable (Modifier) -> Unit = {},
) {
    // Main-confined, as on the other screens: the screen's state is written on the main thread wherever a value comes from.
    val loaded by remember(deps, jobId, pointId) { deps.target(jobId, pointId).map(::Loaded) }.collectAsState(null, Dispatchers.Main.immediate)
    val access = rememberCameraAccess()
    val shutter = viewModel { Shutter(createSavedStateHandle(), deps.photos) }
    val gallery = rememberGalleryPick(deps)
    val context = LocalContext.current

    // Back waits for the photos still being written, then goes; if one of them couldn't be saved, it stays to say so.
    var leaving by remember { mutableStateOf(false) }
    BackHandler(enabled = shutter.saving > 0) { leaving = true }
    if (leaving && shutter.saving == 0) {
        LaunchedEffect(Unit) {
            leaving = false
            if (shutter.problem == null) onBack()
        }
    }
    val close: () -> Unit = {
        if (shutter.saving > 0) {
            leaving = true
        } else {
            onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (access) {
            CameraAccess.ALLOWED -> {
                preview(Modifier.fillMaxSize())
                val target = loaded?.target
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().safeDrawingPadding().padding(16.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.weight(1f)) { target?.let { StampOverlay(it, deps) } }
                    ShutterControls(
                        taken = shutter.taken,
                        saving = shutter.saving > 0,
                        onPress = target?.let { t ->
                            {
                                // The reading of this moment, before the camera starts writing.
                                shutter.press(jobId, pointId, cameraMeta(t.jobName, t.pointLabel, deps.fixes.value, deps.heading.value, deps.now()), controller)
                            }
                        },
                    )
                }
                Messages(
                    problem = shutter.problem,
                    gone = loaded != null && loaded?.target == null,
                    modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 64.dp, start = 16.dp, end = 16.dp),
                )
            }
            CameraAccess.REFUSED -> Refused(
                onSettings = { openAppSettings(context) },
                onGallery = { gallery.pick(jobId, pointId) },
                modifier = Modifier.align(Alignment.Center).safeDrawingPadding().padding(24.dp),
            )
            CameraAccess.ASKING -> Unit
        }
        IconButton(onClick = close, modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(8.dp).testTag(CameraTags.CLOSE)) {
            Icon(Icons.Default.Close, contentDescription = "Close the camera", tint = Color.White)
        }
    }
}

/** The shutter, with the count of photos taken here above it; disabled while the point's name isn't known. */
@Composable
private fun ShutterControls(taken: Int, saving: Boolean, onPress: (() -> Unit)?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (saving) Text("Saving…", color = Color.White, modifier = Modifier.testTag(CameraTags.SAVING))
        if (taken > 0) {
            Text(
                if (taken == 1) "1 photo taken" else "$taken photos taken",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag(CameraTags.COUNT),
            )
        }
        Surface(
            onClick = onPress ?: {},
            enabled = onPress != null,
            shape = CircleShape,
            color = if (onPress != null) Color.White else Color.White.copy(alpha = 0.4f),
            modifier = Modifier
                .size(72.dp)
                .border(4.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                .semantics { contentDescription = "Take photo" }
                .testTag(CameraTags.SHUTTER),
        ) {}
    }
}

@Composable
private fun Messages(problem: String?, gone: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (gone) Text("This point was deleted.", color = FmxColors.Warning, modifier = Modifier.testTag(CameraTags.GONE))
        problem?.let { Text(it, color = FmxColors.Warning, modifier = Modifier.testTag(CameraTags.PROBLEM)) }
    }
}

@Composable
private fun Refused(onSettings: () -> Unit, onGallery: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(REFUSED_TEXT, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(CameraTags.REFUSED))
        Button(onClick = onSettings, modifier = Modifier.testTag(CameraTags.SETTINGS)) { Text("Open settings") }
        OutlinedButton(onClick = onGallery, modifier = Modifier.testTag(CameraTags.GALLERY)) { Text("Pick from gallery") }
    }
}

/**
 * R53: the camera permission, asked each time the camera opens without it (not again on a rotation). A change in the
 * system settings shows on return.
 */
@Composable
private fun rememberCameraAccess(): CameraAccess {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(hasCamera(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted || hasCamera(context)
        refused = !allowed
    }
    LaunchedEffect(Unit) {
        if (!allowed && !asked) {
            asked = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
    LifecycleResumeEffect(context) {
        allowed = hasCamera(context)
        if (allowed) refused = false
        onPauseOrDispose { }
    }
    return when {
        allowed -> CameraAccess.ALLOWED
        refused -> CameraAccess.REFUSED
        else -> CameraAccess.ASKING
    }
}

private fun hasCamera(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** This app's page in the system settings, where its permissions are. */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    for (candidate in listOf(intent, Intent(Settings.ACTION_SETTINGS))) { // a phone without the page: its settings, at least
        try {
            context.startActivity(candidate)
            return
        } catch (e: ActivityNotFoundException) {
            continue
        }
    }
}
