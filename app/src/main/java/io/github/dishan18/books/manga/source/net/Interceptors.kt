package io.github.dishan18.books.manga.source.net

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.InterruptedIOException

/** Identifies the app to sources, as API guidelines (e.g. MangaDex) ask. */
class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
}

/** Applies the first limiter whose predicate matches each request. */
class RateLimitInterceptor(private val rules: List<Pair<(Request) -> Boolean, RateLimiter>>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        try {
            rules.firstOrNull { (matches, _) -> matches(request) }?.second?.acquire()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Cancelled while rate limited")
        }
        return chain.proceed(request)
    }
}

/**
 * Honours a short `Retry-After` on 429/503 by waiting and retrying once. Longer waits are
 * returned to the caller as a rate-limit error instead of blocking.
 */
class RetryAfterInterceptor(private val maxWaitSeconds: Long = 10) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code != 429 && response.code != 503) return response
        val wait = response.header("Retry-After")?.trim()?.toLongOrNull() ?: return response
        if (wait !in 0..maxWaitSeconds) return response
        response.close()
        try {
            Thread.sleep(wait * 1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Cancelled while waiting to retry")
        }
        return chain.proceed(chain.request())
    }
}

/** Adds an auth header to requests for one host only, so credentials never leak elsewhere. */
class HostAuthInterceptor(private val host: String, private val headers: Map<String, String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.equals(host, ignoreCase = true) || headers.isEmpty()) return chain.proceed(request)
        val builder = request.newBuilder()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return chain.proceed(builder.build())
    }
}
