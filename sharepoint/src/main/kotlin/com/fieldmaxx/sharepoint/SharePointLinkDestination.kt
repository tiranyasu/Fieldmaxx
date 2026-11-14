package com.fieldmaxx.sharepoint

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal const val ODATA_ACCEPT = "application/json;odata=nometadata"
internal const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

private const val NEEDS_SIGN_IN = "This link needs a Microsoft sign-in"
private const val NO_UPLOADS = "The link no longer allows uploads"
private const val MAX_REDIRECTS = 10
private const val DIGEST_MARGIN_SECONDS = 60L
private val CACHE_TIME: Duration = Duration.ofMinutes(30)
private val OCTETS = "application/octet-stream".toMediaType()
private val EMPTY_BODY = ByteArray(0).toRequestBody()
private val SESSION_LOST_CODES = setOf(400, 409, 423)

/** SharePoint's answers to a StartUpload on a file another open session holds (its lock), R82. */
private val LOCKED_CODES = setOf(409, 423)

/**
 * [PhotoDestination] behind an "anyone with the link can edit" OneDrive/SharePoint folder link, with no
 * Microsoft sign-in (spec §5.5, §5.6). Opening the link gives a guest cookie; with it the SharePoint REST API
 * lists, creates folders, uploads (never overwriting) and reads back. The link is opened on first use and again
 * when the cookie stops working. Thread-safe: parallel uploads share one opened link and one request digest.
 */
