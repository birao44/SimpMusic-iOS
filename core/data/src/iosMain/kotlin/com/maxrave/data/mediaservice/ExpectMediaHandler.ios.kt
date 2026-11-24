package com.maxrave.data.mediaservice

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.player.ios.NativePlayerRegistry
import com.maxrave.domain.repository.AnalyticsRepository
import com.maxrave.domain.repository.DiscordRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope

// Koin creates the handler at start, so this must not throw. Swift sets NativePlayerRegistry.engine
// before MainViewController() runs; if it somehow has not, fall back to the no-op handler so the app
// still starts (without playback) instead of crashing.
actual fun createMediaServiceHandler(
    dataStoreManager: DataStoreManager,
    songRepository: SongRepository,
    streamRepository: StreamRepository,
    localPlaylistRepository: LocalPlaylistRepository,
    analyticsRepository: AnalyticsRepository,
    discordRepository: DiscordRepository,
    coroutineScope: CoroutineScope,
): MediaPlayerHandler {
    if (NativePlayerRegistry.engine == null) {
        Logger.e("createMediaServiceHandler", "NativePlayerRegistry.engine is null; playback is disabled")
        return NoOpMediaPlayerHandler()
    }
    return IosMediaPlayerHandlerImpl(
        dataStoreManager = dataStoreManager,
        songRepository = songRepository,
        streamRepository = streamRepository,
        localPlaylistRepository = localPlaylistRepository,
        analyticsRepository = analyticsRepository,
        discordRepository = discordRepository,
        coroutineScope = coroutineScope,
    )
}
