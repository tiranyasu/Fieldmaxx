package com.fieldmaxx.sharepoint

import com.fieldmaxx.sharepoint.MockSharePoint.Companion.BAD_FOLDER_PATH
import com.fieldmaxx.sharepoint.MockSharePoint.Companion.BUSINESS_ROOT
import com.fieldmaxx.sharepoint.MockSharePoint.Companion.BUSINESS_SHARE_PATH
import com.fieldmaxx.sharepoint.MockSharePoint.Companion.FORBIDDEN_PATH
import com.fieldmaxx.sharepoint.MockSharePoint.Companion.LOOP_PATH
import com.fieldmaxx.sharepoint.MockSharePoint.Companion.NO_LOCATION_PATH
import com.fieldmaxx.sharepoint.MockSharePoint.Companion.PERSONAL_REDEEM_PATH
import com.fieldmaxx.sharepoint.MockSharePoint.Companion.PERSONAL_ROOT
import com.fieldmaxx.sharepoint.MockSharePoint.Companion.PERSONAL_SHARE_PATH
import com.fieldmaxx.sharepoint.MockSharePoint.Companion.PLAIN_PAGE_PATH
import com.fieldmaxx.sharepoint.MockSharePoint.Companion.SESSION_NOT_FOUND
import com.fieldmaxx.sharepoint.MockSharePoint.Companion.SHARE_PATHS
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharePointLinkTest {
    private val mock = MockSharePoint()

    // No silent OkHttp retries: a dropped connection must surface exactly where the mock drops it.
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    @AfterTest
    fun close() = mock.close()

    private fun business(clock: Clock = Clock.systemUTC()) = SharePointLinkDestination(mock.businessLink, client, clock)

    private fun shareFetches() = mock.calls.count { it.path in SHARE_PATHS }

    private fun tmpFile(size: Int): File = File.createTempFile("photo", ".jpg").apply {
        deleteOnExit()
        writeBytes(Random(size).nextBytes(size))
    }

    private fun failure(block: () -> Unit) = assertFailsWith<DestinationException> { block() }

    @Test
    fun businessLinkRootName() {
        assertEquals("Field Notes", business().rootName())
        val first = mock.calls.first()
        assertEquals("GET $BUSINESS_SHARE_PATH", "${first.method} ${first.path}")
        assertEquals(BROWSER_USER_AGENT, first.headers["User-Agent"])
        assertEquals(1, mock.calls.size, "the onedrive.aspx page itself is never loaded")
    }

    @Test
    fun personalLinkUsesSpopath() {
        mock.folders += "$PERSONAL_ROOT/POLE"
        val d = SharePointLinkDestination(mock.personalLink, client)
        assertEquals("Fieldmaxx", d.rootName())
        assertEquals(listOf("POLE"), d.listFolders(""))
        assertEquals(listOf(PERSONAL_SHARE_PATH, PERSONAL_REDEEM_PATH), mock.calls.take(2).map { it.path })
        assertTrue(mock.calls.take(2).all { it.headers["User-Agent"] == BROWSER_USER_AGENT })
        assertEquals(
            "/personal/cid/_api/web/GetFolderByServerRelativePath(decodedurl='/personal/cid/Documents/Fieldmaxx')/Folders?\$select=Name",
            mock.calls.last().target,
        )
        d.ensureFolder("POLE")
        assertTrue(mock.calls.any { it.path == "/personal/cid/_api/contextinfo" }, "site is <host>/personal/cid")
    }

    @Test
    fun listFoldersEncodesPath() {
        mock.folders += listOf("$BUSINESS_ROOT/O'Brien St #4", "$BUSINESS_ROOT/O'Brien St #4/POLE_2", "$BUSINESS_ROOT/O'Brien St #4/POLE_1")
        val d = business()
        assertEquals(listOf("POLE_1", "POLE_2"), d.listFolders("O'Brien St #4"))
        assertEquals(
            "/personal/u/_api/web/GetFolderByServerRelativePath(decodedurl='/personal/u/Documents/OSP%20FIELDING/Field%20Notes/O''Brien%20St%20%234')/Folders?\$select=Name",
            mock.calls.last().target,
        )
        assertEquals(listOf("O'Brien St #4"), d.listFolders(""))
        assertEquals(DestinationErrorCode.NOT_FOUND, failure { d.listFolders("MH") }.code)
    }

    @Test
    fun pathLiteral() {
        assertEquals("/a/O''Brien%20St/x", spPathLiteral("/a/O'Brien St/x"))
        assertEquals("/x/50%25%20%231%2B2/%C3%A9t%C3%A9", spPathLiteral("/x/50% #1+2/été"))
        assertEquals("POLE_1_39.636130_-119.691104_6018.jpg", spPathLiteral("POLE_1_39.636130_-119.691104_6018.jpg"))
    }

    @Test
    fun ensureFolderCreatesEachSegment() {
        val d = business()
        d.ensureFolder("QUERY 1/Query_1-43")
        assertTrue("$BUSINESS_ROOT/QUERY 1" in mock.folders)
        assertTrue("$BUSINESS_ROOT/QUERY 1/Query_1-43" in mock.folders)
        assertEquals(
            listOf(
                "/personal/u/_api/web/folders/AddUsingPath(decodedurl='/personal/u/Documents/OSP%20FIELDING/Field%20Notes/QUERY%201',overwrite=false)",
                "/personal/u/_api/web/folders/AddUsingPath(decodedurl='/personal/u/Documents/OSP%20FIELDING/Field%20Notes/QUERY%201/Query_1-43',overwrite=false)",
            ),
            mock.calls.filter { "/folders/AddUsingPath" in it.path }.map { it.path },
        )
        d.ensureFolder("QUERY 1/Query_1-43") // existing folders are fine
        assertEquals(
            listOf("mkdir QUERY 1 200", "mkdir QUERY 1/Query_1-43 200", "mkdir QUERY 1 400", "mkdir QUERY 1/Query_1-43 400"),
            mock.log.filter { it.startsWith("mkdir") },
        )
    }

    @Test
    fun ensureFolderAcceptsAlreadyExists() {
        mock.folders += "$BUSINESS_ROOT/POLE"
        business().ensureFolder("POLE/POLE_33") // a new point folder under an existing category
        assertEquals(listOf("mkdir POLE 400", "mkdir POLE/POLE_33 200"), mock.log.filter { it.startsWith("mkdir") })
        assertTrue("$BUSINESS_ROOT/POLE/POLE_33" in mock.folders)

        mock.log.clear()
        mock.existingFolderAnswers200 = true
        business().ensureFolder("POLE/POLE_33")
        assertEquals(listOf("mkdir POLE 200", "mkdir POLE/POLE_33 200"), mock.log.filter { it.startsWith("mkdir") })
    }

    @Test
    fun uploadSendsDigestAndNoOverwrite() {
        val d = business()
        d.ensureFolder("POLE")
        val bytes = Random(1).nextBytes(5000)
        d.upload("POLE/POLE_1_39.636130_-119.691104_6018.jpg", bytes)
        assertContentEquals(bytes, mock.files["$BUSINESS_ROOT/POLE/POLE_1_39.636130_-119.691104_6018.jpg"])
        val call = mock.calls.single { "/Files/AddUsingPath" in it.path }
        assertEquals("POST", call.method)
        assertEquals(
            "/personal/u/_api/web/GetFolderByServerRelativePath(decodedurl='/personal/u/Documents/OSP%20FIELDING/Field%20Notes/POLE')" +
                "/Files/AddUsingPath(decodedurl='POLE_1_39.636130_-119.691104_6018.jpg',overwrite=false)",
            call.path,
        )
        assertEquals(5000, call.bodySize)
        assertEquals(mock.digests.single(), call.headers["X-RequestDigest"])
        assertTrue(mock.calls.all { it.headers["Accept"] == ODATA_ACCEPT }, "every request sends Accept: $ODATA_ACCEPT")
        assertTrue(mock.calls.filter { it.method == "POST" && !it.path.endsWith("/contextinfo") }.all { it.headers["X-RequestDigest"] != null })
    }

    @Test
    fun fileInfoNullOn404() {
        val d = business()
        assertNull(d.fileInfo("POLE/missing.jpg"))
        d.ensureFolder("POLE")
        d.upload("POLE/a.jpg", ByteArray(1234))
        assertEquals(FileInfo(1234), d.fileInfo("POLE/a.jpg"))
        assertEquals(
            "/personal/u/_api/web/GetFileByServerRelativePath(decodedurl='/personal/u/Documents/OSP%20FIELDING/Field%20Notes/POLE/a.jpg')?\$select=Length",
            mock.calls.last().target,
        )
    }

    @Test
    fun existingNameThrowsExists() {
        val d = business()
        d.ensureFolder("POLE")
        d.upload("POLE/a.jpg", byteArrayOf(1))
        val e = failure { d.upload("POLE/a.jpg", byteArrayOf(2)) }
        assertEquals(DestinationErrorCode.EXISTS, e.code)
        assertContentEquals(byteArrayOf(1), mock.files["$BUSINESS_ROOT/POLE/a.jpg"])
        assertEquals(DestinationErrorCode.EXISTS, failure { d.uploadChunked("POLE/a.jpg", tmpFile(CHUNK + 1), CHUNK, null) {} }.code)
        assertTrue(mock.log.none { it.startsWith("start") }, "no upload session on someone else's file")
    }

    @Test
    fun smallFileUploadsInOneRequest() {
        val d = business()
        d.ensureFolder("POLE")
        val f = tmpFile(1000)
        val progress = mutableListOf<ChunkSession>()
        d.uploadChunked("POLE/s.jpg", f, CHUNK, null) { progress += it }
        assertEquals(listOf("create POLE/s.jpg 1000 200"), mock.log.filter { "s.jpg" in it })
        assertContentEquals(f.readBytes(), mock.files["$BUSINESS_ROOT/POLE/s.jpg"])
        assertEquals(emptyList(), progress)
    }

    @Test
    fun chunkedUploadCompletes() {
        val d = business()
        d.ensureFolder("POLE")
        val f = tmpFile(9_000_000)
        val progress = mutableListOf<ChunkSession>()
        d.uploadChunked("POLE/p.jpg", f, CHUNK, null) { progress += it }
        assertEquals(listOf(0L, 3_200_000L, 6_400_000L, 9_000_000L), progress.map { it.offset })
        assertEquals(1, progress.map { it.uploadId }.distinct().size)
        assertEquals(
            listOf(
                "create POLE/p.jpg 0 200",
                "start POLE/p.jpg 3200000 200",
                "continue POLE/p.jpg @3200000 3200000 200",
                "finish POLE/p.jpg @6400000 2600000 200",
            ),
            mock.log.filter { "p.jpg" in it },
        )
        val uploadId = progress.first().uploadId
        assertTrue(mock.calls.single { "/StartUpload" in it.path }.path.endsWith("/StartUpload(uploadId=guid'$uploadId')"))
        assertEquals(FileInfo(f.length()), d.fileInfo("POLE/p.jpg"))
        assertContentEquals(f.readBytes(), mock.files["$BUSINESS_ROOT/POLE/p.jpg"])
    }

    @Test
    fun resumesAtConfirmedOffset() {
        business().ensureFolder("POLE")
        val f = tmpFile(9_000_000)
        val progress = mutableListOf<ChunkSession>()
        mock.dropOnChunk(2)
        val e = failure { business().uploadChunked("POLE/p.jpg", f, CHUNK, null) { progress += it } }
        assertEquals(DestinationErrorCode.NETWORK, e.code)
        val last = progress.last()
        assertEquals(3_200_000L, last.offset)

        mock.log.clear()
        business().uploadChunked("POLE/p.jpg", f, CHUNK, last) { progress += it } // a fresh instance, as after an app restart
        assertEquals(
            listOf("continue POLE/p.jpg @3200000 3200000 200", "finish POLE/p.jpg @6400000 2600000 200"),
            mock.log.filter { "p.jpg" in it },
        )
        assertEquals(listOf(0L, 3_200_000L, 6_400_000L, 9_000_000L), progress.map { it.offset })
        assertContentEquals(f.readBytes(), mock.files["$BUSINESS_ROOT/POLE/p.jpg"])
    }

    /** A chunked upload of [f] to [name] that stopped after its first chunk, whose session SharePoint has since dropped. */
    private fun droppedOvernight(name: String, f: File): ChunkSession {
        var last: ChunkSession? = null
        mock.dropOnChunk(2)
        assertEquals(DestinationErrorCode.NETWORK, failure { business().uploadChunked(name, f, CHUNK, null) { last = it } }.code)
        mock.expireUploadSessions() // e.g. the phone sat out of signal overnight
        mock.log.clear()
        return last!!
    }

    /** Live SharePoint answers a chunk sent to a session it dropped with HTTP 500 (R94); the mock used to answer 400. */
    private val lostSessionAnswers = listOf(500, 400)

    @Test
    fun lostSessionRestartsOnOwnPartialFile() {
        business().ensureFolder("POLE")
        for (code in lostSessionAnswers) {
            mock.lostSessionAnswers500 = code == 500
            val name = "POLE/p$code.jpg"
            val f = tmpFile(9_000_000)
            val last = droppedOvernight(name, f)

            val progress = mutableListOf<ChunkSession>()
            business().uploadChunked(name, f, CHUNK, last) { progress += it }
            assertEquals(
                listOf(
                    "continue $name @3200000 3200000 $code",
                    "info $name 200", // empty: the upload's own partial (it reads as 0 bytes), so it is restarted
                    "start $name 3200000 200",
                    "continue $name @3200000 3200000 200",
                    "finish $name @6400000 2600000 200",
                ),
                mock.log.filter { name in it },
                "a lost session answered $code",
            )
            assertEquals(listOf(3_200_000L, 6_400_000L, 9_000_000L), progress.map { it.offset })
            assertEquals(1, progress.map { it.uploadId }.distinct().size)
            assertNotEquals(last.uploadId, progress.first().uploadId)
            assertContentEquals(f.readBytes(), mock.files["$BUSINESS_ROOT/$name"])
        }
    }

    /** R94: a resumed StartUpload (the first chunk sent again) answered "session not found" is a lost session too. */
    @Test
    fun resumedStartWhoseSessionIsGoneRestarts() {
        business().ensureFolder("POLE")
        val f = tmpFile(9_000_000)
        val progress = mutableListOf<ChunkSession>()
        mock.dropOnChunk(1) // the StartUpload with the first 3.2 MB: only the empty file and its recovery point
        assertEquals(DestinationErrorCode.NETWORK, failure { business().uploadChunked("POLE/p.jpg", f, CHUNK, null) { progress += it } }.code)
        val first = progress.single()

        mock.log.clear()
        mock.failChunk(1, MockSharePoint.spError(500, SESSION_NOT_FOUND, "Error in the application."))
        business().uploadChunked("POLE/p.jpg", f, CHUNK, first) { progress += it }
        assertEquals(
            listOf(
                "start POLE/p.jpg 3200000 500 (injected)",
                "info POLE/p.jpg 200",
                "start POLE/p.jpg 3200000 200",
                "continue POLE/p.jpg @3200000 3200000 200",
                "finish POLE/p.jpg @6400000 2600000 200",
            ),
            mock.log.filter { "p.jpg" in it },
        )
        assertNotEquals(first.uploadId, progress.last().uploadId)
        assertContentEquals(f.readBytes(), mock.files["$BUSINESS_ROOT/POLE/p.jpg"])
    }

    /**
     * R94: only that 500 means a lost session, and only for a session this call didn't just open. Any other 500 stays
     * UNEXPECTED, and so does that 500 on a new upload's StartUpload or on a restart's, so a restart never goes round again.
     */
    @Test
    fun otherServerErrorsStayUnexpected() {
        business().ensureFolder("POLE")
        val f = tmpFile(9_000_000)
        val notFound = MockSharePoint.spError(500, SESSION_NOT_FOUND, "Error in the application.")

        mock.failChunk(1, notFound)
        val fresh = failure { business().uploadChunked("POLE/a.jpg", f, CHUNK, null) {} }
        assertEquals(DestinationErrorCode.UNEXPECTED to "OneDrive answered HTTP 500: Error in the application.", fresh.code to fresh.message)

        mock.lostSessionAnswers500 = true
        val last = droppedOvernight("POLE/b.jpg", f)
        mock.failChunk(2, notFound) // the restart's StartUpload, after the ContinueUpload that found the session gone
        assertEquals(DestinationErrorCode.UNEXPECTED, failure { business().uploadChunked("POLE/b.jpg", f, CHUNK, last) {} }.code)
        assertEquals(1, mock.log.count { it.startsWith("start POLE/b.jpg") }, "restarted once, not again")

        mock.log.clear()
        mock.failChunk(2, MockSharePoint.spError(500, "-2147467259, System.Exception", "Something went wrong."))
        val other = failure { business().uploadChunked("POLE/c.jpg", f, CHUNK, null) {} }
        assertEquals(DestinationErrorCode.UNEXPECTED to "OneDrive answered HTTP 500: Something went wrong.", other.code to other.message)
        assertTrue(mock.log.none { it.startsWith("info POLE/c.jpg") }, "not treated as lost")
    }

    /** m4: a lost session restarts only into an empty file; another file of another size under the name is never written over. */
    @Test
    fun lostSessionNeverWritesOverAnotherFile() {
        mock.lostSessionAnswers500 = true
        business().ensureFolder("POLE")
        val f = tmpFile(9_000_000)
        val last = droppedOvernight("POLE/p.jpg", f)
        val theirs = Random(3).nextBytes(5_000_000) // a whole file of someone else's, under the name meanwhile
        mock.files["$BUSINESS_ROOT/POLE/p.jpg"] = theirs

        val e = failure { business().uploadChunked("POLE/p.jpg", f, CHUNK, last) {} }
        assertEquals(DestinationErrorCode.EXISTS, e.code, "a collision: the caller takes a new counter (R82)")
        assertEquals(listOf("continue POLE/p.jpg @3200000 3200000 500", "info POLE/p.jpg 200"), mock.log.filter { "p.jpg" in it })
        assertContentEquals(theirs, mock.files["$BUSINESS_ROOT/POLE/p.jpg"], "never written over")
    }

    /** m4: the photo's file went (removed by hand) while its session was away: nothing to restart into, nothing made. */
    @Test
    fun lostSessionWhoseFileIsGoneIsNotFound() {
        mock.lostSessionAnswers500 = true
        business().ensureFolder("POLE")
        val f = tmpFile(9_000_000)
        val last = droppedOvernight("POLE/p.jpg", f)
        mock.files.remove("$BUSINESS_ROOT/POLE/p.jpg")

        assertEquals(DestinationErrorCode.NOT_FOUND, failure { business().uploadChunked("POLE/p.jpg", f, CHUNK, last) {} }.code)
        assertEquals(listOf("continue POLE/p.jpg @3200000 3200000 500", "info POLE/p.jpg 404"), mock.log.filter { "p.jpg" in it })
        assertNull(mock.files["$BUSINESS_ROOT/POLE/p.jpg"])
    }

    @Test
    fun firstChunkDroppedResumesSameFile() {
        business().ensureFolder("POLE")
        val f = tmpFile(9_000_000)
        val progress = mutableListOf<ChunkSession>()
        mock.dropOnChunk(1) // the StartUpload with the first 3.2 MB
        assertEquals(DestinationErrorCode.NETWORK, failure { business().uploadChunked("POLE/p.jpg", f, CHUNK, null) { progress += it } }.code)
        assertEquals(1, progress.size)
        assertEquals(0L, progress.single().offset, "a recovery point exists before the first chunk")

        mock.log.clear()
        business().uploadChunked("POLE/p.jpg", f, CHUNK, progress.single()) { progress += it }
        assertEquals(
            listOf(
                "start POLE/p.jpg 3200000 200",
                "continue POLE/p.jpg @3200000 3200000 200",
                "finish POLE/p.jpg @6400000 2600000 200",
            ),
            mock.log.filter { "p.jpg" in it },
        )
        assertEquals(1, progress.map { it.uploadId }.distinct().size, "the same session id, no new file")
        assertContentEquals(f.readBytes(), mock.files["$BUSINESS_ROOT/POLE/p.jpg"])
    }

    @Test
    fun aFreshSessionFillsAnEmptyFile() {
        business().ensureFolder("POLE")
        val f = tmpFile(9_000_000)
        mock.dropOnChunk(1) // another uploader of this photo: its StartUpload never arrives, its empty file stays
        assertEquals(DestinationErrorCode.NETWORK, failure { business().uploadChunked("POLE/p.jpg", f, CHUNK, null) {} }.code)
        assertEquals(FileInfo(0), business().fileInfo("POLE/p.jpg"))

        mock.log.clear()
        val adopt = ChunkSession(UUID.randomUUID().toString(), 0)
        val progress = mutableListOf<ChunkSession>()
        business().uploadChunked("POLE/p.jpg", f, CHUNK, adopt) { progress += it }
        assertEquals(
            listOf(
                "start POLE/p.jpg 3200000 200",
                "continue POLE/p.jpg @3200000 3200000 200",
                "finish POLE/p.jpg @6400000 2600000 200",
            ),
            mock.log.filter { "p.jpg" in it },
            "a new session into the existing file: no second file",
        )
        assertEquals(listOf(3_200_000L, 6_400_000L, 9_000_000L), progress.map { it.offset })
        assertEquals(setOf(adopt.uploadId), progress.map { it.uploadId }.toSet())
        assertContentEquals(f.readBytes(), mock.files["$BUSINESS_ROOT/POLE/p.jpg"])
    }

    @Test
    fun aFileLockedByAnotherSessionIsBusy() {
        mock.lockWhileUploading = true
        business().ensureFolder("POLE")
        val f = tmpFile(9_000_000)
        mock.dropOnChunk(2) // the other uploader's session stays open after its first chunk
        assertEquals(DestinationErrorCode.NETWORK, failure { business().uploadChunked("POLE/p.jpg", f, CHUNK, null) {} }.code)

        mock.log.clear()
        val e = failure { business().uploadChunked("POLE/p.jpg", f, CHUNK, ChunkSession(UUID.randomUUID().toString(), 0)) {} }
        assertEquals(DestinationErrorCode.BUSY, e.code)
        assertEquals(listOf("start POLE/p.jpg 3200000 423"), mock.log.filter { "p.jpg" in it }, "no restart around the lock")
        assertEquals(FileInfo(0), business().fileInfo("POLE/p.jpg"))

        mock.expireUploadSessions() // the lock times out
        business().uploadChunked("POLE/p.jpg", f, CHUNK, ChunkSession(UUID.randomUUID().toString(), 0)) {}
        assertContentEquals(f.readBytes(), mock.files["$BUSINESS_ROOT/POLE/p.jpg"])
    }

    @Test
    fun finishedUploadIsNeverRewritten() {
        business().ensureFolder("POLE")
        for (code in lostSessionAnswers) {
            mock.lostSessionAnswers500 = code == 500
            val name = "POLE/p$code.jpg"
            val f = tmpFile(9_000_000)
            var last: ChunkSession? = null
            mock.loseAnswerOnChunk(3) // FinishUpload is applied, its answer never arrives
            assertEquals(DestinationErrorCode.NETWORK, failure { business().uploadChunked(name, f, CHUNK, null) { last = it } }.code)
            assertEquals(6_400_000L, last!!.offset)
            assertContentEquals(f.readBytes(), mock.files["$BUSINESS_ROOT/$name"])

            mock.log.clear()
            val progress = mutableListOf<ChunkSession>()
            business().uploadChunked(name, f, CHUNK, last) { progress += it }
            assertEquals(listOf("finish $name @6400000 2600000 $code", "info $name 200"), mock.log.filter { name in it }, "answered $code")
            assertEquals(emptyList(), progress)

            // A resume at the full length (the last progress of a finished upload) is done too.
            mock.log.clear()
            business().uploadChunked(name, f, CHUNK, ChunkSession(last!!.uploadId, f.length())) {}
            assertEquals(listOf("finish $name @9000000 0 $code", "info $name 200"), mock.log.filter { name in it }, "answered $code")
            assertContentEquals(f.readBytes(), mock.files["$BUSINESS_ROOT/$name"])
        }
    }

    /** Ledger item 7: BUSY means SharePoint's lock ("locked", SPFileLockException), never any answer with "lock" in it. */
    @Test
    fun onlyARealLockIsBusy() {
        business().ensureFolder("POLE")
        val f = tmpFile(9_000_000)
        mock.failChunk(1, MockResponse().setResponseCode(500).setBody("<html><div style=\"display:block\">Something went wrong</div></html>"))
        assertEquals(DestinationErrorCode.UNEXPECTED, failure { business().uploadChunked("POLE/a.jpg", f, CHUNK, null) {} }.code)
        mock.failChunk(1, MockSharePoint.spError(400, "-2147024809, System.ArgumentException", "The request was blocked."))
        assertEquals(DestinationErrorCode.UPLOAD_SESSION_LOST, failure { business().uploadChunked("POLE/b.jpg", f, CHUNK, null) {} }.code)

        // A.jpg is an empty file now; a session into it meets the lock, in either wording.
        for (lock in listOf(
            MockSharePoint.spError(400, "-2147018894, Microsoft.SharePoint.SPFileLockException", "The file is in use."),
            MockSharePoint.spError(400, "-2130575306, Microsoft.SharePoint.SPException", "The file \"POLE/a.jpg\" is LOCKED for shared use."),
        )) {
            mock.failChunk(1, lock)
            val e = failure { business().uploadChunked("POLE/a.jpg", f, CHUNK, ChunkSession(UUID.randomUUID().toString(), 0)) {} }
            assertEquals(DestinationErrorCode.BUSY, e.code)
        }
        assertEquals(FileInfo(0), business().fileInfo("POLE/a.jpg"))
    }

    @Test
    fun signInRedirect() {
        mock.signInOnly = true
        for (link in listOf(mock.businessLink, mock.personalLink)) {
            val e = failure { SharePointLinkDestination(link, client).listFolders("") }
            assertEquals(DestinationErrorCode.REQUIRES_SIGN_IN, e.code)
            assertEquals("This link needs a Microsoft sign-in", e.message)
        }
    }

    /** Review Focus 4 (m9): a link that was deleted, or has expired, says so when it is entered. */
    @Test
    fun expiredLinkSaysItDoesNotExist() {
        mock.linkGone = true
        for (link in listOf(mock.businessLink, mock.personalLink)) {
            val e = failure { SharePointLinkDestination(link, client).rootName() }
            assertEquals(DestinationErrorCode.NOT_FOUND to "This link doesn't exist (deleted or mistyped)", e.code to e.message, link)
        }
    }

    /** Review Focus 4 (m9): a link that doesn't lead to a OneDrive folder says what is wrong with it. */
    @Test
    fun wrongLinksSayWhatIsWrong() {
        fun at(path: String) = mock.server.url(path).toString()
        val expected = listOf(
            "Field Notes" to (DestinationErrorCode.UNEXPECTED to "This is not a web link"),
            at(PLAIN_PAGE_PATH) to (DestinationErrorCode.UNEXPECTED to "This link doesn't open a OneDrive folder (HTTP 200)"),
            at(NO_LOCATION_PATH) to (DestinationErrorCode.UNEXPECTED to "This link doesn't open a OneDrive folder"),
            at(BAD_FOLDER_PATH) to (DestinationErrorCode.UNEXPECTED to "This link doesn't open a OneDrive folder"),
            at(LOOP_PATH) to (DestinationErrorCode.UNEXPECTED to "This link redirects too many times"),
            at(FORBIDDEN_PATH) to (DestinationErrorCode.FORBIDDEN to "The link no longer allows uploads"),
            at("/:f:/g/personal/u/MISTYPED") to (DestinationErrorCode.NOT_FOUND to "This link doesn't exist (deleted or mistyped)"),
        )
        for ((link, answer) in expected) {
            val e = failure { SharePointLinkDestination(link, client).listFolders("") }
            assertEquals(answer, e.code to e.message, link)
        }
        assertTrue(mock.calls.none { "/_api/" in it.path }, "no link got as far as the folder")
    }

    @Test
    fun digestRefreshedAfterTimeout() {
        val clock = TestClock()
        val d = business(clock)
        d.ensureFolder("A")
        clock.advance(Duration.ofSeconds(1739))
        d.ensureFolder("B")
        clock.advance(Duration.ofSeconds(1)) // 60 s before the 1800 s timeout
        d.ensureFolder("C")
        assertEquals(2, mock.calls.count { it.path.endsWith("/_api/contextinfo") })
        assertEquals(
            listOf("digest-1", "digest-1", "digest-2"),
            mock.calls.filter { "/folders/AddUsingPath" in it.path }.map { it.headers["X-RequestDigest"] },
        )
    }

    @Test
    fun cookieExpiryReResolvesOnce() {
        mock.folders += "$BUSINESS_ROOT/POLE"
        val d = business()
        assertEquals(listOf("POLE"), d.listFolders(""))
        mock.expireCookie()
        assertEquals(listOf("POLE"), d.listFolders(""))
        assertEquals(2, shareFetches())
        assertEquals(3, mock.calls.count { it.path.endsWith("/Folders") })
        assertEquals("FedAuth=abc2", mock.calls.last().headers["Cookie"])

        mock.expireCookie()
        d.upload("POLE/a.jpg", byteArrayOf(1)) // the digest request hits the dead cookie first
        assertEquals(3, shareFetches())
        assertTrue("$BUSINESS_ROOT/POLE/a.jpg" in mock.files)
    }

    @Test
    fun persistent403() {
        val d = business()
        mock.alwaysForbidden = true
        val e = failure { d.listFolders("") }
        assertEquals(DestinationErrorCode.FORBIDDEN, e.code)
        assertEquals("The link no longer allows uploads", e.message)
        assertEquals(2, shareFetches(), "the link is opened again once, not more")
        assertEquals(2, mock.calls.count { it.path.endsWith("/Folders") })
        assertEquals(DestinationErrorCode.FORBIDDEN, failure { d.upload("a.jpg", byteArrayOf(1)) }.code)
    }

    @Test
    fun networkFailure() {
        val d = business()
        mock.offline = true
        assertEquals(DestinationErrorCode.NETWORK, failure { d.listFolders("") }.code)
        assertEquals(DestinationErrorCode.NETWORK, failure { d.rootName() }.code)
        mock.offline = false
        assertEquals(emptyList(), d.listFolders(""), "a failed attempt leaves nothing behind")
    }

    @Test
    fun downloadStreamsBytes() {
        val bytes = Random(7).nextBytes(700_000)
        mock.folders += "$BUSINESS_ROOT/POLE"
        mock.files["$BUSINESS_ROOT/POLE/a.jpg"] = bytes
        val d = business()
        val out = ByteArrayOutputStream()
        d.download("POLE/a.jpg", out)
        assertContentEquals(bytes, out.toByteArray())
        assertTrue(mock.calls.last().path.endsWith("/GetFileByServerRelativePath(decodedurl='/personal/u/Documents/OSP%20FIELDING/Field%20Notes/POLE/a.jpg')/\$value"))
        assertEquals(DestinationErrorCode.NOT_FOUND, failure { d.download("POLE/b.jpg", ByteArrayOutputStream()) }.code)
    }

    @Test
    fun parallelUploadsShareOneSession() {
        business().ensureFolder("POLE")
        mock.calls.clear()
        val d = business()
        val files = (0 until 3).map { tmpFile(7_000_000 + it) }
        val errors = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val threads = files.mapIndexed { i, f ->
            Thread {
                try {
                    start.await()
                    d.uploadChunked("POLE/p$i.jpg", f, CHUNK, null) {}
                } catch (e: Throwable) {
                    errors += e
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(emptyList(), errors.toList())
        files.forEachIndexed { i, f -> assertContentEquals(f.readBytes(), mock.files["$BUSINESS_ROOT/POLE/p$i.jpg"]) }
        assertEquals(1, shareFetches())
        assertEquals(1, mock.calls.count { it.path.endsWith("/_api/contextinfo") })
    }

    @Test
    fun factoryCachesPerUrlFor30Minutes() {
        val clock = TestClock()
        val factory = sharePointDestinations(client, clock)
        val a = factory(mock.businessLink)
        assertEquals(0, mock.calls.size, "nothing is fetched until the destination is used")
        assertSame(a, factory(mock.businessLink))
        assertNotSame(a, factory(mock.personalLink))
        clock.advance(Duration.ofMinutes(29))
        assertSame(a, factory(mock.businessLink))
        clock.advance(Duration.ofMinutes(1))
        assertNotSame(a, factory(mock.businessLink))
    }

    private class TestClock(private var now: Instant = Instant.parse("2026-11-13T12:00:00Z")) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        fun advance(d: Duration) {
            now += d
        }
    }

    private companion object {
        const val CHUNK = 3_200_000
    }
}
