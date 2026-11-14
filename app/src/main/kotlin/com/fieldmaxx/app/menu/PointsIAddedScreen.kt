package com.fieldmaxx.app.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldmaxx.core.model.Point
import kotlinx.coroutines.flow.Flow

object AddedTags {
    const val EMPTY = "added.empty"
    fun row(pointId: String) = "added:$pointId"
}

/** The points this user added, newest first (R50). */
fun myAddedPoints(points: List<Point>, userId: String): List<Point> =
    points.filter { it.addedBy == userId && it.deletedAt == null }.sortedBy { it.label }

/**
 * Menu → "Points I added" (spec §6.5): this job's points added by the signed-in user, each with its label, type and status (R50a: no time); tapping one opens it on the map. 
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointsIAddedScreen(
    points: Flow<List<Point>>,
    userId: suspend () -> String,
    onOpen: (pointId: String) -> Unit,
    onBack: () -> Unit,
) {
    val all by points.collectAsState(emptyList())
    var me by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { me = userId() }
    val mine = remember(all, me) { me?.let { myAddedPoints(all, it) }.orEmpty() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Points I added") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (mine.isEmpty()) {
            Text("You haven't added any points to this job.", modifier = Modifier.padding(padding).padding(16.dp).testTag(AddedTags.EMPTY))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(mine, key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.label) },
                        supportingContent = { Text("${p.type} · ${p.status.name.replace('_', ' ')}") },
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(p.id) }.testTag(AddedTags.row(p.id)),
                    )
                }
            }
        }
    }
}
