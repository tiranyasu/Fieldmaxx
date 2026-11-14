package com.fieldmaxx.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fieldmaxx.app.AppGraph
import com.fieldmaxx.app.admin.ActivityScreen
import com.fieldmaxx.app.admin.AdminJobsScreen
import com.fieldmaxx.app.admin.AdminSettingsScreen
import com.fieldmaxx.app.admin.DeliveryHealthScreen
import com.fieldmaxx.app.admin.UsersScreen
import com.fieldmaxx.app.auth.LoginScreen
import com.fieldmaxx.app.camera.CameraScreen
import com.fieldmaxx.app.camera.GraphCameraDeps
import com.fieldmaxx.app.camera.rememberCameraXCaptureController
import com.fieldmaxx.app.camera.rememberGalleryPick
import com.fieldmaxx.app.jobs.JobInfoScreen
import com.fieldmaxx.app.jobs.JobListScreen
import com.fieldmaxx.app.jobs.NewJobScreen
import com.fieldmaxx.app.jobs.ShareIntakeScreen
import com.fieldmaxx.app.jobs.SharedIntake
import com.fieldmaxx.app.map.GraphMapDeps
import com.fieldmaxx.app.map.MapScreen
import com.fieldmaxx.app.map.rememberMapLibreController
import com.fieldmaxx.app.menu.GraphMenuDeps
import com.fieldmaxx.app.menu.MenuSheet
import com.fieldmaxx.app.menu.PointsIAddedScreen
import com.fieldmaxx.app.menu.SettingsScreen
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.photos.GraphPhotoStripDeps
import com.fieldmaxx.app.photos.GraphReuseDeps
import com.fieldmaxx.app.photos.PhotoStrip
import com.fieldmaxx.app.photos.ReusePicker
import com.fieldmaxx.app.photos.WherePhotosWentScreen
import com.fieldmaxx.app.photos.jobPhotoViewsFor
import com.fieldmaxx.app.point.GraphPointDeps
import com.fieldmaxx.app.point.PointSheet
import com.fieldmaxx.app.update.UpdateBanner
import com.fieldmaxx.app.update.UpdateChecker
import com.fieldmaxx.app.update.UpdateInstaller
import com.fieldmaxx.app.update.checkQuietly
import com.fieldmaxx.app.update.checkWhileStarted
import com.fieldmaxx.app.update.pollPause
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
    const val MAP = "map/{jobId}?select={select}"

    /** Menu → "Points I added" (Task 26); Task 30's menu links here. */
    const val POINTS_ADDED = "pointsAdded/{jobId}"

    /** The built-in camera for one point (Task 27), from the point sheet's "Take photo". */
    const val CAMERA = "camera/{jobId}/{pointId}"

    /** "Use existing photos" for one point (Task 29), from the point sheet. */
    const val REUSE = "reuse/{jobId}/{pointId}"

    /** Menu → "Where the photos went" (Task 29); Task 30's menu links here. */
    const val WHERE_PHOTOS = "wherePhotos/{jobId}"

    /** Menu → Settings: the phone's own settings (the gallery copy). */
    const val SETTINGS = "settings"

    // The admin screens (spec §6.9), from the menu's Admin section.
    const val USERS = "admin/users"
    const val ADMIN_JOBS = "admin/jobs"
    const val DELIVERY_HEALTH = "admin/health"
    const val ACTIVITY = "admin/activity?jobId={jobId}&name={name}"
    const val ADMIN_SETTINGS = "admin/settings"

    /** [files]: picked or shared files to read at once, instead of opening the picker. */
    fun newJob(files: List<Uri> = emptyList(), link: String? = null) = "newJob" + query("files" to filesArg(files), "link" to link)
    fun addFiles(jobId: String, files: List<Uri> = emptyList()) = "addFiles/${Uri.encode(jobId)}" + query("files" to filesArg(files))
    fun jobInfo(jobId: String, link: String? = null) = "jobInfo/${Uri.encode(jobId)}" + query("link" to link)
    fun map(jobId: String, select: String? = null) = "map/${Uri.encode(jobId)}" + query("select" to select)
    fun pointsAdded(jobId: String) = "pointsAdded/${Uri.encode(jobId)}"
    fun camera(jobId: String, pointId: String) = "camera/${Uri.encode(jobId)}/${Uri.encode(pointId)}"
    fun reuse(jobId: String, pointId: String) = "reuse/${Uri.encode(jobId)}/${Uri.encode(pointId)}"
    fun wherePhotos(jobId: String) = "wherePhotos/${Uri.encode(jobId)}"

    /** One job's activity when [jobId] is given ([jobName] titles it), else every job's. */
    fun activity(jobId: String? = null, jobName: String? = null) = "admin/activity" + query("jobId" to jobId, "name" to jobName)

    private fun filesArg(files: List<Uri>) = files.joinToString("\n").ifEmpty { null }

    private fun query(vararg args: Pair<String, String?>): String =
        args.filter { it.second != null }.joinToString("&") { (name, value) -> "$name=${Uri.encode(value)}" }.let { if (it.isEmpty()) "" else "?$it" }
}

