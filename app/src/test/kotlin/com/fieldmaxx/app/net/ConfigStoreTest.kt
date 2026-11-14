package com.fieldmaxx.app.net

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
