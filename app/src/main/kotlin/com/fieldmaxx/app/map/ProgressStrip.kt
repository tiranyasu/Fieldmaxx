package com.fieldmaxx.app.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.ui.FmxColors

private val Muted = Color(0xFFCBD5E1)

/**
 * The progress strip (spec §6.3): "Progress: 152/159 (96%)", "Left: 7" (opens the remaining points, nearest first), the
 * sunset countdown, the job's progress bar and the per-type counts. [sunset] is null while no position is known.
 */
@Composable
internal fun ProgressStrip(progress: Progress, left: Int, sunset: String?, onLeft: () -> Unit, modifier: Modifier = Modifier) {
    val complete = progress.total > 0 && progress.done == progress.total
    Column(modifier.fillMaxWidth().background(FmxColors.Bar).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Progress: ${progress.done}/${progress.total} (${progress.pct}%)",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = onLeft, modifier = Modifier.padding(start = 4.dp).testTag(MapTags.LEFT)) {
                Text("Left: $left", fontWeight = FontWeight.Bold, color = Color.White)
            }
            Text(
                sunset.orEmpty(),
                color = Muted,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        LinearProgressIndicator(
            progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
            color = if (complete) FmxColors.Done else FmxColors.Accent,
            trackColor = Color.White.copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth(),
        )
        if (progress.byType.size > 1) {
            Text(
                progress.byType.joinToString("  ·  ") { "${it.type} ${it.done}/${it.total}" },
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
