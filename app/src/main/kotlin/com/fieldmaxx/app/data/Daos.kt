package com.fieldmaxx.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Lists come back in insertion order (rowid): a job's points in the order its snapshot listed them, later ones after.
// @Upsert updates an existing row in place, so a row keeps its place.

@Dao
interface JobDao {
    @Query("SELECT * FROM JobEntity WHERE id = :id")
    suspend fun get(id: String): JobEntity?

    @Query("SELECT * FROM JobEntity WHERE id = :id")
    fun observe(id: String): Flow<JobEntity?>

    @Upsert
    suspend fun upsert(job: JobEntity)

    @Query("DELETE FROM JobEntity")
    suspend fun deleteAll()
}

@Dao
interface PointDao {
    @Query("SELECT * FROM PointEntity WHERE id = :id")
    suspend fun get(id: String): PointEntity?

    @Query("SELECT * FROM PointEntity WHERE jobId = :jobId ORDER BY rowid")
    suspend fun forJob(jobId: String): List<PointEntity>

    @Query("SELECT * FROM PointEntity WHERE jobId = :jobId ORDER BY rowid")
    fun observeForJob(jobId: String): Flow<List<PointEntity>>

    @Upsert
    suspend fun upsert(point: PointEntity)

    @Upsert
    suspend fun upsertAll(points: List<PointEntity>)

    /** At most a few hundred ids per call (SQLite's bound-parameter limit is 999 on older Android). */
    @Query("DELETE FROM PointEntity WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM PointEntity")
    suspend fun deleteAll()
}

@Dao
interface PhotoInfoDao {
    @Query("SELECT * FROM PhotoInfoEntity WHERE id = :id")
    suspend fun get(id: String): PhotoInfoEntity?

    @Query("SELECT * FROM PhotoInfoEntity WHERE jobId = :jobId ORDER BY rowid")
    suspend fun forJob(jobId: String): List<PhotoInfoEntity>

    @Query("SELECT * FROM PhotoInfoEntity WHERE pointId = :pointId ORDER BY rowid")
    fun observeForPoint(pointId: String): Flow<List<PhotoInfoEntity>>

    @Query("SELECT * FROM PhotoInfoEntity WHERE jobId = :jobId ORDER BY rowid")
    fun observeForJob(jobId: String): Flow<List<PhotoInfoEntity>>

    @Upsert
    suspend fun upsert(photo: PhotoInfoEntity)

    @Upsert
    suspend fun upsertAll(photos: List<PhotoInfoEntity>)

    /** At most a few hundred ids per call, as [PointDao.delete]. */
    @Query("DELETE FROM PhotoInfoEntity WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM PhotoInfoEntity")
    suspend fun deleteAll()
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(op: OutboxEntity): Long

    @Query("SELECT * FROM OutboxEntity ORDER BY seq")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT * FROM OutboxEntity ORDER BY seq LIMIT :limit")
    suspend fun first(limit: Int): List<OutboxEntity>

    @Query("DELETE FROM OutboxEntity WHERE seq IN (:seqs)")
    suspend fun delete(seqs: List<Long>)

    @Query("SELECT COUNT(*) FROM OutboxEntity")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM OutboxEntity")
    fun observeCount(): Flow<Int>
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM MetaEntity WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM MetaEntity WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun put(entry: MetaEntity)

    @Query("SELECT `key` FROM MetaEntity WHERE substr(`key`, 1, length(:prefix)) = :prefix")
    suspend fun keysStartingWith(prefix: String): List<String>

    @Query("DELETE FROM MetaEntity WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM MetaEntity WHERE substr(`key`, 1, length(:prefix)) = :prefix")
    suspend fun deleteStartingWith(prefix: String)
}

@Dao
interface LocalPhotoDao {
    @Query("SELECT * FROM LocalPhotoEntity WHERE id = :id")
    suspend fun get(id: String): LocalPhotoEntity?

