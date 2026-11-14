package com.fieldmaxx.sharepoint

import com.fieldmaxx.core.retry.BUSY_RECHECK_MS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The phone's and the server's chunk size. */
private const val CHUNK = 3_200_000

/** How long a refused repeat is looked at again (every 2 min, as the phone does) before the probe finishes the first session. */
private val LOCK_WAIT: Duration = Duration.ofMinutes(20)

/** This run's folder, one for all the probes. */
private val RUN = "_fieldmaxx_it/" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())

private val GUID = Regex("guid'([0-9a-fA-F-]{36})'")
private val OFFSET = Regex("fileOffset=(\\d+)")
private val CREATED = Regex("Files/AddUsingPath\\(decodedurl='([^']*)'")

/**
 * R90: what the owner's real test folder answers in the upload situations R82 relies on. The answers go to the test's
 * output for the report. The link comes only from `FMX_LIVE_TEST_LINK` (never commit it). It can't delete, so each probe
 * leaves one file of its own under this run's `_fieldmaxx_it/<timestamp>/`, and touches no other file. Each probe
 * asserts only R82's end state: one file under its name, whole. Both sessions on a file send the same bytes, so the
 * size holds whichever one wins.
 */
@EnabledIfEnvironmentVariable(named = "FMX_LIVE_TEST_LINK", matches = ".+")
class SharePointProbeLiveTest {
    private val started = System.nanoTime()
    private val answers = mutableListOf<String>()
    private val ids = mutableMapOf<String, String>()
    private val d = SharePointLinkDestination(
        System.getenv("FMX_LIVE_TEST_LINK"),
        OkHttpClient.Builder().readTimeout(Duration.ofSeconds(60)).writeTimeout(Duration.ofSeconds(60))
            .addInterceptor { chain -> chain.proceed(chain.request()).also { r -> runCatching { record(chain.request(), r) } } }
            .build(),
    )

    /** While a chunked upload's session is open, what does its file read as? R82 takes an empty file for one in progress. */
    @Test
    fun sizeWhileASessionIsOpen() = probe("probe_size_while_open.jpg", chunks = 3) { name, file ->
        outcome("upload, the file's size read after each step") { d.uploadChunked(name, file, CHUNK, null) { d.fileInfo(name) } }
    }

    /** R82: another uploader opens a session of its own (a new id) into the empty file of an upload that is still open. */
    @Test
    fun secondSessionIntoAnOpenFile() = probe("probe_second_session.jpg", chunks = 2) { name, file ->
        val first = openAndLeave(name, file)
        outcome("second session, new id, into the empty file") {
            d.uploadChunked(name, file, CHUNK, ChunkSession(UUID.randomUUID().toString(), 0)) {}
        }
        outcome("first session finished") { d.uploadChunked(name, file, CHUNK, first) {} }
    }

    /**
     * The first StartUpload's answer is lost, so the uploader resumes at (id, 0) and sends it again under the same id. A
     * refusal for a lock is looked at again every 2 min, as the phone does, for up to [LOCK_WAIT]; then the first
     * session is finished from its offset.
     */
    @Test
    fun startUploadRepeatedUnderTheSameId() = probe("probe_repeated_start.jpg", chunks = 2) { name, file ->
        val first = openAndLeave(name, file)
        val again = ChunkSession(first.uploadId, 0)
        val since = System.nanoTime()
        var result = outcome("StartUpload again, same id") { d.uploadChunked(name, file, CHUNK, again) {} }
        while (result == DestinationErrorCode.BUSY && System.nanoTime() - since < LOCK_WAIT.toNanos()) {
            Thread.sleep(BUSY_RECHECK_MS)
            val waited = Duration.ofNanos(System.nanoTime() - since).toSeconds()
            result = outcome("looked again after $waited s") { d.uploadChunked(name, file, CHUNK, again) {} }
        }
        if (d.fileInfo(name)?.size != file.length()) outcome("first session finished") { d.uploadChunked(name, file, CHUNK, first) {} }
    }

    /** A probe on its own file of [chunks] chunks (the last one short): [steps], then R82's end state; prints the answers. */
    private fun probe(fileName: String, chunks: Int, steps: (name: String, file: File) -> Unit) {
        val name = "$RUN/$fileName"
        val file = File.createTempFile("probe", ".jpg").apply {
            deleteOnExit()
            writeBytes(Random(fileName.hashCode()).nextBytes((chunks - 1) * CHUNK + 600_000))
        }
        try {
            redacting {
                d.ensureFolder(RUN)
                steps(name, file)
                assertEquals(FileInfo(file.length()), d.fileInfo(name), "one file under the probe's name, whole")
            }
        } finally {
            println("probe $name (${file.length()} bytes, chunks of $CHUNK):")
            answers.forEach { println("  $it") }
        }
    }

