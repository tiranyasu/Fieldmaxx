package com.fieldmaxx.app.net

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.fieldmaxx.app.sync.SyncStatus
import com.fieldmaxx.app.sync.awaitTrue
import com.fieldmaxx.core.model.AppConfig
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

private class ConfigApi : Api by unusedApi() {
    val calls = AtomicInteger()
    @Volatile var reply: () -> AppConfig = { DEFAULT_CONFIG }

    override suspend fun config(): AppConfig {
        calls.incrementAndGet()
        return reply()
    }
}

/** A DataStore whose first [failReads] reads fail, as a damaged or busy disk would; writes go through. */
private class FlakyStore(private val real: DataStore<Preferences>, private val failReads: Int) : DataStore<Preferences> {
    val failures = AtomicInteger()
    override val data: Flow<Preferences> = flow {
        if (failures.get() < failReads) {
            failures.incrementAndGet()
            throw IOException("read failed")
        }
        emitAll(real.data)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = real.updateData(transform)
}

private fun unusedApi(): Api = Proxy.newProxyInstance(Api::class.java.classLoader, arrayOf(Api::class.java)) { _, method, _ ->
    throw UnsupportedOperationException("Api.${method.name} is not used by the config store")
} as Api

class ConfigStoreTest {
    private val dir: File = Files.createTempDirectory("config").toFile()
    private val file = File(dir, "app_config.preferences_pb")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = ConfigApi()
    private val admin = AppConfig("https://tiles.example.com/{z}/{y}/{x}", "https://labels.example.com/{z}/{y}/{x}", "key-1", 95, 5)

    @AfterTest fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test fun defaultsUntilFetchedThenKept() = runBlocking {
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ConfigStore(api, PreferenceDataStoreFactory.create(scope = first) { file }, first)
        assertEquals(
            AppConfig(
                imageryUrl = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
                labelsUrl = "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}",
                imageryKey = null, photoQuality = 85, backupDays = 3,
            ),
            store.config.value,
            "before any fetch: the map works offline on first launch",
        )

        api.reply = { admin }
        store.refresh()
        awaitTrue("the server's settings") { store.config.value == admin }

        api.reply = { throw IOException("no signal") }
        store.refresh()
        api.reply = { throw ApiException(401, "Sign in required") }
        store.refresh()
        assertEquals(admin, store.config.value, "a failed refresh keeps the last settings")

        // The next launch starts from the kept settings, before any fetch.
        first.coroutineContext.job.cancelAndJoin()
        val again = ConfigStore(api, PreferenceDataStoreFactory.create(scope = scope) { file }, scope)
        awaitTrue("the kept settings") { again.config.value == admin }
    }

    @Test fun currentWaitsForTheKeptSettings() = runBlocking {
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ConfigStore(api, PreferenceDataStoreFactory.create(scope = first) { file }, first)
        assertEquals(DEFAULT_CONFIG, store.current(), "nothing kept yet: the defaults")
        api.reply = { admin }
        store.refresh()
        assertEquals(admin, store.current())

        // A cold start or a process-death restore: read at once, before the state flow may have caught up (Task 28).
        first.coroutineContext.job.cancelAndJoin()
        val again = ConfigStore(api, PreferenceDataStoreFactory.create(scope = scope) { file }, scope)
        assertEquals(admin, again.current())
        assertEquals(DEFAULT_CONFIG, ConfigStore(api, FlakyStore(freshDisk(), failReads = Int.MAX_VALUE), scope).current(), "unreadable: the defaults")
    }

    @Test fun flowSurvivesAReadError() = runBlocking {
        val disk = FlakyStore(freshDisk(), failReads = 1)
        val store = ConfigStore(api, disk, scope)
        awaitTrue("the first read failed") { disk.failures.get() == 1 }
        assertEquals(DEFAULT_CONFIG, store.config.value)

        api.reply = { admin }
        assertTrue(store.refresh())
        awaitTrue("a later refresh still arrives") { store.config.value == admin }
    }

    /** The real store behind a [FlakyStore], on a file of its own. */
    private fun freshDisk(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "flaky${flaky.incrementAndGet()}.preferences_pb") }

    private val flaky = AtomicInteger()

    @Test fun refreshesEachTimeSyncComesOnline() = runBlocking {
        val store = ConfigStore(api, PreferenceDataStoreFactory.create(scope = scope) { file }, scope)
        val status = MutableStateFlow(SyncStatus.OFFLINE)
        store.refreshWhenOnline(status)
        delay(200)
        assertEquals(0, api.calls.get())

        status.value = SyncStatus.SYNCING // connected, catching up
        status.value = SyncStatus.ONLINE
        awaitTrue("a refresh once online") { api.calls.get() == 1 }
        status.value = SyncStatus.SYNCING // an op goes out: still the same connection
        status.value = SyncStatus.ONLINE
        delay(200)
        assertEquals(1, api.calls.get())

        status.value = SyncStatus.OFFLINE
        delay(200) // the connection stays down a moment (a state flow keeps only the latest value)
        status.value = SyncStatus.ONLINE
        awaitTrue("a refresh after the connection came back") { api.calls.get() == 2 }
    }
}
