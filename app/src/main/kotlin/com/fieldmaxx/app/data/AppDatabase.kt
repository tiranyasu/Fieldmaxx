package com.fieldmaxx.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** The phone's local store: server state for offline work, the outbox of pending ops, and local photo progress. */
@Database(
    entities = [JobEntity::class, PointEntity::class, PhotoInfoEntity::class, OutboxEntity::class, MetaEntity::class, LocalPhotoEntity::class],
    version = 1,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun jobs(): JobDao
    abstract fun points(): PointDao
    abstract fun photos(): PhotoInfoDao
    abstract fun outbox(): OutboxDao
    abstract fun meta(): MetaDao
    abstract fun localPhotos(): LocalPhotoDao

    companion object {
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "fieldmaxx.db").build()
    }
}
