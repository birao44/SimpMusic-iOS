package org.simpmusic.loginsync

/*
 * The few things login and library sync need from the platform. Android and Desktop share one set of
 * actuals in jvmSharedMain (java.security, javax.crypto, java.util.zip, java.net); iOS takes no part
 * in either sync, so its actuals say "unsupported" and find nothing.
 */

/** [size] bytes from the platform's cryptographically secure generator. */
internal expect fun secureRandomBytes(size: Int): ByteArray

/** AES-256-GCM: `ciphertext || tag(16)` of [plain] under [key] and [nonce], bound to [aad]. */
internal expect fun aesGcmEncrypt(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    plain: ByteArray,
): ByteArray

/** Opens `nonce(12) || ciphertext || tag(16)`; null when it was not sealed with [key] for [aad]. */
internal expect fun aesGcmDecrypt(
    key: ByteArray,
    aad: ByteArray,
    sealed: ByteArray,
): ByteArray?

internal expect fun gzipBytes(plain: ByteArray): ByteArray

/** Null once the output passes [maxBytes]. */
internal expect fun gunzipBytes(
    packed: ByteArray,
    maxBytes: Int,
): ByteArray?

/** This device's IPv4 addresses on the networks a computer could be on (no cellular, VPN or tunnel). */
internal expect fun lanAddresses(): List<String>

/** The broadcast address of every network this device is on. */
internal expect fun broadcastAddresses(): List<String>

/**
 * Sends [payload] as one UDP datagram to each of [destinations] on [port], then offers every datagram
 * received within [windowMillis] to [accept], with the sender's address, until [accept] returns a value.
 * Blocking: call it on an IO dispatcher.
 */
internal expect fun <T : Any> udpProbe(
    destinations: List<String>,
    port: Int,
    payload: ByteArray,
    windowMillis: Long,
    accept: (host: String, data: ByteArray) -> T?,
): T?
