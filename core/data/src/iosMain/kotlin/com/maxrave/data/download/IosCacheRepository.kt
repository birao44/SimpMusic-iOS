package com.maxrave.data.download

import com.maxrave.common.Config
import com.maxrave.domain.repository.CacheRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * iOS has one on-disk audio store: the downloads directory ([IosDownloadFiles]). AVPlayer keeps no
 * persistent player cache, and Spotify canvases are not cached on disk, so those report nothing.
 *
 * [getAllCacheKeys] for [Config.DOWNLOAD_CACHE] must list every finished download:
 * `SharedViewModel.checkIsRestoring` unmarks any downloaded song missing from it.
 */
internal object IosCacheRepository : CacheRepository {
    override suspend fun getCacheSize(cacheName: String): Long =
        when (cacheName) {
            Config.DOWNLOAD_CACHE -> withContext(Dispatchers.IO) { IosDownloadFiles.totalSize() }
            else -> 0L
        }

    override fun clearCache(cacheName: String) {
        // The download handler's removeAllDownloads, which Settings calls right after, also cancels
        // what is in flight and resets the song rows.
        if (cacheName == Config.DOWNLOAD_CACHE) IosDownloadFiles.deleteEverything()
    }

    override suspend fun getAllCacheKeys(cacheName: String): List<String> =
        when (cacheName) {
            Config.DOWNLOAD_CACHE -> withContext(Dispatchers.IO) { IosDownloadFiles.downloadedIds() }
            else -> emptyList()
        }

    // No player cache on iOS: a song plays offline only when downloaded.
    override suspend fun getFullyCachedSongIds(): List<String> = emptyList()

    override suspend fun removeCachedSong(videoId: String) = Unit
}
