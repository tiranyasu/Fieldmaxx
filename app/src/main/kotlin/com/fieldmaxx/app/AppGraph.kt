package com.fieldmaxx.app

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.net.AndroidKeystoreTokenVault
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.AuthInterceptor
import com.fieldmaxx.app.net.OkHttpApi
import com.fieldmaxx.app.net.ServerAddress
import com.fieldmaxx.app.net.TokenVault
import com.fieldmaxx.app.sync.LiveSocket
import com.fieldmaxx.app.sync.OutboxWorker
import com.fieldmaxx.app.sync.SyncEngine
import com.fieldmaxx.app.sync.liveSocketUrl
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * The app's long-lived objects, built once per process by [FieldmaxxApp]. Tests pass an
 * [com.fieldmaxx.app.net.InMemoryTokenVault], since AndroidKeyStore doesn't exist under Robolectric.
 */
class AppGraph(context: Context, val vault: TokenVault = AndroidKeystoreTokenVault(context)) {
    private val app = context.applicationContext

    val server = ServerAddress(app)

    // Reading a large job file can take the server a while before it answers.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(vault, server::get))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val api: Api = OkHttpApi(http, server::get)

    /** Lives as long as the process: the sync engine and the outbox watch run here. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy { AppDatabase.open(app) }

    /** The live socket to the server; it only carries frames (positions are sent by the screen that owns location). */
    val live: LiveSocket by lazy { LiveSocket(http, { liveSocketUrl(server.get()) }, vault::load) }

    val sync: SyncEngine by lazy {
        SyncEngine(api, db, live, scope, System::currentTimeMillis, server::get) { urls -> prefetch(urls) }
            .also { watchOutbox() }
    }

    /**
     * Coil's app-wide loader, handed out by [FieldmaxxApp]: requests go through the signed-in client, and the disk cache
     * keeps reference images for work without signal. It lives in files, not cache, so the system doesn't clear it.
     */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(app)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .diskCache {
                DiskCache.Builder().directory(app.filesDir.resolve("image_cache").toOkioPath()).maxSizeBytes(IMAGE_CACHE_BYTES).build()
            }
            .build()
    }

    /** Fills the disk cache only: nothing is shown, so the memory cache stays out of it and the decode is tiny. */
    private fun prefetch(urls: List<String>) {
        for (url in urls) {
            imageLoader.enqueue(
                ImageRequest.Builder(app).data(url).memoryCachePolicy(CachePolicy.DISABLED).size(PREFETCH_DECODE_PX).build(),
            )
        }
    }

    /**
     * While ops wait in the outbox, a background flush is lined up for when there's a network. An op that comes while a
     * flush is running may miss that flush, which then ends without it (KEEP leaves the running flush in place), so the
     * outbox is checked again once that run is over. Once per change, so a flush paused on sign-in can't loop.
     */
    private fun watchOutbox() {
        scope.launch {
            db.outbox().observeCount().collectLatest { count ->
                if (count == 0) return@collectLatest
                OutboxWorker.enqueue(app)
                WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(OutboxWorker.NAME)
                    .first { runs -> runs.none { it.state == WorkInfo.State.RUNNING } }
                if (db.outbox().count() > 0) OutboxWorker.enqueue(app)
            }
        }
    }

    /**
     * Tests only; the app's graph lives as long as the process. Stops syncing, waits for everything running here to end,
     * then closes the database.
     */
    @VisibleForTesting
    internal fun close() {
        sync.stop()
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    private companion object {
        const val IMAGE_CACHE_BYTES = 256L * 1024 * 1024
        const val PREFETCH_DECODE_PX = 64
    }
}
