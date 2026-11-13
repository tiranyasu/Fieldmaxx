package com.fieldmaxx.server.jobs

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.CantShootReason
import com.fieldmaxx.core.model.ImportFlag
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.PointStatus
import com.fieldmaxx.core.model.PoleData
import java.sql.ResultSet
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

private val STRINGS = ListSerializer(String.serializer())
private val FLAGS = ListSerializer(ImportFlag.serializer())

private fun ResultSet.longOrNull(col: String): Long? = getLong(col).takeUnless { wasNull() }
private fun ResultSet.doubleOrNull(col: String): Double? = getDouble(col).takeUnless { wasNull() }

fun ResultSet.toJob() = Job(
    id = getString("id"), name = getString("name"), notes = getString("notes") ?: "",
    fieldNotesUrl = getString("field_notes_url"), status = getString("status"), createdBy = getString("created_by"),
    createdAt = getLong("created_at"), finishedAt = longOrNull("finished_at"), deletedAt = longOrNull("deleted_at"),
    deliveryProblem = getString("delivery_problem"),
)

fun ResultSet.toPoint() = Point(
    id = getString("id"), jobId = getString("job_id"), layer = getString("layer"), type = getString("type"),
    num = getString("num"), label = getString("label"), lat = getDouble("lat"), lon = getDouble("lon"),
    origLat = doubleOrNull("orig_lat"), origLon = doubleOrNull("orig_lon"),
    description = getString("description") ?: "",
    status = PointStatus.valueOf(getString("status")),
    cantReason = getString("cant_reason")?.let(CantShootReason::valueOf),
    cantText = getString("cant_text"),
    pole = getString("pole_json")?.let { FmxJson.decodeFromString(PoleData.serializer(), it) },
    comment = getString("comment") ?: "",
    folderPath = getString("folder_path"),
    folderCandidates = getString("folder_candidates_json")?.let { FmxJson.decodeFromString(STRINGS, it) },
    refImageIds = getString("ref_image_ids_json")?.let { FmxJson.decodeFromString(STRINGS, it) } ?: emptyList(),
    flags = getString("flags_json")?.let { FmxJson.decodeFromString(FLAGS, it) } ?: emptyList(),
    addedBy = getString("added_by"), updatedAt = getLong("updated_at"), updatedBy = getString("updated_by"),
    deletedAt = longOrNull("deleted_at"),
)

fun ResultSet.toPhoto() = PhotoInfo(
    id = getString("id"), jobId = getString("job_id"), pointId = getString("point_id"), userId = getString("user_id"),
    fileName = getString("file_name"), size = getLong("size"), status = PhotoStatus.valueOf(getString("status")),
    destPath = getString("dest_path"), lastError = getString("last_error"), takenAt = getLong("taken_at"),
    lat = doubleOrNull("lat"), lon = doubleOrNull("lon"), backedUp = longOrNull("backup_received_at") != null,
    confirmedAt = longOrNull("confirmed_at"), uploadMs = longOrNull("upload_ms"), reusedFrom = getString("reused_from"),
)

internal fun encodeStrings(v: List<String>): String = FmxJson.encodeToString(STRINGS, v)
internal fun encodeFlags(v: List<ImportFlag>): String = FmxJson.encodeToString(FLAGS, v)
