package com.fieldmaxx.server

import com.fieldmaxx.core.model.Change
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.sharepoint.DestinationFactory
import com.fieldmaxx.server.sync.Hub
import java.time.Clock

interface ChangeRecorder {
    /** Call AFTER the writer's transaction commits; never inside a db.tx (it opens its own write transaction). */
    fun record(
        jobId: String?, entity: String, entityId: String,
        job: Job? = null, point: Point? = null, photo: PhotoInfo? = null,
    ): Change

    /** Changes after [seq] (at most [limit]) and the seq to continue from. */
    fun since(seq: Long, limit: Int = 1000): Pair<List<Change>, Long>

    /** The newest change seq (0 when none). */
    fun headSeq(): Long
}

interface DeliveryKick {
    fun kick()
}

/** Placeholder until the change log exists (Task 13). */
object NoopChangeRecorder : ChangeRecorder {
    override fun record(jobId: String?, entity: String, entityId: String, job: Job?, point: Point?, photo: PhotoInfo?) =
        Change(seq = 0, jobId = jobId, entity = entity, entityId = entityId, at = 0)

    override fun since(seq: Long, limit: Int) = emptyList<Change>() to 0L
    override fun headSeq() = 0L
}

/** Placeholder until the delivery worker exists (Task 17). */
object NoopDeliveryKick : DeliveryKick {
    override fun kick() {}
}

class Ctx(
    val config: Config,
    val db: Db,
    val clock: Clock,
    val destinations: DestinationFactory,
    var changes: ChangeRecorder,
    var delivery: DeliveryKick,
    val hub: Hub = Hub(),
)
