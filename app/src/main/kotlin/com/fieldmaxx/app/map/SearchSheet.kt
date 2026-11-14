package com.fieldmaxx.app.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.PointStatus
import kotlinx.coroutines.launch

private const val MAX_RESULTS = 50

/** Search (top bar): points by label, number or description; picking one shows it on the map and opens it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchSheet(points: List<Point>, onPick: (Point) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(points, query) { searchPoints(points, query).take(MAX_RESULTS) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Label, number or description") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus).testTag(MapTags.SEARCH_FIELD),
        )
        LaunchedEffect(focus) { focus.requestFocus() }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(top = 8.dp)) {
            if (query.isNotBlank() && results.isEmpty()) {
                item(key = "none") {
                    Text(
                        "No point matches \"${query.trim()}\"",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(results, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.label) },
                    supportingContent = p.description.takeIf { it.isNotBlank() }?.let { d -> { Text(d, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    trailingContent = statusText(p)?.let { s -> { Text(s, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    modifier = Modifier
                        .clickable { scope.launch { sheet.hide(); onPick(p) } }
                        .testTag(MapTags.searchResult(p.id)),
                )
            }
        }
    }
}

private fun statusText(p: Point): String? = when (p.status) {
    PointStatus.done -> "Done"
    PointStatus.cant_shoot -> "Can't shoot"
    PointStatus.open -> null
}
