package com.fieldmaxx.app.menu

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.AppGraph
import com.fieldmaxx.app.BuildConfig
import com.fieldmaxx.app.admin.StuckRed
import com.fieldmaxx.app.admin.stuckCount
import com.fieldmaxx.app.admin.userLine
import com.fieldmaxx.app.jobs.ExportDialog
import com.fieldmaxx.app.jobs.FinishJobDialog
import com.fieldmaxx.app.jobs.POLE_DATA_EXPORTS
import com.fieldmaxx.app.jobs.finishJob
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.net.TokenVault
import com.fieldmaxx.app.photos.exportPhotosToOneDriveApp as exportToOneDriveApp
import com.fieldmaxx.app.sync.SyncEngine
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.app.ui.Routes
import com.fieldmaxx.app.update.UpdateBanner
import com.fieldmaxx.app.update.UpdateChecker
import com.fieldmaxx.app.update.UpdateInstaller
import com.fieldmaxx.core.model.HealthReport
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The server's minimum (users/UserService.kt), checked here first so the tech hears it without a round trip. */
private const val MIN_PASSWORD = 8
private const val UP_TO_DATE = "Fieldmaxx is up to date"
private const val UPDATE_NEEDS_SIGNAL = "Can't reach the server. Checking for an update needs signal."
private const val REFRESH_OFFLINE = "Can't reach the server. The job shows what this phone has."
private const val PASSWORD_NEEDS_SIGNAL = "Can't reach the server. Changing the password needs signal."
private const val NO_LINK_APP = "No app on this phone can open the link."
private const val SIGN_OUT_INFO = "Changes waiting to be sent stay on this phone and go after the next sign-in. Signing in again needs signal."

/** How long sign-out waits for the server to end the session; without signal it doesn't wait longer, the token goes anyway. */
private const val LOGOUT_WAIT_MS = 5_000L

object MenuTags {
    const val NOTE = "menu.note"
    const val ALL_JOBS = "menu.allJobs"
    const val JOB_INFO = "menu.jobInfo"
    const val FIELD_NOTES = "menu.fieldNotes"
    const val POINTS_ADDED = "menu.pointsAdded"
    const val WHERE_PHOTOS = "menu.wherePhotos"
    const val EXPORT_ONEDRIVE = "menu.exportOneDrive"
    const val POLE_DATA = "menu.poleData"
    const val FINISH = "menu.finish"
    const val REFRESH = "menu.refresh"
    const val PROFILE = "menu.profile"
    const val CHANGE_PASSWORD = "menu.changePassword"
    const val PASSWORD_CURRENT = "menu.password.current"
    const val PASSWORD_NEW = "menu.password.new"
    const val PASSWORD_REPEAT = "menu.password.repeat"
    const val PASSWORD_SAVE = "menu.password.save"
    const val SIGN_OUT = "menu.signOut"
    const val SIGN_OUT_CONFIRM = "menu.signOut.confirm"
    const val USERS = "menu.admin.users"
    const val ADMIN_JOBS = "menu.admin.jobs"
    const val DELIVERY_HEALTH = "menu.admin.health"
    const val STUCK_COUNT = "menu.admin.health.count"
    const val ACTIVITY = "menu.admin.activity"
    const val ADMIN_SETTINGS = "menu.admin.settings"
    const val SETTINGS = "menu.settings"
    const val VERSION = "menu.version"
    const val CHECK_UPDATE = "menu.checkUpdate"
}

/**
 * R71: the red count on the menu, the photos stuck across the crew, for admins. Fetched each time the menu opens and
 * kept for the session, so the next opening shows it at once (and without signal); Delivery health updates it too.
 * Signing out forgets it: the next user may be a tech.
 */
class StuckPhotos(private val api: Api) {
    private val _count = MutableStateFlow<Int?>(null)

    /** Null until known. */
    val count: StateFlow<Int?> = _count.asStateFlow()

    /** Admins only (an admin route); throws [IOException] or [ApiException]. */
    suspend fun refresh() = update(api.health())

    fun update(report: HealthReport) {
        _count.value = stuckCount(report)
    }

