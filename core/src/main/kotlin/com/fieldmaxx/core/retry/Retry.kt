package com.fieldmaxx.core.retry

private const val FIRST_DELAY_MS = 15_000L
const val MAX_RETRY_DELAY_MS = 30L * 60 * 1000

/**
 * How long to wait before the next delivery attempt, given [attempts] = failed attempts so far (counting the one that
 * just failed): 15 s, 30 s, 60 s, 2 m, 4 m, 8 m, 16 m, then every 30 min. Shared by the server and the phone.
 */
fun nextDelayMs(attempts: Int): Long {
    val doublings = (attempts - 1).coerceIn(0, 7)
    return minOf(FIRST_DELAY_MS shl doublings, MAX_RETRY_DELAY_MS)
}

/**
 * R82: how soon the phone or the server first looks again at a photo whose file SharePoint holds for another upload
 * session (the other side's delivery of the same photo, in progress). It is not a failed attempt.
 */
const val BUSY_RECHECK_MS = 2L * 60 * 1000

/**
 * Ledger item 6: this many BUSY answers in a row are looked again at without counting ([busyRecheckMs] apart). Each BUSY
 * after them counts as a failed attempt, with [BUSY_TOO_LONG] as its reason, so a photo whose file stays locked goes
 * stuck and is reported instead of being rechecked unseen for ever.
 */
const val BUSY_RECHECKS = 6

/** The reason a photo shows once its file has stayed locked through [BUSY_RECHECKS] rechecks. */
const val BUSY_TOO_LONG = "OneDrive keeps this file locked"

/**
 * How long after its [checks]th BUSY answer in a row (counting the one just had) the photo is looked at again: 2 min,
 * doubling, at most 30 min (2, 4, 8, 16, 30, 30… min). Every recheck sends the photo's first chunk again, so the doubling
 * keeps a lock that lasts from costing mobile data. Shared by the server and the phone.
 */
fun busyRecheckMs(checks: Int): Long {
    val doublings = (checks - 1).coerceIn(0, 4)
    return minOf(BUSY_RECHECK_MS shl doublings, MAX_RETRY_DELAY_MS)
}
