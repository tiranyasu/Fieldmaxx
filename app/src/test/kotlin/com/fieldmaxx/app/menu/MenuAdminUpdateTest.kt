package com.fieldmaxx.app.menu

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.fieldmaxx.app.AppGraph
import com.fieldmaxx.app.admin.ActivityFilter
import com.fieldmaxx.app.admin.ActivityScreen
import com.fieldmaxx.app.admin.ActivityTags
import com.fieldmaxx.app.admin.AdminJobsScreen
import com.fieldmaxx.app.admin.AdminJobsTags
import com.fieldmaxx.app.admin.AdminSettingsScreen
import com.fieldmaxx.app.admin.AdminSettingsTags
import com.fieldmaxx.app.admin.DeliveryHealthScreen
import com.fieldmaxx.app.admin.HealthTags
import com.fieldmaxx.app.admin.UsersScreen
import com.fieldmaxx.app.admin.UsersTags
import com.fieldmaxx.app.admin.diskText
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.MetaEntity
import com.fieldmaxx.app.data.toEntity
import com.fieldmaxx.app.data.toOp
import com.fieldmaxx.app.data.toOutbox
import com.fieldmaxx.app.jobs.EXPORT_NEEDS_SIGNAL
import com.fieldmaxx.app.jobs.ExportDialog
import com.fieldmaxx.app.jobs.ExportKind
import com.fieldmaxx.app.jobs.ExportTags
import com.fieldmaxx.app.jobs.FinishTags
import com.fieldmaxx.app.jobs.JobListTags
import com.fieldmaxx.app.jobs.POLE_DATA_EXPORTS
import com.fieldmaxx.app.jobs.finishBlockedMessage
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.net.DEFAULT_CONFIG
import com.fieldmaxx.app.net.InMemoryTokenVault
import com.fieldmaxx.app.photos.Gallery
import com.fieldmaxx.app.photos.NOTHING_TO_EXPORT
import com.fieldmaxx.app.photos.PhotoSettings
import com.fieldmaxx.app.sync.ALICE
import com.fieldmaxx.app.sync.FakeLiveSocket
import com.fieldmaxx.app.sync.MetaKeys
import com.fieldmaxx.app.sync.SyncEngine
import com.fieldmaxx.app.sync.SyncStatus
import com.fieldmaxx.app.sync.job
import com.fieldmaxx.app.sync.photo
import com.fieldmaxx.app.sync.snapshot
import com.fieldmaxx.app.ui.FieldmaxxNav
import com.fieldmaxx.app.ui.FieldmaxxTheme
import com.fieldmaxx.app.ui.Routes
import com.fieldmaxx.app.update.InstallStep
import com.fieldmaxx.app.update.UPDATE_BANNER
import com.fieldmaxx.app.update.UpdateBanner
import com.fieldmaxx.app.update.UpdateChecker
import com.fieldmaxx.app.update.UpdateInstaller
import com.fieldmaxx.app.update.UpdateRejectedException
import com.fieldmaxx.app.update.UpdateTags
import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.ActivityItem
import com.fieldmaxx.core.model.AdminSettings
import com.fieldmaxx.core.model.AppConfig
import com.fieldmaxx.core.model.AppRelease
import com.fieldmaxx.core.model.CrewMember
import com.fieldmaxx.core.model.FinishResult
import com.fieldmaxx.core.model.HealthReport
import com.fieldmaxx.core.model.JobListItem
import com.fieldmaxx.core.model.JobSnapshot
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

private const val SERVER = "https://fmx.example.com"
private const val LINK = "https://1drv.ms/f/c/0000000000000000/Field-Notes"
private val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
private val NOV5 = ZonedDateTime.of(2026, 11, 5, 13, 35, 47, 0, ZONE).toInstant().toEpochMilli()
private val NOW = ZonedDateTime.of(2026, 11, 14, 10, 0, 0, 0, ZONE).toInstant().toEpochMilli()
private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR
private val ADMIN = User("u7", "owner", "Owner Admin", Role.admin, disabled = false)
private val BOB = User("u2", "bob", "Bob Tech", Role.tech, disabled = false)

/**
 * A phone's screen, for the tests that compare rows of a lazy list (Robolectric's default is smaller, and a lazy list
 * composes only the rows that show). Not for typing into a dialog: Robolectric never idles there at this size.
 */
private const val PHONE = "w411dp-h891dp"

/** What the tests' password maker hands out: plainly a test value, never one anyone signs in with. */
private const val FAKE_TEMP_PASSWORD = "FAKE-TEMP-PASSWORD"

/** Stand-ins for an APK: the one the server published, and one that came down damaged or replaced on the way. */
private val APK = "PK\u0003\u0004 published test apk".toByteArray()
private val TAMPERED = "PK\u0003\u0004 tampered test apk".toByteArray()

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private val RELEASE = AppRelease(2, "1.2.0", "$SERVER/app/fieldmaxx-1.2.0.apk", sha256(APK))

private data class CreatedUser(val username: String, val displayName: String, val password: String, val role: Role)
private data class UserPatch(val id: String, val displayName: String?, val role: Role?, val disabled: Boolean?, val password: String?)

/** The server as the menu, the admin screens and the updater use it; every other call fails the test. */
private class AdminFakeApi : Api by notUsed() {
    val users = CopyOnWriteArrayList<User>()
    val created = CopyOnWriteArrayList<CreatedUser>()
    val patches = CopyOnWriteArrayList<UserPatch>()

    @Volatile var health = HealthReport(10, emptyList(), null, null)
    val healthCalls = AtomicInteger()

    /** Set to hold the health report's answer until the test completes it. */
    @Volatile var healthGate: CompletableDeferred<Unit>? = null
    val retried = CopyOnWriteArrayList<String>()

    val jobList = CopyOnWriteArrayList<JobListItem>()
    val jobsCalls = CopyOnWriteArrayList<String?>()
    val restored = CopyOnWriteArrayList<String>()
    val snapshots = ConcurrentHashMap<String, JobSnapshot>()

    @Volatile var activityItems: List<ActivityItem> = emptyList()
    val activityCalls = CopyOnWriteArrayList<String?>()

