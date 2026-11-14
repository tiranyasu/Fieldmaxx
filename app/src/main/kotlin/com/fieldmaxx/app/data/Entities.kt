package com.fieldmaxx.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// The phone's copy of server state keeps each core model whole, as FmxJson text (see Converters.kt). `seq` is the
// server change seq of the state a row last took from the server; a row only this phone has made so far has 0. Local
// optimistic edits never change `seq`, so a newer server state always gets through.

/** [updatedAt]: when this phone last stored the job. */
@Entity
data class JobEntity(@PrimaryKey val id: String, val json: String, val updatedAt: Long, val seq: Long)

@Entity(indices = [Index("jobId")])
data class PointEntity(@PrimaryKey val id: String, val jobId: String, val json: String, val seq: Long)

@Entity(indices = [Index("pointId"), Index("jobId")])
data class PhotoInfoEntity(@PrimaryKey val id: String, val jobId: String, val pointId: String, val json: String, val seq: Long)

/** An operation waiting to reach the server; sent in [seq] order. */
@Entity
data class OutboxEntity(@PrimaryKey(autoGenerate = true) val seq: Long = 0, val opJson: String)

@Entity
data class MetaEntity(@PrimaryKey val key: String, val value: String)

/** A photo taken on this phone and how far its delivery got (driven by the photo pipeline). */
@Entity(indices = [Index("pointId"), Index("state")])
data class LocalPhotoEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val pointId: String,
    val originalPath: String,
    val stampedPath: String?,
    val metaJson: String,
    val state: String, // saved|stamped|registered|confirmed
    val fileName: String?,
    val oneDriveUploadId: String?,
    val oneDriveOffset: Long,
    val createdThisFile: Boolean,
    val backupOffset: Long,
    val backupDone: Boolean,
    val attempts: Int,
    val nextAttemptAt: Long,
    val lastError: String?,
    val stuck: Boolean,
    val createdAt: Long,
    val confirmedAt: Long?,
)
