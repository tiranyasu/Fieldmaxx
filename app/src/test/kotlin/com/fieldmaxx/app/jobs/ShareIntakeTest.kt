package com.fieldmaxx.app.jobs

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.fieldmaxx.app.MainActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Serves files the way another app shares them: a content URI without the name, which comes from OpenableColumns. */
class SharedFilesProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val (name, bytes) = files[uri.lastPathSegment] ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) name else if (it == OpenableColumns.SIZE) bytes.size.toLong() else null })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val (_, bytes) = files[uri.lastPathSegment] ?: throw FileNotFoundException(uri.toString())
        val file = File.createTempFile("shared", null, context!!.cacheDir).apply { writeBytes(bytes) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.fieldmaxx.test.shared"
        val files = ConcurrentHashMap<String, Pair<String, ByteArray>>()

        /** A content URI like the OneDrive app's: an id, no file name. */
        fun share(id: String, name: String, bytes: ByteArray): Uri {
            files[id] = name to bytes
            return Uri.parse("content://$AUTHORITY/document/$id")
        }
    }
}

/** The smallest Word file the reader would open: a ZIP holding word/document.xml. */
internal fun docxBytes(): ByteArray = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { zip ->
        zip.putNextEntry(ZipEntry("[Content_Types].xml"))
        zip.write("<Types/>".toByteArray())
        zip.putNextEntry(ZipEntry("word/document.xml"))
        zip.write("<w:document/>".toByteArray())
    }
}.toByteArray()

internal val PDF_BYTES = "%PDF-1.7\n1 0 obj\n".toByteArray()

@RunWith(RobolectricTestRunner::class)
class ShareIntakeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @BeforeTest fun registerProvider() {
        Robolectric.setupContentProvider(SharedFilesProvider::class.java, SharedFilesProvider.AUTHORITY)
    }

    @Test fun shareIntentPdfIsFiles() {
        val pdf = SharedFilesProvider.share("41", "A05J9XE.pdf", PDF_BYTES)
        val one = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, pdf)
        assertEquals(SharedIntake.Files(listOf(pdf)), parseShareIntent(one))

        val docx = SharedFilesProvider.share("42", "QUERY_1_-_A05K51V.docx", docxBytes())
        val two = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(pdf, docx))
        assertEquals(SharedIntake.Files(listOf(pdf, docx)), parseShareIntent(two))
        val twice = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(pdf, docx, pdf))
        assertEquals(SharedIntake.Files(listOf(pdf, docx)), parseShareIntent(twice), "the same file shared twice is read once")

        assertNull(parseShareIntent(Intent(Intent.ACTION_MAIN)), "an ordinary launch shares nothing")
    }

    @Test fun shareIntentTextWithOneDriveLinkIsLink() {
        fun text(t: String) = parseShareIntent(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t))

        val folder = "https://contoso-my.sharepoint.com/:f:/g/personal/tech_contoso_com/EaBcDeF?e=Xy12Z"
        assertEquals(SharedIntake.Link(folder), text(folder))
        assertEquals(SharedIntake.Link("https://1drv.ms/f/s!AbCdEf"), text("Field Notes: https://1drv.ms/f/s!AbCdEf."))
        assertEquals(SharedIntake.Link("https://onedrive.live.com/?id=ABC%21123"), text("Open https://onedrive.live.com/?id=ABC%21123 please"))
        assertNull(text("https://example.com/sharepoint.com/folder"), "only OneDrive and SharePoint hosts")
        assertNull(text("Meet at pole 7"))
    }

    @Test fun applicationStarShareOfDocxIsAccepted() {
        // A PDF and a Word file shared together arrive as application/*, and a file some apps can't type as
        // application/octet-stream: the share filters take both, and the file's name or first bytes decide.
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (type in listOf("application/*", "application/octet-stream")) {
                val handlers = context.packageManager.queryIntentActivities(Intent(action).setType(type), 0)
                assertTrue(handlers.any { it.activityInfo.name == MainActivity::class.java.name }, "$action $type reaches the app")
            }
        }

        val docx = SharedFilesProvider.share("51", "QUERY_1_-_A05K51V.docx", docxBytes())
        val intake = parseShareIntent(Intent(Intent.ACTION_SEND).setType("application/*").putExtra(Intent.EXTRA_STREAM, docx))
        assertEquals(SharedIntake.Files(listOf(docx)), intake)
        val file = readJobFile(context.contentResolver, docx)
        assertEquals("QUERY_1_-_A05K51V.docx", file.name)
        assertContentEquals(docxBytes(), file.bytes)

        // No usable extension: the first bytes tell, and the name gets the extension the server reads by.
        assertEquals("Field notes.docx", readJobFile(context.contentResolver, SharedFilesProvider.share("52", "Field notes", docxBytes())).name)
        assertEquals("A05J9XE.pdf", readJobFile(context.contentResolver, SharedFilesProvider.share("53", "A05J9XE", PDF_BYTES)).name)
    }

    @Test fun ownFilesAreNeverRead() {
        // A share naming a file inside the app's storage (file://) could hand the session or the database to the reader.
        val own = File(context.filesDir, "A05J9XE.pdf").apply { writeBytes(PDF_BYTES) }
        assertFailsWith<FileNotFoundException> { readJobFile(context.contentResolver, Uri.fromFile(own)) }
        assertEquals("Couldn't open this file. Share or pick it again.", refusalOf(context.contentResolver, Uri.fromFile(own)))
    }

    @Test fun txtShareIsRefused() {
        val txt = SharedFilesProvider.share("61", "notes.txt", "POLE 1 = 39.6361, -119.6912".toByteArray())

        val e = assertFailsWith<IllegalArgumentException> { readJobFile(context.contentResolver, txt) }
        assertEquals("Only PDF and Word (.docx) files", e.message)
        val zip = SharedFilesProvider.share("62", "photos.zip", ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("POLE_1.jpg")); it.write(1) }
        }.toByteArray())
        assertEquals("Only PDF and Word (.docx) files", assertFailsWith<IllegalArgumentException> { readJobFile(context.contentResolver, zip) }.message)
    }
}