private fun NavBackStackEntry.jobId(): String = arguments?.getString("jobId").orEmpty()

private fun NavBackStackEntry.pointId(): String = arguments?.getString("pointId").orEmpty()

private fun NavBackStackEntry.files(): List<Uri> =
    arguments?.getString("files")?.split('\n')?.filter { it.isNotEmpty() }?.map(Uri::parse).orEmpty()

private val optional = { name: String -> navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null } }
private val jobIdArg = navArgument("jobId") { type = NavType.StringType }
private val pointIdArg = navArgument("pointId") { type = NavType.StringType }

/**
 * The app's screens. A stored session opens straight on the jobs; otherwise the login screen comes first. Syncing starts
 * on sign-in, and at launch with a stored session. When the server says the session has ended, the token is dropped and
 * the login screen comes back; the outbox is kept, and flushes after the next sign-in.
 *
 * [intake] is what another app shared ("Share → Fieldmaxx"): once signed in, "New job / Add to job" shows for it, once.
 * Saving a new job or adding files opens the job's map (R34).
 *
 * The menu (Task 30) opens from the map's ☰ for its job, and from the job list's without one. Signing out there comes
 * back to the login screen as an ended session does. The app checks for an update on every start, whichever screen it
 * opens on, once a day while it stays open, and right after a sign-in ([UpdateChecks], R88; the checker itself asks the
 * server at most once a day). The update banner shows on the job list and on the map.
 */
