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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fieldmaxx.app.AppGraph
import com.fieldmaxx.app.auth.LoginScreen
import com.fieldmaxx.app.net.ApiException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    const val LOGIN = "login"
    const val JOBS = "jobs"
}

/**
 * The app's screens. A stored session opens straight on the jobs; otherwise the login screen comes first. Syncing starts
 * on sign-in, and at launch with a stored session. When the server says the session has ended, the token is dropped and
 * the login screen comes back; the outbox is kept, and flushes after the next sign-in.
 */
@Composable
fun FieldmaxxNav(graph: AppGraph, navController: NavHostController = rememberNavController()) {
    val start = remember { if (graph.vault.load() != null) Routes.JOBS else Routes.LOGIN }
    LaunchedEffect(graph) {
        // Main-confined, as on the login screen: navigation runs on the main thread wherever a call finishes.
        withContext(Dispatchers.Main.immediate) {
            val toLogin: suspend () -> Unit = {
                withContext(Dispatchers.IO) { graph.vault.save(null) }
                navController.navigate(Routes.LOGIN) {
                    popUpTo(Routes.JOBS) { inclusive = true }
                    launchSingleTop = true
                }
            }
            launch { graph.sync.authRequired.collect { toLogin() } }
            if (start == Routes.JOBS) resumeSync(graph, toLogin)
        }
    }
    NavHost(navController, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(graph.api, graph.vault, graph.server) { user ->
                graph.sync.start(user)
                navController.navigate(Routes.JOBS) { popUpTo(Routes.LOGIN) { inclusive = true } }
            }
        }
        composable(Routes.JOBS) { JobsPlaceholder() }
    }
}

/**
 * Launch with a stored session: sync as the user saved at sign-in, which needs no signal. Should none be saved, the
 * server says who the session belongs to.
 */
private suspend fun resumeSync(graph: AppGraph, toLogin: suspend () -> Unit) {
    val user = graph.sync.savedUser() ?: try {
        graph.api.me()
    } catch (e: ApiException) {
        if (e.status == 401) toLogin()
        return
    } catch (e: IOException) {
        return // no signal and nothing saved: the next launch asks again
    }
    graph.sync.start(user)
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
