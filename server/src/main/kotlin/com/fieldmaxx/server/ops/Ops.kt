package com.fieldmaxx.server.ops

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.CantShootReason
import com.fieldmaxx.core.model.Change
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.OpResult
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.PoleData
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import com.fieldmaxx.core.naming.pointLabel
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.jobs.toPoint
import com.fieldmaxx.server.photos.applyPhotoOp
import java.sql.Connection
import java.sql.Types
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A refusal that leaves the database untouched; its code is the op's `error`. */
internal class OpFail(val code: String) : RuntimeException(code, null, false, false)

/**
 * What an op did: the point or photo it changed (both null when nothing changed) and the event kind to log. [afterCommit]
 * (file clean-up) runs only once the transaction committed; [kickDelivery] wakes the delivery worker then.
 */
internal class Applied(
    val point: Point?, val kind: String, val photo: PhotoInfo? = null,
    val afterCommit: (() -> Unit)? = null, val kickDelivery: Boolean = false,
)

/**
 * Applies [ops] in order. Each op is its own transaction: it is checked against `ops_done` first (a replay returns the
 * stored result and changes nothing), applied field by field, and recorded in `ops_done` in that same transaction. A
 * failing op is recorded too and never undoes earlier ones.
 */
fun applyOps(ctx: Ctx, user: User, ops: List<Op>): List<OpResult> = ops.map { applyOp(ctx, user, it) }

private fun applyOp(ctx: Ctx, user: User, op: Op): OpResult {
    var applied: Applied? = null
    val (result, change) = ctx.db.tx {
        storedOp(op.opId)?.let { (owner, stored) ->
            // An opId belongs to whoever first used it; another user's replay must not read or reuse the result.
            return@tx (if (owner == user.id) stored else OpResult(op.opId, false, "op_id_conflict")) to null
        }
        val now = ctx.clock.millis()
        val r = try {
            applied = apply(ctx, user, op, now)
            OpResult(op.opId, true)
        } catch (e: OpFail) {
            OpResult(op.opId, false, e.code)
        }
        prepareStatement("INSERT INTO ops_done(op_id, user_id, at, result_json) VALUES (?,?,?,?)").use {
            it.setString(1, op.opId); it.setString(2, user.id); it.setLong(3, now)
            it.setString(4, FmxJson.encodeToString(OpResult.serializer(), r)); it.executeUpdate()
        }
        val a = applied
        val p = a?.point
        val photo = a?.photo
        val jobId = p?.jobId ?: photo?.jobId
        if (a != null && jobId != null) {
            prepareStatement("INSERT INTO events(job_id, user_id, kind, data_json, at) VALUES (?,?,?,?,?)").use {
                it.setString(1, jobId); it.setString(2, user.id); it.setString(3, a.kind)
                it.setString(4, buildJsonObject { put("opId", op.opId); if (p != null) put("pointId", p.id); if (photo != null) put("photoId", photo.id) }.toString())
                it.setLong(5, now); it.executeUpdate()
            }
        }
        // Same transaction as the write: change order equals commit order and the snapshot is never stale.
        var change: Change? = null
        if (p != null) change = ctx.changes.recordInTx(this, p.jobId, "point", p.id, point = p)
        else if (photo != null) change = ctx.changes.recordInTx(this, photo.jobId, "photo", photo.id, photo = photo)
        r to change
    }
    change?.let(ctx.changes::publish)
    applied?.afterCommit?.invoke()
    if (applied?.kickDelivery == true && result.ok) ctx.delivery.kick()
    return result
}

/** The user that first used [opId] and the result stored for it. */
private fun Connection.storedOp(opId: String): Pair<String, OpResult>? =
    prepareStatement("SELECT user_id, result_json FROM ops_done WHERE op_id = ?").use { ps ->
        ps.setString(1, opId)
        ps.executeQuery().use { rs ->
            if (rs.next()) rs.getString(1) to (rs.getString(2)?.let { FmxJson.decodeFromString(OpResult.serializer(), it) } ?: OpResult(opId, true)) else null
        }
    }

private fun Connection.findPoint(id: String): Point? =
    prepareStatement("SELECT * FROM points WHERE id = ?").use { ps ->
        ps.setString(1, id); ps.executeQuery().use { if (it.next()) it.toPoint() else null }
    }

/** null: no such job. */
private fun Connection.jobDeleted(jobId: String): Boolean? =
    prepareStatement("SELECT deleted_at FROM jobs WHERE id = ?").use { ps ->
        ps.setString(1, jobId); ps.executeQuery().use { if (it.next()) it.getObject(1) != null else null }
    }

private fun Connection.exec(sql: String, vararg args: Any?) {
    prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a ->
            when (a) {
                null -> ps.setNull(i + 1, Types.NULL)
                is String -> ps.setString(i + 1, a)
                is Long -> ps.setLong(i + 1, a)
                is Double -> ps.setDouble(i + 1, a)
                else -> error("unsupported argument $a")
            }
        }
        ps.executeUpdate()
    }
}

