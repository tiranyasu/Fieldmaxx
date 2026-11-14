package com.fieldmaxx.sharepoint

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SharePoint as the share-link client sees it (spec §5.5, §5.6), on one local [MockWebServer]:
 * both link shapes (business `…-my.sharepoint.com` and personal `1drv.ms`), the guest cookie, and the REST calls.
 * Every request is kept in [calls]; [log] holds one readable line per handled call.
 */
class MockSharePoint : Closeable {
    companion object {
        const val BUSINESS_ROOT = "/personal/u/Documents/OSP FIELDING/Field Notes"
        const val PERSONAL_ROOT = "/personal/cid/Documents/Fieldmaxx"
        const val BUSINESS_SHARE_PATH = "/:f:/g/personal/u/TOKEN"
        const val PERSONAL_SHARE_PATH = "/f/c/cid/TOKEN"
        const val PERSONAL_REDEEM_PATH = "/:f:/g/personal/CID/TOKEN"
        val SHARE_PATHS = setOf(BUSINESS_SHARE_PATH, PERSONAL_SHARE_PATH, PERSONAL_REDEEM_PATH)
        private val ROOTS = mapOf("/personal/u" to BUSINESS_ROOT, "/personal/cid" to PERSONAL_ROOT)

        private const val LIT = "'((?:[^']|'')*)'"
        private const val GUID = "guid'([0-9a-fA-F-]{36})'"
        private val API = Regex("(/personal/[^/]+)/_api/(.*)")
        private val LIST = Regex("web/GetFolderByServerRelativePath\\(decodedurl=$LIT\\)/Folders")
        private val MKDIR = Regex("web/folders/AddUsingPath\\(decodedurl=$LIT,overwrite=false\\)")
        private val CREATE = Regex("web/GetFolderByServerRelativePath\\(decodedurl=$LIT\\)/Files/AddUsingPath\\(decodedurl=$LIT,overwrite=false\\)")
        private val FILE = Regex("web/GetFileByServerRelativePath\\(decodedurl=$LIT\\)(/.*)?")
        private val START = Regex("/StartUpload\\(uploadId=$GUID\\)")
        private val CONTINUE = Regex("/ContinueUpload\\(uploadId=$GUID,fileOffset=(\\d+)\\)")
        private val FINISH = Regex("/FinishUpload\\(uploadId=$GUID,fileOffset=(\\d+)\\)")
    }

    data class Call(val method: String, val target: String, val headers: Headers, val bodySize: Int) {
        /** Encoded path, without the query. */
        val path: String get() = target.substringBefore('?')
    }

    val server = MockWebServer()
    val calls: MutableList<Call> = CopyOnWriteArrayList()
    val log: MutableList<String> = CopyOnWriteArrayList()

