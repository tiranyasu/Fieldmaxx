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
}
