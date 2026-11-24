package org.simpmusic.loginsync

// iOS takes no part in login or library sync (Android sends, Desktop receives). Nothing on iOS calls
// into this module; these actuals exist so it compiles, and refuse or find nothing if it ever does.

private const val UNSUPPORTED = "Login and library sync are not supported on iOS"

internal actual fun secureRandomBytes(size: Int): ByteArray = throw UnsupportedOperationException(UNSUPPORTED)

internal actual fun aesGcmEncrypt(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    plain: ByteArray,
): ByteArray = throw UnsupportedOperationException(UNSUPPORTED)

internal actual fun aesGcmDecrypt(
    key: ByteArray,
    aad: ByteArray,
    sealed: ByteArray,
): ByteArray? = null

internal actual fun gzipBytes(plain: ByteArray): ByteArray = throw UnsupportedOperationException(UNSUPPORTED)

internal actual fun gunzipBytes(
    packed: ByteArray,
    maxBytes: Int,
): ByteArray? = null

internal actual fun lanAddresses(): List<String> = emptyList()

internal actual fun broadcastAddresses(): List<String> = emptyList()

internal actual fun <T : Any> udpProbe(
    destinations: List<String>,
    port: Int,
    payload: ByteArray,
    windowMillis: Long,
    accept: (host: String, data: ByteArray) -> T?,
): T? = null