    @Volatile var serverConfig: AppConfig = DEFAULT_CONFIG
    val settingsPut = CopyOnWriteArrayList<AdminSettings>()

    @Volatile var release: AppRelease? = RELEASE
    @Volatile var latestError: Exception? = null
    val latestCalls = AtomicInteger()

    /** What the server sends for a download path, and the paths asked for. */
    @Volatile var served: (String) -> ByteArray = { APK }
    @Volatile var downloadError: Exception? = null
    val downloads = CopyOnWriteArrayList<String>()

    @Volatile var finishReply = FinishResult(finished = true)
    val finished = CopyOnWriteArrayList<String>()
    val passwordChanges = CopyOnWriteArrayList<Pair<String, String>>()

    override suspend fun adminUsers(): List<User> = users.toList()

    override suspend fun createUser(username: String, displayName: String, password: String, role: Role): User {
        created += CreatedUser(username, displayName, password, role)
        return User("u${users.size + 10}", username, displayName, role, disabled = false).also { users += it }
    }

    override suspend fun patchUser(id: String, displayName: String?, role: Role?, disabled: Boolean?, password: String?): User {
        patches += UserPatch(id, displayName, role, disabled, password)
        val i = users.indexOfFirst { it.id == id }
        val u = users[i].let { it.copy(displayName = displayName ?: it.displayName, role = role ?: it.role, disabled = disabled ?: it.disabled) }
        users[i] = u
        return u
    }

    override suspend fun health(): HealthReport {
        healthCalls.incrementAndGet()
        healthGate?.await()
        return health
    }

    override suspend fun retryPhoto(id: String) {
        retried += id
    }

    override suspend fun jobs(include: String?): List<JobListItem> {
        jobsCalls += include
        return jobList.toList()
    }

    override suspend fun restoreJob(jobId: String) {
        restored += jobId
        val i = jobList.indexOfFirst { it.job.id == jobId }
        jobList[i] = jobList[i].let { it.copy(job = it.job.copy(deletedAt = null)) }
    }

    override suspend fun snapshot(jobId: String): JobSnapshot = snapshots[jobId] ?: throw ApiException(404, "Job not found")

    override suspend fun activity(jobId: String?): List<ActivityItem> {
        activityCalls += jobId
        return activityItems
    }

    override suspend fun config(): AppConfig = serverConfig

    override suspend fun putSettings(s: AdminSettings) {
        settingsPut += s
    }

    override suspend fun latestRelease(): AppRelease? {
        latestCalls.incrementAndGet()
        latestError?.let { throw it }
        return release
    }

    override suspend fun download(path: String, to: File) {
        downloads += path
        downloadError?.let { throw it }
        withContext(Dispatchers.IO) {
            to.parentFile?.mkdirs()
            to.writeBytes(served(path))
        }
    }

    override suspend fun finishJob(jobId: String): FinishResult {
        finished += jobId
        return finishReply
    }

    override suspend fun changePassword(current: String, next: String) {
        passwordChanges += current to next
    }
}

private fun notUsed(): Api = Proxy.newProxyInstance(Api::class.java.classLoader, arrayOf(Api::class.java)) { _, method, _ ->
    throw UnsupportedOperationException("Api.${method.name} is not used by the menu or the admin screens")
} as Api

/** The menu's needs: the fake server, the phone's job (Room through the sync engine), a real update checker. */
private class FakeMenuDeps(override val api: AdminFakeApi, private val sync: SyncEngine, override val updates: UpdateChecker) : MenuDeps {
    @Volatile var me: User? = ALICE
    val refreshed = CopyOnWriteArrayList<String>()
    val exported = CopyOnWriteArrayList<String>()
    val signOuts = AtomicInteger()
    override val stuckPhotos = StuckPhotos(api)
    override val versionName = "0.1.0"

    override suspend fun user(): User? = me
    override fun job(jobId: String) = sync.job(jobId)

    override suspend fun refreshJob(jobId: String) {
        refreshed += jobId
    }

    override suspend fun exportPhotosToOneDriveApp(context: Context, jobId: String): String? {
        exported += jobId
        return NOTHING_TO_EXPORT
    }

    override fun installer(context: Context) = UpdateInstaller(context, api) { true }

    override suspend fun signOut() {
        signOuts.incrementAndGet()
    }
}

/** What a screen asked the system for, and the contract it asked with. */
private class Launch(val contract: ActivityResultContract<*, *>, val input: Any?)

/** Records what a screen asks the system for (a permission), instead of showing the system's dialog. */
private class RecordingRegistry : ActivityResultRegistry() {
    val launches = CopyOnWriteArrayList<Launch>()
    @Volatile private var lastCode = -1

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        lastCode = requestCode
        launches += Launch(contract, input)
    }

    /** The system's answer to the last request. */
    fun answer(result: Any?) {
        dispatchResult(lastCode, result)
    }
}

