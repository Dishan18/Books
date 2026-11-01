package io.github.dishan18.books.manga.source.api

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** What went wrong talking to a source, in terms the UI can explain. */
sealed class SourceError(val message: String) {
    data object Offline : SourceError("You're offline")
    data object Timeout : SourceError("The source took too long to respond")
    data object Unreachable : SourceError("Couldn't reach the source")
    data object Insecure : SourceError("Secure connection failed")
    data object AuthRequired : SourceError("Sign-in details are missing or wrong")
    data object Forbidden : SourceError("Access denied by the source")
    data object NotFound : SourceError("Not found on the source")
    data class RateLimited(val retryAfterSeconds: Long?) : SourceError("Too many requests, try again shortly")
    data class Http(val code: Int) : SourceError("The source returned an error ($code)")
    data object InvalidResponse : SourceError("The source sent something unexpected")
    data class Other(val detail: String) : SourceError(detail)
}

class SourceException(val error: SourceError, cause: Throwable? = null) : Exception(error.message, cause)

/** Maps any failure from a source call to a [SourceException]; cancellation is passed through. */
fun Throwable.toSourceException(): SourceException = when (this) {
    is SourceException -> this
    is UnknownHostException -> SourceException(SourceError.Offline, this)
    is SocketTimeoutException -> SourceException(SourceError.Timeout, this)
    is ConnectException -> SourceException(SourceError.Unreachable, this)
    is SSLException -> SourceException(SourceError.Insecure, this)
    is SerializationException -> SourceException(SourceError.InvalidResponse, this)
    is InterruptedIOException -> SourceException(SourceError.Timeout, this)
    is IOException -> SourceException(SourceError.Unreachable, this)
    else -> SourceException(SourceError.Other(message ?: javaClass.simpleName), this)
}

/** Runs a source call, returning a failed [Result] with a [SourceException] instead of throwing. */
suspend inline fun <T> runSource(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e.toSourceException())
    }

val Throwable.sourceError: SourceError
    get() = toSourceException().error
