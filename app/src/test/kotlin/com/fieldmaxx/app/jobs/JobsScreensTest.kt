package com.fieldmaxx.app.jobs

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.sync.ALICE
import com.fieldmaxx.app.sync.FakeLiveSocket
import com.fieldmaxx.app.sync.SyncEngine
import com.fieldmaxx.app.sync.SyncStatus
import com.fieldmaxx.app.sync.job
import com.fieldmaxx.app.sync.localPhoto
import com.fieldmaxx.app.sync.photo
import com.fieldmaxx.app.sync.point
import com.fieldmaxx.app.sync.snapshot
import com.fieldmaxx.app.ui.FieldmaxxTheme
import com.fieldmaxx.core.model.CreateJobInput
import com.fieldmaxx.core.model.CrewMember
import com.fieldmaxx.core.model.ImportPreview
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.JobFileInfo
import com.fieldmaxx.core.model.JobListItem
import com.fieldmaxx.core.model.JobSnapshot
import com.fieldmaxx.core.model.LinkCheck
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.UnreadLine
import com.fieldmaxx.core.model.User
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

private const val SERVER = "https://fmx.example.com"
private val ADMIN = User("u7", "owner", "Owner Admin", Role.admin, disabled = false)

/** The server as the job screens use it; every other call fails the test. */
class JobsFakeApi : Api by notUsed() {
    val jobsCalls = CopyOnWriteArrayList<String?>()
    @Volatile var jobsReply: () -> List<JobListItem> = { emptyList() }
    val snapshots = ConcurrentHashMap<String, JobSnapshot>()
    val deleted = CopyOnWriteArrayList<String>()
    val linkChecks = CopyOnWriteArrayList<String>()
    @Volatile var linkReply: (String) -> LinkCheck = { LinkCheck(ok = true, rootName = "Field Notes") }
    val imported = CopyOnWriteArrayList<String>()
    @Volatile var importReply: (String, ByteArray) -> ImportPreview = { name, _ -> error("no import expected: $name") }
    val created = CopyOnWriteArrayList<CreateJobInput>()
    val downloads = CopyOnWriteArrayList<String>()

    override suspend fun jobs(include: String?): List<JobListItem> {
        jobsCalls += include
        return jobsReply()
    }

    override suspend fun snapshot(jobId: String): JobSnapshot = snapshots[jobId] ?: throw ApiException(404, "Job not found")

    override suspend fun deleteJob(jobId: String) {
        deleted += jobId
    }

    override suspend fun checkLink(url: String): LinkCheck {
        linkChecks += url
        return linkReply(url)
    }

    override suspend fun importFile(name: String, bytes: ByteArray): ImportPreview {
        imported += name
        return importReply(name, bytes)
    }

    override suspend fun createJob(i: CreateJobInput): Job {
        created += i
        return job("j-new", i.name).copy(fieldNotesUrl = i.fieldNotesUrl)
    }

    override suspend fun download(path: String, to: File) {
        downloads += path
        to.parentFile?.mkdirs()
        to.writeBytes(PDF_BYTES)
    }
}

