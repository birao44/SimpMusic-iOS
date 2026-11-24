package org.simpmusic.loginsync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val random = SecureRandom()

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

internal actual fun aesGcmEncrypt(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    plain: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(LoginSyncWire.TAG_BITS, nonce))
    cipher.updateAAD(aad)
    return cipher.doFinal(plain)
}

internal actual fun aesGcmDecrypt(
    key: ByteArray,
    aad: ByteArray,
    sealed: ByteArray,
): ByteArray? =
    try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(LoginSyncWire.TAG_BITS, sealed, 0, LoginSyncWire.NONCE_BYTES)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        cipher.updateAAD(aad)
        cipher.doFinal(sealed, LoginSyncWire.NONCE_BYTES, sealed.size - LoginSyncWire.NONCE_BYTES)
    } catch (e: GeneralSecurityException) {
        null
    }

internal actual fun gzipBytes(plain: ByteArray): ByteArray =
    ByteArrayOutputStream().use { out ->
        GZIPOutputStream(out).use { it.write(plain) }
        out.toByteArray()
    }

internal actual fun gunzipBytes(
    packed: ByteArray,
    maxBytes: Int,
): ByteArray? =
    GZIPInputStream(ByteArrayInputStream(packed)).use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > maxBytes) return null
        }
        out.toByteArray()
    }

// Cellular, VPN and tunnel interfaces on Android and desktops: sweeping them would knock on strangers.
private val NOT_A_LAN = listOf("rmnet", "ccmni", "pdp", "v4-", "tun", "ppp", "dummy", "tailscale", "utun", "wg")

private fun interfaces(): List<NetworkInterface> =
    runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
        .getOrDefault(emptyList())
        .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }

internal actual fun lanAddresses(): List<String> =
    interfaces()
        .filterNot { nic -> NOT_A_LAN.any { nic.name.startsWith(it) } }
        .flatMap { nic -> nic.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filterNot { it.isLinkLocalAddress }
        .mapNotNull { it.hostAddress }

internal actual fun broadcastAddresses(): List<String> =
    interfaces()
        .flatMap { nic -> nic.interfaceAddresses.orEmpty() }
        .mapNotNull { it.broadcast?.hostAddress }

internal actual fun <T : Any> udpProbe(
    destinations: List<String>,
    port: Int,
    payload: ByteArray,
    windowMillis: Long,
    accept: (host: String, data: ByteArray) -> T?,
): T? =
    DatagramSocket().use { socket ->
        socket.broadcast = true
        socket.soTimeout = 300
        destinations.forEach { address ->
            runCatching { socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(address), port)) }
        }
        val deadline = System.currentTimeMillis() + windowMillis
        val buffer = ByteArray(8192)
        while (System.currentTimeMillis() < deadline) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            }
            val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val host = packet.address?.hostAddress ?: continue
            accept(host, data)?.let { return@use it }
        }
        null
    }
