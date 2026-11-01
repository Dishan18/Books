package io.github.dishan18.books.manga.source.net

import io.github.dishan18.books.manga.source.api.SourceError
import io.github.dishan18.books.manga.source.api.SourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Lenient JSON shared by all sources: unknown fields are ignored so API additions don't break us. */
val SourceJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/** Executes the call without blocking a coroutine thread; cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, _, _ -> response.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
}

/** Throws a typed [SourceException] for unsuccessful responses (closing them). */
fun Response.requireSuccess(): Response {
    if (isSuccessful) return this
    val error = when (code) {
        401 -> SourceError.AuthRequired
        403 -> SourceError.Forbidden
        404, 410 -> SourceError.NotFound
        429 -> SourceError.RateLimited(header("Retry-After")?.trim()?.toLongOrNull())
        else -> SourceError.Http(code)
    }
    close()
    throw SourceException(error)
}

suspend fun <T> OkHttpClient.getJson(request: Request, deserializer: DeserializationStrategy<T>): T {
    val response = newCall(request).await().requireSuccess()
    return withContext(Dispatchers.IO) {
        response.use { SourceJson.decodeFromString(deserializer, it.body.string()) }
    }
}

/**
 * Streams the response body into [destination] via a temporary file, so a half-written image is
 * never left behind under the final name.
 */
suspend fun OkHttpClient.download(
    request: Request,
    destination: File,
    onProgress: (Long, Long) -> Unit,
): Response {
    val response = newCall(request).await().requireSuccess()
    withContext(Dispatchers.IO) {
        response.use {
            val body = it.body
            val total = body.contentLength()
            val temp = File(destination.parentFile, destination.name + ".part")
            destination.parentFile?.mkdirs()
            try {
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            onProgress(read, total)
                        }
                    }
                }
                if (temp.length() == 0L) throw SourceException(SourceError.InvalidResponse)
                if (!temp.renameTo(destination)) {
                    temp.copyTo(destination, overwrite = true)
                    temp.delete()
                }
            } catch (e: Exception) {
                temp.delete()
                throw e
            }
        }
    }
    return response
}
