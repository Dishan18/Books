package io.github.dishan18.books.manga.source.net

import org.junit.Assert.assertEquals
import org.junit.Test

class RateLimiterTest {

    private var now = 0L
    private val sleeps = mutableListOf<Long>()
    private val limiter = RateLimiter(
        permits = 3,
        periodMillis = 1_000,
        clock = { now },
        sleeper = { sleeps += it; now += it },
    )

    @Test
    fun permitsWithinBudgetDoNotWait() {
        repeat(3) { limiter.acquire() }
        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun extraRequestWaitsUntilOldestPermitExpires() {
        repeat(3) { limiter.acquire(); now += 100 }
        // Permits issued at 0, 100, 200; now = 300, so the next waits until 1000.
        limiter.acquire()
        assertEquals(listOf(700L), sleeps)
        assertEquals(1_000L, now)
    }

    @Test
    fun windowSlidesSoSteadyTrafficIsSpacedOut() {
        repeat(9) { limiter.acquire() }
        // Three bursts of three, each a full period apart.
        assertEquals(2_000L, now)
    }
}
