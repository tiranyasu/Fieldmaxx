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
