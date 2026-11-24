package com.maxrave.ktorext.crypto

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock

/** Same tokens as the javax.crypto actual: standard Base64 (padded) of the MAC over the UTF-8 bytes. */
actual class Hmac actual constructor(
    private val algorithm: String,
    secretKey: String,
) {
    private val tokenTtl: Long = 300000 // 5 minutes in milliseconds
    private val key: ByteString = secretKey.encodeUtf8()

    actual fun getMacTimestampPair(uri: String): Pair<String, String> {
        val timestamp = Clock.System.now().toEpochMilliseconds().toString()
        val data = "$timestamp$uri"
        val hmac = this.generateHmac(data)
        return hmac to timestamp
    }

    actual fun generateHmac(data: String): String {
        val bytes = data.encodeUtf8()
        val mac =
            when (algorithm.uppercase()) {
                "HMACSHA1" -> bytes.hmacSha1(key)
                "HMACSHA256" -> bytes.hmacSha256(key)
                "HMACSHA512" -> bytes.hmacSha512(key)
                else -> throw RuntimeException("Failed to initialize HMAC: unsupported algorithm $algorithm")
            }
        return mac.base64()
    }

    actual fun validateHmac(
        data: String,
        hmac: String,
    ): Boolean = generateHmac(data) == hmac

    actual fun isValidTimestamp(timestamp: String): Boolean {
        val requestTime = timestamp.toLongOrNull() ?: return false
        val currentTime = Clock.System.now().toEpochMilliseconds()
        return (currentTime - requestTime) < tokenTtl
    }
}