class SharePointLinkDestination(
    private val shareUrl: String,
    client: OkHttpClient,
    private val clock: Clock = Clock.systemUTC(),
) : PhotoDestination {
    private val cookies = GuestCookieJar()
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(cookies).build()
    private val lock = Any()
    private var opened: Opened? = null
    private var digest: Digest? = null

    /** [site] is the encoded site URL (`https://host/personal/<user>`), [root] the shared folder's server-relative path. */
    private class Opened(val site: String, val root: String) {
        fun path(relPath: String): String = relPath.trim('/').let { if (it.isEmpty()) root else "$root/$it" }
        fun folder(path: String) = "$site/_api/web/GetFolderByServerRelativePath(decodedurl='${spPathLiteral(path)}')"
        fun file(path: String) = "$site/_api/web/GetFileByServerRelativePath(decodedurl='${spPathLiteral(path)}')"
    }

    private class Digest(val opened: Opened, val value: String, val refreshAt: Instant)

    override fun rootName(): String = network { session() }.root.substringAfterLast('/')

    override fun listFolders(relPath: String): List<String> =
        api(null, { "${it.folder(it.path(relPath))}/Folders?\$select=Name" }) { r ->
            if (!r.isSuccessful) throw failure(r, relPath)
            r.json().field("value").jsonArray.map { it.jsonObject.field("Name").jsonPrimitive.content }.sorted()
        }

    /** Creates each missing segment; a folder that already exists is fine (200, or a 400 "already exists"). */
    override fun ensureFolder(relPath: String) {
        var path = ""
        for (part in relPath.split('/').filter { it.isNotEmpty() }) {
            path = if (path.isEmpty()) part else "$path/$part"
            val current = path
            api(EMPTY_BODY, { "${it.site}/_api/web/folders/AddUsingPath(decodedurl='${spPathLiteral(it.path(current))}',overwrite=false)" }) { r ->
                if (r.isSuccessful) return@api
                val text = r.text()
                if (r.code == 400 && text.contains("already exists", ignoreCase = true)) return@api
                throw failure(r.code, text, current)
            }
        }
    }

    override fun fileInfo(relPath: String): FileInfo? =
        api(null, { "${it.file(it.path(relPath))}?\$select=Length" }) { r ->
            when {
                r.isSuccessful -> FileInfo(r.json().field("Length").jsonPrimitive.content.toLong())
                r.code == 404 -> null
                else -> throw failure(r, relPath)
            }
        }

    override fun upload(relPath: String, bytes: ByteArray) = create(relPath, bytes)

    /**
     * A file that fits in one chunk goes up in one request (no session, no progress). Otherwise, with no [resume]:
     * create the empty file (EXISTS if the name is taken) and report `ChunkSession(id, 0)` at once, so a retry
     * resumes this file instead of meeting it as EXISTS; then StartUpload / ContinueUpload / FinishUpload, reporting
     * the confirmed offset after each chunk. With [resume]: carry on from its offset; if SharePoint has lost that
     * session, the file is done when it already has the full size (a FinishUpload whose answer was lost), else the
     * same (own, partial) file is restarted under a new upload id. UPLOAD_SESSION_LOST means the session was lost and
     * not restarted in this call: retry later with the last reported [ChunkSession]. A [resume] at offset 0 under an id
     * SharePoint never saw starts a fresh session into the existing file (R82: an empty file of the same photo, left by
     * another uploader). BUSY: a StartUpload met the file locked by another open session (423, 409, or a lock message).
     */
    override fun uploadChunked(relPath: String, file: File, chunkBytes: Int, resume: ChunkSession?, onProgress: (ChunkSession) -> Unit) {
        require(chunkBytes > 0) { "chunkBytes must be positive" }
        RandomAccessFile(file, "r").use { raf ->
            val chunks = Chunks(raf, chunkBytes)
            when {
                resume == null && chunks.length <= chunkBytes -> create(relPath, chunks.read(0, chunks.length))
                resume == null -> {
                    create(relPath, ByteArray(0))
                    val uploadId = newUploadId()
                    onProgress(ChunkSession(uploadId, 0))
                    sendChunks(relPath, chunks, uploadId, 0, onProgress)
                }
                else -> try {
                    sendChunks(relPath, chunks, resume.uploadId, resume.offset.coerceIn(0, chunks.length), onProgress)
                } catch (e: DestinationException) {
                    if (e.code != DestinationErrorCode.UPLOAD_SESSION_LOST) throw e
                    if (fileInfo(relPath)?.size == chunks.length) return // complete already: never rewrite it
                    sendChunks(relPath, chunks, newUploadId(), 0, onProgress)
                }
            }
        }
    }

    override fun download(relPath: String, out: OutputStream) =
        api(null, { "${it.file(it.path(relPath))}/\$value" }) { r ->
            if (!r.isSuccessful) throw failure(r, relPath)
            r.body!!.byteStream().copyTo(out)
            Unit
        }

    private fun create(relPath: String, bytes: ByteArray) {
        val parent = relPath.substringBeforeLast('/', "")
        val name = relPath.substringAfterLast('/')
        val body = bytes.toRequestBody(OCTETS)
        api(body, { "${it.folder(it.path(parent))}/Files/AddUsingPath(decodedurl='${spPathLiteral(name)}',overwrite=false)" }) { r ->
            if (r.isSuccessful) return@api
            val text = r.text()
            if (r.code == 400 && text.contains("already exists", ignoreCase = true)) {
                throw DestinationException(DestinationErrorCode.EXISTS, "$relPath already exists")
            }
            throw failure(r.code, text, relPath)
        }
    }

    /** Sends the chunks from [from] on; offset 0 opens the session with StartUpload, the last chunk is FinishUpload. */
    private fun sendChunks(relPath: String, chunks: Chunks, uploadId: String, from: Long, onProgress: (ChunkSession) -> Unit) {
        var offset = from
        while (true) {
            val starting = offset == 0L
            val end = minOf(chunks.length, offset + chunks.size)
            val finishing = !starting && end == chunks.length
            val call = when {
                starting -> "StartUpload(uploadId=guid'$uploadId')"
                finishing -> "FinishUpload(uploadId=guid'$uploadId',fileOffset=$offset)"
                else -> "ContinueUpload(uploadId=guid'$uploadId',fileOffset=$offset)"
            }
            val body = chunks.read(offset, end).toRequestBody(OCTETS)
            api(body, { "${it.file(it.path(relPath))}/$call" }) { r ->
                if (!r.isSuccessful) {
                    val text = r.text()
                    if (starting && (r.code in LOCKED_CODES || text.contains("lock", ignoreCase = true))) {
                        throw DestinationException(DestinationErrorCode.BUSY, "OneDrive holds $relPath for another upload")
                    }
                    val lost = r.code in SESSION_LOST_CODES || (r.code == 404 && !starting)
                    if (lost) throw DestinationException(DestinationErrorCode.UPLOAD_SESSION_LOST, "The upload session for $relPath was lost")
                    throw failure(r.code, text, relPath)
                }
                if (!finishing) {
                    val confirmed = r.json()["value"]?.jsonPrimitive?.content?.toLongOrNull()
                    if (confirmed != null && confirmed != end) {
                        throw DestinationException(DestinationErrorCode.UNEXPECTED, "OneDrive confirmed $confirmed bytes of $relPath, expected $end")
                    }
                }
            }
            onProgress(ChunkSession(uploadId, end))
            if (finishing) return
            offset = end
        }
    }

    /** One API request (a POST when [body] is given); on 401/403 the link is opened again once and the request retried. */
    private fun <T> api(body: RequestBody?, url: (Opened) -> String, handle: (Response) -> T): T = network {
        val first = session()
        val response = send(first, body, url) ?: send(reopen(first), body, url)
            ?: throw DestinationException(DestinationErrorCode.FORBIDDEN, NO_UPLOADS)
        response.use(handle)
    }

    /** null when the guest cookie (or the digest made with it) is refused: 401/403. */
    private fun send(current: Opened, body: RequestBody?, url: (Opened) -> String): Response? {
        val request = request(url(current).toHttpUrl())
        if (body != null) request.post(body).header("X-RequestDigest", digest(current) ?: return null)
        val response = http.newCall(request.build()).execute()
        if (response.code != 401 && response.code != 403) return response
        response.close()
        return null
    }

    private fun session(): Opened = synchronized(lock) { opened ?: open().also { opened = it } }

    private fun reopen(stale: Opened): Opened = synchronized(lock) {
        if (opened === stale) {
            opened = null
            digest = null
            cookies.clear()
        }
        session()
    }

    /** Request digest for POSTs, reused until 60 s before it times out; null on 401/403. */
    private fun digest(current: Opened): String? = synchronized(lock) {
        digest?.let { if (it.opened === current && clock.instant() < it.refreshAt) return it.value }
        http.newCall(request("${current.site}/_api/contextinfo".toHttpUrl()).post(EMPTY_BODY).build()).execute().use { r ->
            if (r.code == 401 || r.code == 403) return null
            if (!r.isSuccessful) throw failure(r, "")
            val json = r.json()
            val value = json.field("FormDigestValue").jsonPrimitive.content
            val timeout = json["FormDigestTimeoutSeconds"]?.jsonPrimitive?.longOrNull ?: 0
            digest = Digest(current, value, clock.instant().plusSeconds(timeout - DIGEST_MARGIN_SECONDS))
            value
        }
    }

    /**
     * Follows the link's redirects by hand, keeping the guest cookie, until one names the folder: the `spopath`
     * parameter (personal OneDrive), else the `id` parameter of `…/onedrive.aspx` (business). That page is not loaded.
     */
    private fun open(): Opened {
        var url = shareUrl.trim().toHttpUrlOrNull()
            ?: throw DestinationException(DestinationErrorCode.UNEXPECTED, "This is not a web link")
        repeat(MAX_REDIRECTS) {
            http.newCall(request(url).build()).execute().use { r ->
                if (!r.isRedirect) {
                    throw when (r.code) {
                        401, 403 -> DestinationException(DestinationErrorCode.FORBIDDEN, NO_UPLOADS)
                        404 -> DestinationException(DestinationErrorCode.NOT_FOUND, "This link doesn't exist (deleted or mistyped)")
                        else -> DestinationException(DestinationErrorCode.UNEXPECTED, "This link doesn't open a OneDrive folder (HTTP ${r.code})")
                    }
                }
                val next = r.header("Location")?.let { url.resolve(it) }
                    ?: throw DestinationException(DestinationErrorCode.UNEXPECTED, "This link doesn't open a OneDrive folder")
                if (next.host.startsWith("login.")) throw DestinationException(DestinationErrorCode.REQUIRES_SIGN_IN, NEEDS_SIGN_IN)
                folderOf(next)?.let { return it }
                url = next
            }
        }
        throw DestinationException(DestinationErrorCode.UNEXPECTED, "This link redirects too many times")
    }

    private fun folderOf(location: HttpUrl): Opened? {
        val root = location.queryParameter("spopath")?.takeIf { it.isNotEmpty() }
            ?: location.queryParameter("id")?.takeIf { it.isNotEmpty() && location.encodedPath.endsWith("/onedrive.aspx", ignoreCase = true) }
            ?: return null
        val parts = root.trim('/').split('/')
        if (!root.startsWith("/") || parts.size < 2) {
            throw DestinationException(DestinationErrorCode.UNEXPECTED, "This link doesn't open a OneDrive folder")
        }
        val origin = location.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
        return Opened("$origin/${encodeSegment(parts[0])}/${encodeSegment(parts[1])}", root.trimEnd('/'))
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).header("Accept", ODATA_ACCEPT)

    private inline fun <T> network(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw DestinationException(DestinationErrorCode.NETWORK, "Can't reach OneDrive: ${e.message ?: e.javaClass.simpleName}")
    } catch (e: IllegalArgumentException) { // unreadable JSON (or a URL that won't parse)
        throw DestinationException(DestinationErrorCode.UNEXPECTED, "OneDrive sent an unreadable answer: ${e.message}")
    }

    private fun failure(r: Response, relPath: String) = failure(r.code, r.text(), relPath)

    private fun failure(code: Int, text: String, relPath: String) = when (code) {
        404 -> DestinationException(DestinationErrorCode.NOT_FOUND, if (relPath.isEmpty()) "The shared folder was not found" else "$relPath was not found")
        429, 502, 503, 504 -> DestinationException(DestinationErrorCode.NETWORK, "OneDrive is busy (HTTP $code)")
        else -> DestinationException(DestinationErrorCode.UNEXPECTED, "OneDrive answered HTTP $code: ${errorMessage(text)}")
    }

    private fun newUploadId() = UUID.randomUUID().toString()
}

