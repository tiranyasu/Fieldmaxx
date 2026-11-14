package com.fieldmaxx.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fieldmaxx.app.jobs.parseShareIntent
import com.fieldmaxx.app.ui.FieldmaxxNav
import com.fieldmaxx.app.ui.FieldmaxxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Always dark: light status and navigation bar icons over the app's own background.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val graph = (application as FieldmaxxApp).graph
        // "Share → Fieldmaxx" starts this activity with the shared files or link; the navigation shows them once, also
        // across a rotation (the intent stays the same).
        val intake = parseShareIntent(intent)
        setContent {
            FieldmaxxTheme { FieldmaxxNav(graph, intake = intake) }
        }
    }
}
