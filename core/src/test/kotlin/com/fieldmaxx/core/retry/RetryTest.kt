package com.fieldmaxx.core.retry

import kotlin.test.Test
import kotlin.test.assertEquals

class RetryTest {
    @Test fun doublesFrom15sToA30MinCap() {
        val s = 1000L
        val m = 60 * s
        assertEquals(listOf(15 * s, 30 * s, 60 * s, 2 * m, 4 * m, 8 * m, 16 * m, 30 * m, 30 * m), (1..9).map { nextDelayMs(it) })
        assertEquals(30 * m, nextDelayMs(1000))
        assertEquals(30 * m, nextDelayMs(Int.MAX_VALUE))
    }

    @Test fun noFailureYetWaitsTheFirstStep() = assertEquals(15_000L, nextDelayMs(0))

    /** Ledger item 6: a locked file is looked at again after 2 min, doubling to 30 min; 6 such rechecks go uncounted. */
    @Test fun busyRechecksDoubleFrom2MinToA30MinCap() {
        val m = 60_000L
        assertEquals(listOf(2 * m, 4 * m, 8 * m, 16 * m, 30 * m, 30 * m, 30 * m), (1..7).map { busyRecheckMs(it) })
        assertEquals(BUSY_RECHECK_MS, busyRecheckMs(1))
        assertEquals(BUSY_RECHECK_MS, busyRecheckMs(0))
        assertEquals(30 * m, busyRecheckMs(Int.MAX_VALUE))
        assertEquals(6, BUSY_RECHECKS)
        assertEquals(60 * m + 30 * m, (1..BUSY_RECHECKS).sumOf { busyRecheckMs(it) }, "an hour and a half of rechecks before one counts")
    }
}
