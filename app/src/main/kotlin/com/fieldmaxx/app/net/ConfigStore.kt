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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
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
private const val READ_RETRY_FIRST_MS = 1_000L
private const val READ_RETRY_MAX_MS = 60_000L

/**
 * The server's map and photo settings (`/api/config`), kept on the phone: [config] holds the last ones fetched, or
 * [DEFAULT_CONFIG] until a fetch ever succeeded. [refresh] runs after sign-in and each time sync comes online.
 */
class ConfigStore(private val api: Api, private val store: DataStore<Preferences>, private val scope: CoroutineScope) {
    /**
     * For screens: starts as [DEFAULT_CONFIG] before the kept settings are read, so it can't tell "not read yet" from
     * "the server sent the defaults"; [current] can. A failed read shows the defaults and the file is read again, so a
     * later refresh still arrives (R62).
     */
    val config: StateFlow<AppConfig> = store.data
        .map(::configOf)
        .retryWhen { cause, attempt ->
            if (cause !is IOException) return@retryWhen false
            emit(DEFAULT_CONFIG)
            delay((READ_RETRY_FIRST_MS shl attempt.coerceAtMost(6L).toInt()).coerceAtMost(READ_RETRY_MAX_MS))
            true
        }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_CONFIG)

    /**
     * The kept settings once they have been read (the defaults when none are kept, or the file can't be read): what to
     * use right after a cold start or a process-death restore, the photo quality at the shutter say (R62).
     */
    suspend fun current(): AppConfig = try {
        configOf(store.data.first())
    } catch (e: IOException) {
        DEFAULT_CONFIG
    }

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

    /** Settings written by another version of the app read as [DEFAULT_CONFIG] until the next fetch. */
    private fun configOf(prefs: Preferences): AppConfig = prefs[CONFIG]?.let { json ->
        try {
            FmxJson.decodeFromString(AppConfig.serializer(), json)
        } catch (e: IllegalArgumentException) {
            null
        }
    } ?: DEFAULT_CONFIG
}
