package com.fieldmaxx.app.map

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The map's view per user per job (spec §6.3): saved on every move, restored whenever the job is reopened. */
interface ViewMemory {
    suspend fun save(userId: String, jobId: String, v: CameraView)
    suspend fun load(userId: String, jobId: String): CameraView?
}

/** Kept in DataStore on the phone, so a view outlives the screen and the process. */
class DataStoreViewMemory(private val store: DataStore<Preferences>) : ViewMemory {
    /** Finishes once begun, even when the screen goes away meanwhile: the last move is the view the next open restores. */
    override suspend fun save(userId: String, jobId: String, v: CameraView) {
        withContext(NonCancellable) {
            try {
                store.edit { it[key(userId, jobId)] = "${v.lat},${v.lon},${v.zoom}" }
            } catch (e: IOException) {
                // The disk refused: the view in use stays, only the next open starts from the last one saved.
            }
        }
    }

    /** Null when nothing (readable) is saved: the map then fits all points. */
    override suspend fun load(userId: String, jobId: String): CameraView? {
        val text = try {
            store.data.first()[key(userId, jobId)]
        } catch (e: IOException) {
            null
        } ?: return null
        val parts = text.split(',')
        val numbers = parts.mapNotNull { it.toDoubleOrNull() }
        if (parts.size != 3 || numbers.size != 3) return null
        val (lat, lon, zoom) = numbers
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || zoom !in MIN_ZOOM..MAX_ZOOM) return null
        return CameraView(lat, lon, zoom)
    }

    /** Both ids encoded, so no two pairs share a key. */
    private fun key(userId: String, jobId: String) = stringPreferencesKey("view:" + encode(userId) + ":" + encode(jobId))

    private fun encode(id: String) = URLEncoder.encode(id, "UTF-8")
}