/** An existing point of a live job; [allowDeleted] lets restore (and a repeated delete) see a deleted point. */
internal fun Connection.livePoint(pointId: String, allowDeleted: Boolean = false): Point {
    val p = findPoint(pointId) ?: throw OpFail("not_found")
    if (jobDeleted(p.jobId) == true) throw OpFail("job_deleted")
    if (p.deletedAt != null && !allowDeleted) throw OpFail("point_deleted")
    return p
}

/** Writes only [sets] (column = value) plus updated_at/updated_by, so concurrent edits of other fields survive. */
private fun Connection.touch(pointId: String, user: User, now: Long, kind: String, vararg sets: Pair<String, Any?>): Applied {
    val cols = sets.joinToString("") { "${it.first} = ?, " }
    exec("UPDATE points SET $cols" + "updated_at = ?, updated_by = ? WHERE id = ?", *sets.map { it.second }.toTypedArray(), now, user.id, pointId)
    return Applied(findPoint(pointId), kind)
}

private fun Connection.apply(ctx: Ctx, user: User, op: Op, now: Long): Applied {
    return when (op) {
        is Op.PointUpdate -> {
            livePoint(op.pointId)
            val sets = buildList<Pair<String, Any?>> {
                op.pole?.let { add("pole_json" to FmxJson.encodeToString(PoleData.serializer(), it)) }
                op.comment?.let { add("comment" to it) }
            }
            touch(op.pointId, user, now, "point.update", *sets.toTypedArray())
        }
        is Op.PointMove -> {
            livePoint(op.pointId)
            // The first move remembers where the point came from; later moves keep that original.
            exec("UPDATE points SET orig_lat = COALESCE(orig_lat, lat), orig_lon = COALESCE(orig_lon, lon) WHERE id = ?", op.pointId)
            touch(op.pointId, user, now, "point.move", "lat" to op.lat, "lon" to op.lon)
        }
        is Op.PointClose -> {
            livePoint(op.pointId)
            if (op.photoIds.isEmpty()) throw OpFail("needs_photo")
            touch(op.pointId, user, now, "point.close", "status" to "done", "cant_reason" to null, "cant_text" to null)
        }
        is Op.PointCantShoot -> {
            livePoint(op.pointId)
            val text = op.text?.trim()?.ifEmpty { null }
            if (op.reason == CantShootReason.other && text == null) throw OpFail("needs_text")
            touch(op.pointId, user, now, "point.cantShoot", "status" to "cant_shoot", "cant_reason" to op.reason.name, "cant_text" to text)
        }
        is Op.PointReopen -> {
            livePoint(op.pointId)
            touch(op.pointId, user, now, "point.reopen", "status" to "open", "cant_reason" to null, "cant_text" to null)
        }
        is Op.PointAdd -> {
            if (jobDeleted(op.jobId) == null) throw OpFail("job_not_found")
            if (jobDeleted(op.jobId) == true) throw OpFail("job_deleted")
            if (findPoint(op.pointId) != null) throw OpFail("exists")
            exec(
                "INSERT INTO points(id, job_id, layer, type, num, label, lat, lon, description, status, ref_image_ids_json, flags_json, " +
                    "added_by, created_at, updated_at, updated_by) VALUES (?,?,?,?,?,?,?,?,'','open','[]','[]',?,?,?,?)",
                op.pointId, op.jobId, op.layer, op.type, op.num, pointLabel(op.layer, op.type, op.num), op.lat, op.lon,
                user.id, now, now, user.id,
            )
            Applied(findPoint(op.pointId), "point.add")
        }
        is Op.PointDelete -> {
            val p = livePoint(op.pointId, allowDeleted = true)
            if (p.deletedAt != null) return Applied(null, "point.delete")
            if (user.role != Role.admin && hasConfirmedPhoto(op.pointId)) throw OpFail("photos_delivered")
            exec("UPDATE points SET deleted_at = ?, deleted_by = ? WHERE id = ?", now, user.id, op.pointId)
            touch(op.pointId, user, now, "point.delete")
        }
        is Op.PointRestore -> {
            if (user.role != Role.admin) throw OpFail("admin_only")
            val p = livePoint(op.pointId, allowDeleted = true)
            if (p.deletedAt == null) return Applied(null, "point.restore")
            exec("UPDATE points SET deleted_at = NULL, deleted_by = NULL WHERE id = ?", op.pointId)
            touch(op.pointId, user, now, "point.restore")
        }
        is Op.PointSetFolder -> {
            livePoint(op.pointId)
            touch(op.pointId, user, now, "point.setFolder", "folder_path" to op.folderPath, "folder_candidates_json" to null)
        }
        is Op.PhotoReuse, is Op.PhotoRemove, is Op.PhotoConfirmed, is Op.PhotoFailed -> applyPhotoOp(ctx, user, op, now)
    }
}

private fun Connection.hasConfirmedPhoto(pointId: String): Boolean =
    prepareStatement("SELECT 1 FROM photos WHERE point_id = ? AND (status = 'confirmed' OR confirmed_at IS NOT NULL) LIMIT 1").use { ps ->
        ps.setString(1, pointId); ps.executeQuery().use { it.next() }
    }
