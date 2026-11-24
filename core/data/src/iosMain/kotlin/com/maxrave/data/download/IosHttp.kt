package com.maxrave.data.download

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One HTTP answer, body already in memory (the callers ask for a few MB at most). */
internal class HttpChunk(
    val status: Int,
    val body: ByteArray,
    /** The `/total` of a `Content-Range` header, when the server sent one. */
    val totalLength: Long?,
)

internal class HttpFailure(
    message: String,
) : Exception(message)

/**
 * Plain NSURLSession GETs for downloads. A session of its own (not Ktor's) so the timeouts suit
 * multi-megabyte bodies and nothing here competes with the API client's connection pool.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosHttp {
    private val session: NSURLSession by lazy {
        val config = NSURLSessionConfiguration.defaultSessionConfiguration
        config.timeoutIntervalForRequest = 30.0
        config.timeoutIntervalForResource = 300.0
        config.HTTPMaximumConnectionsPerHost = 8
        NSURLSession.sessionWithConfiguration(config)
    }

    private val CONTENT_RANGE_TOTAL = Regex("""/(\d+)\s*$""")

    /** GET [url], the bytes [from]..[to] inclusive when given. Throws [HttpFailure] on transport errors. */
    suspend fun get(
        url: String,
        from: Long? = null,
        to: Long? = null,
    ): HttpChunk =
        suspendCancellableCoroutine { cont ->
            val nsUrl = NSURL.URLWithString(url)
            if (nsUrl == null) {
                cont.resumeWithException(HttpFailure("Bad URL"))
                return@suspendCancellableCoroutine
            }
            val request = NSMutableURLRequest.requestWithURL(nsUrl) as NSMutableURLRequest
            if (from != null) {
                request.setValue("bytes=$from-${to ?: ""}", forHTTPHeaderField = "Range")
            }
            val task =
                session.dataTaskWithRequest(request) { data, response, error ->
                    if (error != null) {
                        cont.resumeWithException(HttpFailure(error.localizedDescription))
                        return@dataTaskWithRequest
                    }
                    val http = response as? NSHTTPURLResponse
                    val total =
                        http
                            ?.valueForHTTPHeaderField("Content-Range")
                            ?.let { CONTENT_RANGE_TOTAL.find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
                    cont.resume(
                        HttpChunk(
                            status = http?.statusCode?.toInt() ?: 0,
                            body = data?.toByteArray() ?: ByteArray(0),
                            totalLength = total,
                        ),
                    )
                }
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
        }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
    }
