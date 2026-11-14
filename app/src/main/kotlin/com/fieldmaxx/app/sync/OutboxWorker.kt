package com.fieldmaxx.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fieldmaxx.app.FieldmaxxApp

/**
 * Sends the outbox in the background, on screen or not, once there is a network. It sends ops only, never positions,
 * and goes through the app's own [SyncEngine], so it never sends an op twice alongside the engine.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when ((applicationContext as FieldmaxxApp).graph.sync.flushOutbox()) {
        FlushResult.EMPTY -> Result.success()
        FlushResult.PAUSED -> Result.success() // the session ended: the next sign-in sends them
        FlushResult.RETRY -> Result.retry()
    }

    companion object {
        const val NAME = "outbox"

        /** Lines up one background flush; while one is waiting or running, this changes nothing. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
