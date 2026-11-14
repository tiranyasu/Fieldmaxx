package com.fieldmaxx.app.photos

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fieldmaxx.app.FieldmaxxApp
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.LocalPhotoEntity
import com.fieldmaxx.app.data.toJob
import com.fieldmaxx.app.data.toOp
import com.fieldmaxx.app.data.toPhoto
import com.fieldmaxx.app.data.toPoint
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.net.DEFAULT_CONFIG
import com.fieldmaxx.app.sync.SyncEngine
import com.fieldmaxx.app.sync.targetPoint
import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.retry.MAX_RETRY_DELAY_MS
import com.fieldmaxx.core.retry.nextDelayMs
import com.fieldmaxx.sharepoint.DestinationFactory
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// LocalPhotoEntity.state, step by step (spec §6.6).
internal const val SAVED = "saved"
internal const val STAMPED = "stamped"
internal const val REGISTERED = "registered"
internal const val CONFIRMED = "confirmed"

/** Every photo work request carries this tag. */
const val PHOTO_WORK_TAG = "fieldmaxx.photos"

/** Spec §6.6: a photo is stuck after 6 failed attempts (it is still tried, every 30 min). */
internal const val STUCK_AFTER_ATTEMPTS = 6

/** Spec §6.6 step 7: the phone keeps its own copies 3 days after a photo is both confirmed and backed up. */
internal const val KEEP_ON_PHONE_MS = 3L * 24 * 60 * 60 * 1000

internal const val MISSING_FILE = "The photo's file is missing from this phone"
internal const val CANT_REACH_SERVER = "Can't reach the server"

// The photo folders LocalPhotos writes: filesDir/photos/<id>/{original.jpg, meta.json}; the stamped copy goes beside them.
internal const val PHOTO_FOLDERS = "photos"
internal const val META_JSON = "meta.json"
internal const val STAMPED_JPG = "stamped.jpg"

internal const val KEY_PHOTO = "photoId"
internal const val KEY_ATTEMPT = "attempt"

/** R67: how soon the server is asked again, while it can't be reached, whether a deleted point is deleted for good. */
private const val DELETE_CHECK_FIRST_MS = 5_000L

/** A photo's steps (spec §6.6), each its own unique work per photo; [state] is the photo's state when the step is due. */
internal enum class Step(private val key: String, val state: String?) {
    STAMP("stamp", SAVED),
    REGISTER("register", STAMPED),
    ONEDRIVE("onedrive", REGISTERED),
    BACKUP("backup", null),
    ;

    fun workName(photoId: String) = "photo.$key:$photoId"
}

/** Why a photo waits without counting an attempt (R58, R67, spec §6.6.1). */
internal enum class Wait {
    /** Its job has no Field Notes link yet. */
    LINK,

    /** Several OneDrive folders could be its point's: the point sheet asks which. */
    FOLDER,

    /** Its job or point isn't on this phone (yet). */
    ROWS,

    /** Its point is deleted (optimistically, or on the server). */
    DELETED,
}

/** How one run of a step ended. */
internal sealed interface Outcome {
    /** Recorded, and the next step lined up; or there is nothing left for this step to do. */
    data object Done : Outcome

    /** Not a failure: the photo waits until [reason] goes, with no attempt counted and no backoff. */
    data class Waiting(val reason: Wait) : Outcome

    /** A failed attempt: counted, tried again after its backoff, stuck after 6. */
    data class Failed(val error: String) : Outcome

    /** A failure trying again won't soon mend (its files are gone): stuck at once, still tried every 30 min. */
    data class Stuck(val error: String) : Outcome

    /** Not a failure, but the step goes again only after [delayMs]. */
    data class Later(val delayMs: Long) : Outcome
}

