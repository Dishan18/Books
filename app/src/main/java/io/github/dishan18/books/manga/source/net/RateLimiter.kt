package io.github.dishan18.books.manga.source.net

import java.util.ArrayDeque

/**
 * Sliding-window limiter: at most [permits] acquisitions in any [periodMillis] window.
 * Blocks the calling thread (OkHttp interceptors run on its dispatcher threads).
 */
class RateLimiter(
    private val permits: Int,
    private val periodMillis: Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    init {
        require(permits > 0 && periodMillis > 0)
    }

    private val issued = ArrayDeque<Long>(permits)

    /** Waits until a permit is free, then takes it. */
    fun acquire() {
        while (true) {
            val wait = synchronized(this) {
                val now = clock()
                while (issued.isNotEmpty() && now - issued.peekFirst()!! >= periodMillis) issued.pollFirst()
                if (issued.size < permits) {
                    issued.addLast(now)
                    return
                }
                periodMillis - (now - issued.peekFirst()!!)
            }
            sleeper(wait.coerceAtLeast(1))
        }
    }
}
