package com.fieldmaxx.app.net

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.ActivityItem
import com.fieldmaxx.core.model.AdminSettings
import com.fieldmaxx.core.model.AppConfig
import com.fieldmaxx.core.model.AppRelease
import com.fieldmaxx.core.model.Change
import com.fieldmaxx.core.model.CreateJobInput
import com.fieldmaxx.core.model.CrewPosition
import com.fieldmaxx.core.model.FinishResult
import com.fieldmaxx.core.model.HealthReport
import com.fieldmaxx.core.model.ImportPreview
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.JobImportInput
import com.fieldmaxx.core.model.JobListItem
import com.fieldmaxx.core.model.JobSnapshot
import com.fieldmaxx.core.model.LinkCheck
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.OpResult
import com.fieldmaxx.core.model.PhotoRegistration
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.RegisteredPhoto
import com.fieldmaxx.core.model.ReuseCandidate
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

// Request and reply bodies, mirroring the server's route classes field for field.
@Serializable private class LoginBody(val username: String, val password: String)
@Serializable private class LoginReply(val token: String, val user: User)
@Serializable private class UserReply(val user: User)
@Serializable private class PasswordBody(val current: String, val next: String)
@Serializable private class ChangesReply(val changes: List<Change>, val seq: Long)
@Serializable private class OpsBody(val ops: List<Op>)
@Serializable private class OpsReply(val results: List<OpResult>)
@Serializable private class CreateJobReply(val job: Job)
@Serializable private class AddImportReply(val points: List<Point>)
@Serializable private class PatchJobBody(val name: String?, val fieldNotesUrl: String?, val notes: String?)
@Serializable private class LinkBody(val url: String)
@Serializable private class PositionsReply(val positions: List<CrewPosition>)
@Serializable private class BackupReply(val offset: Long, val complete: Boolean)
@Serializable private class UsersReply(val users: List<User>)
@Serializable private class CreateUserBody(val username: String, val displayName: String, val password: String, val role: Role)
@Serializable private class PatchUserBody(val displayName: String?, val role: Role?, val disabled: Boolean?, val password: String?)
@Serializable private class ErrorReply(val error: String)

/**
 * [Api] over OkHttp and [FmxJson], one call per server route under `/api`. [baseUrl] is read on every call, so a
 * server address changed on the login screen applies at once. The client should carry an [AuthInterceptor].
 */
class OkHttpApi(private val client: OkHttpClient, private val baseUrl: () -> String) : Api {

    override suspend fun login(username: String, password: String): Pair<String, User> =
        call<LoginReply>("POST", endpoint("api", "auth", "login"), json(LoginBody(username, password))).let { it.token to it.user }

    override suspend fun me(): User = call<UserReply>("GET", endpoint("api", "auth", "me")).user

    override suspend fun logout() = callUnit("POST", endpoint("api", "auth", "logout"), EMPTY)

    override suspend fun changePassword(current: String, next: String) =
        callUnit("POST", endpoint("api", "auth", "password"), json(PasswordBody(current, next)))

    override suspend fun config(): AppConfig = call("GET", endpoint("api", "config"))

    override suspend fun jobs(include: String?): List<JobListItem> =
        call("GET", endpoint("api", "jobs", query = mapOf("include" to include)))

    override suspend fun snapshot(jobId: String): JobSnapshot = call("GET", endpoint("api", "jobs", jobId, "snapshot"))

    override suspend fun changes(since: Long): Pair<List<Change>, Long> =
        call<ChangesReply>("GET", endpoint("api", "changes", query = mapOf("since" to since.toString()))).let { it.changes to it.seq }

    override suspend fun ops(ops: List<Op>): List<OpResult> =
        call<OpsReply>("POST", endpoint("api", "ops"), json(OpsBody(ops))).results