    fun clear() {
        _count.value = null
    }
}

/** What the menu reads and does; [GraphMenuDeps] in the app, a fake in tests. */
interface MenuDeps {
    val api: Api
    val updates: UpdateChecker
    val stuckPhotos: StuckPhotos
    val versionName: String

    /** The signed-in user, as saved at sign-in. */
    suspend fun user(): User?
    fun job(jobId: String): Flow<Job?>

    /** Refresh: fetches the job again; throws [IOException] or [ApiException], and what is stored stays. */
    suspend fun refreshJob(jobId: String)

    /** Task 29's export; what to tell the tech, or null once the share sheet shows. Throws [IOException]. */
    suspend fun exportPhotosToOneDriveApp(context: Context, jobId: String): String?
    fun installer(context: Context): UpdateInstaller

    /** Ends the session on this phone ([endSession]). */
    suspend fun signOut()
}

/** [MenuDeps] from the app's graph. */
class GraphMenuDeps(private val graph: AppGraph) : MenuDeps {
    override val api: Api get() = graph.api
    override val updates: UpdateChecker get() = graph.updates
    override val stuckPhotos: StuckPhotos get() = graph.stuckPhotos
    override val versionName: String get() = BuildConfig.VERSION_NAME
    override suspend fun user(): User? = graph.sync.savedUser()
    override fun job(jobId: String): Flow<Job?> = graph.sync.job(jobId)
    override suspend fun refreshJob(jobId: String) = graph.sync.openJob(jobId)
    override suspend fun exportPhotosToOneDriveApp(context: Context, jobId: String): String? = exportToOneDriveApp(context, graph.db, jobId)
    override fun installer(context: Context) = UpdateInstaller(context, graph.api)
    override suspend fun signOut() = endSession(graph.api, graph.vault, graph.sync, graph.stuckPhotos)
}

/**
 * Sign out (spec §6.12): syncing stops, the server is asked to end the session (best effort, a few seconds at most) and
 * the token goes from the phone. Its data and the outbox stay (R20): what waits is sent after the next sign-in.
 */
suspend fun endSession(api: Api, vault: TokenVault, sync: SyncEngine, stuck: StuckPhotos) {
    sync.stop()
    try {
        withTimeoutOrNull(LOGOUT_WAIT_MS) { api.logout() }
    } catch (e: IOException) {
        // no signal: the token goes all the same
    } catch (e: ApiException) {
        // the session had ended already
    }
    withContext(Dispatchers.IO) { vault.save(null) }
    stuck.clear()
}

private enum class MenuDialog { POLE_DATA, FINISH, PASSWORD, SIGN_OUT }

