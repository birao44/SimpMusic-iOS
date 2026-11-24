package com.maxrave.data.mediaservice

import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stand-in until the iOS player exists (Phase 2). Koin builds the [MediaPlayerHandler] eagerly
 * (`createdAtStart`), so this has to be constructible and inert: every state flow holds the same
 * initial value the Android and Desktop handlers start with, and every command does nothing.
 */
internal class NoOpMediaPlayerHandler : MediaPlayerHandler {
    override val player: MediaPlayerInterface = NoOpMediaPlayer()

    override val simpleMediaState: StateFlow<SimpleMediaState> = MutableStateFlow(SimpleMediaState.Initial)
    override val nowPlaying: StateFlow<GenericMediaItem?> = MutableStateFlow(null)
    override val queueData: StateFlow<QueueData?> = MutableStateFlow(QueueData())
    override val controlState: StateFlow<ControlState> =
        MutableStateFlow(
            ControlState(
                isPlaying = false,
                isShuffle = false,
                repeatState = RepeatState.None,
                isLiked = false,
                isNextAvailable = false,
                isPreviousAvailable = false,
                isCrossfading = false,
                volume = 1f,
            ),
        )
    override val nowPlayingState: StateFlow<NowPlayingTrackState> = MutableStateFlow(NowPlayingTrackState.initial())
    override val sleepTimerState: StateFlow<SleepTimerState> = MutableStateFlow(SleepTimerState(false, 0))
    override val skipSegments: StateFlow<List<SponsorSkipSegments>?> = MutableStateFlow(null)
    override val format: StateFlow<NewFormatEntity?> = MutableStateFlow(null)
    override val currentSongIndex: StateFlow<Int> = MutableStateFlow(0)
    override val castState: StateFlow<GenericCastState> = MutableStateFlow(GenericCastState.NOT_CASTING)

    override var onUpdateNotification: (List<GenericCommandButton>) -> Unit = {}
    override var pushPlayerError: (PlayerError) -> Unit = {}
    override var showToast: (ToastType) -> Unit = {}

    override suspend fun onPlayerEvent(playerEvent: PlayerEvent) = Unit

    override fun toggleRadio() = Unit

    override fun toggleLike() = Unit

    override fun like(liked: Boolean) = Unit

    override fun resetSongAndQueue() = Unit

    override fun sleepStart(minutes: Int) = Unit

    override fun sleepStop() = Unit

    override fun removeMediaItem(position: Int) = Unit

    override fun addMediaItem(
        mediaItem: GenericMediaItem,
        playWhenReady: Boolean,
    ) = Unit

    override fun clearMediaItems() = Unit

    override fun addMediaItemList(mediaItemList: List<GenericMediaItem>) = Unit

    override fun playMediaItemInMediaSource(index: Int) = Unit

    override fun currentSongIndex(): Int = 0

    override fun currentOrderIndex(): Int = 0

    override suspend fun swap(
        from: Int,
        to: Int,
    ) = Unit

    override fun resetCrossfade() = Unit

    override fun shufflePlaylist(randomTrackIndex: Int) = Unit

    override fun loadMore() = Unit

    override fun getRelated(videoId: String) = Unit

    override fun setQueueData(queueData: QueueData.Data) = Unit

    override fun getCurrentMediaItem(): GenericMediaItem? = null

    override suspend fun moveItemUp(position: Int) = Unit

    override suspend fun moveItemDown(position: Int) = Unit

    override suspend fun moveItemToPlayNext(position: Int) = Unit

    override fun addFirstMediaItemToIndex(
        mediaItem: GenericMediaItem?,
        index: Int,
    ) = Unit

    override fun reset() = Unit

    override suspend fun load(
        downloaded: Int,
        index: Int?,
    ) = Unit

    override suspend fun loadMoreCatalog(
        listTrack: ArrayList<Track>,
        isAddToQueue: Boolean,
    ) = Unit

    override suspend fun updateCatalog(
        downloaded: Int,
        index: Int?,
    ): Boolean = false

    override fun addQueueToPlayer() = Unit

    override fun loadPlaylistOrAlbum(index: Int?) = Unit

    override fun setCurrentSongIndex(index: Int) = Unit

    override suspend fun playNext(track: Track) = Unit

    override suspend fun <T> loadMediaItem(
        anyTrack: T,
        type: String,
        index: Int?,
    ) = Unit

    override fun getPlayerDuration(): Long = 0L

    override fun getProgress(): Long = 0L

    override fun startProgressUpdate() = Unit

    override fun stopProgressUpdate() = Unit

    override fun startBufferedUpdate() = Unit

    override fun stopBufferedUpdate() = Unit

    override fun mayBeNormalizeVolume() = Unit

    override fun mayBeSaveRecentSong(runBlocking: Boolean) = Unit

    override fun mayBeSavePlaybackState() = Unit

    override fun mayBeRestoreQueue() = Unit

    override suspend fun restoreQueueAndPlay(): Boolean = false

    override fun shouldReleaseOnTaskRemoved(): Boolean = true

    override fun release() = Unit
}

/** A player with nothing loaded that ignores every command. */
private class NoOpMediaPlayer : MediaPlayerInterface {
    override fun play() = Unit

    override fun pause() = Unit

    override fun stop() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) = Unit

    override fun seekBack() = Unit

    override fun seekForward() = Unit

    override fun seekToNext() = Unit

    override fun seekToPrevious() = Unit

    override fun seekToPreviousMediaItem() = Unit

    override fun prepare() = Unit

    override fun setMediaItem(mediaItem: GenericMediaItem) = Unit

    override fun addMediaItem(mediaItem: GenericMediaItem) = Unit

    override fun addMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) = Unit

    override fun removeMediaItem(index: Int) = Unit

    override fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) = Unit

    override fun clearMediaItems() = Unit

    override fun replaceMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) = Unit

    override fun getMediaItemAt(index: Int): GenericMediaItem? = null

    override fun getCurrentMediaTimeLine(): List<GenericMediaItem> = emptyList()

    override fun getUnshuffledIndex(shuffledIndex: Int): Int = shuffledIndex

    override val isPlaying: Boolean = false
    override val currentPosition: Long = 0L
    override val duration: Long = 0L
    override val bufferedPosition: Long = 0L
    override val bufferedPercentage: Int = 0
    override val currentMediaItem: GenericMediaItem? = null
    override val currentMediaItemIndex: Int = 0
    override val mediaItemCount: Int = 0
    override val contentPosition: Long = 0L
    override val playbackState: Int = PlayerConstants.STATE_IDLE

    override fun hasNextMediaItem(): Boolean = false

    override fun hasPreviousMediaItem(): Boolean = false

    override var shuffleModeEnabled: Boolean = false
    override var repeatMode: Int = PlayerConstants.REPEAT_MODE_OFF
    override var playWhenReady: Boolean = false
    override var playbackParameters: GenericPlaybackParameters = GenericPlaybackParameters()
    override val audioSessionId: Int = 0
    override var volume: Float = 1f
    override var sleepFadeFactor: Float = 1f
    override var crossfadeSuppressed: Boolean = false
    override var albumTrackIds: Set<String> = emptySet()
    override var skipSilenceEnabled: Boolean = false

    override fun addListener(listener: MediaPlayerListener) = Unit

    override fun removeListener(listener: MediaPlayerListener) = Unit

    override fun release() = Unit
}
