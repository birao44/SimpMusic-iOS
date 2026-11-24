package com.maxrave.data.download

import com.maxrave.logger.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.use
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

private const val TAG = "IosDownloadFiles"

/**
 * Where downloaded songs live on iOS, and every file operation on them.
 *
 * `Library/Application Support/downloads/`, excluded from iCloud/iTunes backup (the audio can always
 * be fetched again, and Apple rejects apps that back up re-downloadable media). Application Support
 * rather than Documents: the files are the app's own, not something the user manages in Files.
 *
 * Per videoId:
 * - `<id>.m4a`       the finished song: AAC in MP4, tagged when the export succeeded;
 * - `<id>.dur`       its true length in ms (googlevideo's `dur=`), a backstop for AVPlayer, which
 *                    over-reports the length of an untagged fragmented MP4;
 * - `<id>.part.m4a`  bytes received so far, always a contiguous prefix of the stream;
 * - `<id>.part.len`  the stream's full length, so a resume can tell whether the part still matches;
 * - `<id>.tag.m4a`   the tagging export's output before it is renamed over `<id>.m4a`.
 *
 * Only `<id>.m4a` counts as downloaded; it appears by rename, so it is never half-written.
 */
object IosDownloadFiles {
    private val fs = FileSystem.SYSTEM

    private const val FINAL_EXT = ".m4a"
    private const val DURATION_EXT = ".dur"
    private const val PART_EXT = ".part.m4a"
    private const val PART_LEN_EXT = ".part.len"
    private const val TAG_EXT = ".tag.m4a"

    val directory: String by lazy {
        @OptIn(ExperimentalForeignApi::class)
        val support =
            NSFileManager.defaultManager
                .URLForDirectory(
                    directory = NSApplicationSupportDirectory,
                    inDomain = NSUserDomainMask,
                    appropriateForURL = null,
                    create = true,
                    error = null,
                )?.path
        "${requireNotNull(support) { "No Application Support directory" }}/downloads"
    }

    private val dirPath: Path get() = directory.toPath()

    /** Creates the directory if needed and keeps it out of backups. Cheap; call before writing. */
    fun ensureDirectory() {
        runCatching { fs.createDirectories(dirPath) }.onFailure { Logger.e(TAG, "mkdir failed: ${it.message}") }
        excludeFromBackup(directory)
    }

    @OptIn(ExperimentalForeignApi::class)
    fun excludeFromBackup(path: String) {
        runCatching {
            NSURL.fileURLWithPath(path).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        }
    }

    fun finalPath(videoId: String): String = "$directory/$videoId$FINAL_EXT"

    fun partPath(videoId: String): String = "$directory/$videoId$PART_EXT"

    fun tagPath(videoId: String): String = "$directory/$videoId$TAG_EXT"

    private fun durationPath(videoId: String): String = "$directory/$videoId$DURATION_EXT"

    private fun partLengthPath(videoId: String): String = "$directory/$videoId$PART_LEN_EXT"

    fun sizeOf(path: String): Long = runCatching { fs.metadataOrNull(path.toPath())?.size }.getOrNull() ?: 0L

    fun exists(path: String): Boolean = runCatching { fs.exists(path.toPath()) }.getOrDefault(false)

    fun delete(path: String) {
        runCatching { fs.delete(path.toPath(), mustExist = false) }.onFailure { Logger.w(TAG, "delete failed: ${it.message}") }
    }

    /** Atomic rename within the directory; replaces [to]. */
    fun move(
        from: String,
        to: String,
    ) {
        fs.atomicMove(from.toPath(), to.toPath())
    }

    fun append(
        path: String,
        bytes: ByteArray,
    ) {
        fs.appendingSink(path.toPath(), mustExist = false).use { sink ->
            okio.Buffer().write(bytes).let { buffer -> sink.write(buffer, buffer.size) }
        }
    }

    private fun readText(path: String): String? = runCatching { fs.read(path.toPath()) { readUtf8() } }.getOrNull()

    private fun writeText(
        path: String,
        text: String,
    ) {
        runCatching { fs.write(path.toPath()) { writeUtf8(text) } }.onFailure { Logger.w(TAG, "write failed: ${it.message}") }
    }

    fun isDownloaded(videoId: String): Boolean = sizeOf(finalPath(videoId)) > 0L

    /** `file://` URL of the finished song, or null when it is not (fully) downloaded. */
    @OptIn(ExperimentalForeignApi::class)
    fun localFileUrl(videoId: String): String? {
        if (!isDownloaded(videoId)) return null
        return NSURL.fileURLWithPath(finalPath(videoId)).absoluteString
    }

    /** True length in ms recorded when the song was downloaded; 0 when unknown. */
    fun durationMs(videoId: String): Long = readText(durationPath(videoId))?.trim()?.toLongOrNull() ?: 0L

    fun writeDurationMs(
        videoId: String,
        ms: Long,
    ) {
        if (ms > 0L) writeText(durationPath(videoId), ms.toString()) else delete(durationPath(videoId))
    }

    fun partExpectedLength(videoId: String): Long? = readText(partLengthPath(videoId))?.trim()?.toLongOrNull()

    fun writePartExpectedLength(
        videoId: String,
        length: Long,
    ) = writeText(partLengthPath(videoId), length.toString())

    fun deletePart(videoId: String) {
        delete(partPath(videoId))
        delete(partLengthPath(videoId))
        delete(tagPath(videoId))
    }

    /** Every file belonging to [videoId], finished or not. */
    fun deleteAll(videoId: String) {
        deletePart(videoId)
        delete(finalPath(videoId))
        delete(durationPath(videoId))
    }

    private fun listNames(): List<String> =
        runCatching { fs.list(dirPath).map { it.name } }.getOrDefault(emptyList())

    /** videoIds with a finished `<id>.m4a`. */
    fun downloadedIds(): List<String> =
        listNames()
            .filter { it.endsWith(FINAL_EXT) && !it.endsWith(PART_EXT) && !it.endsWith(TAG_EXT) }
            .map { it.removeSuffix(FINAL_EXT) }
            .filter { isDownloaded(it) }

    /** videoIds with a partial download on disk. */
    fun partialIds(): List<String> = listNames().filter { it.endsWith(PART_EXT) }.map { it.removeSuffix(PART_EXT) }

    /** Leftover tagging outputs: never valid after a restart. */
    fun deleteStaleTagFiles() {
        listNames().filter { it.endsWith(TAG_EXT) }.forEach { delete("$directory/$it") }
    }

    /** Bytes used by everything in the downloads directory. */
    fun totalSize(): Long = listNames().sumOf { sizeOf("$directory/$it") }

    /** Removes every file in the downloads directory; the directory itself stays. */
    fun deleteEverything() {
        listNames().forEach { delete("$directory/$it") }
    }
}
