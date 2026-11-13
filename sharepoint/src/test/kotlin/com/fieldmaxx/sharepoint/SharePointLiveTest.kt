package com.fieldmaxx.sharepoint

import okhttp3.OkHttpClient
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Against the owner's real test folder; the link comes only from `FMX_LIVE_TEST_LINK` (never commit it).
 * The link can't delete, so everything written here stays: keep it small and under `_fieldmaxx_it/<timestamp>/`.
 */
@EnabledIfEnvironmentVariable(named = "FMX_LIVE_TEST_LINK", matches = ".+")
class SharePointLiveTest {
    @Test
    fun chunkedUploadRoundTrip() {
        val folderAnswers = mutableListOf<String>()
        val client = OkHttpClient.Builder().readTimeout(Duration.ofSeconds(60)).writeTimeout(Duration.ofSeconds(60))
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val path = chain.request().url.encodedPath
                if ("/folders/AddUsingPath" in path) {
                    val folder = path.substringAfter("decodedurl='").substringBefore("',").substringAfterLast('/')
                    folderAnswers += "AddUsingPath $folder -> HTTP ${response.code} ${response.peekBody(300).string()}"
                }
                response
            }
            .build()
        val d = SharePointLinkDestination(System.getenv("FMX_LIVE_TEST_LINK"), client)
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val dir = "_fieldmaxx_it/$stamp"

        d.ensureFolder(dir)
        assertTrue(stamp in d.listFolders("_fieldmaxx_it"))
        folderAnswers.clear()
        d.ensureFolder(dir) // both folders exist now: settles what SharePoint answers (no file is written)
        folderAnswers.forEach { println("re-ensure: $it") }
        val name = "$dir/POLE_99_IT_chunked.jpg"
        assertNull(d.fileInfo(name))

        val file = File.createTempFile("live", ".jpg").apply {
            deleteOnExit()
            writeBytes(Random.nextBytes(9_000_000))
        }
        val offsets = mutableListOf<Long>()
        d.uploadChunked(name, file, 3_200_000, null) { offsets += it.offset }
        assertEquals(listOf(0L, 3_200_000L, 6_400_000L, 9_000_000L), offsets)
        assertEquals(FileInfo(file.length()), d.fileInfo(name))

        val duplicate = assertFailsWith<DestinationException> { d.upload(name, byteArrayOf(1, 2, 3)) }
        assertEquals(DestinationErrorCode.EXISTS, duplicate.code)

        val out = ByteArrayOutputStream()
        d.download(name, out)
        assertContentEquals(file.readBytes(), out.toByteArray())
    }
}
