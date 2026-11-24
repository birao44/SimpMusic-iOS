package com.maxrave.spotify.auth

import okio.Buffer

/**
 * RFC 6238 TOTP with the parameters kotlin-onetimepassword's GoogleAuthenticator uses on Android and
 * JVM: Base32 secret, HMAC-SHA1, 30-second steps, 6 digits.
 */
actual fun generateTotp(
    secret: String,
    timestamp: Long,
): String {
    val key = base32Decode(secret)
    val counter = timestamp / 1000 / 30
    val message = Buffer().writeLong(counter).readByteString()
    val hash = message.hmacSha1(okio.ByteString.of(*key)).toByteArray()
    val offset = hash.last().toInt() and 0x0F
    val binary =
        ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
    return (binary % 1_000_000).toString().padStart(6, '0')
}

private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

/** RFC 4648 Base32, case-insensitive; padding and characters outside the alphabet are skipped. */
private fun base32Decode(input: String): ByteArray {
    val out = Buffer()
    var buffer = 0
    var bits = 0
    for (char in input.uppercase()) {
        val value = BASE32_ALPHABET.indexOf(char)
        if (value < 0) continue
        buffer = (buffer shl 5) or value
        bits += 5
        if (bits >= 8) {
            out.writeByte((buffer shr (bits - 8)) and 0xFF)
            bits -= 8
        }
    }
    return out.readByteArray()
}