    /** Starts a chunked upload and stops it once its first chunk is in, as when the phone loses signal: the session stays open. */
    private fun openAndLeave(name: String, file: File): ChunkSession {
        var open: ChunkSession? = null
        try {
            d.uploadChunked(name, file, CHUNK, null) { s ->
                if (s.offset > 0) {
                    open = s
                    throw LeftOpen()
                }
            }
        } catch (_: LeftOpen) {
        }
        val s = open ?: error("the upload reported no chunk")
        answers += "= first session ${label(s.uploadId)} left open at offset ${s.offset}"
        return s
    }

    private class LeftOpen : RuntimeException()

    /** Runs one step and records how it ended: null when it went through, else the destination's error code. */
    private fun outcome(step: String, block: () -> Unit): DestinationErrorCode? = try {
        block()
        answers += "= $step: went through"
        null
    } catch (e: DestinationException) {
        answers += "= $step: ${e.code} (${redact(e.message)})"
        e.code
    }

    /** One line per request that makes, writes or reads a file or folder: the call, SharePoint's status and its answer. */
    private fun record(request: Request, response: Response) {
        val path = URLDecoder.decode(request.url.encodedPath.replace("+", "%2B"), Charsets.UTF_8)
        val id = GUID.find(path)?.groupValues?.get(1)?.let(::label)
        val offset = OFFSET.find(path)?.groupValues?.get(1)
        val call = when {
            "/StartUpload(" in path -> "StartUpload($id)"
            "/ContinueUpload(" in path -> "ContinueUpload($id, $offset)"
            "/FinishUpload(" in path -> "FinishUpload($id, $offset)"
            "/Files/AddUsingPath(" in path -> "create ${CREATED.find(path)?.groupValues?.get(1)}"
            "/folders/AddUsingPath(" in path -> "mkdir " + path.indexOf("_fieldmaxx_it").let { if (it < 0) "?" else path.substring(it).substringBefore("'") }
            "GetFileByServerRelativePath(" in path && request.method == "GET" && !path.endsWith("/\$value") -> "fileInfo"
            else -> return
        }
        val at = (System.nanoTime() - started) / 1e9
        answers += "+%.1fs %s -> HTTP %d %s".format(at, call, response.code, summary(response)).trimEnd()
    }

    /** SharePoint's answer in short: its error code and message, else the offset it confirmed or the file's length. */
    private fun summary(response: Response): String {
        val body = response.peekBody(64L * 1024).string()
        val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = (json?.get("odata.error") ?: json?.get("error")) as? JsonObject
        val value = json?.get("value") as? JsonPrimitive
        val length = json?.get("Length") as? JsonPrimitive
        val text = when {
            error != null -> {
                val message = error["message"].let { (it as? JsonObject)?.get("value") ?: it }
                "${(error["code"] as? JsonPrimitive)?.content}: ${(message as? JsonPrimitive)?.content}"
            }
            value != null -> "value=${value.content}"
            length != null -> "Length=${length.content}"
            response.isSuccessful -> ""
            else -> body.take(300)
        }
        return redact(text)
    }

    /** Upload ids as id1, id2… in the order they first appear. */
    private fun label(uploadId: String): String = ids.getOrPut(uploadId.lowercase()) { "id${ids.size + 1}" }
}

/** Runs [block]; a failure is thrown again with its message redacted, since SharePoint's messages name the folder's path. */
private inline fun redacting(block: () -> Unit) = try {
    block()
} catch (e: Throwable) {
    throw AssertionError("${e.javaClass.simpleName}: ${redact(e.message)}").apply { stackTrace = e.stackTrace }
}

/**
 * [text] without the link or any part of it (the account id, the share code), without the folder's server path, which
 * SharePoint's messages carry (`personal/<account>/Documents/<folder>/…`), and without the people they name.
 */
private fun redact(text: String?): String {
    val link = System.getenv("FMX_LIVE_TEST_LINK").orEmpty().trim()
    val uri = runCatching { URI(link) }.getOrNull()
    val parts = listOf(link) + uri?.rawPath.orEmpty().split('/') + uri?.rawQuery.orEmpty().split('&', '=')
    var out = text.orEmpty()
    for (part in parts.filter { it.length >= 4 }) out = out.replace(part, "…", ignoreCase = true)
    return out.replace(Regex("""/?personal/[^\s"'<>]*?(?=/_fieldmaxx_it)"""), "<test folder>")
        .replace(Regex("""personal/[^/\s"'<>]+"""), "personal/…")
        .replace(Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+"""), "<email>")
        .replace(Regex("""((?:use|modified) by )[^."]+"""), "\$1<user>")
}
