package com.fieldmaxx.app.map

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.ui.FmxColors

/**
 * The chip row (spec §6.3): "Hide shot", "All (N)", then one chip per type with its done/total and a progress
 * underline. A type chip filters the map to that type; tapping it again, or "All", shows every type.
 */
@Composable
internal fun ChipRow(
    progress: Progress,
    type: String?,
    hideShot: Boolean,
    onType: (String?) -> Unit,
    onHideShot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(FmxColors.Bar).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = hideShot, onClick = onHideShot, label = { Text("Hide shot") }, modifier = Modifier.testTag(MapTags.HIDE_SHOT))
        FilterChip(selected = type == null, onClick = { onType(null) }, label = { Text("All (${progress.total})") })
        for (t in progress.byType) {
            FilterChip(
                selected = type == t.type,
                onClick = { onType(if (type == t.type) null else t.type) },
                label = { UnderlinedLabel(t) },
            )
        }
    }
}

/** The chip's text over a thin bar showing how much of the type is done; green once it all is. */
@Composable
private fun UnderlinedLabel(t: TypeProgress) {
    val done = if (t.total == 0) 0f else t.done.toFloat() / t.total
    val color = if (t.total > 0 && t.done == t.total) FmxColors.Done else FmxColors.Accent
    Text(
        chipLabel(t),
        modifier = Modifier
            .drawBehind {
                val stroke = 2.dp.toPx()
                val y = size.height - stroke / 2
                drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), stroke)
                if (done > 0f) drawLine(color, Offset(0f, y), Offset(size.width * done, y), stroke)
            }
            .padding(bottom = 4.dp),
    )
}
