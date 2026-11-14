package com.fieldmaxx.app.net

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

/** The server answered with an error status; [message] is its `{"error"}` text, ready to show. */
class ApiException(val status: Int, override val message: String) : Exception(message)

/**
 * The Fieldmaxx server, signed in with the session token from the [TokenVault]. Every call throws [ApiException]
 * when the server answers with an error status (401 "Sign in required" once the session has ended) and an
 * [java.io.IOException] when it can't be reached.
 */
interface Api {
    /** Returns the new session token and the user; the caller keeps the token in the [TokenVault]. */
    suspend fun login(username: String, password: String): Pair<String, User>
    suspend fun me(): User
    suspend fun logout()
    suspend fun changePassword(current: String, next: String)

    suspend fun config(): AppConfig

    /** Active jobs; [include] adds `finished` and/or `deleted` (admins only), comma-separated. */
    suspend fun jobs(include: String? = null): List<JobListItem>
    suspend fun snapshot(jobId: String): JobSnapshot

    /** Changes after [since], and the server's latest change sequence number. */
    suspend fun changes(since: Long): Pair<List<Change>, Long>

    /** Applies idempotent operations; one result per op, in order. */
    suspend fun ops(ops: List<Op>): List<OpResult>

    /** Uploads a PDF or Word file for reading; the preview feeds the check screen. */
    suspend fun importFile(name: String, bytes: ByteArray): ImportPreview

    /**
     * [requestId] goes as the `Idempotency-Key` header: sent again (a retry after a lost reply), the server answers with
     * the job it created the first time and creates no other (R44).
     */
    suspend fun createJob(i: CreateJobInput, requestId: String): Job

    /**
     * Adds a checked import to an existing job; returns the points created (identical ones are skipped). A [requestId]
     * sent again adds nothing more (R44).
     */
    suspend fun addImport(jobId: String, i: JobImportInput, requestId: String): List<Point>

    /** Changes only the fields given; an empty [fieldNotesUrl] removes the link. */
    suspend fun patchJob(jobId: String, name: String? = null, fieldNotesUrl: String? = null, notes: String? = null): Job
    suspend fun checkLink(url: String): LinkCheck
    suspend fun deleteJob(jobId: String)
    suspend fun restoreJob(jobId: String)
    suspend fun finishJob(jobId: String): FinishResult

    suspend fun positions(): List<CrewPosition>

    suspend fun registerPhoto(r: PhotoRegistration): RegisteredPhoto
    suspend fun renamePhoto(id: String): RegisteredPhoto

    /**
     * Appends [bytes] at [offset] to the server's backup copy. Returns the server's confirmed offset and whether the
     * copy is complete; on a wrong offset (HTTP 409) that is the server's current position, to continue from.
     */
    suspend fun backupChunk(id: String, offset: Long, bytes: ByteArray): Pair<Long, Boolean>

    /** Photos within the server's reuse radius (150 m) of the spot, from any job. */
    suspend fun nearby(lat: Double, lon: Double, excludePointId: String?): List<ReuseCandidate>
    suspend fun jobPhotos(jobId: String): List<ReuseCandidate>

    suspend fun adminUsers(): List<User>
    suspend fun createUser(username: String, displayName: String, password: String, role: Role): User
    suspend fun patchUser(id: String, displayName: String? = null, role: Role? = null, disabled: Boolean? = null, password: String? = null): User
    suspend fun health(): HealthReport
    suspend fun retryPhoto(id: String)

    /** Newest first; all jobs when [jobId] is null. */
    suspend fun activity(jobId: String?): List<ActivityItem>
    suspend fun putSettings(s: AdminSettings)

    /** The newest published APK, or null when none is published. */
    suspend fun latestRelease(): AppRelease?

    /**
     * Saves the response body of a server path such as `/api/jobs/{id}/files/{fileId}`, or of an absolute URL such as
     * [AppRelease.url], to [to]. [to] only ever holds a complete download.
     */
    suspend fun download(path: String, to: java.io.File)
}
