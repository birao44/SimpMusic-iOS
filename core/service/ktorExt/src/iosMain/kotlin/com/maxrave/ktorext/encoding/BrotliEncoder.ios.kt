package com.maxrave.ktorext.encoding

import io.ktor.util.ContentEncoder
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlin.coroutines.CoroutineContext

/**
 * Lets the clients keep advertising `br`, as on Android and JVM. NSURLSession, under the Darwin engine,
 * decodes brotli bodies itself before Ktor sees them, so decoding here passes the bytes through.
 * Like the JVM encoder, it cannot compress.
 */
object BrotliEncoder : ContentEncoder {
    override val name: String = "br"

    override fun decode(
        source: ByteReadChannel,
        coroutineContext: CoroutineContext,
    ): ByteReadChannel = source

    override fun encode(
        source: ByteReadChannel,
        coroutineContext: CoroutineContext,
    ): ByteReadChannel = throw UnsupportedOperationException("Brotli encoding is not supported.")

    override fun encode(
        source: ByteWriteChannel,
        coroutineContext: CoroutineContext,
    ): ByteWriteChannel = throw UnsupportedOperationException("Brotli encoding is not supported.")
}

actual fun createBrotliEncoder(): ContentEncoder = BrotliEncoder