/**
 * The menu (spec §6.12), from the map's ☰ for [jobId], or from the job list without one. Job: All jobs, Job info, the
 * Field Notes link, Points I added, Where the photos went, Export photos to OneDrive app, Pole data export, Finish this
 * job, Refresh. Account: the profile, change password, sign out. Admin (admins only, with the red count of stuck photos,
 * R71): the admin screens. Settings. The app's version and "Check for update", and the update banner once there is one.
 * [onOpen] goes to a [Routes] screen; [onSignedOut] once the session has ended on this phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(jobId: String?, deps: MenuDeps, onOpen: (route: String) -> Unit, onAllJobs: () -> Unit, onSignedOut: () -> Unit, onDismiss: () -> Unit) {
    // Main-confined, as on the other screens: the state is written on the main thread wherever a call finishes.
    val main = Dispatchers.Main.immediate
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<User?>(null) }
    val job by remember(deps, jobId) { jobId?.let(deps::job) ?: flowOf(null) }.collectAsState(null, main)
    val stuck by deps.stuckPhotos.count.collectAsState(main)
    val installer = remember(deps, context) { deps.installer(context) }
    var note by remember { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf<MenuDialog?>(null) }
    var checking by remember { mutableStateOf(false) }

    LaunchedEffect(deps) {
        withContext(main) {
            val me = deps.user()
            user = me
            if (me?.role != Role.admin) return@withContext // techs never call admin routes (R71)
            try {
                deps.stuckPhotos.refresh()
            } catch (e: IOException) {
                // the count kept from before stays
            } catch (e: ApiException) {
                // the same
            }
        }
    }

    /** Shows [busy] while [work] runs, then what it says. */
    fun report(busy: String?, work: suspend () -> String?) {
        note = busy
        scope.launch(main) { note = work() }
    }

    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            UpdateBanner(deps.updates.available, installer, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            note?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(MenuTags.NOTE)) }

            if (jobId != null) {
                Section(job?.name ?: "This job")
                Entry("All jobs", MenuTags.ALL_JOBS, onClick = onAllJobs)
                Entry("Job info", MenuTags.JOB_INFO) { onOpen(Routes.jobInfo(jobId)) }
                val link = job?.fieldNotesUrl?.trim().orEmpty()
                Entry("Field Notes link", MenuTags.FIELD_NOTES, supporting = if (link.isEmpty()) "None yet: add it in Job info" else "Opens the Field Notes folder") {
                    if (link.isEmpty()) onOpen(Routes.jobInfo(jobId)) else note = openLink(context, link)
                }
                Entry("Points I added", MenuTags.POINTS_ADDED) { onOpen(Routes.pointsAdded(jobId)) }
                Entry("Where the photos went", MenuTags.WHERE_PHOTOS) { onOpen(Routes.wherePhotos(jobId)) }
                Entry("Export photos to OneDrive app", MenuTags.EXPORT_ONEDRIVE, supporting = "The photos not in OneDrive yet, as a ZIP") {
                    report("Making the ZIP…") {
                        try {
                            deps.exportPhotosToOneDriveApp(context, jobId)
                        } catch (e: IOException) {
                            "Couldn't make the ZIP: ${e.message}"
                        } catch (e: ActivityNotFoundException) {
                            "No app on this phone can take the ZIP."
                        }
                    }
                }
                Entry("Pole data export", MenuTags.POLE_DATA, supporting = "Excel or CSV, to save or share") { dialog = MenuDialog.POLE_DATA }
                val finished = job?.status == "finished"
                Entry("Finish this job", MenuTags.FINISH, supporting = if (finished) "Finished" else null, enabled = !finished) { dialog = MenuDialog.FINISH }
                Entry("Refresh", MenuTags.REFRESH) {
                    report("Refreshing…") {
                        try {
                            deps.refreshJob(jobId)
                            "Job refreshed"
                        } catch (e: IOException) {
                            REFRESH_OFFLINE
                        } catch (e: ApiException) {
                            e.message
                        }
                    }
                }
            }

            Section("Account")
            user?.let { me ->
                ListItem(
                    headlineContent = { Text(me.displayName) },
                    supportingContent = { Text(userLine(me)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.testTag(MenuTags.PROFILE),
                )
            }
            Entry("Change password", MenuTags.CHANGE_PASSWORD) { dialog = MenuDialog.PASSWORD }
            Entry("Sign out", MenuTags.SIGN_OUT) { dialog = MenuDialog.SIGN_OUT }

            if (user?.role == Role.admin) {
                Section("Admin")
                Entry("Users", MenuTags.USERS, supporting = "Create, reset password, disable, promote") { onOpen(Routes.USERS) }
                Entry("Jobs", MenuTags.ADMIN_JOBS, supporting = "Progress, crew, dates; restore deleted jobs") { onOpen(Routes.ADMIN_JOBS) }
                val count = stuck?.takeIf { it > 0 }
                Entry(
                    "Delivery health", MenuTags.DELIVERY_HEALTH, supporting = "Photos not in OneDrive yet, across the crew",
                    trailing = count?.let { n ->
                        {
                            Text(
                                "$n",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(StuckRed, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)
                                    .testTag(MenuTags.STUCK_COUNT),
                            )
                        }
                    },
                ) { onOpen(Routes.DELIVERY_HEALTH) }
                Entry(
                    "Activity", MenuTags.ACTIVITY,
                    supporting = (if (jobId != null) "This job" else "Every job") + ": who shot what, Can't shoot, deleted points",
                ) { onOpen(Routes.activity(jobId, job?.name)) }
                Entry("Admin settings", MenuTags.ADMIN_SETTINGS, supporting = "Photo quality, backups, map") { onOpen(Routes.ADMIN_SETTINGS) }
            }

            Section("Settings")
            Entry("Settings", MenuTags.SETTINGS, supporting = "Copy photos to the phone's gallery") { onOpen(Routes.SETTINGS) }

            Section("App")
            Text(
                "Fieldmaxx ${deps.versionName}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(MenuTags.VERSION),
            )
            Entry("Check for update", MenuTags.CHECK_UPDATE, enabled = !checking) {
                checking = true
                report("Checking for an update…") {
                    try {
                        if (deps.updates.check(force = true) == null) UP_TO_DATE else null // found: the banner above shows it
                    } catch (e: IOException) {
                        UPDATE_NEEDS_SIGNAL
                    } catch (e: ApiException) {
                        e.message
                    } finally {
                        checking = false
                    }
                }
            }
        }
    }

    when (dialog) {
        MenuDialog.POLE_DATA -> jobId?.let { id -> ExportDialog(id, job?.name ?: id, POLE_DATA_EXPORTS, deps.api, onDismiss = { dialog = null }) }
        MenuDialog.FINISH -> jobId?.let { id ->
            FinishJobDialog(job?.name ?: "this job", finish = { finishJob(deps.api, deps::refreshJob, id) }, onDismiss = { dialog = null })
        }
        MenuDialog.PASSWORD -> ChangePasswordDialog(
            deps.api,
            onChanged = {
                dialog = null
                note = "Password changed. Any other phone signed in as you has to sign in again."
            },
            onDismiss = { dialog = null },
        )
        MenuDialog.SIGN_OUT -> SignOutDialog(
            signOut = deps::signOut,
            onSignedOut = {
                dialog = null
                onSignedOut()
            },
            onDismiss = { dialog = null },
        )
        null -> {}
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = FmxColors.Accent,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Entry(
    title: String,
    tag: String,
    supporting: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val faded = MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        headlineContent = { Text(title, color = if (enabled) Color.Unspecified else faded) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).testTag(tag),
    )
}