@Composable
fun FieldmaxxNav(graph: AppGraph, navController: NavHostController = rememberNavController(), intake: SharedIntake? = null) {
    val start = remember { if (graph.vault.load() != null) Routes.JOBS else Routes.LOGIN }
    val scope = rememberCoroutineScope() // lives as long as the navigation, unlike a screen's
    UpdateChecks(graph.updates, signedIn = { graph.vault.load() != null })
    LaunchedEffect(graph) {
        // Main-confined, as on the login screen: navigation runs on the main thread wherever a call finishes.
        withContext(Dispatchers.Main.immediate) {
            val toLogin: suspend () -> Unit = {
                withContext(Dispatchers.IO) { graph.vault.save(null) }
                navController.toLogin()
            }
            launch { graph.sync.authRequired.collect { toLogin() } }
            if (start == Routes.JOBS) resumeSync(graph, toLogin)
        }
    }
    var intakeShown by rememberSaveable { mutableStateOf(false) }
    var jobsNotice by rememberSaveable { mutableStateOf<String?>(null) } // e.g. the map's "This job was deleted" (R42)
    val toMap: (String) -> Unit = { jobId -> navController.navigate(Routes.map(jobId)) { popUpTo(Routes.JOBS) } }
    val back: () -> Unit = { navController.popBackStack() }
    val toJobs: () -> Unit = {
        if (!navController.popBackStack(Routes.JOBS, inclusive = false)) {
            navController.navigate(Routes.JOBS) { popUpTo(navController.graph.id) { inclusive = true } }
        }
    }
    val cameraDeps = remember(graph) { GraphCameraDeps(graph) }
    val stripDeps = remember(graph) { GraphPhotoStripDeps(graph) }
    val menuDeps = remember(graph) { GraphMenuDeps(graph) }

    /** The menu as a sheet over [jobId]'s map, or over the job list (null); every way out of it closes it first. */
    @Composable
    fun Menu(jobId: String?, close: () -> Unit) {
        MenuSheet(
            jobId, menuDeps,
            onOpen = { route ->
                close()
                navController.navigate(route)
            },
            onAllJobs = {
                close()
                toJobs()
            },
            onSignedOut = {
                close()
                navController.toLogin()
            },
            onDismiss = close,
        )
    }

    NavHost(navController, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(graph.api, graph.vault, graph.server) { user ->
                graph.sync.start(user)
                graph.refreshConfig() // the map's imagery and the photo quality come from the server (R39)
                scope.launch { graph.updates.checkQuietly() } // signed in now: the start's check was skipped without a session
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
            val context = LocalContext.current
            val installer = remember(graph, context) { UpdateInstaller(context, graph.api) }
            var menu by rememberSaveable { mutableStateOf(false) }
            JobListScreen(
                graph.jobList, graph.sync.status, graph.sync::savedUser,
                onOpenJob = { navController.navigate(Routes.map(it)) },
                onNewJob = { navController.navigate(Routes.newJob()) },
                onAddFiles = { navController.navigate(Routes.addFiles(it)) },
                onJobInfo = { navController.navigate(Routes.jobInfo(it)) },
                notice = jobsNotice,
                onNoticeShown = { jobsNotice = null },
                onMenu = { menu = true },
                banner = { UpdateBanner(graph.updates.available, installer) },
            )
            if (menu) Menu(null) { menu = false }
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
        composable(Routes.MAP, arguments = listOf(jobIdArg, optional("select"))) { entry ->
            val controller = rememberMapLibreController(graph.compass.heading)
            val pointDeps = remember(graph) { GraphPointDeps(graph) }
            val gallery = rememberGalleryPick(cameraDeps)
            var menu by rememberSaveable { mutableStateOf(false) }
            val context = LocalContext.current
            val installer = remember(graph, context) { UpdateInstaller(context, graph.api) }
            MapScreen(
                entry.jobId(), remember(graph) { GraphMapDeps(graph) }, controller, mapView = { controller.View(it) },
                onBack = back,
                initialPointId = entry.arguments?.getString("select"),
                onMenu = { menu = true }, // R43: the ☰ opens the menu for this job
                banner = { UpdateBanner(graph.updates.available, installer) }, // R88: the update offer on the map too
                onJobGone = { message ->
                    jobsNotice = message
                    if (!navController.popBackStack(Routes.JOBS, inclusive = false)) {
                        navController.navigate(Routes.JOBS) { popUpTo(Routes.MAP) { inclusive = true } }
                    }
                },
                pointSheet = { pointId, close ->
                    PointSheet(
                        entry.jobId(), pointId, pointDeps, controller, onClose = close,
                        onTakePhoto = { p -> navController.navigate(Routes.camera(p.jobId, p.id)) { launchSingleTop = true } },
                        onPickFromGallery = { p -> gallery.pick(p.jobId, p.id) },
                        onUseExistingPhotos = { p -> navController.navigate(Routes.reuse(p.jobId, p.id)) { launchSingleTop = true } },
                        photoStrip = { p, photos -> PhotoStrip(p, photos, stripDeps) },
                    )
                },
            )
            if (menu) Menu(entry.jobId()) { menu = false }
        }
        composable(Routes.CAMERA, arguments = listOf(jobIdArg, pointIdArg)) { entry ->
            val camera = rememberCameraXCaptureController()
            CameraScreen(entry.jobId(), entry.pointId(), cameraDeps, camera, onBack = back, preview = { camera.Preview(it) })
        }
        composable(Routes.REUSE, arguments = listOf(jobIdArg, pointIdArg)) { entry ->
            ReusePicker(entry.jobId(), entry.pointId(), remember(graph) { GraphReuseDeps(graph) }, onDone = back)
        }
        composable(Routes.WHERE_PHOTOS, arguments = listOf(jobIdArg)) { entry ->
            val jobId = entry.jobId()
            WherePhotosWentScreen(
                remember(graph, jobId) { graph.sync.job(jobId) },
                remember(graph, jobId) { graph.sync.points(jobId) },
                remember(graph, jobId) { jobPhotoViewsFor(jobId, graph.db, graph.server::get) },
                onBack = back,
            )
        }
        composable(Routes.POINTS_ADDED, arguments = listOf(jobIdArg)) { entry ->
            val jobId = entry.jobId()
            PointsIAddedScreen(
                remember(graph, jobId) { graph.sync.points(jobId) }, { graph.sync.savedUser()?.id.orEmpty() },
                onOpen = { pointId -> navController.navigate(Routes.map(jobId, pointId)) { popUpTo(Routes.MAP) { inclusive = true } } },
                onBack = back,
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen(graph.photoSettings, onBack = back) }

        // ---- the admin screens (spec §6.9): the menu shows them to admins only ----
        composable(Routes.USERS) { UsersScreen(graph.api, graph.sync::savedUser, onBack = back) }
        composable(Routes.ADMIN_JOBS) {
            AdminJobsScreen(
                graph.api,
                onOpenJob = { navController.navigate(Routes.map(it)) },
                onActivity = { jobId, name -> navController.navigate(Routes.activity(jobId, name)) },
                onBack = back,
            )
        }
        composable(Routes.DELIVERY_HEALTH) { DeliveryHealthScreen(graph.api, onReport = graph.stuckPhotos::update, onBack = back) }
        composable(Routes.ACTIVITY, arguments = listOf(optional("jobId"), optional("name"))) { entry ->
            val jobId = entry.arguments?.getString("jobId")
            val name = entry.arguments?.getString("name")
            val title = when {
                jobId == null -> "Activity · every job"
                name != null -> "Activity · $name"
                else -> "Activity"
            }
            ActivityScreen(graph.api, jobId, title, graph.sync::enqueue, onBack = back)
        }
        composable(Routes.ADMIN_SETTINGS) {
            AdminSettingsScreen(graph.api, kept = graph::currentConfig, onSaved = graph::refreshConfig, onBack = back)
        }
    }
}

/**
 * R88: the app's update checks, tied to the lifecycle of whatever hosts them: placed above the NavHost, that is the
 * activity's, so they run on every start whichever screen Android opens on (the map after a restore too) and once a
 * day while the app stays open ([checkWhileStarted]).
 */
@Composable
internal fun UpdateChecks(checker: UpdateChecker, signedIn: () -> Boolean, pause: suspend () -> Unit = ::pollPause) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session by rememberUpdatedState(signedIn)
    val between by rememberUpdatedState(pause)
    LaunchedEffect(checker, lifecycle) { checker.checkWhileStarted(lifecycle, { session() }, { between() }) }
}

/**
 * To the login screen with nothing to go back to: after the session ended on the server, or on Menu → Sign out. The
 * jobs screen is the root of every signed-in back stack, so popping it clears them all.
 */
private fun NavHostController.toLogin() = navigate(Routes.LOGIN) {
    popUpTo(Routes.JOBS) { inclusive = true }
    launchSingleTop = true
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