private fun notUsed(): Api = Proxy.newProxyInstance(Api::class.java.classLoader, arrayOf(Api::class.java)) { _, method, _ ->
    throw UnsupportedOperationException("Api.${method.name} is not used by the job screens")
} as Api

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JobsScreensTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val api = JobsFakeApi()
    private val jobs = JobList(api, db) { SERVER }
    private val status = MutableStateFlow(SyncStatus.OFFLINE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest fun registerProvider() {
        Robolectric.setupContentProvider(SharedFilesProvider::class.java, SharedFilesProvider.AUTHORITY)
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun item(id: String, name: String, done: Int, total: Int, status: String = "active", createdBy: String = ALICE.id) =
        JobListItem(job(id, name).copy(status = status, createdBy = createdBy), pointsTotal = total, pointsDone = done)

    private fun showJobList(onOpen: (String) -> Unit = {}) {
        compose.setContent {
            FieldmaxxTheme { JobListScreen(jobs, status, currentUser = { ALICE }, onOpenJob = onOpen, onNewJob = {}, onAddFiles = {}, onJobInfo = {}) }
        }
    }

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForTag(tag: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** Lets the main looper run between checks too: under Robolectric that's what delivers state written outside a frame. */
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(5_000) {
        compose.waitForIdle()
        condition()
    }

    private fun openMenuItem(jobId: String, item: String) {
        compose.onNodeWithTag(JobListTags.menu(jobId)).performClick()
        compose.onNodeWithText(item).performClick()
    }

    @Test fun jobListShowsProgressAndFinishedSection() {
        api.jobsReply = { listOf(item("j1", "A05J9XE", done = 5, total = 7), item("j2", "A05K51V", done = 26, total = 26, status = "finished")) }
        var opened: String? = null
        showJobList(onOpen = { opened = it })

        waitForText("A05J9XE")
        compose.onNodeWithText("5/7 done").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(5f / 7f, 0f..1f))).assertExists()
        val active = compose.onNodeWithText("A05J9XE").getUnclippedBoundsInRoot()
        val header = compose.onNodeWithText("Finished").getUnclippedBoundsInRoot()
        val finished = compose.onNodeWithText("A05K51V").getUnclippedBoundsInRoot()
        assertTrue(active.top < header.top && header.top < finished.top, "active jobs, then the finished section")
        compose.onNodeWithText("26/26 done").assertIsDisplayed()
        assertEquals(listOf<String?>("finished"), api.jobsCalls.distinct(), "the list asks for finished jobs too")

        compose.onNodeWithText("A05J9XE").performClick()
        assertEquals("j1", opened)
    }

    @Test fun deleteOnlyForCreatorOrAdminConfirmNamesJob() {
        api.jobsReply = { listOf(item("j1", "A05J9XE", 0, 7, createdBy = ALICE.id), item("j2", "A05K51V", 0, 2, createdBy = "u9")) }
        api.snapshots["j1"] = snapshot(job("j1", "A05J9XE"), points = (1..7).map { point("p$it") }, seq = 1)
        api.snapshots["j2"] = snapshot(job("j2", "A05K51V"), points = listOf(point("q1", "j2"), point("q2", "j2")), seq = 1)
        var user by mutableStateOf(ALICE)
        compose.setContent {
            FieldmaxxTheme { key(user) { JobListScreen(jobs, status, { user }, onOpenJob = {}, onNewJob = {}, onAddFiles = {}, onJobInfo = {}) } }
        }

        // Alice (a tech) didn't create A05K51V: no delete.
        waitForText("A05K51V")
        compose.onNodeWithTag(JobListTags.menu("j2")).performClick()
        compose.onNodeWithText("Job info").assertIsDisplayed()
        compose.onAllNodesWithText("Delete").assertCountEquals(0)
        compose.onNodeWithText("Job info").performClick()

        // She created A05J9XE: delete asks first, naming the job and what's in it.
        openMenuItem("j1", "Delete")
        waitForText("Delete A05J9XE?")
        waitForText("7 points · 0 photos")
        assertTrue(api.deleted.isEmpty(), "nothing is deleted before the confirmation")
        compose.onNodeWithTag(JobListTags.DELETE_CONFIRM).performClick()
        waitFor { api.deleted.isNotEmpty() }
        assertEquals(listOf("j1"), api.deleted)

        // An admin may delete anyone's job.
        user = ADMIN
        waitForText("A05K51V")
        openMenuItem("j2", "Delete")
        waitForText("Delete A05K51V?")
        waitForText("2 points · 0 photos")
    }

    @Test fun deleteWarnsOnlyWhenPhotosUndelivered() {
        api.jobsReply = { listOf(item("j1", "A05J9XE", 7, 7), item("j2", "A05K51V", 1, 2)) }
        api.snapshots["j1"] = snapshot(
            job("j1", "A05J9XE"), (1..7).map { point("p$it") }, seq = 1,
            photos = listOf(photo("ph1", "p1", status = PhotoStatus.confirmed), photo("ph2", "p2", status = PhotoStatus.confirmed)),
        )
        api.snapshots["j2"] = snapshot(
            job("j2", "A05K51V"), listOf(point("q1", "j2"), point("q2", "j2")), seq = 1,
            photos = listOf(
                photo("ph3", "q1", "j2", PhotoStatus.confirmed),
                photo("ph4", "q1", "j2", PhotoStatus.registered), // the server has it, OneDrive doesn't
                photo("ph5", "q2", "j2", PhotoStatus.removed),
            ),
        )
        runBlocking {
            db.localPhotos().upsert(localPhoto("lp1", pointId = "q2", jobId = "j2")) // on this phone, not registered yet
            db.localPhotos().upsert(localPhoto("ph4", pointId = "q1", jobId = "j2").copy(state = "registered")) // counted once
        }
        showJobList()
        waitForText("A05J9XE")

        openMenuItem("j1", "Delete")
        waitForText("7 points · 2 photos")
        compose.onAllNodesWithTag(JobListTags.DELETE_WARNING).assertCountEquals(0)
        compose.onNodeWithText("server's backup copies", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()

        openMenuItem("j2", "Delete")
        waitForText("2 points · 3 photos")
        compose.onNodeWithTag(JobListTags.DELETE_WARNING)
            .assertTextEquals("2 photos haven't reached OneDrive yet. They will stay undelivered (they remain on the phone that took them).")
        compose.onNodeWithText("server's backup copies", substring = true).assertIsDisplayed()
        assertTrue(api.deleted.isEmpty())
    }

    @Test fun offlineShowsCachedList() {
        runBlocking {
            api.jobsReply = { listOf(item("j9", "Z99OLD", 1, 1)) }
            JobList(api, db) { "https://old.example.com" }.fetch() // another server's list
            api.jobsReply = { listOf(item("j1", "A05J9XE", 5, 7)) }
            jobs.fetch() // the last list this server sent
        }
        api.jobsReply = { throw IOException("no signal") }

        showJobList()

        waitForTag(JobListTags.OFFLINE)
        compose.onNodeWithTag(JobListTags.OFFLINE).assertTextEquals("Offline · showing the list from your last connection")
        compose.onNodeWithText("A05J9XE").assertIsDisplayed()
        compose.onNodeWithText("5/7 done").assertIsDisplayed()
        compose.onAllNodesWithText("Z99OLD").assertCountEquals(0)
    }

    @Test fun refreshesWhenSyncComesOnline() {
        api.jobsReply = { throw IOException("no signal") }
        showJobList()
        waitForTag(JobListTags.OFFLINE)
        compose.onNodeWithTag(JobListTags.OFFLINE).assertTextEquals("Offline · the job list needs signal the first time")

        api.jobsReply = { listOf(item("j1", "A05J9XE", 5, 7)) }
        status.value = SyncStatus.ONLINE

        waitForText("A05J9XE")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(JobListTags.OFFLINE).fetchSemanticsNodes().isEmpty() }
        runBlocking { assertEquals(listOf("A05J9XE"), jobs.cached()?.map { it.job.name }, "kept for the next time without signal") }
    }

    @Test fun linkCheckShowsRootOrError() {
        compose.setContent {
            FieldmaxxTheme {
                var link by remember { mutableStateOf("") }
                FieldNotesLinkField(link, { link = it }, api)
            }
        }
        val good = "https://contoso.sharepoint.com/:f:/s/Fielding/EaBcDe?e=1"
        compose.onNodeWithTag(LinkTags.FIELD).performTextInput(good)
        compose.onNodeWithTag(LinkTags.CHECK).performClick()
        waitForText("✓ Folder: Field Notes")
        compose.onNodeWithTag(LinkTags.RESULT).assertTextEquals("✓ Folder: Field Notes")
        assertEquals(listOf(good), api.linkChecks)

        api.linkReply = { LinkCheck(ok = false, error = "This link needs a Microsoft sign-in") }
        compose.onNodeWithTag(LinkTags.FIELD).performTextReplacement("https://contoso.sharepoint.com/:f:/s/Fielding/SignIn")
        compose.onAllNodesWithTag(LinkTags.RESULT).assertCountEquals(0) // a changed link hasn't been checked
        compose.onNodeWithTag(LinkTags.CHECK).performClick()
        waitForText("This link needs a Microsoft sign-in")
        compose.onNodeWithTag(LinkTags.RESULT).assertTextEquals("This link needs a Microsoft sign-in")

        api.linkReply = { throw IOException("no signal") }
        compose.onNodeWithTag(LinkTags.CHECK).performClick()
        waitForText("Can't reach the server. Check your signal.")
    }

    @Test fun saveWithoutLinkShowsPhotosWillWait() {
        val file = SharedFilesProvider.share("81", "A05J9XE.pdf", PDF_BYTES)
        api.importReply = { name, _ -> preview((1..7).map { draft("POLE $it") }, fileName = name) }
        val opened = CopyOnWriteArrayList<String>()
        var done: String? = null
        compose.setContent {
            FieldmaxxTheme {
                NewJobScreen(
                    api, openJob = { opened += it }, addToJobId = null, sharedFiles = listOf(file), sharedLink = null,
                    onDone = { done = it }, onBack = {},
                )
            }
        }

        waitForText("Found 7 POLE points")
        compose.onNodeWithTag(CheckTags.NAME).assertTextContains("A05J9XE")
        compose.onNodeWithTag(CheckTags.SAVE).performClick()
        compose.onNodeWithText("Photos will wait until the Field Notes link is added").assertIsDisplayed()
        assertTrue(api.created.isEmpty(), "nothing is saved until the tech says so")

        compose.onNodeWithText("Save without link").performClick()
        waitFor { done != null }
        assertEquals("j-new", done)
        assertEquals(listOf("j-new"), opened, "the new job is opened before the map shows")
        assertEquals(listOf("A05J9XE.pdf"), api.imported)
        val input = api.created.single()
        assertEquals("A05J9XE", input.name)
        assertNull(input.fieldNotesUrl)
        assertEquals((1..7).map { "POLE $it" }, input.imports.single().points.map { it.label })
    }

    @Test fun unreadLinesStayUntilFixedOrIgnored() {
        val file = SharedFilesProvider.share("85", "A05J9XE.pdf", PDF_BYTES)
        val pole9 = UnreadLine("POLE 9 = 39.63,abc", 1, "type and number without readable coordinates")
        val stray = UnreadLine("39.63612682461489,-", 1, "number that looks like a coordinate")
        api.importReply = { name, _ -> preview(listOf(draft("POLE 8"), noCoordinates("HH 2")), unread = listOf(pole9, stray), fileName = name) }
        val link = "https://contoso.sharepoint.com/:f:/s/Fielding/EaBcDe?e=1"
        compose.setContent {
            FieldmaxxTheme {
                NewJobScreen(api, openJob = {}, addToJobId = null, sharedFiles = listOf(file), sharedLink = link, onDone = {}, onBack = {})
            }
        }
        fun inCard(tag: String, text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)))
        fun scrollTo(tag: String) = compose.onNodeWithTag(CheckTags.LIST).performScrollToNode(hasTestTag(tag))

        waitForText("Found 1 POLE point")
        compose.onNodeWithText("1 needs coordinates").assertExists()
        compose.onNodeWithTag(CheckTags.SAVE).assertIsNotEnabled()
        compose.onNodeWithTag(CheckTags.LIST).performScrollToNode(hasText("Couldn't read (2)"))

        // Fix the pole line: the form starts with the label it found, and takes only readable coordinates.
        scrollTo(CheckTags.unread("imp1", 0))
        inCard(CheckTags.unread("imp1", 0), "Fix").performScrollTo().performClick()
        compose.onNodeWithTag(CheckTags.FORM_LABEL).assertTextContains("POLE 9")
        compose.onNodeWithTag(CheckTags.FORM_COORDINATES).performTextInput("39.63, -119.69")
        compose.onNodeWithTag(CheckTags.FORM_CONFIRM).performClick()
        compose.onNodeWithText("Coordinates not recognised").assertIsDisplayed()
        compose.onNodeWithTag(CheckTags.FORM_COORDINATES).performTextReplacement("39.6361, -119.6912")
        compose.onNodeWithTag(CheckTags.FORM_CONFIRM).performClick()
        compose.onNodeWithTag(CheckTags.LIST).performScrollToNode(hasText("Couldn't read (1)"))
        scrollTo(CheckTags.point("imp1", 2))
        inCard(CheckTags.point("imp1", 2), "POLE 9").assertExists()

        // Ignoring the other line keeps it listed, and can be undone.
        scrollTo(CheckTags.unread("imp1", 0))
        inCard(CheckTags.unread("imp1", 0), stray.text).assertExists()
        inCard(CheckTags.unread("imp1", 0), "Ignore").performScrollTo().performClick()
        inCard(CheckTags.unread("imp1", 0), "Ignored").assertExists()
        inCard(CheckTags.unread("imp1", 0), stray.text).assertExists()
        compose.onNodeWithTag(CheckTags.SAVE).assertIsNotEnabled() // HH 2 still has no coordinates

        scrollTo(CheckTags.point("imp1", 1))
        inCard(CheckTags.point("imp1", 1), "HH 2").assertExists()
        inCard(CheckTags.point("imp1", 1), "Remove").performScrollTo().performClick()
        compose.onNodeWithTag(CheckTags.SAVE).assertIsEnabled()

        scrollTo(CheckTags.unread("imp1", 0))
        inCard(CheckTags.unread("imp1", 0), "Undo").performScrollTo().performClick()
        compose.onNodeWithTag(CheckTags.SAVE).assertIsNotEnabled()
        inCard(CheckTags.unread("imp1", 0), "Ignore").performScrollTo().performClick()

        compose.onNodeWithTag(CheckTags.SAVE).performClick()
        waitFor { api.created.isNotEmpty() }
        val input = api.created.single()
        assertEquals(link, input.fieldNotesUrl)
        assertEquals(listOf("POLE 8", "POLE 9"), input.imports.single().points.map { it.label })
    }

    @Test fun jobInfoOpensSourceFile() {
        val sync = SyncEngine(api, db, FakeLiveSocket(), scope, { 0L }, { SERVER })
        val link = "https://contoso.sharepoint.com/:f:/s/Fielding/EaBcDe?e=1"
        api.snapshots["j1"] = snapshot(
            job("j1", "A05J9XE").copy(notes = "NOTE FOR AERIAL PATH", fieldNotesUrl = link), listOf(point("p1")), seq = 3,
            files = listOf(JobFileInfo("f1", "A05J9XE.pdf", "POLE")), crew = listOf(CrewMember("u1", "Alice Tech"), CrewMember("u2", "Bob Tech")),
        )
        compose.setContent { FieldmaxxTheme { JobInfoScreen("j1", api, sync, sharedLink = null, onBack = {}) } }

        waitForText("A05J9XE.pdf")
        compose.onNodeWithText("NOTE FOR AERIAL PATH").assertExists()
        compose.onNodeWithText("Bob Tech").assertExists()
        compose.onNodeWithTag(LinkTags.FIELD).assertTextContains(link)

        compose.onNodeWithTag(JobInfoTags.file("f1")).performScrollTo().performClick()
        val app = shadowOf(context as Application)
        waitFor { app.peekNextStartedActivity() != null }
        assertEquals(listOf("/api/jobs/j1/files/f1"), api.downloads)
        val view = app.nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("application/pdf", view.type)
        assertEquals("${context.packageName}.files", view.data!!.authority)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0, "the viewer may read it")
        assertTrue(File(context.cacheDir, "shared").walk().any { it.name == "A05J9XE.pdf" }, "handed over from cacheDir/shared/")
    }

    @Test fun shareOffersNewJobOrAddToJob() {
        val pdf = SharedFilesProvider.share("91", "A05J9XE.pdf", PDF_BYTES)
        val txt = SharedFilesProvider.share("92", "notes.txt", "hello".toByteArray())
        api.jobsReply = { listOf(item("j1", "A05J9XE", 5, 7), item("j2", "A05K51V", 26, 26, status = "finished")) }
        var added: Pair<String, List<Uri>>? = null
        compose.setContent {
            FieldmaxxTheme {
                ShareIntakeScreen(
                    SharedIntake.Files(listOf(pdf, txt)), jobs, onNewJob = { _, _ -> }, onAddToJob = { id, files, _ -> added = id to files },
                    onClose = {},
                )
            }
        }

        waitForText("notes.txt: Only PDF and Word (.docx) files")
        compose.onNodeWithText("New job").assertIsDisplayed()
        compose.onNodeWithText("Add to job").performClick()
        waitForText("A05J9XE")
        compose.onAllNodesWithText("A05K51V").assertCountEquals(0) // only active jobs take files
        compose.onNodeWithText("A05J9XE").performClick()
        assertEquals("j1" to listOf(pdf), added)
    }
}
