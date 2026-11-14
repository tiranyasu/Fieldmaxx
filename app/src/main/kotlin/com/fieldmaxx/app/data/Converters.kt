package com.fieldmaxx.app.data

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.Point

// Room keeps the core models as JSON text columns, so no Room type converter is involved: these are the conversions
// between the models and their rows, in both directions.

fun Job.toEntity(seq: Long, storedAt: Long) = JobEntity(id, FmxJson.encodeToString(Job.serializer(), this), storedAt, seq)

fun JobEntity.toJob(): Job = FmxJson.decodeFromString(Job.serializer(), json)

fun Point.toEntity(seq: Long) = PointEntity(id, jobId, FmxJson.encodeToString(Point.serializer(), this), seq)

fun PointEntity.toPoint(): Point = FmxJson.decodeFromString(Point.serializer(), json)

/** The same row holding [p] instead; its server [PointEntity.seq] stays. */
fun PointEntity.with(p: Point) = copy(json = FmxJson.encodeToString(Point.serializer(), p))

fun PhotoInfo.toEntity(seq: Long) = PhotoInfoEntity(id, jobId, pointId, FmxJson.encodeToString(PhotoInfo.serializer(), this), seq)

fun PhotoInfoEntity.toPhoto(): PhotoInfo = FmxJson.decodeFromString(PhotoInfo.serializer(), json)

/** The same row holding [p] instead; its server [PhotoInfoEntity.seq] stays. */
fun PhotoInfoEntity.with(p: PhotoInfo) = copy(json = FmxJson.encodeToString(PhotoInfo.serializer(), p))

fun Op.toOutbox() = OutboxEntity(opJson = FmxJson.encodeToString(Op.serializer(), this))

fun OutboxEntity.toOp(): Op = FmxJson.decodeFromString(Op.serializer(), opJson)
