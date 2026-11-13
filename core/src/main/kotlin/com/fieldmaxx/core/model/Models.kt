@file:Suppress("EnumEntryName")

package com.fieldmaxx.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable enum class Role { admin, tech }

@Serializable
data class User(val id: String, val username: String, val displayName: String, val role: Role, val disabled: Boolean)

@Serializable enum class PointStatus { open, done, cant_shoot }

@Serializable enum class CantShootReason { not_found, no_access, not_applicable, other }

val POLE_TAGS: List<String> = listOf("N/A", "Carrier label", "Grounding", "No ground", "No fiber", "Damaged cable")

@Serializable enum class Attachment { self_supporting, lashed }

@Serializable
data class PoleData(
    val lineHeightFt: Double? = null,
    val anchor: Boolean = false,
    val attachment: Attachment? = null,
    val tags: List<String> = emptyList(),
)

@Serializable enum class ImportFlag { swapped, sign_mismatch, far_from_job, duplicate_label, unlabelled, no_coordinates }

@Serializable
data class DraftPoint(
    val layer: String,
    val type: String,
    val num: String,
    val label: String,
    val lat: Double?,
    val lon: Double?,
    val description: String,
    val flags: List<ImportFlag>,
    val sourcePage: Int?,
    val refImageIds: List<String>,
    val rawText: String,
)

@Serializable
data class UnreadLine(val text: String, val sourcePage: Int?, val reason: String)

/** [kind] is "page" or "item". */
@Serializable
data class RefImageInfo(val id: String, val kind: String, val page: Int?)

/** [kind] is "pdf" or "docx". */
@Serializable
data class ImportPreview(
    val importId: String,
    val fileName: String,
    val kind: String,
    val layerSuggestion: String,
    val notes: String,
    val points: List<DraftPoint>,
    val unread: List<UnreadLine>,
    val refImages: List<RefImageInfo>,
)

/** [status] is "active" or "finished". */
@Serializable
data class Job(
    val id: String,
    val name: String,
    val notes: String,
    val fieldNotesUrl: String?,
    val status: String,
    val createdBy: String,
    val createdAt: Long,
    val finishedAt: Long?,
    val deletedAt: Long?,
    val deliveryProblem: String?,
)

@Serializable
data class JobListItem(val job: Job, val pointsTotal: Int, val pointsDone: Int)

@Serializable
data class Point(
    val id: String,
    val jobId: String,
    val layer: String,
    val type: String,
    val num: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val origLat: Double?,
    val origLon: Double?,
    val description: String,
    val status: PointStatus,
    val cantReason: CantShootReason?,
    val cantText: String?,
    val pole: PoleData?,
    val comment: String,
    val folderPath: String?,
    val folderCandidates: List<String>?,
    val refImageIds: List<String>,
    val flags: List<ImportFlag>,
    val addedBy: String?,
    val updatedAt: Long,
    val updatedBy: String?,
    val deletedAt: Long?,
)

@Serializable enum class PhotoStatus { registered, confirmed, stuck, removed }

@Serializable
data class PhotoInfo(
    val id: String,
    val jobId: String,
    val pointId: String,
    val userId: String,
    val fileName: String?,
    val size: Long,
    val status: PhotoStatus,
    val destPath: String?,
    val lastError: String?,
    val takenAt: Long,
    val lat: Double?,
    val lon: Double?,
    val backedUp: Boolean,
    val confirmedAt: Long?,
    val uploadMs: Long?,
    val reusedFrom: String?,
)

@Serializable
data class PhotoRegistration(
    val id: String,
    val jobId: String,
    val pointId: String,
    val size: Long,
    val sha256: String,
    val takenAt: Long,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Double?,
    val headingDeg: Double?,
)

@Serializable
data class RegisteredPhoto(val fileName: String, val status: PhotoStatus, val backupOffset: Long)

@Serializable
data class CrewPosition(
    val userId: String,
    val displayName: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val at: Long,
    val paused: Boolean,
)

/** An idempotent client operation; the JSON `kind` is the [SerialName] of each subtype. */
@Serializable
sealed interface Op {
    val opId: String

    @Serializable @SerialName("point.update")
    data class PointUpdate(override val opId: String, val pointId: String, val pole: PoleData? = null, val comment: String? = null) : Op

    @Serializable @SerialName("point.move")
    data class PointMove(override val opId: String, val pointId: String, val lat: Double, val lon: Double) : Op

    @Serializable @SerialName("point.close")
    data class PointClose(override val opId: String, val pointId: String, val photoIds: List<String>) : Op