/**
 * A server-relative path as the inside of an OData string literal in a URL (`decodedurl='…'`): each segment
 * percent-encoded (UTF-8; only `A-Z a-z 0-9 - . _ ~` and `'` kept), `'` doubled, `/` kept.
 */
fun spPathLiteral(serverRelativePath: String): String =
    serverRelativePath.split('/').joinToString("/") { encodeSegment(it.replace("'", "''")) }

/** Destinations by Field Notes link; each one (with its opened link and digest) is reused for 30 minutes. */
fun sharePointDestinations(client: OkHttpClient, clock: Clock = Clock.systemUTC()): DestinationFactory {
    class Entry(val destination: SharePointLinkDestination, val created: Instant)
    val cache = ConcurrentHashMap<String, Entry>()
    return { url ->
        val now = clock.instant()
        cache.values.removeIf { Duration.between(it.created, now) >= CACHE_TIME }
        cache.computeIfAbsent(url) { Entry(SharePointLinkDestination(url, client, clock), now) }.destination
    }
}

private const val HEX = "0123456789ABCDEF"

private fun encodeSegment(segment: String): String = buildString {
    for (b in segment.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xff
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~'") append(ch) else append('%').append(HEX[c shr 4]).append(HEX[c and 15])
    }
}

private class Chunks(private val raf: RandomAccessFile, val size: Int) {
    val length: Long = raf.length()

    fun read(from: Long, to: Long): ByteArray {
        val bytes = ByteArray((to - from).toInt())
        raf.seek(from)
        raf.readFully(bytes)
        return bytes
    }
}

/** In-memory cookies for one link (the guest `FedAuth` cookie and friends). */
private class GuestCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (c in cookies) {
            this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.expiresAt > now) this.cookies += c
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() = cookies.clear()
}

private fun Response.text(): String = body?.string().orEmpty()

private fun Response.json(): JsonObject = Json.parseToJsonElement(text()).jsonObject

private fun JsonObject.field(name: String) = get(name) ?: throw IllegalArgumentException("no \"$name\" in the answer")

/** The message of a SharePoint error answer (`{"odata.error":{"message":{"value":…}}}`), else the start of the text. */
private fun errorMessage(text: String): String = runCatching {
    val root = Json.parseToJsonElement(text).jsonObject
    val error = (root["odata.error"] ?: root["error"])!!.jsonObject
    val message = error["message"]!!
    (if (message is JsonObject) message["value"]!! else message).jsonPrimitive.content
}.getOrElse { text.take(200) }