/** The job's Field Notes folder in the phone's browser or the OneDrive app; what to tell the tech when no app can. */
private fun openLink(context: Context, link: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
    null
} catch (e: ActivityNotFoundException) {
    NO_LINK_APP
}

/** Account → Change password: the current one, and the new one twice. Nothing typed is kept in saved state. */
@Composable
private fun ChangePasswordDialog(api: Api, onChanged: () -> Unit, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PasswordField(current, { current = it }, "Current password", MenuTags.PASSWORD_CURRENT, !busy)
                PasswordField(next, { next = it }, "New password", MenuTags.PASSWORD_NEW, !busy)
                PasswordField(repeat, { repeat = it }, "New password again", MenuTags.PASSWORD_REPEAT, !busy)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    problem = when {
                        next.length < MIN_PASSWORD -> "Password must be at least $MIN_PASSWORD characters"
                        next != repeat -> "The new passwords don't match"
                        else -> null
                    }
                    if (problem != null) return@TextButton
                    busy = true
                    scope.launch(Dispatchers.Main.immediate) {
                        try {
                            api.changePassword(current, next)
                            onChanged()
                        } catch (e: IOException) {
                            problem = PASSWORD_NEEDS_SIGNAL
                        } catch (e: ApiException) {
                            problem = e.message // "Current password is wrong"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.testTag(MenuTags.PASSWORD_SAVE),
            ) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, tag: String, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** Account → Sign out, confirmed first: without signal, signing in again has to wait. */
@Composable
private fun SignOutDialog(signOut: suspend () -> Unit, onSignedOut: () -> Unit, onDismiss: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Sign out?") },
        text = { Text(SIGN_OUT_INFO) },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    scope.launch(Dispatchers.Main.immediate) {
                        signOut()
                        onSignedOut()
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag(MenuTags.SIGN_OUT_CONFIRM),
            ) { Text("Sign out") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
