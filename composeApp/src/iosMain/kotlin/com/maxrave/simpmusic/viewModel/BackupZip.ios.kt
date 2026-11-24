package com.maxrave.simpmusic.viewModel

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import platform.zlib.crc32

/*
 * A minimal zip writer for the iOS backup. java.util.zip does not exist on Kotlin/Native and okio
 * only reads zips (openZip), so entries are written STORED (no compression) with sizes and CRC in
 * the local header and no data descriptor. That is a plain, valid zip: Android's and Desktop's
 * ZipInputStream read it, and so does okio's openZip on iOS, which is how restore reads the
 * DEFLATE-compressed backups the other platforms write.
 */
internal class StoredZipWriter(
    private val sink: BufferedSink,
) : AutoCloseable {
    private class CentralEntry(
        val name: ByteArray,
        val crc: Int,
        val size: Int,
        val offset: Int,
    )

    private val entries = mutableListOf<CentralEntry>()
    private var offset = 0L

    fun addFile(
        name: String,
        file: Path,
    ) {
        val fs = FileSystem.SYSTEM
        val size = fs.metadata(file).size ?: error("No size for $file")
        // ZIP32 limits; a backup this large would not fit in memory-sized tooling anyway.
        require(size < 0xFFFF_FFFFL && offset < 0xFFFF_FFFFL) { "Backup entry too large for a zip32 archive: $name" }
        val crc = crc32Of(file)
        val nameBytes = name.encodeToByteArray()
        val entry = CentralEntry(nameBytes, crc, size.toInt(), offset.toInt())

        sink.writeIntLe(0x04034b50) // local file header signature
        sink.writeShortLe(VERSION_NEEDED)
        sink.writeShortLe(FLAG_UTF8)
        sink.writeShortLe(0) // method: stored
        sink.writeShortLe(DOS_TIME)
        sink.writeShortLe(DOS_DATE)
        sink.writeIntLe(entry.crc)
        sink.writeIntLe(entry.size) // compressed size
        sink.writeIntLe(entry.size) // uncompressed size
        sink.writeShortLe(nameBytes.size)
        sink.writeShortLe(0) // extra length
        sink.write(nameBytes)
        fs.source(file).use { sink.writeAll(it) }

        offset += 30L + nameBytes.size + size
        entries += entry
    }

    override fun close() {
        sink.use {
            val centralStart = offset
            var centralSize = 0L
            for (entry in entries) {
                sink.writeIntLe(0x02014b50) // central directory header signature
                sink.writeShortLe(VERSION_NEEDED) // version made by
                sink.writeShortLe(VERSION_NEEDED)
                sink.writeShortLe(FLAG_UTF8)
                sink.writeShortLe(0) // method: stored
                sink.writeShortLe(DOS_TIME)
                sink.writeShortLe(DOS_DATE)
                sink.writeIntLe(entry.crc)
                sink.writeIntLe(entry.size)
                sink.writeIntLe(entry.size)
                sink.writeShortLe(entry.name.size)
                sink.writeShortLe(0) // extra length
                sink.writeShortLe(0) // comment length
                sink.writeShortLe(0) // disk number start
                sink.writeShortLe(0) // internal attributes
                sink.writeIntLe(0) // external attributes
                sink.writeIntLe(entry.offset)
                sink.write(entry.name)
                centralSize += 46L + entry.name.size
            }
            sink.writeIntLe(0x06054b50) // end of central directory signature
            sink.writeShortLe(0) // this disk
            sink.writeShortLe(0) // disk with the central directory
            sink.writeShortLe(entries.size)
            sink.writeShortLe(entries.size)
            sink.writeIntLe(centralSize.toInt())
            sink.writeIntLe(centralStart.toInt())
            sink.writeShortLe(0) // comment length
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun crc32Of(file: Path): Int {
        var crc = 0uL
        val buffer = ByteArray(64 * 1024)
        FileSystem.SYSTEM.source(file).buffer().use { source ->
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read <= 0) break
                crc = buffer.usePinned { crc32(crc, it.addressOf(0).reinterpret<UByteVar>(), read.toUInt()) }
            }
        }
        return crc.toInt()
    }

    private companion object {
        const val VERSION_NEEDED = 20
        const val FLAG_UTF8 = 0x0800
        const val DOS_TIME = 0

        // 1980-01-01, the DOS epoch: a valid date, unlike 0. The real time is in the file name.
        const val DOS_DATE = (0 shl 9) or (1 shl 5) or 1
    }
}