    /** Server-relative, decoded paths. The two link roots exist from the start. */
    val folders: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add(BUSINESS_ROOT); add(PERSONAL_ROOT) }
    val files: MutableMap<String, ByteArray> = ConcurrentHashMap()
    val digests: MutableList<String> = CopyOnWriteArrayList()

    /** The link's share URL redirects to the Microsoft sign-in page. */
    @Volatile var signInOnly = false

    /** Every API call answers 403, even after the link is opened again. */
    @Volatile var alwaysForbidden = false

    /** Every request's connection is dropped. */
    @Volatile var offline = false

    /**
     * `folders/AddUsingPath` on an existing folder: 400 "already exists" (seen live on the personal test link,
     * 2026-11-13), or 200 when true (the business-link spike's note).
     */
    @Volatile var existingFolderAnswers200 = false

    /** A StartUpload on a file another open session writes to answers 423, SharePoint's file lock (R82); else it is let in. */
    @Volatile var lockWhileUploading = false

    private val lock = Any()
    private var cookie: String? = null
    private var cookiesIssued = 0
    private var chunkCalls = 0
    private var dropAt: Int? = null
    private var loseAnswerAt: Int? = null
    private var loseAnswer = false
    private val uploads = mutableMapOf<String, Upload>()

    private class Upload(val path: String, val data: ByteArrayOutputStream = ByteArrayOutputStream())

    val businessLink: String get() = server.url("$BUSINESS_SHARE_PATH?e=x").toString()
    val personalLink: String get() = server.url("$PERSONAL_SHARE_PATH?e=x").toString()

    /** The guest cookie stops working: API calls answer 403 `Access denied` until the share URL is opened again. */
    fun expireCookie() = synchronized(lock) { cookie = null }

    /** Every open upload session is forgotten; their upload ids now answer 400. */
    fun expireUploadSessions() = synchronized(lock) { uploads.clear() }

    /** Drop the connection on the [n]th chunk request (Start/Continue/FinishUpload) from now; the chunk is not stored. */
    fun dropOnChunk(n: Int) = synchronized(lock) { dropAt = chunkCalls + n }

    /** On the [n]th chunk request from now, store the chunk, then drop the connection before answering. */
    fun loseAnswerOnChunk(n: Int) = synchronized(lock) { loseAnswerAt = chunkCalls + n }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(lock) {
                val response = handle(request)
                if (!loseAnswer) return@synchronized response
                loseAnswer = false
                log += "answer lost"
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            }
        }
        server.start()
    }

    override fun close() = server.shutdown()

    private fun handle(request: RecordedRequest): MockResponse {
        val method = request.method ?: "GET"
        val target = request.path ?: "/"
        val body = request.body.readByteArray()
        calls += Call(method, target, request.headers, body.size)
        if (offline) return dropped("offline $method ${target.substringBefore('?')}")
        val path = target.substringBefore('?')
        return when (path) {
            BUSINESS_SHARE_PATH -> share(
                MockResponse().setResponseCode(302).setHeader(
                    "Location",
                    "/personal/u/_layouts/15/onedrive.aspx?id=%2Fpersonal%2Fu%2FDocuments%2FOSP%20FIELDING%2FField%20Notes&ga=1",
                ),
            )
            PERSONAL_SHARE_PATH -> personal(request) ?: MockResponse().setResponseCode(301)
                .setHeader("Location", server.url("$PERSONAL_REDEEM_PATH?e=x&migratedtospo=true").toString())
                .also { log += "share 301" }
            PERSONAL_REDEEM_PATH -> personal(request) ?: share(
                MockResponse().setResponseCode(302).setHeader(
                    "Location",
                    server.url(
                        "/?id=184EC04AA3230F57%21sTOKEN&spopath=%2Fpersonal%2Fcid%2FDocuments%2FFieldmaxx" +
                            "&listurl=https%3A%2F%2Fonedrive.live.com%2Fpersonal%2Fcid%2FDocuments",
                    ).toString(),
                ),
            )
            else -> api(method, path, request, body)
        }
    }

    /** The personal link shows a "browser not supported" page to non-browser clients. */
    private fun personal(request: RecordedRequest): MockResponse? =
        if (request.getHeader("User-Agent")?.startsWith("Mozilla/5.0") == true) null
        else MockResponse().setResponseCode(200).setBody("<html>Please update your browser</html>").also { log += "share 200 (not a browser)" }

    private fun share(redirect: MockResponse): MockResponse {
        if (signInOnly) {
            log += "share 302 (sign-in)"
            return MockResponse().setResponseCode(302)
                .setHeader("Location", "https://login.microsoftonline.com/common/oauth2/authorize?client_id=00000003-0000-0ff1-ce00-000000000000")
        }
        cookiesIssued++
        val value = if (cookiesIssued == 1) "abc" else "abc$cookiesIssued"
        cookie = value
        log += "share ${redirect.status.split(' ')[1]}"
        return redirect.addHeader("Set-Cookie", "FedAuth=$value; path=/; HttpOnly")
    }

    private fun api(method: String, path: String, request: RecordedRequest, body: ByteArray): MockResponse {
        val m = API.matchEntire(path) ?: return status(404, "no such page")
        val site = m.groupValues[1]
        val root = ROOTS[site] ?: return status(404, "no such site")
        val call = m.groupValues[2]
        val cookieHeader = request.getHeader("Cookie").orEmpty()
        if (alwaysForbidden || cookie == null || cookieHeader.split("; ").none { it == "FedAuth=$cookie" }) {
            log += "$call 403"
            return error(403, "-2147024891, System.UnauthorizedAccessException", "Access denied.")
        }
        if (call == "contextinfo") {
            if (method != "POST") return status(405, "POST only")
            val digest = "digest-${digests.size + 1}"
            digests += digest
            log += "contextinfo 200"
            return json(buildJsonObject { put("FormDigestValue", digest); put("FormDigestTimeoutSeconds", 1800) }.toString())
        }
        if (method == "POST" && request.getHeader("X-RequestDigest") !in digests) {
            log += "$call 403 (digest)"
            return error(403, "-2130575251, Microsoft.SharePoint.SPException", "The security validation for this page is invalid.")
        }
        fun rel(p: String) = p.removePrefix(root).removePrefix("/").ifEmpty { "<root>" }
        fun inside(p: String) = p == root || p.startsWith("$root/")

        LIST.matchEntire(call)?.let { r ->
            val p = literal(r.groupValues[1])
            if (method != "GET") return status(405, "GET only")
            if (!inside(p)) return error(403, "-2147024891, System.UnauthorizedAccessException", "Access denied.")
            if (p !in folders) return notFound("list ${rel(p)}")
            log += "list ${rel(p)} 200"
            val names = folders.filter { it.substringBeforeLast('/') == p }.map { it.substringAfterLast('/') }
            return json(buildJsonObject { putJsonArray("value") { names.forEach { n -> addJsonObject { put("Name", n) } } } }.toString())
        }
        MKDIR.matchEntire(call)?.let { r ->
            val p = literal(r.groupValues[1])
            if (method != "POST") return status(405, "POST only")
            if (!inside(p)) return error(403, "-2147024891, System.UnauthorizedAccessException", "Access denied.")
            if (p.substringBeforeLast('/') !in folders) return notFound("mkdir ${rel(p)}")
            if (p in folders && !existingFolderAnswers200) {
                log += "mkdir ${rel(p)} 400"
                return error(400, "-2130575257, Microsoft.SharePoint.SPException", "A file or folder with the name ${p.removePrefix("/")} already exists.")
            }
            folders += p
            log += "mkdir ${rel(p)} 200"
            return json(buildJsonObject { put("Name", p.substringAfterLast('/')); put("ServerRelativeUrl", p) }.toString())
        }
        CREATE.matchEntire(call)?.let { r ->
            val folder = literal(r.groupValues[1])
            val name = literal(r.groupValues[2])
            val p = "$folder/$name"
            if (method != "POST") return status(405, "POST only")
            if (!inside(folder) || '/' in name) return error(403, "-2147024891, System.UnauthorizedAccessException", "Access denied.")
            if (folder !in folders) return notFound("create ${rel(p)} ${body.size}")
            if (p in files) {
                log += "create ${rel(p)} ${body.size} 400"
                return error(400, "-2130575257, Microsoft.SharePoint.SPException", "A file with the name ${p.removePrefix("/")} already exists. It was last modified by Guest Contributor on 13 Nov 2026 10:00:00 -0800.")
            }
            files[p] = body
            log += "create ${rel(p)} ${body.size} 200"
            return json(fileJson(p, body.size.toLong()))
        }
        FILE.matchEntire(call)?.let { r ->
            val p = literal(r.groupValues[1])
            if (!inside(p)) return error(403, "-2147024891, System.UnauthorizedAccessException", "Access denied.")
            return file(method, rel(p), p, r.groupValues[2], body)
        }
        log += "$call 400 (unknown call)"
        return status(400, "unsupported call")
    }

    private fun file(method: String, rel: String, p: String, op: String, body: ByteArray): MockResponse {
        if (op.isEmpty() || op == "/\$value") {
            if (method != "GET") return status(405, "GET only")
            val bytes = files[p] ?: return notFound(if (op.isEmpty()) "info $rel" else "download $rel")
            return if (op.isEmpty()) {
                log += "info $rel 200"
                json(buildJsonObject { put("Length", bytes.size.toString()) }.toString())
            } else {
                log += "download $rel 200"
                MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(bytes))
            }
        }
        if (method != "POST") return status(405, "POST only")
        START.matchEntire(op)?.let { r ->
            val line = "start $rel ${body.size}"
            if (dropChunk()) return dropped(line)
            if (p !in files) return notFound(line)
            if (r.groupValues[1] in uploads) return chunkError(line, "The upload session already exists.")
            if (lockWhileUploading && uploads.values.any { it.path == p }) {
                log += "$line 423"
                return error(423, "-2147018894, Microsoft.SharePoint.SPFileLockException", "The file \"${p.removePrefix("/")}\" is locked for shared use by Guest Contributor.")
            }
            uploads[r.groupValues[1]] = Upload(p).apply { data.write(body) }
            log += "$line 200"
            return json(buildJsonObject { put("value", body.size.toString()) }.toString())
        }
        (CONTINUE.matchEntire(op) ?: FINISH.matchEntire(op))?.let { r ->
            val finish = op.startsWith("/Finish")
            val offset = r.groupValues[2].toLong()
            val line = "${if (finish) "finish" else "continue"} $rel @$offset ${body.size}"
            if (dropChunk()) return dropped(line)
            val upload = uploads[r.groupValues[1]]
            if (upload == null || upload.path != p) return chunkError(line, "The upload session was not found.")
            if (upload.data.size().toLong() != offset) return chunkError(line, "The fileOffset parameter does not match the upload session.")
            upload.data.write(body)
            log += "$line 200"
            if (!finish) return json(buildJsonObject { put("value", upload.data.size().toString()) }.toString())
            uploads.remove(r.groupValues[1])
            files[p] = upload.data.toByteArray()
            return json(fileJson(p, upload.data.size().toLong()))
        }
        log += "${op.removePrefix("/")} 400 (unknown call)"
        return status(400, "unsupported call")
    }

    private fun dropChunk(): Boolean {
        chunkCalls++
        if (loseAnswerAt == chunkCalls) {
            loseAnswerAt = null
            loseAnswer = true
        }
        if (dropAt != chunkCalls) return false
        dropAt = null
        return true
    }

    /** OData string literal in a URL: percent-decoded, then `''` → `'`. */
    private fun literal(raw: String): String = URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8).replace("''", "'")

    private fun fileJson(p: String, size: Long) = buildJsonObject {
        put("Length", size.toString())
        put("Name", p.substringAfterLast('/'))
        put("ServerRelativeUrl", p)
    }.toString()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json;odata=nometadata;streaming=true;charset=utf-8").setBody(body)

    private fun error(code: Int, spCode: String, message: String) = json(
        buildJsonObject {
            putJsonObject("odata.error") {
                put("code", spCode)
                putJsonObject("message") { put("lang", "en-US"); put("value", message) }
            }
        }.toString(),
    ).setResponseCode(code)

    private fun notFound(line: String): MockResponse {
        log += "$line 404"
        return error(404, "-2147024894, System.IO.FileNotFoundException", "File Not Found.")
    }

    private fun chunkError(line: String, message: String): MockResponse {
        log += "$line 400"
        return error(400, "-2147024809, System.ArgumentException", message)
    }

    private fun dropped(line: String): MockResponse {
        log += "$line dropped"
        return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
    }

    private fun status(code: Int, text: String) = MockResponse().setResponseCode(code).setBody(text)
}
