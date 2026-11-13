package com.fieldmaxx.server.sync

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.Change
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.ServerMessage
import com.fieldmaxx.server.ChangeRecorder
import com.fieldmaxx.server.Db
import java.sql.Connection
import java.sql.Statement
import java.time.Clock

/** Append-only, sequence-numbered feed of job/point/photo changes; every record is also pushed to live clients. */
class ChangeLog(private val db: Db, private val clock: Clock, private val hub: Hub) : ChangeRecorder {
    override fun record(
        jobId: String?, entity: String, entityId: String,
        job: Job?, point: Point?, photo: PhotoInfo?,
    ): Change {
        val change = db.tx { recordInTx(this, jobId, entity, entityId, job, point, photo) }
        publish(change)
        return change
    }

    override fun recordInTx(
        conn: Connection, jobId: String?, entity: String, entityId: String,
        job: Job?, point: Point?, photo: PhotoInfo?,
    ): Change {
        val at = clock.millis()
        val payload = FmxJson.encodeToString(Change.serializer(), Change(0, jobId, entity, entityId, job, point, photo, at))
        val seq = conn.prepareStatement(
            "INSERT INTO changes(job_id, entity, entity_id, data_json, at) VALUES (?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setString(1, jobId); it.setString(2, entity); it.setString(3, entityId)
            it.setString(4, payload); it.setLong(5, at)
            it.executeUpdate()
            it.generatedKeys.use { k -> k.next(); k.getLong(1) }
        }
        return Change(seq, jobId, entity, entityId, job, point, photo, at)
    }

    override fun publish(change: Change) = hub.broadcast(ServerMessage.ChangeMsg(change))

    /** Changes after [seq] (at most [limit]) and the seq to continue from: the last one returned, else the current head. */
    override fun since(seq: Long, limit: Int): Pair<List<Change>, Long> = db.read {
        val list = prepareStatement("SELECT seq, data_json FROM changes WHERE seq > ? ORDER BY seq LIMIT ?").use { ps ->
            ps.setLong(1, seq); ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(FmxJson.decodeFromString(Change.serializer(), rs.getString(2)).copy(seq = rs.getLong(1))) }
            }
        }
        list to (list.lastOrNull()?.seq ?: headSeq())
    }

    override fun headSeq(): Long = db.read { headSeq() }

    private fun java.sql.Connection.headSeq(): Long =
        createStatement().use { st -> st.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM changes").use { it.next(); it.getLong(1) } }
}
