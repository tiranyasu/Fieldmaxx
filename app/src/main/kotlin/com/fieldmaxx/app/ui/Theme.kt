package com.fieldmaxx.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The app's fixed colours (global constraints). */
object FmxColors {
    /** Top and bottom bars: #0f172a at 92% opacity, so the map shows faintly through. */
    val Bar = Color(0xFF0F172A).copy(alpha = 0.92f)
    val Accent = Color(0xFF2563EB)
    val Done = Color(0xFF22C55E)
    val Warning = Color(0xFFFBBF24)
    /** Markers of points that already have photos: #9ca3af at 55% opacity. */
    val ShotMarker = Color(0xFF9CA3AF).copy(alpha = 0.55f)
}

// Slate greys around the accent, so no Material default (purple-tinted) colour shows anywhere.
private val DarkScheme = darkColorScheme(
    primary = FmxColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF93C5FD),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFE2E8F0),
    background = Color(0xFF020617),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceDim = Color(0xFF020617),
    surfaceBright = Color(0xFF334155),
    surfaceContainerLowest = Color(0xFF020617),
    surfaceContainerLow = Color(0xFF0B1222),
    surfaceContainer = Color(0xFF0F172A),
    surfaceContainerHigh = Color(0xFF1E293B),
    surfaceContainerHighest = Color(0xFF334155),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF334155),
    error = Color(0xFFF87171),
    onError = Color(0xFF0F172A),
)

/** Fieldmaxx is dark only. */
@Composable
fun FieldmaxxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}

/** Top app bar in the bar colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun fmxBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = FmxColors.Bar,
    scrolledContainerColor = FmxColors.Bar,
    titleContentColor = Color.White,
    navigationIconContentColor = Color.White,
    actionIconContentColor = Color.White,
)
