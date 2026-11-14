package com.fieldmaxx.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fieldmaxx.app.ui.FieldmaxxNav
import com.fieldmaxx.app.ui.FieldmaxxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Always dark: light status and navigation bar icons over the app's own background.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val graph = (application as FieldmaxxApp).graph
        setContent {
            FieldmaxxTheme { FieldmaxxNav(graph) }
        }
    }
}
