package com.fieldmaxx.app.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.sync.SyncStatus
import com.fieldmaxx.app.ui.FmxColors

private val BannerRed = Color(0xFFB91C1C) // white text stays readable on it
private val Slate = Color(0xFF334155)
private val Ink = FmxColors.Bar.copy(alpha = 1f) // dark text on the amber and green pills

/** The top bar (spec §6.3): back, the job's name, search, the connection pill and the menu ☰. */
@Composable
internal fun TopBar(
    title: String,
    status: SyncStatus,
    waiting: Int,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(FmxColors.Bar).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSearch, modifier = Modifier.testTag(MapTags.SEARCH)) {
            Icon(Icons.Default.Search, contentDescription = "Search points", tint = Color.White)
        }
        Pill(status, waiting)
        IconButton(onClick = onMenu, modifier = Modifier.testTag(MapTags.MENU)) {
            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
        }
    }
}

/** Amber while photos wait, blue while syncing, green when everything is through, grey without a connection. */
@Composable
private fun Pill(status: SyncStatus, waiting: Int) {
    val (background, text) = when {
        status == SyncStatus.SYNCING -> FmxColors.Accent to Color.White
        waiting > 0 -> FmxColors.Warning to Ink
        status == SyncStatus.ONLINE -> FmxColors.Done to Ink
        else -> Slate to Color.White
    }
    Text(
        pillText(status, waiting),
        color = text,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = Modifier.testTag(MapTags.PILL).background(background, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Under the top bar while OneDrive delivery for this job fails, with the server's reason (spec §6.3). */
@Composable
internal fun DeliveryBanner(reason: String) {
    Text(
        deliveryBannerText(reason),
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().background(BannerRed).padding(horizontal = 16.dp, vertical = 8.dp).testTag(MapTags.BANNER),
    )
}
