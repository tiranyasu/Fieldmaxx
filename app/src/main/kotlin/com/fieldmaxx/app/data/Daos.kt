package com.fieldmaxx.app.data

import androidx.room.Dao
import androidx.room.Insert
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
}

@Dao
interface LocalPhotoDao {
    @Query("SELECT * FROM LocalPhotoEntity WHERE id = :id")
    suspend fun get(id: String): LocalPhotoEntity?

    @Query("SELECT * FROM LocalPhotoEntity WHERE pointId = :pointId ORDER BY createdAt")
    fun observeForPoint(pointId: String): Flow<List<LocalPhotoEntity>>

    @Query("SELECT * FROM LocalPhotoEntity WHERE state IN (:states) ORDER BY createdAt")
    suspend fun inStates(states: List<String>): List<LocalPhotoEntity>

    @Upsert
    suspend fun upsert(photo: LocalPhotoEntity)
}
