package com.fieldmaxx.app.net

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fieldmaxx.app.sync.SyncStatus
import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.AppConfig
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the phone uses before the server's settings have ever arrived (R39, global constraints): the map works offline at once. */
val DEFAULT_CONFIG = AppConfig(
    imageryUrl = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
    labelsUrl = "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}",
    imageryKey = null,
    photoQuality = 85,
    backupDays = 3,
)

private val CONFIG = stringPreferencesKey("config")

/**
 * The server's map and photo settings (`/api/config`), kept on the phone: [config] holds the last ones fetched, or
 * [DEFAULT_CONFIG] until a fetch ever succeeded. [refresh] runs after sign-in and each time sync comes online.
 */
class ConfigStore(private val api: Api, private val store: DataStore<Preferences>, private val scope: CoroutineScope) {
    val config: StateFlow<AppConfig> = store.data
        .map { it[CONFIG]?.let(::decode) ?: DEFAULT_CONFIG }
        .catch { e -> if (e is IOException) emit(DEFAULT_CONFIG) else throw e }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_CONFIG)

    /** Fetches and keeps the server's settings; false when the server couldn't be reached or refused (the last ones stay). */
    suspend fun refresh(): Boolean {
        val fetched = try {
            api.config()
        } catch (e: IOException) {
            return false
        } catch (e: ApiException) {
            return false
        }
        return try {
            store.edit { it[CONFIG] = FmxJson.encodeToString(AppConfig.serializer(), fetched) }
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Refreshes each time [status] turns ONLINE after being OFFLINE. Busy spells (SYNCING) within one connection don't
     * count: they come and go with every op sent.
     */
    fun refreshWhenOnline(status: Flow<SyncStatus>) {
        scope.launch {
            status.filter { it != SyncStatus.SYNCING }.map { it == SyncStatus.ONLINE }.distinctUntilChanged().filter { it }.collect { refresh() }
        }
    }

    /** Null for settings written by another version of the app: [DEFAULT_CONFIG] until the next fetch. */
    private fun decode(json: String): AppConfig? = try {
        FmxJson.decodeFromString(AppConfig.serializer(), json)
    } catch (e: IllegalArgumentException) {
        null
    }
}