/**
 * Takes each photo saved on this phone (spec §6.6) through its steps, each in a WorkManager worker of its own, each
 * recorded in Room before the next starts, so a crash, a dead battery or a force-stop never loses a photo:
 * stamp ([StampWorker], no network needed) → register with our server, which names it ([RegisterWorker]) → upload to the
 * job's Field Notes folder and check it there ([OneDriveWorker]), alongside the server backup ([BackupWorker]); and every
 * 6 hours [CleanupWorker] deletes the phone's own copies 3 days after a photo is both confirmed and backed up.
 *
 * - A step's first enqueue is unique per photo and step with KEEP. A failed attempt counts, and the worker lines up its own
 *   next try with APPEND_OR_REPLACE after [nextDelayMs] (KEEP from a running worker would drop it, R55): 15 s doubling,
 *   stuck after 6 attempts and then every 30 min, reported to the server once stuck (R56 opIds).
 * - Waiting for a link, a folder choice or a deleted point's fate is not a failure (R58, R67): the photo waits, and
 *   in-process watchers line it up again when its job, point or the outbox change.
 * - A photo the server shows removed before it reached OneDrive (an admin's delete) is never delivered (R86): each step,
 *   and the watcher for a photo that waits, discard it as [discardIfRemoved] says.
 * - [resumeAll] at app start, and the cleanup worker every 6 hours, recover what a power cut left (R80) and line up every
 *   unfinished photo's step again from its persisted state.
 *
 * [quality] is the server's photo quality (85 or 95), read as `AppGraph.currentConfig()` gives it (R62); [galleryCopy] the
 * gallery-copy setting; [zone] the stamp's time zone (the camera overlay's); [scope] lives as long as the process.
 */