    @Serializable @SerialName("point.cantShoot")
    data class PointCantShoot(override val opId: String, val pointId: String, val reason: CantShootReason, val text: String? = null) : Op

    @Serializable @SerialName("point.reopen")
    data class PointReopen(override val opId: String, val pointId: String) : Op

    @Serializable @SerialName("point.add")
    data class PointAdd(
        override val opId: String,
        val jobId: String,
        val pointId: String,
        val layer: String,
        val type: String,
        val num: String,
        val lat: Double,
        val lon: Double,
    ) : Op

    @Serializable @SerialName("point.delete")
    data class PointDelete(override val opId: String, val pointId: String) : Op

    @Serializable @SerialName("point.restore")
    data class PointRestore(override val opId: String, val pointId: String) : Op

    @Serializable @SerialName("point.setFolder")
    data class PointSetFolder(override val opId: String, val pointId: String, val folderPath: String) : Op

    @Serializable @SerialName("photo.reuse")
    data class PhotoReuse(override val opId: String, val photoId: String, val fromPhotoId: String, val pointId: String) : Op

    @Serializable @SerialName("photo.remove")
    data class PhotoRemove(override val opId: String, val photoId: String) : Op

    @Serializable @SerialName("photo.confirmed")
    data class PhotoConfirmed(override val opId: String, val photoId: String, val destPath: String, val uploadMs: Long) : Op

    @Serializable @SerialName("photo.failed")
    data class PhotoFailed(override val opId: String, val photoId: String, val error: String, val attempts: Int) : Op
}

@Serializable
data class OpResult(val opId: String, val ok: Boolean, val error: String? = null)

/** [entity] is "job", "point" or "photo"; the matching payload field is set. */
@Serializable
data class Change(
    val seq: Long,
    val jobId: String?,
    val entity: String,
    val entityId: String,
    val job: Job? = null,
    val point: Point? = null,
    val photo: PhotoInfo? = null,
    val at: Long,
)

@Serializable
sealed interface ServerMessage {
    @Serializable @SerialName("change")
    data class ChangeMsg(val change: Change) : ServerMessage

    @Serializable @SerialName("position")
    data class PositionMsg(val pos: CrewPosition) : ServerMessage

    @Serializable @SerialName("hello")
    data class Hello(val seq: Long) : ServerMessage
}

@Serializable
sealed interface ClientMessage {
    @Serializable @SerialName("position")
    data class PositionUpdate(val lat: Double, val lon: Double, val accuracyM: Double, val altitudeM: Double? = null) : ClientMessage

    @Serializable @SerialName("paused")
    data object Paused : ClientMessage
}

@Serializable
data class JobFileInfo(val id: String, val name: String, val layer: String)

@Serializable
data class CrewMember(val userId: String, val displayName: String)

@Serializable
data class JobSnapshot(
    val job: Job,
    val points: List<Point>,
    val photos: List<PhotoInfo>,
    val refImages: List<RefImageInfo>,
    val files: List<JobFileInfo>,
    val crew: List<CrewMember>,
    val seq: Long,
)

@Serializable
data class JobImportInput(val importId: String, val layer: String, val notes: String, val points: List<DraftPoint>)

@Serializable
data class CreateJobInput(val name: String, val fieldNotesUrl: String?, val imports: List<JobImportInput>)

@Serializable
data class LinkCheck(val ok: Boolean, val rootName: String? = null, val error: String? = null)

@Serializable
data class FinishResult(val finished: Boolean, val pointsLeft: Int = 0, val photosPending: Int = 0)

/** [photoQuality] is 85 or 95. */
@Serializable
data class AppConfig(val imageryUrl: String, val labelsUrl: String, val imageryKey: String?, val photoQuality: Int, val backupDays: Int)

@Serializable
data class AdminSettings(
    val photoQuality: Int? = null,
    val backupDays: Int? = null,
    val imageryUrl: String? = null,
    val labelsUrl: String? = null,
    val imageryKey: String? = null,
)

@Serializable
data class HealthReport(val diskUsedPct: Int, val photos: List<PhotoInfo>, val phoneToConfirmedMedianS: Long?, val uploadMedianS: Long?)

@Serializable
data class ActivityItem(val at: Long, val user: String, val kind: String, val label: String?, val detail: String)

@Serializable
data class AppRelease(val versionCode: Int, val versionName: String, val url: String, val sha256: String)

@Serializable
data class ReuseCandidate(val photo: PhotoInfo, val pointLabel: String, val jobName: String, val distanceM: Double?)
