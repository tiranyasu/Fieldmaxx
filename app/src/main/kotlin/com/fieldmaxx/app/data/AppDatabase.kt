package com.fieldmaxx.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

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
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "fieldmaxx.db").addCallback(DurableCommits).build()
    }
}

/**
 * R80: each commit is on the disk before it returns, so a power cut can't take back a saved photo's row (or a queued op)
 * the tech was already told about. Android's WAL default, synchronous=NORMAL, can. A PRAGMA that sets a value and returns
 * no row goes through execSQL. It is set on the connection that writes, each time the database opens.
 */
private object DurableCommits : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA synchronous = FULL")
    }
}