class PhotoPipeline(
    context: Context,
    internal val db: AppDatabase,
    internal val api: Api,
    internal val destinations: DestinationFactory,
    internal val sync: SyncEngine,
    internal val now: () -> Long,
    internal val quality: suspend () -> Int = { DEFAULT_CONFIG.photoQuality },
    internal val galleryCopy: suspend () -> Boolean = { true },
    internal val gallery: GallerySaver = GallerySaver(context),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    internal val zone: () -> ZoneId = ZoneId::systemDefault,
    internal val chunkBytes: Int = ONE_DRIVE_CHUNK_BYTES,
    internal val backupChunkBytes: Int = BACKUP_CHUNK_BYTES,
) : PhotoPipelineStarter {
    private val app: Context = context.applicationContext
    internal val filesDir: File = app.filesDir
    internal val photos get() = db.localPhotos()
    private val workManager: WorkManager by lazy { WorkManager.getInstance(app) }

    /** Photos waiting (not failing), and what for; this process only: resumeAll lines everything up after a restart. */
    private val waiting = ConcurrentHashMap<String, Wait>()
    private val watching = AtomicBoolean(false)
    private val reconciling = Mutex()
    private val folderSearches = ConcurrentHashMap<String, Mutex>()

    /** One OneDrive folder search per job at a time, so photos of one point agree on its folder. */
    internal fun folderSearch(jobId: String): Mutex = folderSearches.computeIfAbsent(jobId) { Mutex() }
    private val deleteChecks: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * A photo just saved (LocalPhotos): its stamp step, the 6-hourly cleanup, and the watcher (I1: a process that started
     * with no photo folders never ran resumeAll, and its photos still wait for links, folder picks and deletes).
     */
    override fun start(localPhotoId: String) {
        watch()
        enqueue(Step.STAMP, localPhotoId, ExistingWorkPolicy.KEEP)
        scheduleCleanup()
    }

    /** "Retry now" (spec §6.6): the photo's next step is tried at once, without waiting out its backoff; its backup too. */
    fun retryNow(localPhotoId: String): Job = scope.launch {
        guarded("Retrying photo $localPhotoId") {
            val row = photos.get(localPhotoId) ?: return@guarded
            photos.dueNow(localPhotoId, now())
            waiting.remove(localPhotoId)
            deliveryStep(row)?.let { force(it, localPhotoId) }
            if (backupOwed(row)) force(Step.BACKUP, localPhotoId)
        }
    }

    /**
     * At app start: watches what photos wait on, recovers the photo folders a power cut left without their row (R80),
     * settles deleted points and server takeovers, and lines up every unfinished photo's step from its persisted state,
     * keeping work WorkManager still has and any backoff still running (R55).
     */
    fun resumeAll(): Job = scope.launch { guarded("Resuming photos") { resume() } }

    /** resumeAll's work, also the 6-hourly safety net. */
    internal suspend fun resume() {
        watch()
        sweepOrphans()
        reconcile()
        for (row in photos.unfinished()) enqueueCurrent(row, ExistingWorkPolicy.KEEP, row.nextAttemptAt - now())
        scheduleCleanup()
    }

    // ---- running a step ----

    /**
     * One run of a delivery step (stamp, register, OneDrive). A vanished row ends it quietly (R58). A photo the server shows
     * removed is discarded (R86). A row at another state lines up the step it is at instead. A deleted point holds the photo
     * (R67). Then [body]'s [Outcome] is recorded; a stopped worker records nothing (WorkManager runs it again).
     */
    internal suspend fun runStep(step: Step, id: String, body: suspend (LocalPhotoEntity) -> Outcome) {
        val row = photos.get(id) ?: return
        if (discardIfRemoved(row)) return
        if (row.state != step.state) {
            enqueueCurrent(row, ExistingWorkPolicy.KEEP)
            return
        }
        if (pointDeleted(row)) {
            waitFor(row, Wait.DELETED)
            return
        }
        val outcome = try {
            body(row)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(readable(e))
        }
        when (outcome) {
            Outcome.Done -> Unit
            is Outcome.Waiting -> waitFor(row, outcome.reason)
            is Outcome.Later -> enqueue(step, id, ExistingWorkPolicy.APPEND_OR_REPLACE, outcome.delayMs)
            is Outcome.Failed -> failed(step, row, outcome.error, stuckNow = false)
            is Outcome.Stuck -> failed(step, row, outcome.error, stuckNow = true)
        }
    }

    /**
     * A failed attempt: counted, the next one lined up after its backoff (30 min once stuck). Once stuck, each failure is
     * reported to the server, if the server knows the photo, in the same transaction (R56).
     * R83: the server refuses PhotoFailed for a photo it never registered, so one stuck before registering shows its reason
     * and "Retry now" on this phone only.
     */
    private suspend fun failed(step: Step, row: LocalPhotoEntity, error: String, stuckNow: Boolean) {
        currentCoroutineContext().ensureActive() // a stopped worker's error isn't the photo's
        val wait = db.withTransaction {
            val current = photos.get(row.id)?.takeIf { it.state == row.state } ?: return@withTransaction null
            val attempts = if (stuckNow) max(current.attempts + 1, STUCK_AFTER_ATTEMPTS) else current.attempts + 1
            val stuck = attempts >= STUCK_AFTER_ATTEMPTS
            val wait = if (stuck) MAX_RETRY_DELAY_MS else nextDelayMs(attempts)
            photos.recordFailure(row.id, row.state, attempts, now() + wait, error, stuck)
            if (stuck && row.state == REGISTERED) sync.enqueue(Op.PhotoFailed("photo.failed:${row.id}:$attempts", row.id, error, attempts))
            wait
        } ?: return
        enqueue(step, row.id, ExistingWorkPolicy.APPEND_OR_REPLACE, wait)
    }

    /** The photo waits for [reason] to go; if it went meanwhile, it goes on at once. */
    internal suspend fun waitFor(row: LocalPhotoEntity, reason: Wait) {
        waiting[row.id] = reason
        wakeIfReady(row.id, reason)
    }

    private suspend fun wakeIfReady(id: String, reason: Wait) {
        val row = photos.get(id)
        if (row == null) {
            waiting.remove(id, reason)
            return
        }
        // APPEND_OR_REPLACE: the worker that is waiting may still be running, and KEEP would drop this.
        if (ready(row, reason) && waiting.remove(id, reason)) enqueueCurrent(row, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    /** The opposite of each worker's reason to wait, so a photo woken never waits again for the same reason. */
    private suspend fun ready(row: LocalPhotoEntity, reason: Wait): Boolean = when (reason) {
        Wait.LINK -> db.jobs().get(row.jobId)?.toJob()?.fieldNotesUrl?.isNotBlank() == true
        Wait.FOLDER -> db.points().get(row.pointId)?.toPoint()?.let { it.folderPath != null || it.folderCandidates.isNullOrEmpty() } ?: true
        Wait.ROWS -> db.jobs().get(row.jobId) != null && db.points().get(row.pointId) != null
        Wait.DELETED -> !pointDeleted(row)
    }

    internal suspend fun pointDeleted(row: LocalPhotoEntity): Boolean = db.points().get(row.pointId)?.toPoint()?.deletedAt != null

    // ---- what the server and the points say (watched) ----

    /** Every change to the photos, points, jobs, server photos or the outbox goes through [reconcile]. */
    private fun watch() {
        if (!watching.compareAndSet(false, true)) return
        scope.launch {
            guarded("Watching the photos") {
                db.invalidationTracker.createFlow("LocalPhotoEntity", "PointEntity", "JobEntity", "PhotoInfoEntity", "OutboxEntity")
                    .conflate()
                    .collect { guarded("Checking the photos") { reconcile() } }
            }
            watching.set(false) // the next resume watches again
        }
    }

    /**
     * Brings into each unfinished photo what the server and its point say: R86 (removed on the server before OneDrive, it
     * is discarded, also while it waits for a link, a folder or a stream), R67 (a deleted point holds its photos; once the
     * server shows it deleted, those not in OneDrive are discarded), R57 (the server delivered the photo itself, or renamed
     * it), the server's backup, and the photos waiting for a link, a folder or their rows.
     */
    internal suspend fun reconcile() = reconciling.withLock {
        var inOutbox: Set<String>? = null
        for (row in photos.unfinished()) {
            val info = db.photos().get(row.id)?.toPhoto()
            if (discardIfRemoved(row, info)) continue // first: the photo is gone for everyone, whatever its point's fate
            if (row.state != CONFIRMED && pointDeleted(row)) {
                val busy = inOutbox ?: pointsInOutbox().also { inOutbox = it }
                if (row.pointId !in busy) checkDelete(row.jobId)
                continue
            }
            if (info == null) continue
            if (row.state == REGISTERED && info.delivered()) {
                confirmedByServer(row, info, cancelUpload = true)
                continue
            }
            if (row.state == REGISTERED && renamedByServer(row.fileName, info.fileName)) photos.rename(row.id, row.fileName!!, info.fileName!!)
            if (info.backedUp && !row.backupDone && photos.markBackedUp(row.id, null, now(), KEEP_ON_PHONE_MS) == 1) {
                workManager.cancelUniqueWork(Step.BACKUP.workName(row.id))
            }
        }
        for ((id, reason) in waiting) wakeIfReady(id, reason)
    }

    /** The points the outbox still has an op for: a delete of one of them isn't answered yet. */
    private suspend fun pointsInOutbox(): Set<String> = db.outbox().all().mapNotNullTo(HashSet()) { it.toOp().targetPoint }

    /**
     * R67: a deleted point whose delete has its answer. The phone's row alone can't tell an accepted delete from a refused
     * one yet: a refusal is undone only once the job's fresh snapshot is in, and that can take a while without signal. So
     * the job is fetched again (and again with a backoff while the server can't be reached), and only the photos of points
     * the server's own state shows deleted, with no new op for them, are discarded.
     */
    private fun checkDelete(jobId: String) {
        if (!deleteChecks.add(jobId)) return
        scope.launch {
            try {
                var wait = DELETE_CHECK_FIRST_MS
                while (settling(jobId).isNotEmpty()) {
                    val fetched = try {
                        sync.openJob(jobId)
                        true
                    } catch (e: IOException) {
                        false
                    } catch (e: ApiException) {
                        false
                    }
                    if (fetched) {
                        reconciling.withLock {
                            val busy = pointsInOutbox()
                            settling(jobId).filter { it.pointId !in busy }.forEach { discard(it) }
                        }
                        return@launch
                    }
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Checking the deleted points of job $jobId failed", e)
            } finally {
                deleteChecks.remove(jobId)
            }
        }
    }

    /** The job's photos not in OneDrive whose point is deleted on this phone. */
    private suspend fun settling(jobId: String): List<LocalPhotoEntity> =
        photos.unfinished().filter { it.jobId == jobId && it.state != CONFIRMED && pointDeleted(it) }

    /**
     * R67: the photo goes with its point; or alone, deleted before OneDrive (R64, Task 29's PhotoRemover). Its work stops, then
     * its files go (meta.json first of all, or the orphan sweep would bring it back), then its row.
     */
    internal suspend fun discard(row: LocalPhotoEntity) {
        workManager.cancelAllWorkByTag(photoTag(row.id))
        deleteFiles(row)
        photos.delete(row.id)
        waiting.remove(row.id)
    }

    /**
     * R86: the server shows the photo removed before it reached OneDrive (an admin deleted it, say): the phone that holds it
     * never delivers it. Its work stops and its copy goes as [discard] does. Not once this phone confirmed it, nor once the
     * server shows it delivered: a removal after that only hides it on the server. Not cancellable, since stopping the
     * photo's work may stop the very worker that asks. True when it was discarded.
     */
    internal suspend fun discardIfRemoved(row: LocalPhotoEntity): Boolean = discardIfRemoved(row, db.photos().get(row.id)?.toPhoto())

    private suspend fun discardIfRemoved(row: LocalPhotoEntity, info: PhotoInfo?): Boolean {
        if (row.state == CONFIRMED || info == null || info.status != PhotoStatus.removed || info.delivered()) return false
        withContext(NonCancellable) { discard(row) }
        return true
    }

    /** The photo's own folder, meta.json first: a sweep must never find a meta.json whose row is gone. */
    internal suspend fun deleteFiles(row: LocalPhotoEntity) {
        withContext(Dispatchers.IO) {
            val folder = File(File(filesDir, PHOTO_FOLDERS), row.id)
            File(folder, META_JSON).delete()
            folder.deleteRecursively()
        }
    }

    /**
     * R57: the server delivered the photo itself (its takeover): confirmed here too, its stored session dropped, and no
     * report owed. [cancelUpload]: the phone's own OneDrive step stops (not when it is the one asking).
     */
    internal suspend fun confirmedByServer(row: LocalPhotoEntity, info: PhotoInfo?, cancelUpload: Boolean) {
        val backedUp = info?.backedUp == true
        if (photos.markConfirmedByServer(row.id, info?.confirmedAt ?: now(), backedUp, now(), KEEP_ON_PHONE_MS) == 0) return
        waiting.remove(row.id)
        if (cancelUpload) workManager.cancelUniqueWork(Step.ONEDRIVE.workName(row.id))
        if (backedUp) workManager.cancelUniqueWork(Step.BACKUP.workName(row.id))
    }

    // ---- WorkManager ----

    internal fun enqueue(step: Step, id: String, policy: ExistingWorkPolicy, delayMs: Long = 0, attempt: Int = 0) {
        val request = when (step) {
            Step.STAMP -> OneTimeWorkRequestBuilder<StampWorker>()
            Step.REGISTER -> OneTimeWorkRequestBuilder<RegisterWorker>()
            Step.ONEDRIVE -> OneTimeWorkRequestBuilder<OneDriveWorker>()
            Step.BACKUP -> OneTimeWorkRequestBuilder<BackupWorker>()
        }
        request.setInputData(workDataOf(KEY_PHOTO to id, KEY_ATTEMPT to attempt)).addTag(PHOTO_WORK_TAG).addTag(photoTag(id))
        if (step != Step.STAMP) request.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        if (delayMs > 0) request.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        workManager.enqueueUniqueWork(step.workName(id), policy, request.build())
    }

    /** The step the photo is at, and its backup while that is owed. */
    internal fun enqueueCurrent(row: LocalPhotoEntity, policy: ExistingWorkPolicy, delayMs: Long = 0) {
        deliveryStep(row)?.let { enqueue(it, row.id, policy, delayMs) }
        if (backupOwed(row)) enqueue(Step.BACKUP, row.id, ExistingWorkPolicy.KEEP)
    }

    private fun scheduleCleanup() {
        val request = PeriodicWorkRequestBuilder<CleanupWorker>(CLEANUP_EVERY_HOURS, TimeUnit.HOURS)
            .setInitialDelay(CLEANUP_EVERY_HOURS, TimeUnit.HOURS) // resumeAll has just done what it does
            .addTag(PHOTO_WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(CLEANUP_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** At once, replacing a delayed attempt; one already running is left to finish. */
    private suspend fun force(step: Step, id: String) {
        val running = workManager.getWorkInfosForUniqueWorkFlow(step.workName(id)).first().any { it.state == WorkInfo.State.RUNNING }
        if (!running) enqueue(step, id, ExistingWorkPolicy.REPLACE)
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
        }
    }

    private companion object {
        const val TAG = "PhotoPipeline"
    }
}

/** Whether any photo, saved or on its way, has its folder on this phone: then the app's start has a pipeline to resume. */
fun photosOnPhone(filesDir: File): Boolean = File(filesDir, PHOTO_FOLDERS).list()?.isNotEmpty() == true

/** The app's pipeline, for the workers WorkManager makes itself (through their (Context, WorkerParameters) constructor). */
internal fun pipelineOf(context: Context): PhotoPipeline = (context.applicationContext as FieldmaxxApp).graph.photos

/** The photo a step's work request is for. */
internal val Data.photoId: String get() = getString(KEY_PHOTO) ?: error("A photo step without its photo")

internal fun photoTag(id: String) = "photo:$id"

internal fun LocalPhotoEntity.captureMeta(): CaptureMeta = FmxJson.decodeFromString(CaptureMeta.serializer(), metaJson)

internal fun LocalPhotoEntity.stampedFile(): File? = stampedPath?.let(::File)?.takeIf { it.isFile }

private fun deliveryStep(row: LocalPhotoEntity): Step? = when (row.state) {
    SAVED -> Step.STAMP
    STAMPED -> Step.REGISTER
    REGISTERED -> Step.ONEDRIVE
    else -> null
}

/** Registered (the server takes backups only of photos it knows) and not backed up yet. */
private fun backupOwed(row: LocalPhotoEntity) = !row.backupDone && (row.state == REGISTERED || row.state == CONFIRMED)

/** The server shows the photo in OneDrive: delivered by its takeover, or confirmed by this phone earlier. */
internal fun PhotoInfo.delivered() = confirmedAt != null || status == PhotoStatus.confirmed

/** R57: the server gave the photo a newer counter than this phone has (its own delivery met another file of the name). */
internal fun renamedByServer(local: String?, server: String?): Boolean {
    val mine = counterOf(local) ?: return false
    val theirs = counterOf(server) ?: return false
    return theirs > mine
}

/** `POLE_1_39.636130_-119.691104_6018.jpg` → 6018. */
private fun counterOf(fileName: String?): Long? = fileName?.removeSuffix(".jpg")?.substringAfterLast('_')?.toLongOrNull()

private fun readable(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
