package com.fieldmaxx.app.admin

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.app.ui.fmxBarColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// What the admin screens (spec §6.9) share: they read the server, so they need signal, and they list things by date.

internal const val ADMIN_NEEDS_SIGNAL = "Can't reach the server. The admin screens need signal."

private val DAY = DateTimeFormatter.ofPattern("MM/dd/yy", Locale.US)
private val DAY_TIME = DateTimeFormatter.ofPattern("MM/dd/yy HH:mm", Locale.US)

/** "11/05/26", the stamp's way of writing dates. */
internal fun dayText(ms: Long, zone: ZoneId): String = DAY.format(Instant.ofEpochMilli(ms).atZone(zone))

/** "11/05/26 13:35". */
internal fun dayTimeText(ms: Long, zone: ZoneId): String = DAY_TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

/** An admin screen's frame: its title, back, and [onRefresh] (fetch again from the server) when given. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdminScaffold(
    title: String,
    onBack: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { onRefresh?.let { IconButton(onClick = it) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") } } },
                colors = fmxBarColors(),
            )
        },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

/** A line in the warning colour: no signal, or what the server refused. */
@Composable
internal fun AdminNote(text: String, modifier: Modifier = Modifier) {
    Text(text, color = FmxColors.Warning, style = MaterialTheme.typography.bodyMedium, modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}
