package com.fieldmaxx.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fieldmaxx.app.AppGraph
import com.fieldmaxx.app.auth.LoginScreen

object Routes {
    const val LOGIN = "login"
    const val JOBS = "jobs"
}

/** The app's screens. A stored session opens straight on the jobs; otherwise the login screen comes first. */
@Composable
fun FieldmaxxNav(graph: AppGraph, navController: NavHostController = rememberNavController()) {
    val start = remember { if (graph.vault.load() != null) Routes.JOBS else Routes.LOGIN }
    NavHost(navController, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(graph.api, graph.vault, graph.server) {
                navController.navigate(Routes.JOBS) { popUpTo(Routes.LOGIN) { inclusive = true } }
            }
        }
        composable(Routes.JOBS) { JobsPlaceholder() }
    }
}

/** Stands in for the job list until it exists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobsPlaceholder() {
    Scaffold(topBar = { TopAppBar(title = { Text("Jobs") }, colors = fmxBarColors()) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text("Your jobs will show here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