    override suspend fun importFile(name: String, bytes: ByteArray): ImportPreview {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, bytes.toRequestBody(OCTET_STREAM))
            .build()
        return call("POST", endpoint("api", "imports"), form)
    }

    override suspend fun createJob(i: CreateJobInput, requestId: String): Job =
        call<CreateJobReply>("POST", endpoint("api", "jobs"), json(i), idempotencyKey = requestId).job

    override suspend fun addImport(jobId: String, i: JobImportInput, requestId: String): List<Point> =
        call<AddImportReply>("POST", endpoint("api", "jobs", jobId, "imports"), json(i), idempotencyKey = requestId).points

    override suspend fun patchJob(jobId: String, name: String?, fieldNotesUrl: String?, notes: String?): Job =
        call("PATCH", endpoint("api", "jobs", jobId), json(PatchJobBody(name, fieldNotesUrl, notes)))

    override suspend fun checkLink(url: String): LinkCheck = call("POST", endpoint("api", "links", "check"), json(LinkBody(url)))

    override suspend fun deleteJob(jobId: String) = callUnit("DELETE", endpoint("api", "jobs", jobId))

    override suspend fun restoreJob(jobId: String) = callUnit("POST", endpoint("api", "jobs", jobId, "restore"), EMPTY)

    override suspend fun finishJob(jobId: String): FinishResult = call("POST", endpoint("api", "jobs", jobId, "finish"), EMPTY)

    override suspend fun positions(): List<CrewPosition> = call<PositionsReply>("GET", endpoint("api", "positions")).positions

    override suspend fun registerPhoto(r: PhotoRegistration): RegisteredPhoto =
        call("POST", endpoint("api", "photos", "register"), json(r))

    override suspend fun renamePhoto(id: String, from: String): RegisteredPhoto =
        call("POST", endpoint("api", "photos", id, "rename", query = mapOf("from" to from)), EMPTY)

    override suspend fun backupChunk(id: String, offset: Long, bytes: ByteArray): Pair<Long, Boolean> {
        val url = endpoint("api", "photos", id, "backup", query = mapOf("offset" to offset.toString()))
        // A 409 (wrong offset) carries the server's position as well, so the caller continues from there.
        val reply = exchange(request("PUT", url, bytes.toRequestBody(OCTET_STREAM))) { response ->
            (if (response.code == 409) response else response.requireOk()).decode(BackupReply.serializer())
        }
        return reply.offset to reply.complete
    }

    override suspend fun nearby(lat: Double, lon: Double, excludePointId: String?): List<ReuseCandidate> = call(
        "GET",
        endpoint("api", "photos", "nearby", query = mapOf("lat" to lat.toString(), "lon" to lon.toString(), "excludePointId" to excludePointId)),
    )

    override suspend fun jobPhotos(jobId: String): List<ReuseCandidate> = call("GET", endpoint("api", "jobs", jobId, "photos"))

    override suspend fun adminUsers(): List<User> = call<UsersReply>("GET", endpoint("api", "admin", "users")).users

    override suspend fun createUser(username: String, displayName: String, password: String, role: Role): User =
        call("POST", endpoint("api", "admin", "users"), json(CreateUserBody(username, displayName, password, role)))

    override suspend fun patchUser(id: String, displayName: String?, role: Role?, disabled: Boolean?, password: String?): User =
        call("PATCH", endpoint("api", "admin", "users", id), json(PatchUserBody(displayName, role, disabled, password)))

    override suspend fun health(): HealthReport = call("GET", endpoint("api", "admin", "health"))

    override suspend fun retryPhoto(id: String) = callUnit("POST", endpoint("api", "admin", "photos", id, "retry"), EMPTY)

    override suspend fun activity(jobId: String?): List<ActivityItem> =
        call("GET", endpoint("api", "admin", "activity", query = mapOf("jobId" to jobId)))

    override suspend fun putSettings(s: AdminSettings) = callUnit("PUT", endpoint("api", "admin", "settings"), json(s))

    override suspend fun latestRelease(): AppRelease? = call("GET", endpoint("api", "app", "latest"))

    override suspend fun download(path: String, to: File) {
        val absolute = path.startsWith("http://") || path.startsWith("https://")
        val url = (if (absolute) path else baseUrl().trimEnd('/') + "/" + path.trimStart('/')).toHttpUrlOrNull()
            ?: throw IOException("Not a download address: $path")
        val target = to.absoluteFile
        val part = File(target.parentFile, target.name + ".part")
        exchange(request("GET", url, null)) { response ->
            val body = response.requireOk().body ?: throw IOException("Empty download")
            part.parentFile?.mkdirs()
            try {
                part.outputStream().use { body.byteStream().copyTo(it) }
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                part.delete() // only still there when the download broke off
            }
        }
    }

    private fun endpoint(vararg segments: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val base = baseUrl().toHttpUrlOrNull() ?: throw IOException("Not a server address: ${baseUrl()}")
        return base.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (name, value) -> if (value != null) addQueryParameter(name, value) }
        }.build()
    }

    /** [idempotencyKey]: the server applies a write sent again with the same key only once (R44). */
    private fun request(method: String, url: HttpUrl, body: RequestBody?, idempotencyKey: String? = null): Request =
        Request.Builder().url(url).method(method, body).apply { if (idempotencyKey != null) header(IDEMPOTENCY_KEY, idempotencyKey) }.build()

    private inline fun <reified T> json(value: T): RequestBody = FmxJson.encodeToString(serializer<T>(), value).toRequestBody(JSON)

    /** A 2xx answer decoded as [T]; anything else becomes an [ApiException]. */
    private suspend inline fun <reified T> call(method: String, url: HttpUrl, body: RequestBody? = null, idempotencyKey: String? = null): T =
        exchange(request(method, url, body, idempotencyKey)) { it.requireOk().decode(serializer<T>()) }

    /** For routes whose answer carries nothing the app needs (204, `{"ok":true}`, the saved settings). */
    private suspend fun callUnit(method: String, url: HttpUrl, body: RequestBody? = null) {
        exchange(request(method, url, body)) { it.requireOk() }
    }

    /** Blocking OkHttp on the IO pool; cancelling the coroutine interrupts the exchange. */
    private suspend fun <T> exchange(request: Request, read: (Response) -> T): T =
        runInterruptible(Dispatchers.IO) { client.newCall(request).execute().use(read) }

    private fun Response.requireOk(): Response {
        if (isSuccessful) return this
        val message = try {
            FmxJson.decodeFromString(ErrorReply.serializer(), body?.string().orEmpty()).error
        } catch (e: IllegalArgumentException) { // not the server's {"error"} body (a proxy's page, say)
            null
        } catch (e: IOException) {
            null
        }
        throw ApiException(code, message?.takeIf { it.isNotBlank() } ?: "Server error (HTTP $code)")
    }

    private fun <T> Response.decode(reply: DeserializationStrategy<T>): T = try {
        FmxJson.decodeFromString(reply, body?.string().orEmpty())
    } catch (e: IllegalArgumentException) { // SerializationException is one: not what this route answers
        throw ApiException(code, "Unexpected reply from the server")
    }

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        val JSON = "application/json; charset=utf-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val EMPTY = ByteArray(0).toRequestBody()
    }
}