    @Query("SELECT * FROM LocalPhotoEntity WHERE pointId = :pointId ORDER BY createdAt")
    fun observeForPoint(pointId: String): Flow<List<LocalPhotoEntity>>

    /** The job's photos on this phone: "Where the photos went" (Task 29). */
    @Query("SELECT * FROM LocalPhotoEntity WHERE jobId = :jobId ORDER BY createdAt")
    fun observeForJob(jobId: String): Flow<List<LocalPhotoEntity>>

    /** The job's photos on this phone: "Export photos to OneDrive app" (Task 29). */
    @Query("SELECT * FROM LocalPhotoEntity WHERE jobId = :jobId ORDER BY createdAt")
    suspend fun forJob(jobId: String): List<LocalPhotoEntity>

    @Query("SELECT * FROM LocalPhotoEntity WHERE state IN (:states) ORDER BY createdAt")
    suspend fun inStates(states: List<String>): List<LocalPhotoEntity>

    /** This phone's photos that OneDrive hasn't confirmed yet, across all jobs: the map's "3 photos waiting" (R40). */
    @Query("SELECT COUNT(*) FROM LocalPhotoEntity WHERE state != 'confirmed'")
    fun observeWaitingCount(): Flow<Int>

    /** The job's points with a photo on this phone that OneDrive hasn't confirmed yet: the map's badge. */
    @Query("SELECT DISTINCT pointId FROM LocalPhotoEntity WHERE jobId = :jobId AND state != 'confirmed'")
    fun observeWaitingPointIds(jobId: String): Flow<List<String>>

    /** How many of the point's photos are still only on this phone: the count the point sheet's delete warning gives. */
    @Query("SELECT COUNT(*) FROM LocalPhotoEntity WHERE pointId = :pointId AND state != 'confirmed'")
    fun observeOnPhoneCount(pointId: String): Flow<Int>

    @Upsert
    suspend fun upsert(photo: LocalPhotoEntity)

    // ---- the photo pipeline (Task 28) ----
    // Each step's update names only the fields that step owns and checks the state it expects, returning the rows changed:
    // the OneDrive and the backup worker work on one row side by side, and neither may write back the other's fields from
    // a stale copy. 0 means the photo moved on (or was deleted) meanwhile.

    /** The orphan sweep's insert: it must never write over a row LocalPhotos has just put in. -1 when one was there. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(photo: LocalPhotoEntity): Long

    @Query("SELECT id FROM LocalPhotoEntity")
    suspend fun ids(): List<String>

    /** The photos the pipeline still owes something: not in OneDrive yet, or not backed up yet. */
    @Query("SELECT * FROM LocalPhotoEntity WHERE state != 'confirmed' OR backupDone = 0 ORDER BY createdAt")
    suspend fun unfinished(): List<LocalPhotoEntity>

    /** Confirmed and backed up, and kept on the phone long enough ([LocalPhotoEntity.nextAttemptAt]). */
    @Query("SELECT * FROM LocalPhotoEntity WHERE state = 'confirmed' AND backupDone = 1 AND nextAttemptAt <= :now")
    suspend fun cleanupDue(now: Long): List<LocalPhotoEntity>

    @Query("DELETE FROM LocalPhotoEntity WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        "UPDATE LocalPhotoEntity SET state = 'stamped', stampedPath = :stampedPath, attempts = 0, stuck = 0, lastError = NULL, " +
            "nextAttemptAt = 0 WHERE id = :id AND state = 'saved'",
    )
    suspend fun markStamped(id: String, stampedPath: String): Int

    @Query(
        "UPDATE LocalPhotoEntity SET state = 'registered', fileName = :fileName, backupOffset = :backupOffset, attempts = 0, stuck = 0, " +
            "lastError = NULL, nextAttemptAt = 0 WHERE id = :id AND state = 'stamped'",
    )
    suspend fun markRegistered(id: String, fileName: String, backupOffset: Long): Int

