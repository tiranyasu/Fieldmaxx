package com.fieldmaxx.server

import com.fieldmaxx.core.model.Change
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.sharepoint.DestinationFactory
import com.fieldmaxx.server.sync.Hub
import java.time.Clock

interface ChangeRecorder {
    fun record(
        jobId: String?, entity: String, entityId: String,
        job: Job? = null, point: Point? = null, photo: PhotoInfo? = null,
    ): Change
}

interface DeliveryKick {
    fun kick()
}

/** Placeholder until the change log exists (Task 13). */
object NoopChangeRecorder : ChangeRecorder {
    override fun record(jobId: String?, entity: String, entityId: String, job: Job?, point: Point?, photo: PhotoInfo?) =
        Change(seq = 0, jobId = jobId, entity = entity, entityId = entityId, at = 0)
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
