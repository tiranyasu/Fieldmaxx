package com.fieldmaxx.app.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldmaxx.app.photos.cameraMeta
import com.fieldmaxx.app.photos.stampMeta
import com.fieldmaxx.app.platform.Fix
import com.fieldmaxx.app.platform.Heading
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.stamp.stampLines
import java.time.ZoneId
import kotlinx.coroutines.delay

/** The overlay is worked out again this often: the clock, the position and the compass move under it. */
const val OVERLAY_REFRESH_MS = 500L

private const val CALIBRATE = "Compass: calibrate"

/**
 * Exactly what a shutter press at [now] would stamp (spec §6.6): the stamp's lines for the photo [cameraMeta] would
 * record, so "(GPS stale)" once the fix is older than 10 s, "No GPS" without one.
 */
fun overlayLines(jobName: String, pointLabel: String, fix: Fix?, heading: Heading?, now: Long, zone: ZoneId): List<String> =
    stampLines(cameraMeta(jobName, pointLabel, fix, heading, now).stampMeta(zone))

/** "Compass: calibrate" while the compass reports low accuracy (spec §6.6); not part of the stamp. */
fun calibrationHint(heading: Heading?): String? = if (heading != null && !heading.reliable) CALIBRATE else null

private class Shown(val lines: List<String>, val hint: String?)

private val StampText = TextStyle(
    color = Color.White,
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(1f, 1f), blurRadius = 6f),
)

/**
 * The live stamp over the camera preview, bottom-left in white bold over a dark shadow, as the photo will carry it; the
 * calibration hint (with its figure-8 move) above it. It re-renders [overlayLines] every [OVERLAY_REFRESH_MS].
 */
@Composable
internal fun StampOverlay(target: CameraTarget, deps: CameraDeps, modifier: Modifier = Modifier) {
    fun current(): Shown {
        val heading = deps.heading.value
        return Shown(overlayLines(target.jobName, target.pointLabel, deps.fixes.value, heading, deps.now(), deps.zone()), calibrationHint(heading))
    }
    var shown by remember(target, deps) { mutableStateOf(current()) }
    LaunchedEffect(target, deps) {
        while (true) {
            shown = current()
            delay(OVERLAY_REFRESH_MS)
        }
    }
    Column(modifier.testTag(CameraTags.OVERLAY), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.hint?.let { hint ->
            Text(hint, style = StampText.copy(color = FmxColors.Warning), modifier = Modifier.testTag(CameraTags.HINT))
            Text("Move the phone in a figure 8", style = StampText.copy(fontWeight = FontWeight.Normal, fontSize = 12.sp))
        }
        for (line in shown.lines) Text(line, style = StampText)
    }
}
