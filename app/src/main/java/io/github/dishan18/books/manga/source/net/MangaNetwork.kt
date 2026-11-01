package io.github.dishan18.books.manga.source.net

import android.content.Context
import io.github.dishan18.books.BuildConfig
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One connection pool and HTTP cache for the whole app. Each source derives its own client from
 * [base] with its own rate limits and auth, so sources can't interfere with each other.
 */
class MangaNetwork(context: Context) {
    val userAgent = "Books/${BuildConfig.VERSION_NAME} (Android; +https://github.com/Dishan18/Books)"

    val base: OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "http"), 32L * 1024 * 1024))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(UserAgentInterceptor(userAgent))
        .build()

    fun clientFor(vararg interceptors: Interceptor): OkHttpClient =
        base.newBuilder()
            .apply { interceptors.forEach { addInterceptor(it) } }
            .addInterceptor(RetryAfterInterceptor())
            .build()
}
