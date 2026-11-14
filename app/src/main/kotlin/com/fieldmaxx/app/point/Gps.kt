package com.fieldmaxx.app.point

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.platform.Fix
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.geo.metersToFeet
import kotlin.math.roundToInt

enum class Tone { GREEN, AMBER, RED, NONE }

data class GpsBadge(val text: String, val tone: Tone)

private const val GREEN_MAX_FT = 16
private const val AMBER_MAX_FT = 50

private val Red = Color(0xFFDC2626)
private val Slate = Color(0xFF334155)
private val Ink = Color(0xFF0F172A) // dark text on the green and amber badges

/**
 * "GPS ±12 ft" (spec §6.4): green to 16 ft, amber to 50 ft, red above, judged on the feet shown. "No GPS yet" before
 * the first fix; a fix the phone gave no accuracy for (Fix.accuracyM is then MAX_VALUE) is red.
 */
fun gpsBadge(f: Fix?): GpsBadge {
    if (f == null) return GpsBadge("No GPS yet", Tone.NONE)
    val feet = metersToFeet(f.accuracyM)
    if (!feet.isFinite() || feet >= Int.MAX_VALUE) return GpsBadge("GPS accuracy unknown", Tone.RED)
    val ft = feet.roundToInt()
    val tone = when {
        ft <= GREEN_MAX_FT -> Tone.GREEN
        ft <= AMBER_MAX_FT -> Tone.AMBER
        else -> Tone.RED
    }
    return GpsBadge("GPS ±$ft ft", tone)
}

@Composable
internal fun GpsBadgeView(badge: GpsBadge, modifier: Modifier = Modifier) {
    val (background, text) = when (badge.tone) {
        Tone.GREEN -> FmxColors.Done to Ink
        Tone.AMBER -> FmxColors.Warning to Ink
        Tone.RED -> Red to Color.White
        Tone.NONE -> Slate to Color.White
    }
    Text(
        badge.text,
        color = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.background(background, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp).testTag(PointTags.GPS),
    )
}