/**
 * Task 30: the menu (spec §6.12), the admin screens (§6.9), exports and finishing a job (§6.10), the in-app update
 * (§6.11, R69) and the tech's settings. Room in memory, a fake server, and nothing reaches a network or OneDrive.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MenuAdminUpdateTest {
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    /** The database closes once the screen under test is gone (JUnit's @After runs before the compose rule ends the activity). */
    @get:Rule val rules: RuleChain = RuleChain.outerRule(
        object : ExternalResource() {
            override fun after() = db.close()
        },
    ).around(compose)
    @get:Rule val temp = TemporaryFolder()

    private val api = AdminFakeApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sync = SyncEngine(api, db, FakeLiveSocket(), scope, { NOW }, { SERVER })
    private val registry = RecordingRegistry()
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }
    private val app get() = shadowOf(context as Application)

    @Before fun setUp() {
        forgetFileProviderRoots()
        ShadowLog.clear()
    }

    @After fun tearDown() {
        sync.stop()
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        File(context.cacheDir, "shared").deleteRecursively()
        forgetFileProviderRoots()
    }

    /**
     * FileProvider keeps each authority's roots in a static map, while Robolectric gives every test a fresh cacheDir in the
     * same class loader: a root another test read first would point at that test's cacheDir. Cleared before and after.
     */
    private fun forgetFileProviderRoots() {
        val roots = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>
        synchronized(roots) { roots.clear() }
    }

    // ---- fixtures ----

    private fun store(name: String): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
        produceFile = { File(temp.root, "$name.preferences_pb") },
    )

    /** Job A05J9XE stored on the phone, with its Field Notes link. */
    private fun seedJob() = runBlocking { db.jobs().upsert(job("j1", "A05J9XE").copy(fieldNotesUrl = LINK).toEntity(seq = 1, storedAt = NOW)) }

    private fun menuDeps(me: User = ALICE) =
        FakeMenuDeps(api, sync, UpdateChecker(api, currentVersionCode = 1, now = { NOW }, store = store("updates"))).also { it.me = me }

    private fun stuck(id: String, by: User, reason: String) =
        photo(id, "p5").copy(userId = by.id, status = PhotoStatus.stuck, lastError = reason, fileName = "POLE_5_39.636130_-119.691104_$id.jpg", takenAt = NOV5)

    private fun waiting(id: String): PhotoInfo = photo(id, "p6").copy(userId = BOB.id, fileName = null, takenAt = NOV5 + HOUR)

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { FieldmaxxTheme { content() } }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(5_000) {
        compose.waitForIdle()
        condition()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitForTag(tag: String) = waitFor { exists(tag) }
    private fun hasText(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitForText(text: String) = waitFor { hasText(text) }

    /** The text of a node that is itself a Text (or a field), by its tag. */
    private fun textOf(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.let { c ->
            c.getOrNull(SemanticsProperties.EditableText)?.text ?: c[SemanticsProperties.Text].joinToString("") { it.text }
        }

    private fun top(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

    /** Taps the node, brought into view first when it is in a scrolling list (a lazy list composes only what shows). */
    private fun tap(tag: String) {
        if (!exists(tag) && compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().size == 1) {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        }
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        try {
            node.performScrollTo()
        } catch (e: AssertionError) {
            // not in a scrolling list
        }
        node.performClick()
    }

    private fun tapText(text: String) {
        compose.onNodeWithText(text, useUnmergedTree = true).performClick()
    }

    private fun outbox(): List<Op> = runBlocking { db.outbox().all().map { it.toOp() } }

    // ---- the brief's tests ----

    @Test fun menuAdminSectionOnlyForAdmins() {
        seedJob()
        val deps = menuDeps(me = ALICE)
        val opened = CopyOnWriteArrayList<String>()
        var who by mutableStateOf(ALICE)
        compose.setContent {
            Host { key(who) { MenuSheet("j1", deps, onOpen = { opened += it }, onAllJobs = {}, onSignedOut = {}, onDismiss = {}) } }
        }
        val adminTags = listOf(MenuTags.USERS, MenuTags.ADMIN_JOBS, MenuTags.DELIVERY_HEALTH, MenuTags.ACTIVITY, MenuTags.ADMIN_SETTINGS)

        // A tech's menu (§6.12): the job, the account, the settings, the app; no admin screens, and no admin route asked (R71).
        waitForTag(MenuTags.PROFILE)
        for (tag in listOf(
            MenuTags.ALL_JOBS, MenuTags.JOB_INFO, MenuTags.FIELD_NOTES, MenuTags.POINTS_ADDED, MenuTags.WHERE_PHOTOS, MenuTags.EXPORT_ONEDRIVE,
            MenuTags.POLE_DATA, MenuTags.FINISH, MenuTags.REFRESH, MenuTags.CHANGE_PASSWORD, MenuTags.SIGN_OUT, MenuTags.SETTINGS,
            MenuTags.VERSION, MenuTags.CHECK_UPDATE,
        )) {
            assertTrue(exists(tag), tag)
        }
        assertTrue(hasText("Alice Tech") && hasText("alice · Tech"), "the profile")
        assertEquals("Fieldmaxx 0.1.0", textOf(MenuTags.VERSION))
        for (tag in adminTags) assertFalse(exists(tag), "a tech sees no $tag")
        assertEquals(0, api.healthCalls.get(), "techs never call admin routes")

        // The job's entries open their screens.
        tap(MenuTags.POINTS_ADDED)
        tap(MenuTags.WHERE_PHOTOS)
        tap(MenuTags.JOB_INFO)
        assertEquals(listOf(Routes.pointsAdded("j1"), Routes.wherePhotos("j1"), Routes.jobInfo("j1")), opened.toList())
        tap(MenuTags.FIELD_NOTES)
        val folder = assertNotNull(app.nextStartedActivity, "the Field Notes link opens")
        assertEquals(Intent.ACTION_VIEW, folder.action)
        assertEquals(LINK, folder.dataString)
        tap(MenuTags.EXPORT_ONEDRIVE)
        waitForText(NOTHING_TO_EXPORT)
        assertEquals(listOf("j1"), deps.exported.toList(), "Export photos to OneDrive app, from Task 29")

        // An admin's menu has the admin screens.
        opened.clear()
        deps.me = ADMIN
        who = ADMIN
        waitForTag(MenuTags.USERS)
        for (tag in adminTags) assertTrue(exists(tag), tag)
        for (tag in adminTags) tap(tag)
        assertEquals(
            listOf(Routes.USERS, Routes.ADMIN_JOBS, Routes.DELIVERY_HEALTH, Routes.activity("j1", "A05J9XE"), Routes.ADMIN_SETTINGS),
            opened.toList(),
        )
        waitFor { api.healthCalls.get() == 1 }
    }

    @Test @Config(qualifiers = PHONE) fun stuckRedCountOnDeliveryHealth() {
        seedJob()
        api.health = HealthReport(42, listOf(stuck("s1", BOB, "The link no longer allows uploads"), stuck("s2", ALICE, "No signal"), waiting("w1")), 40, 4)
        api.users += listOf(ADMIN, ALICE, BOB)
        api.jobList += JobListItem(job("j1", "A05J9XE"), 10, 4)
        val deps = menuDeps(me = ADMIN)
        var screen by mutableStateOf("menu")
        compose.setContent {
            Host {
                when (screen) {
                    "menu" -> MenuSheet("j1", deps, onOpen = {}, onAllJobs = {}, onSignedOut = {}, onDismiss = {})
                    "health" -> DeliveryHealthScreen(api, onReport = deps.stuckPhotos::update, onBack = {}, zone = ZONE)
                }
            }
        }

        // A red count on the menu (R71): the stuck photos, fetched as the menu opens.
        waitForTag(MenuTags.STUCK_COUNT)
        assertEquals("2", textOf(MenuTags.STUCK_COUNT))
        assertEquals(1, api.healthCalls.get())

        // Kept for the session: the next opening shows it at once, while it is fetched again.
        api.healthGate = CompletableDeferred()
        screen = "none"
        compose.waitForIdle()
        screen = "menu"
        waitForTag(MenuTags.STUCK_COUNT)
        assertEquals("2", textOf(MenuTags.STUCK_COUNT))
        waitFor { api.healthCalls.get() == 2 }
        api.health = api.health.copy(photos = listOf(waiting("w1")))
        api.healthGate!!.complete(Unit)
        waitFor { !exists(MenuTags.STUCK_COUNT) } // nothing stuck: no count

        // The delivery health screen: the same count, stuck photos first, each with its phone's last reason.
        api.health = HealthReport(42, listOf(stuck("s1", BOB, "The link no longer allows uploads"), stuck("s2", ALICE, "No signal"), waiting("w1")), 40, 4)
        screen = "health"
        waitForTag(HealthTags.photo("w1"))
        assertEquals("2 stuck", textOf(HealthTags.STUCK_COUNT))
        assertEquals("3 photos not in OneDrive yet", textOf(HealthTags.SUMMARY))
        assertTrue(top(HealthTags.photo("s1")) < top(HealthTags.photo("s2")) && top(HealthTags.photo("s2")) < top(HealthTags.photo("w1")))
        assertTrue(hasText("Stuck: The link no longer allows uploads") && hasText("Stuck: No signal"), "each phone's last reason")
        assertTrue(hasText("Bob Tech · A05J9XE · taken 11/05/26 13:35"), "whose, which job, when")
        assertEquals(2, deps.stuckPhotos.count.value, "the screen keeps the menu's count up to date")
        tap(HealthTags.retry("s1"))
        waitFor { api.retried.toList() == listOf("s1") }
        assertFalse(exists(HealthTags.retry("w1")), "only stuck photos have Retry now")
    }

    @Test fun deliveryHealthDiskAlertAndMedians() {
        api.health = HealthReport(92, emptyList(), 40, 4)
        var shown by mutableStateOf(1)
        compose.setContent { Host { key(shown) { DeliveryHealthScreen(api, onReport = {}, onBack = {}, zone = ZONE) } } }

        waitForTag(HealthTags.DISK)
        assertEquals("Server disk 92% full. Backups are still kept for their full time: add disk space.", textOf(HealthTags.DISK))
        assertEquals("Typical over the last 24 hours: 40 s from shutter to OneDrive, 4 s upload", textOf(HealthTags.TIMES))
        assertEquals("Every photo is in OneDrive", textOf(HealthTags.SUMMARY))
        assertFalse(exists(HealthTags.STUCK_COUNT), "no count when nothing is stuck")

        api.health = HealthReport(42, emptyList(), 125, null)
        shown = 2
        waitFor { exists(HealthTags.DISK) && textOf(HealthTags.DISK) == "Server disk 42% used" }
        assertEquals("Typical over the last 24 hours: 2 min 5 s from shutter to OneDrive", textOf(HealthTags.TIMES))

        api.health = HealthReport(42, emptyList(), null, null)
        shown = 3
        waitFor { exists(HealthTags.TIMES) && textOf(HealthTags.TIMES) == "No photos reached OneDrive in the last 24 hours" }

        // The admin is warned above 80% (spec §6.6).
        assertEquals("Server disk 80% used", diskText(80))
        assertTrue(diskText(81).startsWith("Server disk 81% full."))
    }

    @Test fun usersCreateShowsTempPasswordOnce() {
        api.users += listOf(ADMIN, ALICE)
        val made = AtomicInteger()
        compose.setContent {
            Host { UsersScreen(api, me = { ADMIN }, onBack = {}, newPassword = { made.incrementAndGet(); FAKE_TEMP_PASSWORD }) }
        }
        waitForText("Alice Tech")
        assertTrue(hasText("alice · Tech"))

        tap(UsersTags.NEW)
        compose.onNodeWithTag(UsersTags.DISPLAY_NAME).performTextInput("Ann Tech")
        compose.onNodeWithTag(UsersTags.USERNAME).performTextInput("ann")
        tap(UsersTags.CREATE)
        waitForTag(UsersTags.TEMP_PASSWORD)
        assertEquals(listOf(CreatedUser("ann", "Ann Tech", FAKE_TEMP_PASSWORD, Role.tech)), api.created.toList())
        assertEquals(FAKE_TEMP_PASSWORD, textOf(UsersTags.TEMP_PASSWORD))
        assertEquals(1, compose.onAllNodesWithText(FAKE_TEMP_PASSWORD, useUnmergedTree = true).fetchSemanticsNodes().size)

        // Shown once: gone when the admin is done, and the list has the new user.
        tap(UsersTags.TEMP_DONE)
        waitForText("Ann Tech")
        waitFor { !hasText(FAKE_TEMP_PASSWORD) }

        // A reset: a new temporary password, shown once the same way.
        tap(UsersTags.menu(ALICE.id))
        tapText("Reset password")
        tap(UsersTags.CONFIRM)
        waitForTag(UsersTags.TEMP_PASSWORD)
        assertEquals(UserPatch(ALICE.id, null, null, null, FAKE_TEMP_PASSWORD), api.patches.last())
        tap(UsersTags.TEMP_DONE)
        waitFor { !hasText(FAKE_TEMP_PASSWORD) }
        assertEquals(2, made.get(), "one password per create or reset")

        // Promote and disable; the admin's own row has none of it (no locking yourself out).
        tap(UsersTags.menu(ALICE.id))
        tapText("Make admin")
        tap(UsersTags.CONFIRM)
        waitFor { api.patches.last() == UserPatch(ALICE.id, null, Role.admin, null, null) }
        waitForText("alice · Admin")
        tap(UsersTags.menu(ALICE.id))
        tapText("Disable")
        tap(UsersTags.CONFIRM)
        waitFor { api.patches.last() == UserPatch(ALICE.id, null, null, true, null) }
        waitForText("alice · Admin · Disabled")
        assertFalse(exists(UsersTags.menu(ADMIN.id)))

        // Never logged, never kept on the phone.
        assertTrue(ShadowLog.getLogs().none { FAKE_TEMP_PASSWORD in it.msg.orEmpty() }, "not in the log")
        val kept = context.dataDir.walk().filter { it.isFile }.filter { FAKE_TEMP_PASSWORD in it.readText(Charsets.ISO_8859_1) }.toList()
        assertEquals(emptyList(), kept, "not in any file on the phone")
    }

    @Test @Config(qualifiers = PHONE) fun adminRestoresDeletedJob() {
        api.jobList += listOf(
            JobListItem(job("j1", "A05J9XE").copy(createdAt = NOV5), 40, 12),
            JobListItem(job("j3", "A05K51V").copy(status = "finished", createdAt = NOV5 - 7 * DAY, finishedAt = NOV5 + 4 * DAY), 8, 8),
            JobListItem(job("j2", "A05J9XF").copy(createdAt = NOV5, deletedAt = NOW - 2 * DAY), 5, 0),
        )
        api.snapshots["j1"] = snapshot(job("j1"), emptyList(), seq = 1, crew = listOf(CrewMember(ALICE.id, "Alice Tech"), CrewMember(BOB.id, "Bob Tech")))
        val activity = CopyOnWriteArrayList<String>()
        compose.setContent {
            Host { AdminJobsScreen(api, onOpenJob = {}, onActivity = { id, _ -> activity += id }, onBack = {}, now = { NOW }, zone = ZONE) }
        }

        waitForTag(AdminJobsTags.restore("j2"))
        assertEquals(listOf<String?>("finished,deleted"), api.jobsCalls.toList(), "all jobs: finished and deleted ones too")
        assertTrue(hasText("12/40 done"), "progress")
        assertTrue(hasText("Created 11/05/26"))
        assertTrue(hasText("Created 10/29/26 · Finished 11/09/26"))
        assertTrue(hasText("Deleted 11/12/26 · restore until 12/12/26"), "30 days")
        assertFalse(exists(AdminJobsTags.restore("j1")))

        // A job's crew, and its activity (where its deleted points are restored, R68).
        tap(AdminJobsTags.row("j1"))
        waitForTag(AdminJobsTags.crew("j1"))
        assertEquals("Crew: Alice Tech, Bob Tech", textOf(AdminJobsTags.crew("j1")))
        tap(AdminJobsTags.activity("j1"))
        assertEquals(listOf("j1"), activity.toList())

        tap(AdminJobsTags.restore("j2"))
        waitFor { api.restored.toList() == listOf("j2") }
        waitFor { !exists(AdminJobsTags.restore("j2")) }
        assertEquals(2, api.jobsCalls.size, "the list is fetched again: the job is back among the active ones")
        assertTrue(hasText("A05J9XF restored"))
    }

    @Test fun adminSettingsBackupDaysMin3Message() {
        api.serverConfig = DEFAULT_CONFIG
        val saved = AtomicInteger()
        compose.setContent { Host { AdminSettingsScreen(api, kept = { DEFAULT_CONFIG }, onSaved = { saved.incrementAndGet() }, onBack = {}) } }
        waitForTag(AdminSettingsTags.BACKUP_DAYS)
        assertEquals("3", textOf(AdminSettingsTags.BACKUP_DAYS))

        compose.onNodeWithTag(AdminSettingsTags.BACKUP_DAYS).performTextReplacement("2")
        tap(AdminSettingsTags.SAVE)
        waitFor { exists(AdminSettingsTags.MESSAGE) && textOf(AdminSettingsTags.MESSAGE) == "Backups must be kept at least 3 days" }
        compose.waitForIdle()
        assertTrue(api.settingsPut.isEmpty(), "nothing sent")
        assertEquals(0, saved.get())

        compose.onNodeWithTag(AdminSettingsTags.BACKUP_DAYS).performTextReplacement("5")
        tap(AdminSettingsTags.SAVE)
        waitFor { api.settingsPut.size == 1 }
        assertEquals(AdminSettings(backupDays = 5), api.settingsPut.single(), "only what changed")
        waitFor { saved.get() == 1 } // the phone's settings are fetched again: the map and the photo quality follow at once
        waitFor { textOf(AdminSettingsTags.MESSAGE) == "Settings saved" }

        tap(AdminSettingsTags.MAXIMUM)
        tap(AdminSettingsTags.SAVE)
        waitFor { api.settingsPut.size == 2 }
        assertEquals(AdminSettings(photoQuality = 95), api.settingsPut.last())
    }

    @Test fun exportPoleDataSavesOrShares() {
        val downloads = Robolectric.setupContentProvider(Gallery::class.java, MediaStore.AUTHORITY)
        val csv = "Point,Original lat,Original lon\nPOLE 1,39.636130,-119.691104\n".toByteArray()
        api.served = { csv }
        var sdk by mutableStateOf(35)
        compose.setContent { Host { key(sdk) { ExportDialog("j1", "A05J9XE", POLE_DATA_EXPORTS, api, onDismiss = {}, sdk = sdk) } } }

        // Pole data as CSV, downloaded into the folder the FileProvider serves.
        tap(ExportTags.choice(ExportKind.POLE_CSV))
        waitForTag(ExportTags.SHARE)
        assertEquals(listOf("/api/jobs/j1/pole-data.csv"), api.downloads.toList())
        val file = File(context.cacheDir, "shared/exports/A05J9XE_pole_data.csv")
        assertEquals(csv.toList(), file.readBytes().toList())

        // Save to Downloads (MediaStore, Android 10+).
        tap(ExportTags.SAVE)
        waitFor { downloads.published.size == 1 }
        val (collection, values) = downloads.inserts.single()
        assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, collection)
        assertEquals("A05J9XE_pole_data.csv", values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("text/csv", values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(csv.toList(), downloads.published.single().readBytes().toList())
        waitFor { textOf(ExportTags.STATUS) == "Saved to Downloads: A05J9XE_pole_data.csv" }

        // Or Share, through the app's FileProvider.
        tap(ExportTags.SHARE)
        val chooser = assertNotNull(app.nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = assertNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/csv", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = assertNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("content://${context.packageName}.files/shared/exports/A05J9XE_pole_data.csv", uri.toString())

        // Android 9: the share sheet only, no storage permission (R70).
        sdk = 28
        waitForTag(ExportTags.choice(ExportKind.POLE_XLSX))
        tap(ExportTags.choice(ExportKind.POLE_XLSX))
        waitForTag(ExportTags.SHARE)
        assertEquals("/api/jobs/j1/pole-data.xlsx", api.downloads.last())
        assertTrue(File(context.cacheDir, "shared/exports/A05J9XE_pole_data.xlsx").isFile)
        assertFalse(exists(ExportTags.SAVE))
        assertTrue(registry.launches.isEmpty(), "no permission asked")
    }

    @Test fun finishBlockedMessage() {
        assertEquals("7 points left, 3 photos not in OneDrive yet", finishBlockedMessage(FinishResult(false, 7, 3)))
        assertEquals("1 point left", finishBlockedMessage(FinishResult(false, 1, 0)))
        assertEquals("1 photo not in OneDrive yet", finishBlockedMessage(FinishResult(false, 0, 1)))

        // Menu → Finish this job: it asks first, then says what is left.
        seedJob()
        api.finishReply = FinishResult(finished = false, pointsLeft = 7, photosPending = 3)
        val deps = menuDeps()
        compose.setContent { Host { MenuSheet("j1", deps, onOpen = {}, onAllJobs = {}, onSignedOut = {}, onDismiss = {}) } }
        waitForTag(MenuTags.FINISH)
        tap(MenuTags.FINISH)
        waitForTag(FinishTags.CONFIRM)
        assertTrue(api.finished.isEmpty(), "asks first")
        tap(FinishTags.CONFIRM)
        waitForTag(FinishTags.MESSAGE)
        assertEquals("7 points left, 3 photos not in OneDrive yet", textOf(FinishTags.MESSAGE))
        assertEquals(listOf("j1"), api.finished.toList())
        assertTrue(deps.refreshed.isEmpty(), "nothing changed on the server")
        tap(FinishTags.OK)
        waitFor { !exists(FinishTags.MESSAGE) }

        // Everything confirmed: finished, and the job is fetched again so it shows Finished.
        api.finishReply = FinishResult(finished = true)
        tap(MenuTags.FINISH)
        tap(FinishTags.CONFIRM)
        waitForText("A05J9XE is finished")
        waitFor { deps.refreshed.toList() == listOf("j1") }
    }

    @Test fun updateCheckOncePerDay() {
        val kept = store("updates")
        var now = NOW
        val checker = UpdateChecker(api, currentVersionCode = 1, now = { now }, store = kept)
        runBlocking {
            assertEquals(RELEASE, checker.check())
            assertEquals(1, api.latestCalls.get())
            assertEquals(RELEASE, checker.available.value, "the banner's state")

            now += 23 * HOUR
            assertEquals(RELEASE, checker.check(), "the update found stays known")
            assertEquals(1, api.latestCalls.get(), "at most once a day")

            // Kept on the phone: a new start within the day doesn't ask again, and still knows the update.
            val restarted = UpdateChecker(api, currentVersionCode = 1, now = { now }, store = kept)
            assertEquals(RELEASE, restarted.check())
            assertEquals(1, api.latestCalls.get())

            now += 2 * HOUR
            checker.check()
            assertEquals(2, api.latestCalls.get(), "a day later it asks again")
            assertEquals(RELEASE, checker.check(force = true))
            assertEquals(3, api.latestCalls.get(), "Check for update asks every time")

            // No signal: the check counts for nothing, so the next one asks again.
            api.latestError = IOException("no signal")
            now += 2 * DAY
            assertFailsWith<IOException> { checker.check() }
            assertEquals(RELEASE, checker.available.value, "the update found before stays known")
            api.latestError = null
            checker.check()
            assertEquals(5, api.latestCalls.get())

            // Nothing newer than this version: no update.
            val current = UpdateChecker(api, currentVersionCode = 2, now = { now }, store = store("updates-current"))
            assertNull(current.check())
            assertNull(current.available.value)
        }
    }

    @Test fun updateBannerText() {
        seedJob()
        val deps = menuDeps()
        val installer = UpdateInstaller(compose.activity, api) { true }
        var screen by mutableStateOf("banner")
        compose.setContent {
            Host {
                when (screen) {
                    "banner" -> UpdateBanner(deps.updates.available, installer)
                    "menu" -> MenuSheet(null, deps, onOpen = {}, onAllJobs = {}, onSignedOut = {}, onDismiss = {})
                }
            }
        }
        compose.waitForIdle()
        assertFalse(exists(UpdateTags.BANNER), "nothing shows before a newer version is known")

        // Menu → Check for update finds it; the banner shows in the menu.
        screen = "menu"
        waitForTag(MenuTags.CHECK_UPDATE)
        tap(MenuTags.CHECK_UPDATE)
        waitForTag(UpdateTags.BANNER)
        assertEquals(1, api.latestCalls.get())
        assertTrue(hasText(UPDATE_BANNER))
        assertEquals("Update available — tap to install", UPDATE_BANNER)

        // Tapping it downloads, checks and opens Android's installer.
        screen = "banner"
        waitForTag(UpdateTags.BANNER)
        assertTrue(hasText(UPDATE_BANNER) && hasText("Version 1.2.0"))
        tap(UpdateTags.BANNER)
        waitFor { app.peekNextStartedActivity() != null }
        assertEquals(Intent.ACTION_VIEW, app.nextStartedActivity.action)
    }

    @Test fun installerRejectsShaMismatch() {
        api.served = { TAMPERED }
        val installer = UpdateInstaller(compose.activity, api) { true }

        assertFailsWith<UpdateRejectedException> { runBlocking { installer.downloadAndInstall(RELEASE) } }
        assertEquals(listOf("/app/fieldmaxx-1.2.0.apk"), api.downloads.toList(), "only ever from our server's release endpoint")

        // A release that can't be checked, or whose file isn't our server's, is refused before any download.
        api.served = { APK }
        for (bad in listOf(
            RELEASE.copy(sha256 = ""),
            RELEASE.copy(sha256 = "not-a-hash"),
            RELEASE.copy(url = "$SERVER/app/fieldmaxx-1.2.0.exe"),
            RELEASE.copy(url = "$SERVER/app/..%2F..%2Fsecrets.apk"),
            RELEASE.copy(url = "not a link"),
        )) {
            assertFailsWith<UpdateRejectedException>(bad.toString()) { runBlocking { installer.downloadAndInstall(bad) } }
        }
        assertEquals(1, api.downloads.size, "nothing else was downloaded")
        assertNull(app.nextStartedActivity, "nothing was installed")
    }

    @Test fun updateBannerTapNeverInstallsAMismatchedApk() {
        seedJob()
        api.served = { TAMPERED }
        val deps = menuDeps()
        runBlocking { deps.updates.check() }
        val installer = UpdateInstaller(compose.activity, api) { true }
        compose.setContent { Host { UpdateBanner(deps.updates.available, installer) } }

        tap(UpdateTags.BANNER)
        waitFor { exists(UpdateTags.STATUS) && textOf(UpdateTags.STATUS).startsWith("The download didn't match") }
        assertEquals(1, api.downloads.size)
        // R69: the file is deleted, and nothing went to the installer (nor to the install-permission settings).
        assertEquals(emptyList(), File(context.cacheDir, "shared").walk().filter { it.isFile }.toList(), "the rejected file is deleted")
        assertNull(app.nextStartedActivity, "the installer is never called")
    }

    @Test fun gallerySettingToggles() {
        val settings = PhotoSettings(store("photo_settings"))
        compose.setContent { Host { SettingsScreen(settings, onBack = {}, sdk = 35) } }

        waitForTag(SettingsTags.GALLERY)
        compose.onNodeWithTag(SettingsTags.GALLERY).assertIsOn() // on by default (R60)
        tap(SettingsTags.GALLERY)
        waitFor { runBlocking { !settings.galleryCopy.first() } }
        compose.onNodeWithTag(SettingsTags.GALLERY).assertIsOff()
        tap(SettingsTags.GALLERY)
        waitFor { runBlocking { settings.galleryCopy.first() } }
        compose.onNodeWithTag(SettingsTags.GALLERY).assertIsOn()
        assertTrue(registry.launches.isEmpty(), "Android 10+ asks for nothing")
    }

    // ---- the rulings' tests ----

    /** R68: a deleted point's activity item restores it, through the outbox. */
    @Test fun deletedPointActivityItemRestoresIt() {
        api.activityItems = listOf(
            ActivityItem(NOW - 1_000, "Owner Admin", "point.restore", "POLE 6", "", "p6"),
            ActivityItem(NOW - 120_000, "Alice Tech", "point.delete", "POLE 5", "", "p5"),
            ActivityItem(NOW - 180_000, "Alice Tech", "point.delete", "POLE 6", "", "p6"), // restored since
            ActivityItem(NOW - 240_000, "Bob Tech", "point.cantShoot", "POLE 7", "other: gate locked", "p7"),
            ActivityItem(NOW - 300_000, "Bob Tech", "photo.confirmed", null, "", null), // delivery, not activity
            ActivityItem(NOV5, "Bob Tech", "point.close", "POLE 1", "", "p1"),
        )
        compose.setContent { Host { ActivityScreen(api, "j1", "A05J9XE", sync::enqueue, onBack = {}, zone = ZONE) } }

        waitForTag(ActivityTags.restore("p5"))
        assertEquals(listOf<String?>("j1"), api.activityCalls.toList())
        assertTrue(hasText("Deleted by Alice Tech · 11/14/26 09:58"))
        assertFalse(exists(ActivityTags.restore("p6")), "a point restored since has nothing to restore")
        assertFalse(exists(ActivityTags.restore("p7")))
        assertFalse(hasText("Photo in OneDrive by Bob Tech · 11/14/26 09:55"), "deliveries are on Delivery health")

        tap(ActivityTags.restore("p5"))
        waitFor { outbox().isNotEmpty() }
        val restore = outbox().single()
        assertTrue(restore is Op.PointRestore && restore.pointId == "p5", "$restore")
        waitForTag(ActivityTags.restoreSent("p5"))
        assertFalse(exists(ActivityTags.restore("p5")), "sent once")

        // Who shot which point and when; the Can't shoot list with its reasons.
        tap(ActivityTags.filter(ActivityFilter.SHOT))
        waitFor { !hasText("POLE 5") }
        assertTrue(hasText("POLE 1") && hasText("Shot by Bob Tech · 11/05/26 13:35"))
        tap(ActivityTags.filter(ActivityFilter.CANT_SHOOT))
        waitFor { !hasText("POLE 1") }
        assertTrue(hasText("POLE 7") && hasText("Other: gate locked"))
    }

    /** R69: a downloaded APK whose SHA-256 differs from the release's is deleted, and the installer is never called. */
    @Test fun mismatchedApkIsDeletedAndNeverInstalled() {
        api.served = { TAMPERED }
        for (allowed in listOf(true, false)) {
            val installer = UpdateInstaller(compose.activity, api) { allowed }
            assertFailsWith<UpdateRejectedException> { runBlocking { installer.downloadAndInstall(RELEASE) } }
            assertEquals(emptyList(), File(context.cacheDir, "shared").walk().filter { it.isFile }.toList(), "deleted (installs allowed: $allowed)")
            assertNull(app.nextStartedActivity, "neither the installer nor the install settings (installs allowed: $allowed)")
        }
        assertEquals(2, api.downloads.size)
    }

    /** R69: only a verified APK reaches the installer, through the FileProvider; without the install permission its settings come first. */
    @Test fun installerOpensOnlyAVerifiedApk() {
        var allowed = false
        val installer = UpdateInstaller(compose.activity, api) { allowed }

        assertEquals(InstallStep.ALLOW_INSTALLS, runBlocking { installer.downloadAndInstall(RELEASE) })
        val settings = assertNotNull(app.nextStartedActivity)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.action)
        assertEquals(Uri.parse("package:${context.packageName}"), settings.data)
        assertNull(app.nextStartedActivity, "not the installer yet")

        // Allowed now: the kept download is checked again and handed to the installer.
        allowed = true
        assertEquals(InstallStep.INSTALLER_OPENED, runBlocking { installer.downloadAndInstall(RELEASE) })
        val view = assertNotNull(app.nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("application/vnd.android.package-archive", view.type)
        assertEquals("content://${context.packageName}.files/shared/update/fieldmaxx-2.apk", view.data.toString())
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(APK.toList(), File(context.cacheDir, "shared/update/fieldmaxx-2.apk").readBytes().toList())
        assertEquals(listOf("/app/fieldmaxx-1.2.0.apk"), api.downloads.toList(), "downloaded once")

        // The merged manifest asks for the install permission (R69).
        val requested = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()
        assertTrue(Manifest.permission.REQUEST_INSTALL_PACKAGES in requested, "$requested")
    }

    /** Spec §6.12, R20: sign out drops the session and stops syncing; the phone's data and the outbox stay. */
    @Test fun signOutKeepsTheOutbox() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/api/jobs" -> MockResponse().setBody("[]")
                "/api/app/latest" -> MockResponse().setBody("null")
                "/api/auth/logout" -> MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(503).setBody("""{"error":"Try again later"}""") // ops wait in the outbox
            }
        }
        server.start()
        val vault = InMemoryTokenVault("tok-1")
        val graph = AppGraph(context, vault)
        try {
            graph.server.set(server.url("/").toString().trimEnd('/'))
            val waitingOp = Op.PointUpdate("op-1", "p1", comment = "Needs a ladder")
            runBlocking {
                graph.db.meta().put(MetaEntity(MetaKeys.USER, FmxJson.encodeToString(User.serializer(), ALICE)))
                graph.db.outbox().insert(waitingOp.toOutbox())
            }
            lateinit var nav: NavHostController
            compose.setContent {
                nav = rememberNavController()
                Host { FieldmaxxNav(graph, nav) }
            }

            waitForTag(JobListTags.OPEN_MENU)
            tap(JobListTags.OPEN_MENU)
            waitForTag(MenuTags.SIGN_OUT)
            tap(MenuTags.SIGN_OUT)
            waitForTag(MenuTags.SIGN_OUT_CONFIRM)
            tap(MenuTags.SIGN_OUT_CONFIRM)
            waitFor { nav.currentDestination?.route == Routes.LOGIN }

            assertNull(vault.load(), "the session token is gone")
            assertNull(nav.previousBackStackEntry, "nothing to go back to")
            assertEquals(listOf<Op>(waitingOp), runBlocking { graph.db.outbox().all().map { it.toOp() } }, "the outbox stays for the next sign-in")
            assertEquals(ALICE, runBlocking { graph.sync.savedUser() }, "the phone's data stays")
            assertEquals(SyncStatus.OFFLINE, graph.sync.status.value, "syncing stopped")
            val requests = List(server.requestCount) { server.takeRequest() }
            val logout = assertNotNull(requests.firstOrNull { it.path == "/api/auth/logout" }, "the server is told: ${requests.map { it.path }}")
            assertEquals("Bearer tok-1", logout.getHeader("Authorization"))
        } finally {
            graph.close()
            server.shutdown()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    // ---- more ----

    /** R70: exports need signal, and say so. */
    @Test fun exportsNeedSignal() {
        api.downloadError = IOException("no signal")
        compose.setContent { Host { ExportDialog("j1", "A05J9XE", listOf(ExportKind.PHOTOS_ZIP), api, onDismiss = {}, sdk = 35) } }
        waitFor { exists(ExportTags.STATUS) && textOf(ExportTags.STATUS) == EXPORT_NEEDS_SIGNAL }
        assertEquals(listOf("/api/jobs/j1/photos.zip"), api.downloads.toList(), "the server's photos ZIP: no format to choose")
        assertFalse(exists(ExportTags.SHARE))
        assertEquals(emptyList(), File(context.cacheDir, "shared").walk().filter { it.isFile }.toList())
    }

    /** Task 28's carry: on Android 9 switching the gallery copy on asks for storage; refused, it stays off with the reason. */
    @Test fun galleryCopyAsksForStorageOnAndroid9() {
        val settings = PhotoSettings(store("photo_settings"))
        compose.setContent { Host { SettingsScreen(settings, onBack = {}, sdk = 28) } }

        waitForTag(SettingsTags.GALLERY)
        compose.onNodeWithTag(SettingsTags.GALLERY).assertIsOff() // without storage access, Android 9 copies nothing
        tap(SettingsTags.GALLERY)
        waitFor { registry.launches.size == 1 }
        assertEquals(Manifest.permission.WRITE_EXTERNAL_STORAGE, registry.launches.single().input)
        registry.answer(false)
        waitForTag(SettingsTags.GALLERY_NOTE)
        compose.onNodeWithTag(SettingsTags.GALLERY).assertIsOff()
        assertEquals(GALLERY_STORAGE_REFUSED, textOf(SettingsTags.GALLERY_NOTE))

        tap(SettingsTags.GALLERY)
        waitFor { registry.launches.size == 2 }
        app.grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        registry.answer(true)
        waitFor { !exists(SettingsTags.GALLERY_NOTE) }
        compose.onNodeWithTag(SettingsTags.GALLERY).assertIsOn()
        assertTrue(runBlocking { settings.galleryCopy.first() })

        tap(SettingsTags.GALLERY)
        waitFor { runBlocking { !settings.galleryCopy.first() } }
        assertEquals(2, registry.launches.size, "switching it off asks for nothing")
    }
}
