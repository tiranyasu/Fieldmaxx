package com.fieldmaxx.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fieldmaxx.app.AppGraph
import com.fieldmaxx.app.auth.LoginScreen
import com.fieldmaxx.app.jobs.JobInfoScreen
import com.fieldmaxx.app.jobs.JobListScreen
import com.fieldmaxx.app.jobs.NewJobScreen
import com.fieldmaxx.app.jobs.ShareIntakeScreen
import com.fieldmaxx.app.jobs.SharedIntake
import com.fieldmaxx.app.net.ApiException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    const val LOGIN = "login"
    const val JOBS = "jobs"

    /** "New job / Add to job" for what another app shared. */
    const val SHARE = "share"
    const val NEW_JOB = "newJob?files={files}&link={link}"
    const val ADD_FILES = "addFiles/{jobId}?files={files}"
    const val JOB_INFO = "jobInfo/{jobId}?link={link}"

    /** The job's map (Task 24). */
    const val MAP = "map/{jobId}"

    /** [files]: picked or shared files to read at once, instead of opening the picker. */
    fun newJob(files: List<Uri> = emptyList(), link: String? = null) = "newJob" + query("files" to filesArg(files), "link" to link)
    fun addFiles(jobId: String, files: List<Uri> = emptyList()) = "addFiles/${Uri.encode(jobId)}" + query("files" to filesArg(files))
    fun jobInfo(jobId: String, link: String? = null) = "jobInfo/${Uri.encode(jobId)}" + query("link" to link)
    fun map(jobId: String) = "map/${Uri.encode(jobId)}"

    private fun filesArg(files: List<Uri>) = files.joinToString("\n").ifEmpty { null }

    private fun query(vararg args: Pair<String, String?>): String =
        args.filter { it.second != null }.joinToString("&") { (name, value) -> "$name=${Uri.encode(value)}" }.let { if (it.isEmpty()) "" else "?$it" }
}

private fun NavBackStackEntry.jobId(): String = arguments?.getString("jobId").orEmpty()

private fun NavBackStackEntry.files(): List<Uri> =
    arguments?.getString("files")?.split('\n')?.filter { it.isNotEmpty() }?.map(Uri::parse).orEmpty()

private val optional = { name: String -> navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null } }
private val jobIdArg = navArgument("jobId") { type = NavType.StringType }

/**
 * The app's screens. A stored session opens straight on the jobs; otherwise the login screen comes first. Syncing starts
 * on sign-in, and at launch with a stored session. When the server says the session has ended, the token is dropped and
 * the login screen comes back; the outbox is kept, and flushes after the next sign-in.
 *
 * [intake] is what another app shared ("Share → Fieldmaxx"): once signed in, "New job / Add to job" shows for it, once.
 * Saving a new job or adding files opens the job's map (R34).
 */
@Composable
fun FieldmaxxNav(graph: AppGraph, navController: NavHostController = rememberNavController(), intake: SharedIntake? = null) {
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
    var intakeShown by rememberSaveable { mutableStateOf(false) }
    val toMap: (String) -> Unit = { jobId -> navController.navigate(Routes.map(jobId)) { popUpTo(Routes.JOBS) } }
    val back: () -> Unit = { navController.popBackStack() }
    NavHost(navController, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(graph.api, graph.vault, graph.server) { user ->
                graph.sync.start(user)
                navController.navigate(Routes.JOBS) { popUpTo(Routes.LOGIN) { inclusive = true } }
            }
        }
        composable(Routes.JOBS) {
            if (intake != null && !intakeShown) {
                LaunchedEffect(Unit) {
                    intakeShown = true
                    navController.navigate(Routes.SHARE)
                }
            }
            JobListScreen(
                graph.jobList, graph.sync.status, graph.sync::savedUser,
                onOpenJob = { navController.navigate(Routes.map(it)) },
                onNewJob = { navController.navigate(Routes.newJob()) },
                onAddFiles = { navController.navigate(Routes.addFiles(it)) },
                onJobInfo = { navController.navigate(Routes.jobInfo(it)) },
            )
        }
        composable(Routes.SHARE) {
            if (intake == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }
            ShareIntakeScreen(
                intake, graph.jobList,
                onNewJob = { files, link -> navController.navigate(Routes.newJob(files, link)) { popUpTo(Routes.SHARE) { inclusive = true } } },
                onAddToJob = { jobId, files, link ->
                    // Files are added through the check screen; a link is checked and saved on the job's info.
                    val route = if (files.isNotEmpty()) Routes.addFiles(jobId, files) else Routes.jobInfo(jobId, link)
                    navController.navigate(route) { popUpTo(Routes.SHARE) { inclusive = true } }
                },
                onClose = back,
            )
        }
        composable(Routes.NEW_JOB, arguments = listOf(optional("files"), optional("link"))) { entry ->
            NewJobScreen(
                graph.api, graph.sync::openJob, addToJobId = null, sharedFiles = entry.files(), sharedLink = entry.arguments?.getString("link"),
                onDone = toMap, onBack = back,
            )
        }
        composable(Routes.ADD_FILES, arguments = listOf(jobIdArg, optional("files"))) { entry ->
            NewJobScreen(
                graph.api, graph.sync::openJob, addToJobId = entry.jobId(), sharedFiles = entry.files(), sharedLink = null,
                onDone = toMap, onBack = back,
            )
        }
        composable(Routes.JOB_INFO, arguments = listOf(jobIdArg, optional("link"))) { entry ->
            JobInfoScreen(entry.jobId(), graph.api, graph.sync, sharedLink = entry.arguments?.getString("link"), onBack = back)
        }
        composable(Routes.MAP, arguments = listOf(jobIdArg)) { entry -> MapPlaceholder(graph, entry.jobId(), onBack = back) }
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

/** Stands in for the job's map until Task 24 replaces it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapPlaceholder(graph: AppGraph, jobId: String, onBack: () -> Unit) {
    val job by remember(jobId) { graph.sync.job(jobId) }.collectAsState(null)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(job?.name ?: "Map") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = fmxBarColors(),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text("The map will show here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