    /** R57: a chunk session the upload reported, from its upload's own callback (so not suspending; never on the main thread). */
    @Query(
        "UPDATE LocalPhotoEntity SET oneDriveUploadId = :uploadId, oneDriveOffset = :offset, createdThisFile = 1 " +
            "WHERE id = :id AND state = 'registered' AND fileName = :fileName",
    )
    fun saveSession(id: String, fileName: String, uploadId: String, offset: Long): Int

    @Query(
        "UPDATE LocalPhotoEntity SET oneDriveUploadId = NULL, oneDriveOffset = 0, createdThisFile = 0 " +
            "WHERE id = :id AND state = 'registered' AND fileName = :fileName",
    )
    suspend fun forgetSession(id: String, fileName: String): Int

    /** A new name (a new counter) drops the session of the old one: that file is not this name's. */
    @Query(
        "UPDATE LocalPhotoEntity SET fileName = :newName, oneDriveUploadId = NULL, oneDriveOffset = 0, createdThisFile = 0 " +
            "WHERE id = :id AND state = 'registered' AND fileName = :oldName",
    )
    suspend fun rename(id: String, oldName: String, newName: String): Int

    @Query(
        "UPDATE LocalPhotoEntity SET state = 'confirmed', confirmedAt = :at, oneDriveUploadId = NULL, oneDriveOffset = 0, attempts = 0, " +
            "stuck = 0, lastError = NULL, nextAttemptAt = CASE WHEN backupDone THEN :at + :keepMs ELSE 0 END " +
            "WHERE id = :id AND state = 'registered'",
    )
    suspend fun markConfirmed(id: String, at: Long, keepMs: Long): Int

    /** The server delivered it (R57): its session dropped, and backed up too when the server says so. */
    @Query(
        "UPDATE LocalPhotoEntity SET state = 'confirmed', confirmedAt = :confirmedAt, oneDriveUploadId = NULL, oneDriveOffset = 0, " +
            "createdThisFile = 0, attempts = 0, stuck = 0, lastError = NULL, backupDone = (backupDone OR :backedUp), " +
            "nextAttemptAt = CASE WHEN (backupDone OR :backedUp) THEN :now + :keepMs ELSE 0 END WHERE id = :id AND state = 'registered'",
    )
    suspend fun markConfirmedByServer(id: String, confirmedAt: Long, backedUp: Boolean, now: Long, keepMs: Long): Int

    @Query("UPDATE LocalPhotoEntity SET backupOffset = :offset WHERE id = :id AND backupDone = 0")
    suspend fun saveBackupOffset(id: String, offset: Long): Int

    /** [offset] null keeps the one recorded. Once in OneDrive too, its copies here are kept [keepMs] from [now]. */
    @Query(
        "UPDATE LocalPhotoEntity SET backupDone = 1, backupOffset = COALESCE(:offset, backupOffset), " +
            "nextAttemptAt = CASE WHEN state = 'confirmed' THEN :now + :keepMs ELSE nextAttemptAt END WHERE id = :id AND backupDone = 0",
    )
    suspend fun markBackedUp(id: String, offset: Long?, now: Long, keepMs: Long): Int

    @Query(
        "UPDATE LocalPhotoEntity SET attempts = :attempts, nextAttemptAt = :nextAttemptAt, lastError = :error, stuck = :stuck " +
            "WHERE id = :id AND state = :state",
    )
    suspend fun recordFailure(id: String, state: String, attempts: Int, nextAttemptAt: Long, error: String, stuck: Boolean): Int

    /** R58: back to "saved" for a new stamped copy; the OneDrive session of the old copy is dropped with it. */
    @Query(
        "UPDATE LocalPhotoEntity SET state = 'saved', stampedPath = NULL, oneDriveUploadId = NULL, oneDriveOffset = 0, createdThisFile = 0 " +
            "WHERE id = :id AND state = :state",
    )
    suspend fun restamp(id: String, state: String): Int

    @Query("UPDATE LocalPhotoEntity SET nextAttemptAt = :at WHERE id = :id AND state != 'confirmed'")
    suspend fun dueNow(id: String, at: Long): Int
}
