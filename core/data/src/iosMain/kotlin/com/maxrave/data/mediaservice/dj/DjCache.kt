package com.maxrave.data.mediaservice.dj

import com.maxrave.data.download.IosDownloadFiles
import com.maxrave.logger.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

private const val TAG = "DjCache"

/**
 * `Library/Caches/dj/`: everything the DJ transitions need, all of it re-creatable (iOS may purge
 * Caches when storage runs low; the pipeline then simply rebuilds what it needs).
 *
 * - `<id>.m4a`            the whole song (AAC), unless it is downloaded: then the download is read;
 * - `<id>.json`           its [com.maxrave.domain.mediaservice.djmix.DjTrackAnalysis];
 * - `<a>__<b>.wav`        the rendered A→B segment, 44.1 kHz stereo 16-bit;
 * - `<a>__<b>.mix.json`   its [DjTransitionMeta] (written last, so its presence means "ready").
 *
 * Size-capped at [CAP_BYTES] with LRU eviction by modification time ([touch] on every use); files
 * of the ids in the protected set (current, next, the pair being played) are never evicted.
 */
internal object DjCache {
    private val fs = FileSystem.SYSTEM

    const val CAP_BYTES = 400L * 1024 * 1024

    val directory: String by lazy {
        @OptIn(ExperimentalForeignApi::class)
        val caches =
            NSFileManager.defaultManager
                .URLForDirectory(
                    directory = NSCachesDirectory,
                    inDomain = NSUserDomainMask,
                    appropriateForURL = null,
                    create = true,
                    error = null,
                )?.path
        "${requireNotNull(caches) { "No Caches directory" }}/dj"
    }

    private val dirPath: Path get() = directory.toPath()

    fun ensureDirectory() {
        runCatching { fs.createDirectories(dirPath) }.onFailure { Logger.e(TAG, "mkdir failed: ${it.message}") }
    }

    /** Own audio file of [videoId] in the cache (may not exist). */
    fun songPath(videoId: String): String = "$directory/$videoId.m4a"

    fun partPath(videoId: String): String = "$directory/$videoId.part.m4a"

    fun exportPath(videoId: String): String = "$directory/$videoId.export.m4a"

    fun analysisPath(videoId: String): String = "$directory/$videoId.json"

    fun mixPath(
        a: String,
        b: String,
    ): String = "$directory/${a}__$b.wav"

    fun mixMetaPath(
        a: String,
        b: String,
    ): String = "$directory/${a}__$b.mix.json"

    /** The file to decode for [videoId]: the downloaded song when there is one, else the cached copy; null when neither exists. */
    fun audioFileFor(videoId: String): String? {
        if (IosDownloadFiles.isDownloaded(videoId)) return IosDownloadFiles.finalPath(videoId)
        val own = songPath(videoId)
        return own.takeIf { sizeOf(it) > 0L }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun fileUrl(path: String): String = NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"

    fun sizeOf(path: String): Long = runCatching { fs.metadataOrNull(path.toPath())?.size }.getOrNull() ?: 0L

    fun exists(path: String): Boolean = sizeOf(path) > 0L

    fun delete(path: String) {
        runCatching { fs.delete(path.toPath(), mustExist = false) }
    }

    fun move(
        from: String,
        to: String,
    ) {
        fs.atomicMove(from.toPath(), to.toPath())
    }

    fun readText(path: String): String? = runCatching { fs.read(path.toPath()) { readUtf8() } }.getOrNull()

    /** Written to a temp name and renamed, so a reader never sees half a file. */
    fun writeText(
        path: String,
        text: String,
    ) {
        val tmp = "$path.tmp"
        runCatching {
            fs.write(tmp.toPath()) { writeUtf8(text) }
            fs.atomicMove(tmp.toPath(), path.toPath())
        }.onFailure { Logger.w(TAG, "write $path failed: ${it.message}") }
    }

    @OptIn(ExperimentalForeignApi::class)
    /** Marks [path] as just used, for the LRU order. Files outside the cache (downloads) are left alone. */
    fun touch(path: String) {
        if (!path.startsWith(directory)) return
        runCatching {
            NSFileManager.defaultManager.setAttributes(
                mapOf<Any?, Any?>(NSFileModificationDate to NSDate()),
                ofItemAtPath = path,
                error = null,
            )
        }
    }

    /** Which videoId(s) a cache file name belongs to. */
    private fun idsOf(name: String): List<String> {
        val stem =
            when {
                name.endsWith(".mix.json") -> name.removeSuffix(".mix.json")
                else -> name.substringBefore('.')
            }
        return stem.split("__")
    }

    /**
     * Deletes the least recently used files until the cache is under [CAP_BYTES]. Files that belong
     * to an id in [protectedIds] (both ids, for a transition) are kept whatever their age.
     */
    fun evict(protectedIds: Set<String>) {
        val entries =
            runCatching { fs.list(dirPath) }.getOrDefault(emptyList()).mapNotNull { path ->
                val meta = runCatching { fs.metadataOrNull(path) }.getOrNull() ?: return@mapNotNull null
                Triple(path, meta.size ?: 0L, meta.lastModifiedAtMillis ?: 0L)
            }
        var total = entries.sumOf { it.second }
        if (total <= CAP_BYTES) return
        for ((path, size, _) in entries.sortedBy { it.third }) {
            if (total <= CAP_BYTES) break
            val ids = idsOf(path.name)
            // A transition is kept when either of its songs is protected: the pair being played.
            if (ids.any { it in protectedIds }) continue
            runCatching { fs.delete(path, mustExist = false) }
            total -= size
            Logger.d(TAG, "Evicted ${path.name} ($size bytes)")
        }
    }
}
