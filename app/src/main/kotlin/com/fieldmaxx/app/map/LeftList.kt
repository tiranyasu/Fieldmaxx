package com.fieldmaxx.app.map

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.geo.distanceMeters
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.naming.markerCode
import kotlinx.coroutines.launch

/**
 * "Left: 7" opened: the points still to shoot, [left] already sorted nearest first from [from] (your position; null
 * while unknown). Picking one shows it on the map and opens it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LeftList(left: List<Point>, from: LatLon?, onPick: (Point) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Text("Left: ${left.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            when {
                left.isEmpty() -> "Every point is shot."
                from == null -> "In the job's order until your position is known"
                else -> "Nearest first"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(left, key = { it.id }) { p ->
                ListItem(
                    leadingContent = { TypeDot(p.type) },
                    headlineContent = { Text(p.label) },
                    supportingContent = p.description.takeIf { it.isNotBlank() }?.let { d -> { Text(d, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    trailingContent = from?.let { here -> { Text(distanceText(distanceMeters(here, LatLon(p.lat, p.lon)))) } },
                    modifier = Modifier
                        .clickable { scope.launch { sheet.hide(); onPick(p) } }
                        .testTag(MapTags.leftItem(p.id)),
                )
            }
        }
    }
}

/** The marker as the map draws it: the type's colour and short code. */
@Composable
private fun TypeDot(type: String) {
    Box(Modifier.size(28.dp).background(Color(AndroidColor.parseColor(typeColor(type))), CircleShape), contentAlignment = Alignment.Center) {
        Text(markerCode(type), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
