package com.fieldmaxx.app.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.AppRelease
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the app shows once a newer version is published (spec §6.11). */
const val UPDATE_BANNER = "Update available — tap to install"

/** The server is asked at most this often, unless the tech asks ("Check for update", R69). */
internal const val UPDATE_CHECK_EVERY_MS = 24L * 3_600_000

private val LAST_CHECK = longPreferencesKey("lastCheck")
private val FOUND = stringPreferencesKey("release")

/**
 * Asks our server whether a newer version than [currentVersionCode] is published (spec §6.11, R69): at most once a day,
 * unless forced. When the last check was and the release it found are kept in [store], so a new start within the day
 * asks nothing and still knows the update. [available] is that release, for the banner.
 */
class UpdateChecker(
    private val api: Api,
    private val currentVersionCode: Int,
    private val now: () -> Long,
    private val store: DataStore<Preferences>,
) {
    private val _available = MutableStateFlow<AppRelease?>(null)

    /** The newer release the last check found, or null: none, or not checked yet. */
    val available: StateFlow<AppRelease?> = _available.asStateFlow()

    private val lock = Mutex() // one check at a time: the start's own and the menu's can meet

    /**
     * The newer release, or null. Within a day of the last check (and without [force]) the server isn't asked: the
     * release kept from then is the answer. Throws [IOException] or [ApiException] when the server can't be reached or
     * refuses; such a try doesn't count as a check, and what was found before stays known.
     */
    suspend fun check(force: Boolean = false): AppRelease? = lock.withLock {
        val kept = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }.first()
        val known = kept[FOUND]?.let(::decode)?.takeIf { it.versionCode > currentVersionCode }
        _available.value = known
        val last = kept[LAST_CHECK]
        // A clock set back before the last check is no reason to wait: ask.
        if (!force && last != null && now() - last in 0 until UPDATE_CHECK_EVERY_MS) return@withLock known
        val newer = api.latestRelease()?.takeIf { it.versionCode > currentVersionCode }
        _available.value = newer
        try {
            store.edit {
                it[LAST_CHECK] = now()
                if (newer == null) it.remove(FOUND) else it[FOUND] = FmxJson.encodeToString(AppRelease.serializer(), newer)
            }
        } catch (e: IOException) {
            // Not kept: the next start asks again.
        }
        newer
    }

    /** A release kept by another version of the app that can't be read is no release. */
    private fun decode(json: String): AppRelease? = try {
        FmxJson.decodeFromString(AppRelease.serializer(), json)
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** The start's check: without signal, or refused, it just waits for the next one. */
suspend fun UpdateChecker.checkQuietly() {
    try {
        check()
    } catch (e: IOException) {
        // no signal: the next start or day asks again
    } catch (e: ApiException) {
        // the session has ended or the server refused: sync deals with the session
    }
}
