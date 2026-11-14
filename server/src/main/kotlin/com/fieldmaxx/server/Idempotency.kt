package com.fieldmaxx.server

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.User
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.sql.Connection
import kotlinx.serialization.KSerializer

/** Sent by the app with a job write, so that a retry after a lost reply happens once (R44). */
const val IDEMPOTENCY_KEY = "Idempotency-Key"
private const val MAX_KEY_LENGTH = 200

/** The request's idempotency key; null when it has none, which keeps the write as it always was. */
fun ApplicationCall.idempotencyKey(): String? =
    request.headers[IDEMPOTENCY_KEY]?.trim()?.ifEmpty { null }?.also {
        require(it.length <= MAX_KEY_LENGTH) { "Idempotency-Key is too long" }
    }

/**
 * Inside a write transaction, before the work: the answer stored for [opId] when [user] sent it before (a retry whose
 * reply was lost), else null. Another user's id is refused with 409 `op_id_conflict`, as for ops. The answers live in
 * `ops_done` beside the ops'; callers prefix their key, so an endpoint never reads another one's answer.
 */
fun <T> Connection.storedAnswer(opId: String?, user: User, serializer: KSerializer<T>): T? {
    if (opId == null) return null
    val (owner, json) = prepareStatement("SELECT user_id, result_json FROM ops_done WHERE op_id = ?").use { ps ->
        ps.setString(1, opId)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
    } ?: return null
    if (owner != user.id || json == null) throw HttpError(HttpStatusCode.Conflict, "op_id_conflict")
    return FmxJson.decodeFromString(serializer, json)
}

/** In the same transaction as the work: keeps [answer] for replays of [opId]. */
fun <T> Connection.storeAnswer(opId: String?, user: User, at: Long, serializer: KSerializer<T>, answer: T) {
    if (opId == null) return
    prepareStatement("INSERT INTO ops_done(op_id, user_id, at, result_json) VALUES (?,?,?,?)").use {
        it.setString(1, opId); it.setString(2, user.id); it.setLong(3, at)
        it.setString(4, FmxJson.encodeToString(serializer, answer)); it.executeUpdate()
    }
}
