package com.fieldmaxx.app.photos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore

/**
 * The phone's upload streams (R59, spec §6.6 "OneDrive gets bandwidth priority"): up to 3 photos go to OneDrive at once,
 * and one backup stream to our server runs alongside them. The backup never waits for a OneDrive result, but OneDrive goes
 * first: while a OneDrive upload is waiting for a stream, the backup holds back its next chunk.
 */
object UploadGate {
    val oneDrive: Semaphore = Semaphore(3)
    val backup: Semaphore = Semaphore(1)

    /** OneDrive uploads waiting for one of the [oneDrive] streams. */
    private val waitingForOneDrive = MutableStateFlow(0)

    /** Runs [block] holding one of the 3 OneDrive streams. */
    suspend fun <T> withOneDrive(block: suspend () -> T): T {
        waitingForOneDrive.update { it + 1 }
        try {
            oneDrive.acquire()
        } finally {
            waitingForOneDrive.update { it - 1 }
        }
        try {
            return block()
        } finally {
            oneDrive.release()
        }
    }

    /** Before each backup chunk: returns once no OneDrive upload is waiting for a stream. */
    suspend fun oneDriveFirst() {
        waitingForOneDrive.first { it == 0 }
    }
}
