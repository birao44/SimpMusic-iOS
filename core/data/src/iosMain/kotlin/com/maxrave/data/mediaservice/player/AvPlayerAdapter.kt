package com.maxrave.data.mediaservice.player

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.data.mediaservice.dj.DjCache
import com.maxrave.data.mediaservice.dj.DjMixPipeline
import com.maxrave.data.mediaservice.dj.DjReadyTransition
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.djmix.DjMatch
import com.maxrave.domain.mediaservice.djmix.DjTrackAnalysis
import com.maxrave.domain.mediaservice.djmix.matchTraits
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.mediaservice.player.ios.NativePlayerConstants
import com.maxrave.domain.mediaservice.player.ios.NativePlayerEngine
import com.maxrave.domain.mediaservice.player.ios.NativePlayerEngineListener
import com.maxrave.domain.mediaservice.player.ios.NativeVideoOutput
import com.maxrave.domain.data.player.AudioOutput
import com.maxrave.domain.extension.isVideo
import com.maxrave.domain.repository.StreamRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "AvPlayerAdapter"

private const val NO_HANDLE = NativePlayerConstants.NO_HANDLE

/**
 * iOS implementation of [MediaPlayerInterface], driving the Swift AVPlayer engine through
 * [NativePlayerEngine].
 *
 * Structural port of `MpvPlayerAdapter` (core/media/media-jvm): the queue, shuffle order, repeat
 * modes, precaching, crossfade stepping, commit-incoming, URL resolution and 403 retry handling are
 * carried over with the same state machine, method bodies and names. Each `MpvPlayer` object becomes
 * an engine handle (`Int`, [NativePlayerConstants.NO_HANDLE] for none); identity checks become handle
 * comparisons, and promoting a precached or fading-in player to current keeps its handle, exactly as
 * the mpv code keeps the same object. The contract with Swift is `docs/ios-player-bridge.md`.
 *
 * Left out, because the engine has no equivalent (spec section 4): the DJ / BPM / Camelot / AutoMix
 * machinery, equalizer, effects, 8D, waveform, pitch and memory trimming. Video is not frames as on
 * desktop: a video track plays ONE muxed URL on a handle created with `video = true`, and the
 * composeApp views attach an `AVPlayerLayer` to the handle published in [NativeVideoOutput]. Audio
 * outputs list the system route; picking one opens the system AirPlay picker ([AvAudioRoutes]).
 *
 * Threading. The mpv adapter relies on its scope being one service thread. This one does not assume
 * that of the scope it is given: it derives its own serial context ([SerialDispatcher] over
 * `Dispatchers.Default.limitedParallelism(1)`, child of the given scope's Job) and every state
 * mutation runs there. Engine callbacks arrive on Main and are re-dispatched onto it. Public calls
 * keep the mpv shape: the ones mpv launches still launch; the ones mpv runs synchronously on the
 * caller's thread (addMediaItem, the shuffle/repeat setters, ...) run synchronously ON the serial
 * context through [runSerial], so the caller still sees the result on return. Reads from other
 * threads come from `@Volatile` scalars and an immutable [QueueView] snapshot published after every
 * serial task.
 */
class AvPlayerAdapter(
    coroutineScope: CoroutineScope,
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
    private val engine: NativePlayerEngine,
) : MediaPlayerInterface {
    // Internal state enum for proper state machine
    private enum class InternalState {
        IDLE,
        PREPARING,
        READY,
        PLAYING,
        PAUSED,
        ENDED,
        ERROR,
    }

    private fun InternalState.isInReadyState(): Boolean = this == InternalState.READY || this == InternalState.PLAYING || this == InternalState.PAUSED

    // ========== Serial context ==========

    private val serialDispatcher =
        SerialDispatcher(
            owner = this,
            inner = Dispatchers.Default.limitedParallelism(1),
            afterTask = ::onSerialTaskFinished,
        )

    /**
     * Named like the mpv field so the ported bodies read the same; it is NOT the scope passed in.
     * Its Job is a child of the given scope's, so cancelling that scope stops this adapter too, while
     * [release] cancels only this one and leaves the caller's scope alone.
     */
    private val coroutineScope: CoroutineScope =
        CoroutineScope(
            coroutineScope.coroutineContext + SupervisorJob(coroutineScope.coroutineContext[Job]) + serialDispatcher,
        )

    /**
     * Run [block] on the serial context and return its result. Inline when already there (a listener
     * callback calling back into the player, or one adapter method calling another); otherwise the
     * calling thread waits for its turn. Never called from code that the serial context itself waits
     * on, and the serial context never blocks on another thread, so this cannot deadlock.
     */
    private fun <T> runSerial(block: () -> T): T =
        if (serialDispatcher.isCurrent()) {
            block()
        } else {
            runBlocking(serialDispatcher) {
                val result = block()
                // The dispatcher publishes after the task, but runBlocking wakes the caller when the
                // block completes — which is inside the task. Publish first so the caller's next read
                // already sees what it just did.
                onSerialTaskFinished()
                result
            }
        }

    /** Live queue on the serial context, the published snapshot everywhere else. */
    private fun view(): QueueView = if (serialDispatcher.isCurrent()) liveQueueView() else queueSnapshot

    private fun liveQueueView(): QueueView =
        QueueView(
            playlist = playlist,
            currentIndex = localCurrentMediaItemIndex,
            shuffleEnabled = internalShuffleModeEnabled,
            shuffleOrder = shuffleOrder,
            shuffleIndices = shuffleIndices,
            repeatMode = internalRepeatMode,
        )

    @Volatile
    private var queueSnapshot: QueueView = QueueView.EMPTY

    // ========== State Management ==========

    /** Copy-on-write, so the serial context iterates a stable list while other threads add/remove. */
    @Volatile
    private var listeners: List<MediaPlayerListener> = emptyList()

    @Volatile
    private var currentPlayer: Int = NO_HANDLE

    @Volatile
    private var internalState = InternalState.IDLE

    @Volatile
    private var internalPlayWhenReady = true

    @Volatile
    private var internalVolume = 1.0f

    /** Sleep-timer fade gain, kept apart from [internalVolume] so the user's level survives it. */
    @Volatile
    private var internalSleepFadeFactor = 1.0f

    @Volatile
    private var internalRepeatMode = PlayerConstants.REPEAT_MODE_OFF

    @Volatile
    private var internalShuffleModeEnabled = false

    @Volatile
    private var internalPlaybackSpeed = 1.0f

    /**
     * Where a seek asked from another thread is going, until the serial context has handed it to the
     * engine (which from then on answers with the target itself). Keeps the seek bar from snapping
     * back for the length of one hop, which is what mpv's synchronous `cachedPosition = positionMs`
     * was for. -1 = none.
     */
    @Volatile
    private var pendingSeekPositionMs = -1L

    private var cachedIsLoading = false

    /**
     * A fresh load has been handed to the engine and the adapter has moved on to READY/PLAYING, but
     * the engine has not reported that it is ready (paused load) or actually playing yet. AVPlayer
     * still has to open the URL and buffer, which on a phone takes a noticeable while; ExoPlayer stays
     * in STATE_BUFFERING with isPlaying = false for exactly this stretch, and the UI's loading
     * indicator is keyed on that. So until [finishAwaitingEngineStart], READY/PLAYING are announced
     * as BUFFERING, isLoading stays true and isPlaying(true) is held back.
     */
    @Volatile
    private var awaitingEngineStart = false

    /**
     * Where the item being loaded will start. While PREPARING, [currentPlayer] is still the OLD
     * track's handle (it is only replaced once the new URL is resolved), and reads of position,
     * duration and buffer must not answer with the old track's values: the handler would publish the
     * previous song's length and clock against the new song (the length also picks which lyrics a
     * provider returns) and would read the old track's buffer as "no need to show loading".
     */
    @Volatile
    private var preparingStartMs = 0L

    // Position update job (polling for crossfade detection)
    private var positionUpdateJob: Job? = null

    // Precaching system
    private data class PrecachedPlayer(
        val player: Int,
        val mediaItem: GenericMediaItem,
        val source: PlayableSource,
    )

    private val precachedPlayers = LinkedHashMap<String, PrecachedPlayer>()
    private var precacheEnabled = true
    private val maxPrecacheCount = 2
    private var precacheJob: Job? = null

    // Crossfade system
    private var crossfadeEnabled = false

    /** See MediaPlayerInterface.crossfadeSuppressed — set while in a Listen Together room. */
    @Volatile
    override var crossfadeSuppressed: Boolean = false

    private var crossfadeDurationMs = 5000

    /** User setting: leave transitions inside an album alone. */
    private var skipCrossfadeInAlbum = false

    /** Set by the handler when an album is loaded; empty for every other kind of queue. */
    @Volatile
    private var internalAlbumTrackIds: Set<String> = emptySet()

    @Volatile
    private var secondaryPlayer: Int = NO_HANDLE

    private var crossfadeJob: Job? = null

    @Volatile
    private var isCrossfading = false

    /** Index we're crossfading from; used when cancelling to revert localCurrentMediaItemIndex. */
    private var crossfadeFromIndex = -1

    /** Last index a crossfade was refused for, so the refusal is logged once instead of per poll. */
    private var lastBlockedCrossfadeIndex = -1

    /**
     * Set only while [commitIncomingAsCurrent] tears down a fade. [performCrossfade]'s
     * cancellation handler releases the incoming player by default; during a commit we are
     * promoting that very player, so it must be kept alive.
     */
    private var committingIncomingPlayer = false

    /**
     * Crossfade attenuation per handle, 0..1; a handle missing here is at 1. Replaces mpv's per-handle
     * fade volume: the engine only takes the final product, see [applyPlaybackLevels].
     */
    private val fadeGains = HashMap<Int, Float>()

    private fun setCrossfading(value: Boolean) {
        if (isCrossfading != value) {
            isCrossfading = value
            notifyListeners { onCrossfadeStateChanged(value) }
        }
    }

    // ========== DJ transitions (pre-rendered A→B segments, see the DJ section below) ==========

    /** Builds the transitions in the background; never touches the player. */
    private val djPipeline = DjMixPipeline(dataStoreManager, streamRepository)

    /** `crossfadeDjMode`: with crossfade on, use a rendered DJ transition when one is ready. */
    private var djModeEnabled = false

    private enum class DjPhase {
        /** Segment and B preloaded paused; A plays as usual towards the join. */
        ARMED,

        /** The segment plays as the current handle; B waits paused at its resume point. */
        MIXING,
    }

    private class DjSwitch(
        val ready: DjReadyTransition,
        val fromItem: GenericMediaItem,
        val toItem: GenericMediaItem,
        val aHandle: Int,
        val mixHandle: Int,
        var bHandle: Int,
        val aDurationMs: Long,
    ) {
        var phase = DjPhase.ARMED
        var job: Job? = null

        /** playSynced sent for the segment (ARMED) / for B (MIXING); undone by pause and seek. */
        var mixIssued = false
        var bIssued = false
        var identityPassed = false
        var finishing = false
    }

    /** Serial context only. */
    private var dj: DjSwitch? = null

    /** What other threads need while the segment plays: its time mapped onto A's or B's timeline. */
    private class DjView(
        val mixHandle: Int,
        val aStartMs: Long,
        val aDurationMs: Long,
        val bResumeMs: Long,
        val bDurationMs: Long,
        val mixLengthMs: Long,
    ) {
        @Volatile
        var identityPassed = false

        fun positionMs(mixPos: Long): Long =
            if (!identityPassed) {
                (aStartMs + mixPos).coerceIn(0L, if (aDurationMs > 0) aDurationMs else Long.MAX_VALUE)
            } else {
                (bResumeMs - (mixLengthMs - mixPos)).coerceIn(0L, if (bDurationMs > 0) bDurationMs else Long.MAX_VALUE)
            }

        fun durationMs(): Long = if (!identityPassed) aDurationMs else bDurationMs

        /** Lock screen: elapsed = segment position + this. */
        fun offsetMs(): Long = if (!identityPassed) aStartMs else bResumeMs - mixLengthMs
    }

    @Volatile
    private var djView: DjView? = null

    /** Last (current, next) handed to [djPipeline]. */
    private var djSentPair: Pair<String?, String?>? = null

    /** Pair a "ready too late" was logged for, so it is logged once. */
    private var djLateLogged: String? = null

    // ----- Smart next (see the DJ section) -----

    /** `djSmartOrder`: with DJ transitions on, move the best-mixing upcoming track to play next. */
    private var djSmartOrderEnabled = true

    /** Look-ahead ids last handed to [djPipeline] with [djSentPair]. */
    private var djSentLook: List<String> = emptyList()

    /**
     * Tracks the USER put right after the current one (Play next, a move to that slot). Smart next
     * never moves anything in front of them, nor looks past them. By identity; serial context only.
     */
    private val userPlacedNext = IdentitySet<GenericMediaItem>()

    /** Analyses the pipeline reported, by videoId (null = cannot be analysed). Serial context only. */
    private val smartAnalyses = LinkedHashMap<String, DjTrackAnalysis?>()

    /** The current track the smart-next decision is about, and whether it has been made. */
    private var smartFor: GenericMediaItem? = null
    private var smartDecided = false

    private var sentPositionOffset = 0L

    // Retry system - mirrors Android CrossfadeExoPlayerAdapter retry logic
    private var retryCount = 0
    private var retryVideoId: String? = null
    private val maxRetryCount = 2

    // Playlist management
    private val playlist = mutableListOf<GenericMediaItem>()
    private var localCurrentMediaItemIndex = -1

    // Shuffle management
    private var shuffleIndices = mutableListOf<Int>()
    private var shuffleOrder = mutableListOf<Int>()

    /**
     * Tracks that keep their place right after the current one when a track is appended under
     * shuffle: one put there with Play next, and the one a crossfade has picked. Held by identity,
     * since a queue can hold the same song twice.
     */
    private val pinnedNextItems = IdentitySet<GenericMediaItem>()

    // Loading management
    private var currentLoadJob: Job? = null

    // ========== Engine event bookkeeping (no mpv counterpart) ==========

    /**
     * The handle whose full set of events is honoured — what `setEventListener(listener)` on an
     * `MpvPlayer` meant. The engine has one listener for all handles, so "attaching" and "detaching"
     * the per-player listener becomes setting and clearing this.
     */
    private var eventPlayer: Int = NO_HANDLE

    /** Last onBufferingChanged / onPlayingChanged seen for [eventPlayer]. */
    private var eventPlayerBuffering = false
    private var eventPlayerPlaying = false

    /**
     * A system pause (`onPlayingChanged(false)` we did not ask for) waiting to settle: AVPlayer also
     * reports not-playing for a stall and right before `onEnded`, and neither is a pause.
     */
    private var pendingPausedJob: Job? = null

    /** Set when an interruption paused playback that the user had running; cleared by any play/pause. */
    private var resumeAfterInterruption = false

    @Volatile
    private var released = false

    // ========== Video (task 3.1) ==========

    /** The watch-video setting, as the mpv adapter keeps it for the crossfade skip rules. */
    @Volatile
    private var watchVideoEnabled = false

    /** Handles created with `video = true`. Serial context only. */
    private val videoHandles = HashSet<Int>()

    /** Last value given to [NativeVideoOutput], so it is only re-published on change. */
    private var sentVideoHandle = Int.MIN_VALUE

    /** The system route as outputs, and the AirPlay picker (spec section 4). */
    private val audioRoutes = AvAudioRoutes()

    // What the engine was last told, so the system state is only re-sent when it changes.
    private var sentActiveHandle = Int.MIN_VALUE
    private var sentNowPlayingItem: GenericMediaItem? = null
    private var sentNowPlayingHandle = Int.MIN_VALUE
    private var sentNowPlayingDurationMs = -1L
    private var sentNowPlayingLive = false
    private var sentRemoteNext: Boolean? = null
    private var sentRemotePrevious: Boolean? = null

    // ========== Playback Source ==========
    private data class PlayableSource(
        val isVideo: Boolean,
        val url: String,
        // A live broadcast's HLS playlist: the engine gets live = true (no duration, no onEnded).
        val isLive: Boolean = false,
        // The stream's real length from YouTube (0 = unknown, use the engine's); see trueDurations.
        val trueDurationMs: Long = 0L,
    )

    // ========== True length (AVPlayer over-reports YouTube's fragmented MP4) ==========

    /**
     * Per handle, the length YouTube states for the loaded stream. AVPlayer reads the duration of
     * itag 140 (DASH-style fragmented MP4, no usable mvhd/mehd duration) by estimating, and on a
     * phone it comes out far too long — often double — and then plays silence up to that length
     * before onEnded. Where this is known it replaces the engine's duration, caps the position and
     * ends the track (see [checkTrueEndInternal]). Written on the serial context only; replaced
     * whole so other threads can read it.
     */
    @Volatile
    private var trueDurations: Map<Int, Long> = emptyMap()

    /** Handles ended by [checkTrueEndInternal]: their engine onEnded, if it ever comes, is a repeat. */
    private val trueEndedHandles = HashSet<Int>()

    private fun trueDurationMs(handle: Int): Long = if (handle == NO_HANDLE) 0L else trueDurations[handle] ?: 0L

    /** The length to use for [handle]: YouTube's when known, else the engine's. */
    private fun effectiveDurationMs(handle: Int): Long {
        if (handle == NO_HANDLE) return 0L
        val known = trueDurationMs(handle)
        return if (known > 0L) known else engine.durationMs(handle)
    }

    /** The engine's position for [handle], never past its true length. */
    private fun effectivePositionMs(handle: Int): Long {
        if (handle == NO_HANDLE) return 0L
        val pos = engine.positionMs(handle)
        val known = trueDurationMs(handle)
        return if (known > 0L) pos.coerceAtMost(known) else pos
    }

    /** [positionMs] kept inside [handle]'s true length (a seek past it would only play silence). */
    private fun clampToTrueLength(
        handle: Int,
        positionMs: Long,
    ): Long {
        val known = trueDurationMs(handle)
        return if (known > 0L) positionMs.coerceAtMost(known) else positionMs
    }

    /**
     * Ends the current track when the engine's clock reaches its true length — exactly what the
     * engine's onEnded does ([onEngineEnded]), which for these streams would only come once the
     * over-long length had been played out in silence. Called from the position poll.
     */
    private fun checkTrueEndInternal() {
        val player = currentPlayer
        if (player == NO_HANDLE || internalState != InternalState.PLAYING || isCrossfading) return
        if (!isListened(player) || player in trueEndedHandles) return
        val known = trueDurationMs(player)
        if (known <= 0L) return
        val pos = engine.positionMs(player)
        if (pos < known - TRUE_END_TOLERANCE_MS) return
        Logger.d(TAG, "True end reached at ${pos}ms (true length ${known}ms, engine ${engine.durationMs(player)}ms)")
        trueEndedHandles.add(player)
        pendingPausedJob?.cancel()
        pendingPausedJob = null
        // Stop the silence: nothing after this point is audio.
        engine.pause(player)
        transitionToState(InternalState.ENDED)
        handleTrackEndInternal()
    }

    /**
     * The true length of [url]: the `dur=` (seconds, ms precision) googlevideo puts on every
     * adaptive-format URL — it is the format's own approxDurationMs — else the video's
     * lengthSeconds (whole seconds, so rounded up a second to never cut the tail). 0 for HLS,
     * whose playlist AVPlayer measures correctly.
     */
    private fun trueDurationOf(
        url: String,
        isLive: Boolean,
        lengthSeconds: Int?,
    ): Long {
        if (isLive || url.contains(".m3u8") || url.contains("manifest")) return 0L
        val dur = DUR_PARAM.find(url)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        if (dur != null && dur > 0.0) return (dur * 1000.0).toLong()
        return lengthSeconds?.takeIf { it > 0 }?.let { (it + 1) * 1000L } ?: 0L
    }

    // ========== Playback Control ==========

    override fun play() {
        Logger.d(TAG, "play() called (current state: $internalState)")
        coroutineScope.launch {
            resumeAfterInterruption = false
            playInternal()
        }
    }

    private fun playInternal() {
        when (internalState) {
            InternalState.READY, InternalState.ENDED, InternalState.PAUSED -> {
                currentPlayer.takeIf { it != NO_HANDLE }?.let { player ->
                    // At the end of the queue the handle is parked at its end, and telling it to play
                    // there does nothing — the press would look ignored. Rewind first so the last
                    // track replays, which is what the Play button is offering.
                    if (internalState == InternalState.ENDED) {
                        Logger.d(TAG, "Play: replaying from the start after end of queue")
                        engine.seek(player, 0L)
                        trueEndedHandles.remove(player)
                    }
                    Logger.d(TAG, "Play: calling engine play")
                    engine.play(player)
                    transitionToState(InternalState.PLAYING)
                    internalPlayWhenReady = true
                } ?: Logger.w(TAG, "Play called but currentPlayer is null")
            }

            InternalState.PREPARING -> {
                if (!cachedIsLoading) {
                    cachedIsLoading = true
                    notifyListeners { onIsLoadingChanged(true) }
                }
                internalPlayWhenReady = true
                Logger.d(TAG, "Play: During PREPARING - will auto-play when ready")
            }

            InternalState.PLAYING -> {
                internalPlayWhenReady = true
                cachedIsLoading = false
            }

            else -> {
                Logger.w(TAG, "Play: Called in invalid state: $internalState")
            }
        }
    }

    override fun pause() {
        Logger.d(TAG, "pause() called (current state: $internalState)")
        coroutineScope.launch {
            resumeAfterInterruption = false
            pauseInternal(clearSleepFade = true)
        }
    }

    /**
     * The body of [pause]. [clearSleepFade] is false for an interruption: playback will resume where
     * the sleep timer left it, so its attenuation must survive.
     */
    private suspend fun pauseInternal(clearSleepFade: Boolean) {
        try {
            // Pausing mid-crossfade stays on A+1 — the track the UI already shows — and freezes
            // it in place via the when(internalState) block below. It does NOT jump back to A.
            if (isCrossfading) {
                Logger.d(TAG, "Pause: committing incoming (A+1) and pausing in place")
                commitIncomingAsCurrent()
            }
            // A scheduled synced start must not fire while paused.
            djBeforePauseInternal()

            when (internalState) {
                InternalState.PLAYING, InternalState.READY -> {
                    currentPlayer.takeIf { it != NO_HANDLE }?.let { player ->
                        Logger.d(TAG, "Pause: calling engine pause")
                        engine.pause(player)
                        transitionToState(InternalState.PAUSED)
                        internalPlayWhenReady = false
                    }
                }

                InternalState.PREPARING -> {
                    internalPlayWhenReady = false
                    Logger.d(TAG, "Pause: During PREPARING - will not auto-play")
                }

                else -> {
                    Logger.w(TAG, "Pause: Called in invalid state: $internalState")
                }
            }
        } finally {
            // Playback has genuinely stopped by this point, so a sleep-timer attenuation has
            // served its purpose and is cleared here rather than by the caller. The caller
            // cannot do it safely: pause() is asynchronous and this task suspends at
            // commitIncomingAsCurrent() above, so anything the caller queued afterwards would
            // run during that suspension and re-open the volume over the last of the audio.
            //
            // In `finally` because that suspension point can also throw — committing a
            // crossfade joins a job that may be cancelled. Skipping this would leave playback
            // attenuated with nothing to ever restore it.
            if (clearSleepFade && internalSleepFadeFactor < 1f) {
                internalSleepFadeFactor = 1f
                forEachLiveHandle { applyVolume(it) }
            }
        }
    }

    override fun stop() {
        coroutineScope.launch {
            currentPlayer.takeIf { it != NO_HANDLE }?.let { player ->
                Logger.d(TAG, "Stop called")
                abortDjInternal("stop")
                // mpv's `stop` unloads the file and leaves a handle that can only be reloaded; the
                // engine has no unload, and a released handle is the same thing for every caller
                // below (play() in IDLE is refused, prepare() loads afresh).
                cleanupEventListenerInternal()
                releaseHandle(player)
                currentPlayer = NO_HANDLE
                transitionToState(InternalState.IDLE)
                stopPositionUpdates()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        // A live broadcast is followed at its live edge: its seek bar shows LIVE, not a length.
        if (isCurrentTrackLive()) return
        // Set here rather than inside the coroutine so the UI reflects the seek immediately.
        pendingSeekPositionMs = positionMs
        coroutineScope.launch {
            // Seeking mid-crossfade: commit the incoming track (A+1) as current first, the same way
            // pause() does. The progress bar the user just dragged belongs to A+1 — that is the
            // track the UI shows and the one position is read from during a crossfade.
            // Without this the seek lands on the *outgoing* track while the crossfade carries on,
            // so the old song keeps playing underneath and the seek appears to do nothing.
            try {
                if (isCrossfading) {
                    Logger.d(TAG, "seekTo: committing incoming (A+1) before seeking")
                    commitIncomingAsCurrent()
                }
                // Mid DJ segment the seek bar shows A (before B's entry) or B: that one is kept.
                djBeforeSeekInternal()
                currentPlayer.takeIf { it != NO_HANDLE }?.let { player ->
                    // Past the true end there is only silence; land on the end and let the poll end it.
                    val target = clampToTrueLength(player, positionMs)
                    engine.seek(player, target)
                    if (target < trueDurationMs(player) - TRUE_END_TOLERANCE_MS) trueEndedHandles.remove(player)
                    Logger.d(TAG, "Seeked to position: $target")
                    // Same contract as the Android adapter: every seek is reported, whoever asked
                    // for it (seek bar, lyric tap, ±5 s, Control Center, SponsorBlock).
                    notifyListeners { onSeeked(target) }
                }
                // Seeked back before the join: a ready transition can be armed again.
                djRefreshInternal()
            } finally {
                // A later seek may already have replaced it; only clear our own.
                if (pendingSeekPositionMs == positionMs) pendingSeekPositionMs = -1L
            }
        }
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        if (mediaItemIndex !in view().playlist.indices) return

        coroutineScope.launch {
            val shouldPlay = internalPlayWhenReady

            // Cancel any ongoing crossfade and await completion
            if (isCrossfading) {
                Logger.d(TAG, "seekTo: Cancelling crossfade")
                cancelCrossfadeAndCleanup(revertIndex = false)
            }

            // Cancel any ongoing load
            currentLoadJob?.cancel()
            abortDjInternal("jump to another track")

            localCurrentMediaItemIndex = mediaItemIndex
            unpinCurrent()
            releaseHandle(currentPlayer)
            currentPlayer = NO_HANDLE
            loadAndPlayTrackInternal(mediaItemIndex, positionMs, shouldPlay)
        }
    }

    override fun seekBack() {
        val newPosition = (currentPosition - 5000).coerceAtLeast(0)
        seekTo(newPosition)
    }

    override fun seekForward() {
        val target = currentPosition + 5000
        // An unknown duration (0) must not cap the seek to the start.
        val dur = duration
        seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
    }

    override fun seekToNext() {
        coroutineScope.launch {
            // During crossfade A→A+1, "next" commits A+1 as current (the UI already shows A+1)
            // and then advances to A+2. Outside a crossfade it advances normally. hasNextMediaItem()
            // is evaluated AFTER the commit so it is relative to A+1, not A.
            // Mid DJ segment, still showing A: Next means B, which is already playing — commit to it.
            if (djCommitForNextInternal()) return@launch
            val wasCrossfading = isCrossfading
            if (wasCrossfading) {
                Logger.d(TAG, "seekToNext: committing incoming (A+1), then advancing to A+2")
                commitIncomingAsCurrent()
            }
            if (hasNextMediaItem()) {
                seekTo(getNextMediaItemIndex(), 0)
            } else if (wasCrossfading) {
                // A+1 was the last track — stay on it; it is already promoted and announced.
                Logger.d(TAG, "seekToNext: A+1 was the last track — staying on it")
            }
        }
    }

    override fun seekToPrevious() {
        coroutineScope.launch {
            // Commit the incoming track (A+1) as current FIRST, so the 3-second rule below
            // evaluates against A+1's position and index rather than A's.
            if (isCrossfading) {
                Logger.d(TAG, "seekToPrevious: committing incoming (A+1) first")
                commitIncomingAsCurrent()
            }
            djCommitDisplayedInternal()

            // Standard music player behavior:
            // Position > 3s → seek to start of current track
            // Position <= 3s → go to previous track
            val positionThresholdMs = 3000L
            val position = currentPosition
            // A live broadcast has no start to go back to, so Previous always means the previous track.
            if (position > positionThresholdMs && !isCurrentTrackLive()) {
                Logger.d(TAG, "seekToPrevious: pos=${position}ms > ${positionThresholdMs}ms — seeking to start")
                currentPlayer.takeIf { it != NO_HANDLE }?.let { engine.seek(it, 0L) }
            } else if (hasPreviousMediaItem()) {
                Logger.d(TAG, "seekToPrevious: pos=${position}ms <= ${positionThresholdMs}ms — going to previous track")
                val prevIndex = getPreviousMediaItemIndex()
                seekTo(prevIndex, 0)
            } else if (!isCurrentTrackLive()) {
                Logger.d(TAG, "seekToPrevious: No previous item, seeking to start")
                currentPlayer.takeIf { it != NO_HANDLE }?.let { engine.seek(it, 0L) }
            }
        }
    }

    override fun seekToPreviousMediaItem() {
        coroutineScope.launch {
            // Mirror seekToPrevious(): commit the incoming track (A+1) as current first, so
            // "previous" means the track before A+1 (i.e. A), not the one before A.
            if (isCrossfading) {
                Logger.d(TAG, "seekToPreviousMediaItem: committing incoming (A+1) first")
                commitIncomingAsCurrent()
            }
            djCommitDisplayedInternal()

            // Always go to the previous track — skips the 3-second "seek to start"
            // rule used by seekToPrevious().
            if (hasPreviousMediaItem()) {
                val prevIndex = getPreviousMediaItemIndex()
                Logger.d(TAG, "seekToPreviousMediaItem: jumping to previous index=$prevIndex")
                seekTo(prevIndex, 0)
            } else {
                Logger.d(TAG, "seekToPreviousMediaItem: No previous item — no-op")
            }
        }
    }

    /**
     * An empty playlist that gains an item points at it, as ExoPlayer's own does. clearMediaItems()
     * leaves the index at -1 and nothing else moves it, so prepare() below loaded nothing and Add to
     * queue with nothing loaded (fresh start, or after closing the mini player) never played.
     */
    private fun pointAtFirstItemIfUnset() {
        if (localCurrentMediaItemIndex < 0 && playlist.isNotEmpty()) localCurrentMediaItemIndex = 0
    }

    override fun prepare() {
        val queue = view()
        if (queue.playlist.isNotEmpty() && queue.currentIndex >= 0) {
            coroutineScope.launch {
                loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, false)
            }
        }
    }

    // ========== Media Item Management ==========

    override fun setMediaItem(mediaItem: GenericMediaItem) {
        coroutineScope.launch {
            currentLoadJob?.cancel()
            cancelPrecaching()

            playlist.clear()
            userPlacedNext.clear()
            clearAllPrecacheInternal()
            playlist.add(mediaItem)
            localCurrentMediaItemIndex = 0

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
            loadAndPlayTrackInternal(0, 0, internalPlayWhenReady)
        }
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) {
        runSerial {
            playlist.add(mediaItem)
            pointAtFirstItemIfUnset()

            if (internalShuffleModeEnabled) {
                insertAppendedIntoShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            // Re-precache only when the appended track is one of the next ones Next plays. Under shuffle that
            // is its distance in the shuffled order, not in the playlist; re-triggering on every append of a
            // batch would cancel an in-flight extraction each time. The trigger keeps the precaches Next still
            // reaches and releases the rest, so it needs no clear first (that threw away the next player).
            val landsInPrecache =
                if (internalShuffleModeEnabled) {
                    val gap =
                        (shuffleIndices.getOrNull(playlist.lastIndex) ?: -1) -
                            (shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1)
                    gap in 1..maxPrecacheCount
                } else {
                    playlist.size - 1 - currentMediaItemIndex <= maxPrecacheCount
                }
            if (landsInPrecache) {
                coroutineScope.launch { triggerPrecachingInternal() }
            }
        }
    }

    override fun addMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) = runSerial<Unit> {
        if (index in 0..playlist.size) {
            val currentIndexBeforeInsert = localCurrentMediaItemIndex

            playlist.add(index, mediaItem)

            if (index <= localCurrentMediaItemIndex) {
                localCurrentMediaItemIndex++
            }
            pointAtFirstItemIfUnset()
            // Play next (or any insert right after the current track): the user's pick for next.
            if (currentIndexBeforeInsert >= 0 && index == currentIndexBeforeInsert + 1) userPlacedNext.add(mediaItem)
            smartQueueEditedByUser()

            if (internalShuffleModeEnabled) {
                if (currentIndexBeforeInsert >= 0 && index == currentIndexBeforeInsert + 1) {
                    val currentShufflePos = shuffleIndices.getOrNull(currentIndexBeforeInsert) ?: 0
                    insertIntoShuffleOrder(index, currentShufflePos)
                    pinnedNextItems.add(mediaItem)
                } else {
                    createShuffleOrder()
                }
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            // Same as the append above: under shuffle the insert may reorder what Next plays.
            if (internalShuffleModeEnabled || index - 1 - currentMediaItemIndex <= maxPrecacheCount) {
                coroutineScope.launch { triggerPrecachingInternal() }
            }
        }
    }

    override fun removeMediaItem(index: Int) {
        coroutineScope.launch {
            // Bounds check MUST run inside the launch, on the same serial context as removeAt.
            // If it sits outside (on the caller thread), another queued op (clearMediaItems/setMediaItem)
            // can empty the playlist between the check and removeAt → IndexOutOfBounds (issue #2156 /
            // SIMPMUSIC-DESKTOP-3Y: "Index 0 out of bounds for length 0").
            if (index !in playlist.indices) return@launch
            // Where the track sat in the shuffled order, read before the order loses it.
            val shuffledPos = if (internalShuffleModeEnabled) shuffleIndices.getOrNull(index) ?: -1 else -1
            val track = playlist.removeAt(index)
            // Only this track leaves the shuffled order, before anything below reads the order.
            // Rebuilding it here (createShuffleOrder) reshuffled the whole queue every time one track
            // was removed with shuffle on.
            if (internalShuffleModeEnabled) removeFromShuffleOrder(index)
            pinnedNextItems.remove(track)
            userPlacedNext.remove(track)
            smartQueueEditedByUser()

            precachedPlayers.remove(track.mediaId)?.let { cached ->
                cleanupPlayerInternal(cached.player)
            }

            when {
                index < localCurrentMediaItemIndex -> {
                    localCurrentMediaItemIndex--
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }

                index == localCurrentMediaItemIndex -> {
                    if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                        // The track after it in the shuffled order takes over, not the next one in
                        // the playlist: the removal shifted that track into the removed one's slot.
                        localCurrentMediaItemIndex = shuffleOrder[shuffledPos.coerceIn(0, shuffleOrder.lastIndex)]
                    } else if (localCurrentMediaItemIndex >= playlist.size) {
                        localCurrentMediaItemIndex = playlist.size - 1
                    }
                    unpinCurrent()
                    if (localCurrentMediaItemIndex >= 0) {
                        loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, internalPlayWhenReady)
                    } else {
                        cleanupCurrentPlayerInternal()
                    }
                }

                else -> {
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
        }
    }

    /**
     * Drops `[fromIndex, toIndex)` of already-played tracks in one pass: one precache pass, one
     * timeline notification — the per-item versions of those are what the queue trim must not
     * repeat 50 times.
     *
     * Indices count the UNSHUFFLED playlist, like [removeMediaItem] and [currentMediaItemIndex].
     * Only a range strictly BELOW the current track is accepted; anything else is refused rather
     * than guessed at, because getting it wrong would stop playback. [onRemoved] hears back exactly
     * once either way, from inside this block — the listeners here are called through a queue
     * ([notifyListeners]), so a caller that waited for the timeline event would see snapshots taken
     * before the removal.
     */
    override fun removeMediaItems(
        fromIndex: Int,
        toIndex: Int,
        onRemoved: (removedIds: List<String>) -> Unit,
    ) {
        coroutineScope.launch {
            // Re-checked inside the launch, on the serial context, for the reason in removeMediaItem
            // above (issue #2156).
            val refused =
                fromIndex < 0 ||
                    toIndex <= fromIndex ||
                    toIndex > playlist.size ||
                    toIndex > localCurrentMediaItemIndex ||
                    // crossfadeFromIndex is a position in this same playlist and is NOT shifted here;
                    // a cancelled fade would revert to a track ~toIndex positions away.
                    isCrossfading ||
                    // With shuffle on, the tracks before the current one in this unshuffled list are
                    // not the played ones — they are part of what is still to come.
                    internalShuffleModeEnabled
            if (refused) {
                onRemoved(emptyList())
                return@launch
            }

            val removed = playlist.subList(fromIndex, toIndex).toList()
            playlist.subList(fromIndex, toIndex).clear()
            localCurrentMediaItemIndex -= removed.size
            // Before anything else can run, so the caller cuts its copy of the queue in this same step.
            onRemoved(removed.map { it.mediaId })

            removed.forEach { track ->
                precachedPlayers.remove(track.mediaId)?.let { cached ->
                    cleanupPlayerInternal(cached.player)
                }
            }
            // The removed tracks are all behind the current one and precache is keyed by mediaId,
            // so the window ahead needs no rebuild — clearing it here would throw away the handle
            // the next crossfade is about to use. This only tops it back up, which also restores
            // any handle an id repeated in the dropped history took with it.
            triggerPrecachingInternal()

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
        }
    }

    override fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) {
        coroutineScope.launch {
            val item = moveMediaItemInternal(fromIndex, toIndex) ?: return@launch
            // A track the user moved right after the current one is their pick for next.
            if (toIndex == localCurrentMediaItemIndex + 1) userPlacedNext.add(item) else userPlacedNext.remove(item)
            smartQueueEditedByUser()
        }
    }

    /** The playlist move itself (also smart next's), on the serial context. Returns the moved item. */
    private fun moveMediaItemInternal(
        fromIndex: Int,
        toIndex: Int,
    ): GenericMediaItem? {
        run {
            // Same reason as removeMediaItem (issue #2156): re-check bounds inside the launch, since the
            // playlist can be emptied by another queued op between an outside-launch guard and removeAt.
            if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return null
            val item = playlist.removeAt(fromIndex)
            playlist.add(toIndex, item)

            localCurrentMediaItemIndex =
                when {
                    localCurrentMediaItemIndex == fromIndex -> {
                        toIndex
                    }
                    fromIndex < localCurrentMediaItemIndex && toIndex >= localCurrentMediaItemIndex -> {
                        localCurrentMediaItemIndex - 1
                    }
                    fromIndex > localCurrentMediaItemIndex && toIndex <= localCurrentMediaItemIndex -> {
                        localCurrentMediaItemIndex + 1
                    }
                    else -> {
                        localCurrentMediaItemIndex
                    }
                }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            clearPrecacheExceptCurrentInternal()
            triggerPrecachingInternal()
            return item
        }
    }

    override fun moveShuffledItem(
        fromShuffledIndex: Int,
        toShuffledIndex: Int,
    ) {
        coroutineScope.launch {
            val moved = moveShuffledItemInternal(fromShuffledIndex, toShuffledIndex) ?: return@launch
            if (toShuffledIndex == (shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1) + 1) {
                userPlacedNext.add(moved)
            } else {
                userPlacedNext.remove(moved)
            }
            smartQueueEditedByUser()
        }
    }

    /** The shuffled-order move itself (also smart next's), on the serial context. Returns the moved item. */
    private fun moveShuffledItemInternal(
        fromShuffledIndex: Int,
        toShuffledIndex: Int,
    ): GenericMediaItem? {
        run {
            // Same reason as removeMediaItem (issue #2156): checked against the order as it is now.
            if (!internalShuffleModeEnabled) return null
            if (fromShuffledIndex !in shuffleOrder.indices || toShuffledIndex !in shuffleOrder.indices) return null
            // Only the play order moves. Playlist indices stay where they were, and with them the
            // current index and the position a running crossfade would revert to.
            shuffleOrder.add(toShuffledIndex, shuffleOrder.removeAt(fromShuffledIndex))
            rebuildShuffleIndices()
            // Put right after the current track (Move to play next, or a drag), it keeps that slot
            // the way a Play next track does; moved anywhere else, it no longer holds one.
            playlist.getOrNull(shuffleOrder[toShuffledIndex])?.let { moved ->
                if (toShuffledIndex == (shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1) + 1) {
                    pinnedNextItems.add(moved)
                } else {
                    pinnedNextItems.remove(moved)
                }
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            // What plays next may have changed.
            clearPrecacheExceptCurrentInternal()
            triggerPrecachingInternal()
            return playlist.getOrNull(shuffleOrder[toShuffledIndex])
        }
    }

    override fun clearMediaItems() {
        coroutineScope.launch {
            playlist.clear()
            userPlacedNext.clear()
            localCurrentMediaItemIndex = -1
            clearShuffleOrder()
            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
            cleanupCurrentPlayerInternal()
            clearAllPrecacheInternal()
            // The lock-screen entry is removed by syncSystemIntegration() at the end of this task,
            // which sees the empty playlist.
        }
    }

    override fun replaceMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        coroutineScope.launch {
            // Same reason as removeMediaItem (issue #2156): a queued removal — a radio trimming its
            // history — can shrink the playlist between an outside check and this body.
            if (index !in playlist.indices) return@launch
            playlist[index] = mediaItem

            precachedPlayers.remove(mediaItem.mediaId)?.let { cached ->
                cleanupPlayerInternal(cached.player)
            }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            if (index == localCurrentMediaItemIndex) {
                loadAndPlayTrackInternal(index, 0, internalPlayWhenReady)
            } else {
                triggerPrecachingInternal()
            }
        }
    }

    override fun getMediaItemAt(index: Int): GenericMediaItem? = view().playlist.getOrNull(index)

    override fun getCurrentMediaTimeLine(): List<GenericMediaItem> = view().timeline()

    override fun getUnshuffledIndex(shuffledIndex: Int): Int = view().unshuffledIndex(shuffledIndex)

    // ========== Playback State Properties ==========

    override val isPlaying: Boolean
        get() = internalState == InternalState.PLAYING

    /**
     * The handle the UI is showing: the incoming one during a crossfade, else the current one. The
     * same handle is the engine's active handle (lock screen).
     */
    private fun displayHandle(): Int {
        val secondary = secondaryPlayer
        return if (isCrossfading && secondary != NO_HANDLE) secondary else currentPlayer
    }

    override val currentPosition: Long
        get() {
            val pending = pendingSeekPositionMs
            if (pending >= 0) return pending
            if (internalState == InternalState.PREPARING) return preparingStartMs
            djView?.let { v -> if (v.mixHandle == currentPlayer) return v.positionMs(engine.positionMs(v.mixHandle)) }
            return effectivePositionMs(displayHandle())
        }

    override val duration: Long
        get() {
            // Not known yet for the item being prepared (ExoPlayer: C.TIME_UNSET); see preparingStartMs.
            if (internalState == InternalState.PREPARING) return 0L
            djView?.let { v -> if (v.mixHandle == currentPlayer) return v.durationMs() }
            return effectiveDurationMs(displayHandle())
        }

    override val bufferedPosition: Long
        get() {
            if (internalState == InternalState.PREPARING) return 0L
            // A local segment, followed by a local file: everything is there.
            djView?.let { v -> if (v.mixHandle == currentPlayer) return v.durationMs() }
            val handle = displayHandle()
            return if (handle == NO_HANDLE) 0L else clampToTrueLength(handle, engine.bufferedMs(handle))
        }

    override val bufferedPercentage: Int
        get() {
            val dur = duration
            if (dur <= 0) return 0
            return ((bufferedPosition * 100) / dur).toInt().coerceIn(0, 100)
        }

    override val currentMediaItem: GenericMediaItem?
        get() = view().currentItem

    override val currentMediaItemIndex: Int
        get() = view().currentIndex

    override val mediaItemCount: Int
        get() = view().playlist.size

    override val contentPosition: Long
        get() = currentPosition

    override val playbackState: Int
        get() =
            when (internalState) {
                InternalState.IDLE -> PlayerConstants.STATE_IDLE
                InternalState.PREPARING -> PlayerConstants.STATE_BUFFERING
                InternalState.READY, InternalState.PLAYING ->
                    if (awaitingEngineStart) PlayerConstants.STATE_BUFFERING else PlayerConstants.STATE_READY
                InternalState.ENDED -> PlayerConstants.STATE_ENDED
                InternalState.ERROR -> PlayerConstants.STATE_IDLE
                InternalState.PAUSED -> PlayerConstants.STATE_READY
            }

    // ========== Navigation ==========

    // Repeat One only loops a track that ends on its own (handleTrackEndInternal);
    // Next and Previous move through the queue as they do with repeat off. See QueueView.
    override fun hasNextMediaItem(): Boolean = view().hasNext()

    override fun hasPreviousMediaItem(): Boolean = view().hasPrevious()

    /** The index Next plays after [from]; [from] itself when nothing follows it. Serial context only. */
    private fun getNextMediaItemIndex(from: Int = localCurrentMediaItemIndex): Int = liveQueueView().nextIndex(from)

    private fun getPreviousMediaItemIndex(): Int = liveQueueView().previousIndex()

    // ========== Playback Modes ==========

    override var shuffleModeEnabled: Boolean
        get() = internalShuffleModeEnabled
        set(value) {
            runSerial {
                if (internalShuffleModeEnabled == value) return@runSerial

                internalShuffleModeEnabled = value

                if (value) {
                    createShuffleOrder()
                } else {
                    clearShuffleOrder()
                }

                val mediaItemList = getShuffledMediaItemList()
                notifyListeners { onShuffleModeEnabledChanged(value, mediaItemList) }
                notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
            }
        }

    override var repeatMode: Int
        get() = internalRepeatMode
        set(value) {
            runSerial {
                if (internalRepeatMode == value) return@runSerial
                internalRepeatMode = value
                notifyListeners { onRepeatModeChanged(value) }
            }
        }

    override var playWhenReady: Boolean
        get() = internalPlayWhenReady
        set(value) {
            runSerial { internalPlayWhenReady = value }
            if (value) play() else pause()
        }

    override var playbackParameters: GenericPlaybackParameters
        // Pitch is not supported on iOS (spec section 4): it always reads back as 1.0.
        get() = GenericPlaybackParameters(internalPlaybackSpeed, 1.0f)
        set(value) {
            internalPlaybackSpeed = value.speed
            coroutineScope.launch {
                // Every live handle, not just the current one: speed is a playback-wide setting, so
                // the precached handles have to carry it too or the next track starts back at 1.0x.
                forEachLiveHandle { engine.setRate(it, value.speed) }
            }
        }

    // ========== Audio Settings ==========

    override val audioSessionId: Int
        get() = 0 // AVAudioSession has no session id to hand out

    override var volume: Float
        get() = internalVolume
        set(value) {
            Logger.w(TAG, "Setting volume to $value")
            internalVolume = value.coerceIn(0f, 1f)
            // The field itself is set synchronously so the getter reads back what was just written;
            // only the engine calls are deferred to the serial context, where the crossfade gains
            // they are multiplied with live.
            coroutineScope.launch {
                forEachLiveHandle { applyVolume(it) }
            }
            notifyListeners { onVolumeChanged(internalVolume) }
        }

    override var albumTrackIds: Set<String>
        get() = internalAlbumTrackIds
        set(value) {
            internalAlbumTrackIds = value
        }

    override var sleepFadeFactor: Float
        get() = internalSleepFadeFactor
        set(value) {
            internalSleepFadeFactor = value.coerceIn(0f, 1f)
            // Hop onto the serial context instead of writing from the caller's — the sleep timer
            // ramps this from Main. pause() queues onto this same serial context, and going through
            // it is what guarantees the timer's closing restore to 1f lands *after* playback has
            // actually stopped, instead of briefly re-opening the volume over the last of the audio.
            coroutineScope.launch {
                // Re-read rather than capture (inside applyVolume): if the queue backs up, the
                // pending writes collapse onto the newest value instead of replaying a stale ramp.
                forEachLiveHandle { applyVolume(it) }
            }
        }

    /** Stored only; AVPlayer has no silence skipping (spec section 4). */
    override var skipSilenceEnabled: Boolean = false

    // iOS routes audio system-wide: the list shows where the sound goes now, and picking any entry
    // opens the system AirPlay / Bluetooth picker instead of routing this player alone.
    override val audioOutputs: StateFlow<List<AudioOutput>>
        get() = audioRoutes.outputs

    override fun selectAudioOutput(id: String?) {
        audioRoutes.showRoutePicker()
    }

    override fun refreshAudioOutputs() {
        audioRoutes.refresh()
    }

    /**
     * Every handle that can currently reach the audio output.
     *
     * [secondaryPlayer] — the track fading in during a crossfade — is the easy one to miss: it is
     * removed from [precachedPlayers] before being promoted, so it belongs to neither collection.
     */
    private fun forEachLiveHandle(action: (Int) -> Unit) {
        currentPlayer.takeIf { it != NO_HANDLE }?.let(action)
        secondaryPlayer.takeIf { it != NO_HANDLE }?.let(action)
        precachedPlayers.values.toList().forEach { action(it.player) }
        // The DJ handles that are not current: the segment, B, and A until B's entry.
        dj?.let { d ->
            listOf(d.aHandle, d.mixHandle, d.bHandle)
                .filter { it != NO_HANDLE && it != currentPlayer }
                .distinct()
                .forEach(action)
        }
    }

    /** The one volume the engine is ever given: user level × sleep fade × crossfade gain. */
    private fun applyVolume(handle: Int) {
        if (handle == NO_HANDLE) return
        val gain = fadeGains[handle] ?: 1f
        engine.setVolume(handle, (internalVolume * internalSleepFadeFactor * gain).coerceIn(0f, 1f))
    }

    /**
     * Push the playback-wide settings onto a handle that is about to become audible.
     *
     * A fresh handle starts at full volume and 1.0x speed, so anything already in force has to be
     * re-asserted here — otherwise a fade in progress jumps back to full volume mid-ramp, and a track
     * started while the user has speed turned up plays at normal speed instead.
     */
    private fun applyPlaybackLevels(handle: Int) {
        if (handle == NO_HANDLE) return
        applyVolume(handle)
        engine.setRate(handle, internalPlaybackSpeed)
    }

    private fun setFadeGain(
        handle: Int,
        gain: Float,
    ) {
        if (handle == NO_HANDLE) return
        fadeGains[handle] = gain.coerceIn(0f, 1f)
        applyVolume(handle)
    }

    /** Return a handle to untouched playback: no crossfade attenuation, the user's speed. */
    private fun endCrossfadeAudio(handle: Int) {
        // Only handles still owned: the spec promises that release() ignores unknown handles, not
        // that every other call does.
        if (handle == NO_HANDLE || (handle != currentPlayer && handle != secondaryPlayer)) return
        fadeGains.remove(handle)
        applyPlaybackLevels(handle)
    }

    // ========== Listener Management ==========

    override fun addListener(listener: MediaPlayerListener) {
        runSerial { listeners = listeners + listener }
    }

    override fun removeListener(listener: MediaPlayerListener) {
        runSerial { listeners = listeners - listener }
    }

    // ========== Release Resources ==========

    override fun release() {
        runSerial {
            if (released) return@runSerial
            // Before the cleanup below, so syncSystemIntegration() stops re-announcing a track whose
            // handle is going away.
            released = true

            currentLoadJob?.cancel()
            precacheJob?.cancel()
            positionUpdateJob?.cancel()
            crossfadeJob?.cancel()
            pendingPausedJob?.cancel()
            abortDjInternal("release")
            djPipeline.release()

            releaseHandle(secondaryPlayer)
            secondaryPlayer = NO_HANDLE
            isCrossfading = false
            crossfadeFromIndex = -1

            // Our own scope only. The mpv adapter cancels the scope it was given; that one is shared
            // with the handler here, so it is left to its owner.
            coroutineScope.cancel()
            cleanupCurrentPlayerInternal()
            clearAllPrecacheInternal()
            listeners = emptyList()

            engine.setActiveHandle(NO_HANDLE)
            NativeVideoOutput.publish(NO_HANDLE)
            videoHandles.clear()
            audioRoutes.release()
            engine.clearNowPlaying()
            // Every handle is released above; this also deactivates the audio session.
            engine.releaseAll()
        }
    }

    // ========== Internal Methods ==========

    /**
     * Transition internal state and notify listeners.
     * Listeners are called on the serial context (not Main), as on desktop, where they run on the
     * service thread because the handler uses thread-safe StateFlow updates and contains
     * runBlocking calls that would deadlock on Main.
     */
    private fun transitionToState(newState: InternalState) {
        if (internalState == newState) return

        val oldState = internalState
        internalState = newState

        Logger.d(TAG, "State transition: $oldState -> $newState (playWhenReady=$internalPlayWhenReady)")

        val listeners = listeners
        when (newState) {
            InternalState.PAUSED -> {
                endLoadingForStop(listeners)
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.IDLE -> {
                endLoadingForStop(listeners)
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.PREPARING -> {
                Logger.d(TAG, "transitionToState PREPARING -> isLoading=true")
                awaitingEngineStart = false
                cachedIsLoading = true
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_BUFFERING) }
                listeners.forEach { it.onIsLoadingChanged(true) }
            }

            InternalState.READY -> {
                // Still loading while the engine opens a fresh item (see awaitingEngineStart).
                val awaiting = awaitingEngineStart
                Logger.d(TAG, "transitionToState READY -> isLoading=$awaiting")
                cachedIsLoading = awaiting
                listeners.forEach {
                    it.onPlaybackStateChanged(if (awaiting) PlayerConstants.STATE_BUFFERING else PlayerConstants.STATE_READY)
                }
                if (!awaiting) listeners.forEach { it.onIsLoadingChanged(false) }
                if (internalPlayWhenReady) {
                    play()
                } else {
                    listeners.forEach { it.onIsPlayingChanged(false) }
                }
            }

            InternalState.PLAYING -> {
                val awaiting = awaitingEngineStart
                Logger.d(TAG, "transitionToState PLAYING -> isLoading=$awaiting")
                cachedIsLoading = awaiting
                listeners.forEach {
                    it.onPlaybackStateChanged(if (awaiting) PlayerConstants.STATE_BUFFERING else PlayerConstants.STATE_READY)
                }
                if (!awaiting) {
                    listeners.forEach { it.onIsLoadingChanged(false) }
                    listeners.forEach { it.onIsPlayingChanged(true) }
                }
            }

            InternalState.ENDED -> {
                endLoadingForStop(listeners)
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_ENDED) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.ERROR -> {
                endLoadingForStop(listeners)
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
                listeners.forEach {
                    it.onPlayerError(
                        PlayerError(
                            errorCode = 403,
                            errorCodeName = "ERROR_UNKNOWN",
                            message = "Can not extract playable URL or playback error",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Playback stopped (paused, ended, failed, unloaded) while a load or a stall was being reported:
     * say loading is over BEFORE the state itself, so the handler stops its buffered-percentage loop
     * (which would otherwise keep publishing Loading over the paused/ended state) and the state
     * notification that follows is the one the UI settles on.
     */
    private fun endLoadingForStop(listeners: List<MediaPlayerListener>) {
        awaitingEngineStart = false
        if (cachedIsLoading) {
            cachedIsLoading = false
            listeners.forEach { it.onIsLoadingChanged(false) }
        }
    }

    /**
     * The engine has the new item ready (paused load) or is actually playing it: announce what
     * READY/PLAYING held back while [awaitingEngineStart].
     */
    private fun finishAwaitingEngineStart() {
        if (!awaitingEngineStart) return
        awaitingEngineStart = false
        cachedIsLoading = false
        Logger.d(TAG, "Engine started the item -> isLoading=false (state: $internalState)")
        val listeners = listeners
        listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
        listeners.forEach { it.onIsLoadingChanged(false) }
        if (internalState == InternalState.PLAYING) {
            listeners.forEach { it.onIsPlayingChanged(true) }
        }
    }

    /**
     * Load and play track - MUST run on coroutineScope
     */
    private fun loadAndPlayTrackInternal(
        index: Int,
        startPositionMs: Long,
        shouldPlay: Boolean,
    ) {
        if (index !in playlist.indices) return

        val mediaItem = playlist[index]
        val videoId = mediaItem.mediaId

        currentLoadJob?.cancel()
        // A fresh load replaces whatever the DJ handles were doing; the current one goes with the load.
        abortDjInternal("load")

        currentLoadJob =
            coroutineScope.launch {
                try {
                    // The old track stops being "the one playing" now, as ExoPlayer's isPlaying turns
                    // false the moment it moves to an item it still has to buffer. Announced before
                    // PREPARING so the handler's pause bookkeeping still reads the old track's
                    // position; it also stops the handler's progress ticker, which would otherwise
                    // keep publishing the old track's clock (and clear the loading flag) over the
                    // new song while its URL is resolved.
                    if (internalState == InternalState.PLAYING) {
                        listeners.forEach { it.onIsPlayingChanged(false) }
                    }
                    preparingStartMs = startPositionMs.coerceAtLeast(0L)
                    transitionToState(InternalState.PREPARING)

                    // Notify media item transition (fire-and-forget to avoid
                    // blocking the serial context with runBlocking inside handler)
                    notifyListeners {
                        onMediaItemTransition(
                            mediaItem,
                            PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                        )
                    }

                    // Detach event listener from old player IMMEDIATELY before
                    // spending time on URL extraction. This prevents stale events
                    // (error/finished) from the old player seeing the updated
                    // playlist/index and interfering with the new track load.
                    cleanupEventListenerInternal()
                    stopPositionUpdates()

                    // Extract URL on IO (network); engine calls stay on the serial context
                    val cachedPrecache = precachedPlayers.remove(videoId)
                    var resolvedSource: PlayableSource? = null
                    val player =
                        if (cachedPrecache != null) {
                            cachedPrecache.player
                        } else {
                            var source = withContext(Dispatchers.IO) { extractPlayableUrl(mediaItem) }
                            if (source == null || source.url.isEmpty()) {
                                Logger.w(TAG, "First extract failed for $videoId, invalidating and retrying...")
                                withContext(Dispatchers.IO) {
                                    streamRepository.invalidateFormat(videoId)
                                    streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$videoId")
                                }
                                source = withContext(Dispatchers.IO) { extractPlayableUrl(mediaItem) }
                            }
                            if (source == null || source.url.isEmpty()) {
                                Logger.e(TAG, "Failed to extract playable URL for $videoId after retry")
                                transitionToState(InternalState.ERROR)
                                return@launch
                            }
                            resolvedSource = source
                            createMediaPlayerInternal(source)
                        }

                    if (player == NO_HANDLE) {
                        Logger.e(TAG, "Failed to create engine player for $videoId")
                        transitionToState(InternalState.ERROR)
                        return@launch
                    }

                    cleanupCurrentPlayerInternal()
                    currentPlayer = player
                    setupPlayerEventsInternal(player)
                    applyPlaybackLevels(player)

                    if (cachedPrecache != null) {
                        // The precached handle already has the item loaded and held paused;
                        // seeking (if asked) and unpausing is all that is needed.
                        if (startPositionMs > 0) {
                            engine.seek(player, clampToTrueLength(player, startPositionMs))
                        }
                        if (shouldPlay) {
                            engine.play(player)
                        }
                        Logger.d(TAG, "Playing from precache for $videoId")
                    } else {
                        val source = resolvedSource
                        if (source != null) {
                            // The start position rides on load() instead of mpv's 100 ms-later seek:
                            // the engine seeks before the first frame.
                            loadFile(player, source, startPaused = !shouldPlay, startMs = startPositionMs.coerceAtLeast(0L))
                        } else {
                            Logger.e(TAG, "resolvedSource is null — should not happen")
                            transitionToState(InternalState.ERROR)
                            return@launch
                        }
                    }

                    // Always transition to READY first; the READY handler will auto-call play() when
                    // internalPlayWhenReady is true. A precached handle is already open, so READY is
                    // real. A fresh load is not: the engine has only been handed the URL, so loading
                    // stays reported until it says it is ready/playing (awaitingEngineStart).
                    awaitingEngineStart = cachedPrecache == null
                    transitionToState(InternalState.READY)

                    // Start position updates
                    startPositionUpdates()

                    // Trigger precaching
                    triggerPrecachingInternal()
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e(TAG, "Load track error: ${e.message}", e)
                        transitionToState(InternalState.ERROR)
                    }
                }
            }
    }

    /**
     * `MpvPlayer.loadFile` for the engine: one URL, no headers (spec section 3, Sources), levels
     * re-asserted afterwards in case the engine resets them with the new item.
     */
    private fun loadFile(
        player: Int,
        source: PlayableSource,
        startPaused: Boolean,
        startMs: Long = 0L,
    ) {
        // Recorded before load() so the first duration read already has it; a reused handle drops
        // the previous item's entry.
        trueDurations =
            if (source.trueDurationMs > 0L) trueDurations + (player to source.trueDurationMs) else trueDurations - player
        trueEndedHandles.remove(player)
        engine.load(player, source.url, emptyMap(), clampToTrueLength(player, startMs), !startPaused, source.isLive)
        applyPlaybackLevels(player)
    }

    /** Create an engine player for [source]: a video handle when it is a muxed video stream. */
    private fun createMediaPlayerInternal(source: PlayableSource): Int {
        Logger.d(TAG, if (source.isVideo) "Creating video player" else "Creating audio-only player")
        val handle = engine.create(source.isVideo)
        if (source.isVideo && handle != NO_HANDLE) videoHandles.add(handle)
        return handle
    }

    /**
     * Attach the full event listener to [player] — with one engine listener for every handle, that
     * means marking [player] as the one whose events are honoured (see [onEngineEvent] callers).
     */
    private fun setupPlayerEventsInternal(player: Int) {
        // Remove old listener
        cleanupEventListenerInternal()
        eventPlayer = player
    }

    /**
     * Clean up event listener from current player
     */
    private fun cleanupEventListenerInternal() {
        eventPlayer = NO_HANDLE
        eventPlayerBuffering = false
        eventPlayerPlaying = false
        pendingPausedJob?.cancel()
        pendingPausedJob = null
    }

    /** Whether [handle]'s full events should be acted on: it is current and its listener attached. */
    private fun isListened(handle: Int): Boolean = handle != NO_HANDLE && handle == currentPlayer && handle == eventPlayer

    private fun releaseHandle(handle: Int) {
        if (handle == NO_HANDLE) return
        fadeGains.remove(handle)
        trueDurations = trueDurations - handle
        trueEndedHandles.remove(handle)
        videoHandles.remove(handle)
        engine.release(handle)
    }

    /**
     * Cleanup a player instance
     */
    private fun cleanupPlayerInternal(player: Int) {
        try {
            releaseHandle(player)
        } catch (e: Exception) {
            Logger.w(TAG, "Error cleaning up player: ${e.message}")
        }
    }

    /**
     * Cleanup current player
     */
    private fun cleanupCurrentPlayerInternal() {
        stopPositionUpdates()
        cleanupEventListenerInternal()
        abortDjInternal("player cleanup")

        crossfadeJob?.cancel()
        crossfadeJob = null
        setCrossfading(false)

        // The crossfade handle has to die with its job. This runs on the load path (next / prev /
        // picking another track), which never passes through cancelCrossfadeAndCleanup, so nothing
        // else would release it: the following crossfade overwrites `secondaryPlayer` and the old
        // handle would be leaked while still holding an AVPlayer and its item.
        secondaryPlayer.takeIf { it != NO_HANDLE }?.let { cleanupPlayerInternal(it) }
        secondaryPlayer = NO_HANDLE

        currentPlayer.takeIf { it != NO_HANDLE }?.let { cleanupPlayerInternal(it) }
        currentPlayer = NO_HANDLE
    }

    /**
     * Crossfade is skipped when the NEXT track will play as a video: a video should start from its
     * first frame instead of fading in under the outgoing song. As on desktop.
     */
    private fun isNextTrackVideo(): Boolean = watchVideoEnabled && playlist.getOrNull(getNextMediaItemIndex())?.isVideo() == true

    /** Same skip rule for the CURRENT track. */
    private fun isCurrentTrackVideo(): Boolean = watchVideoEnabled && currentMediaItem?.isVideo() == true

    /**
     * A live broadcast never reaches an end to fade out of; the engine reports no duration for it,
     * and treating "time remaining" as meaningful near the live edge would set off a crossfade.
     */
    private fun isCurrentTrackLive(): Boolean = currentMediaItem?.mediaId?.let(LiveStreamRegistry::isLive) == true

    /** Nor does a live broadcast fade in: started early in the background, it would begin behind the live edge. */
    private fun isNextTrackLive(): Boolean = playlist.getOrNull(getNextMediaItemIndex())?.mediaId?.let(LiveStreamRegistry::isLive) == true

    /**
     * The fade length in ms. Auto has no AutoMix analysis to resolve against on iOS (spec section 3),
     * so it is a fixed-length fade of [AUTO_CROSSFADE_DURATION_MS].
     */
    private fun configuredCrossfadeDurationMs(): Int =
        if (crossfadeDurationMs == DataStoreManager.CROSSFADE_DURATION_AUTO) AUTO_CROSSFADE_DURATION_MS else crossfadeDurationMs

    /**
     * Crossfade needs a track long enough that both sides of the blend are still worth hearing. At
     * the default 5s fade a 20s track would spend half its length fading in or out, and a longer
     * fade setting swallows it whole — so the bar scales with the fade rather than being fixed.
     *
     * Only the current track is measured: the next one has not been loaded yet, so its duration is
     * unknown until it becomes current.
     */
    private fun isCurrentTrackTooShortForCrossfade(): Boolean {
        val currentDuration = duration
        if (currentDuration <= 0L) return false
        val fadeMs = configuredCrossfadeDurationMs()
        return currentDuration < maxOf(MIN_CROSSFADE_TRACK_MS, fadeMs * 3L)
    }

    /**
     * True when both this track and the next came from the album loaded in the queue, and the user
     * asked for albums to play through uninterrupted.
     *
     * Requiring *both* sides is what keeps the edges intact: the last album track into the first
     * track endless queue appended still crossfades, because that one is not in the set.
     */
    private fun isWithinAlbum(): Boolean {
        if (!skipCrossfadeInAlbum) return false
        val ids = internalAlbumTrackIds
        if (ids.isEmpty()) return false
        val current = currentMediaItem?.mediaId ?: return false
        val next = playlist.getOrNull(getNextMediaItemIndex())?.mediaId ?: return false
        return current in ids && next in ids
    }

    /**
     * Handle track end
     */
    private fun handleTrackEndInternal() {
        // If crossfade is already in progress (triggered by position update before track ended),
        // don't interrupt it. The old player ending is expected — the crossfade will complete
        // and finalizeCrossfade() will handle the transition.
        if (isCrossfading) {
            Logger.d(TAG, "handleTrackEndInternal: crossfade in progress, ignoring track end")
            return
        }
        // A track that ends while a DJ switch is armed never reached the join: plain fallback.
        abortDjInternal("track ended before the join")

        val shouldCrossfade =
            crossfadeEnabled &&
                !crossfadeSuppressed &&
                // Repeat One loops this track through the branch below instead of fading into the next.
                internalRepeatMode != PlayerConstants.REPEAT_MODE_ONE &&
                hasNextMediaItem() &&
                !isCurrentTrackVideo() &&
                !isNextTrackVideo() &&
                !isCurrentTrackLive() &&
                !isNextTrackLive() &&
                !isCurrentTrackTooShortForCrossfade() &&
                !isWithinAlbum()

        if (shouldCrossfade) {
            val nextIndex = getNextMediaItemIndex()
            triggerCrossfadeTransition(nextIndex)
        } else {
            when (internalRepeatMode) {
                PlayerConstants.REPEAT_MODE_ONE -> {
                    seekTo(localCurrentMediaItemIndex, 0)
                }

                PlayerConstants.REPEAT_MODE_ALL -> {
                    if (hasNextMediaItem()) {
                        seekToNext()
                    }
                }

                else -> {
                    if (hasNextMediaItem()) {
                        seekToNext()
                    }
                }
            }
        }
    }

    /**
     * Trigger crossfade to next track
     */
    private fun triggerCrossfadeTransition(nextIndex: Int) {
        if (nextIndex !in playlist.indices || isCrossfading) {
            // Says which of the two blocked it — an index the playlist no longer holds, or a
            // crossfading flag left set. Reported once per blocked index rather than on every poll:
            // the position loop reaches here every poll for as long as the track stays inside the
            // trigger window, and a repeating line buries everything else.
            if (lastBlockedCrossfadeIndex != nextIndex) {
                lastBlockedCrossfadeIndex = nextIndex
                Logger.w(
                    TAG,
                    "Crossfade not started: nextIndex=$nextIndex, playlistSize=${playlist.size}, " +
                        "isCrossfading=$isCrossfading, currentIndex=$localCurrentMediaItemIndex",
                )
            }
            return
        }
        lastBlockedCrossfadeIndex = -1

        // Pinned now, not inside the launch: the target is already chosen, and an append that runs
        // before the fade makes it current must not take its slot.
        if (internalShuffleModeEnabled) pinnedNextItems.add(playlist[nextIndex])

        crossfadeJob =
            coroutineScope.launch {
                try {
                    setCrossfading(true)
                    val nextMediaItem = playlist[nextIndex]
                    val nextVideoId = nextMediaItem.mediaId

                    Logger.d(TAG, "Starting crossfade to track $nextIndex ($nextVideoId)")

                    // Extract URL on IO (network); engine calls stay on the serial context
                    val cachedPrecache = precachedPlayers.remove(nextVideoId)
                    val nextPlayer: Int =
                        if (cachedPrecache != null) {
                            Logger.d(TAG, "Using precached player for crossfade")
                            cachedPrecache.player
                        } else {
                            val nextSource = withContext(Dispatchers.IO) { extractPlayableUrl(nextMediaItem) }
                            if (nextSource == null || nextSource.url.isEmpty()) {
                                Logger.e(TAG, "Failed to extract URL for crossfade")
                                NO_HANDLE
                            } else {
                                createMediaPlayerInternal(nextSource).also { newPlayer ->
                                    loadFile(newPlayer, nextSource, startPaused = true)
                                }
                            }
                        }

                    if (nextPlayer != NO_HANDLE) {
                        // The secondary player only gets the minimal crossfade error handling (see
                        // onEngineError). DO NOT call setupPlayerEventsInternal() here - that would
                        // detach the events of the current player (which is still playing).
                        secondaryPlayer = nextPlayer
                        // Silent before it starts, then the fade below opens it. mpv also muted the
                        // handle for 50 ms around play() to hide a blip from its shared ao-volume;
                        // the engine applies volume per AVPlayer, so there is nothing to hide.
                        fadeGains[nextPlayer] = 0f
                        applyPlaybackLevels(nextPlayer)
                        engine.play(nextPlayer)
                    }

                    if (nextPlayer == NO_HANDLE) {
                        setCrossfading(false)
                        seekTo(nextIndex, 0)
                        return@launch
                    }

                    // Capture current index BEFORE advancing localCurrentMediaItemIndex
                    crossfadeFromIndex = localCurrentMediaItemIndex

                    // Update now playing immediately: the UI (and the lock screen, through
                    // syncSystemIntegration) moves to the incoming track as the fade starts.
                    localCurrentMediaItemIndex = nextIndex
                    unpinCurrent()
                    notifyListeners {
                        onMediaItemTransition(
                            nextMediaItem,
                            PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                        )
                    }

                    Logger.d(TAG, "Now playing updated to track $nextIndex during crossfade")

                    val resolvedConfigDurationMs = configuredCrossfadeDurationMs()

                    // Calculate effective crossfade duration based on ACTUAL remaining time.
                    val actualTimeRemaining =
                        currentPlayer.takeIf { it != NO_HANDLE }?.let { player ->
                            val dur = effectiveDurationMs(player)
                            val pos = effectivePositionMs(player)
                            if (dur > 0 && pos >= 0) (dur - pos) else resolvedConfigDurationMs.toLong()
                        } ?: resolvedConfigDurationMs.toLong()

                    val effectiveCrossfadeDurationMs =
                        minOf(
                            resolvedConfigDurationMs.toLong(),
                            actualTimeRemaining,
                        ).coerceAtLeast(1000L).toInt()

                    Logger.d(
                        TAG,
                        "Crossfade duration: configured=${resolvedConfigDurationMs}ms " +
                            "(auto=${crossfadeDurationMs == DataStoreManager.CROSSFADE_DURATION_AUTO}), " +
                            "actualRemaining=${actualTimeRemaining}ms, effective=${effectiveCrossfadeDurationMs}ms",
                    )

                    performCrossfade(
                        nextIndex,
                        nextPlayer,
                        effectiveCrossfadeDurationMs,
                    )
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e(TAG, "Crossfade error: ${e.message}", e)
                        setCrossfading(false)
                        seekTo(nextIndex, 0)
                    }
                }
            }
    }

    /**
     * Cancel the ongoing crossfade, await its completion, and clean up state.
     * Uses cancel + join to ensure the crossfade coroutine's catch block
     * has finished before the caller proceeds, preventing race conditions.
     *
     * @param revertIndex If true, revert [localCurrentMediaItemIndex] to the track
     *   that was playing before the crossfade started.
     */
    private suspend fun cancelCrossfadeAndCleanup(revertIndex: Boolean) {
        val job = crossfadeJob
        crossfadeJob = null
        job?.cancel()
        job?.join()
        // secondaryPlayer may already be released by performCrossfade's catch block; the engine
        // ignores unknown handles, so releasing again is safe.
        releaseHandle(secondaryPlayer)
        secondaryPlayer = NO_HANDLE
        if (revertIndex && crossfadeFromIndex >= 0) {
            localCurrentMediaItemIndex = crossfadeFromIndex
            playlist.getOrNull(crossfadeFromIndex)?.let { mediaItem ->
                notifyListeners {
                    onMediaItemTransition(
                        mediaItem,
                        PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_SEEK,
                    )
                }
            }
        }
        crossfadeFromIndex = -1
        setCrossfading(false)
    }

    /**
     * Abort an in-progress crossfade by committing the INCOMING track (A+1) as the new current
     * player — the mid-fade counterpart of [finalizeCrossfade]. Invoked when the user interacts
     * during a crossfade (next / previous / pause / seek).
     *
     * Direction: "crossfade means we have moved to A+1", so A+1 is kept and A is dropped. This
     * mirrors CrossfadeExoPlayerAdapter.commitIncomingAsCurrentInternal() on Android — every
     * platform must resolve mid-crossfade transport commands the same way.
     *
     * [localCurrentMediaItemIndex] already equals A+1 and its onMediaItemTransition was emitted
     * by [triggerCrossfadeTransition], so neither is touched here: the UI already shows A+1.
     *
     * Position polling is deliberately left running, unlike the Android counterpart. On this
     * adapter the poll loop also drives crossfade detection and [play] never restarts it, so
     * stopping it here would disable every later crossfade.
     *
     * Does NOT change [internalState] and does NOT seek — the caller decides what happens next
     * (pause in place, advance to A+2, go to previous, ...).
     */
    private suspend fun commitIncomingAsCurrent() {
        val incoming = secondaryPlayer
        if (incoming == NO_HANDLE) {
            // The fade already collapsed (the secondary player errored out and was released).
            // Nothing to promote, so revert to A instead — that keeps index and player in sync.
            Logger.w(TAG, "commitIncomingAsCurrent: no incoming player, reverting to outgoing track")
            cancelCrossfadeAndCleanup(revertIndex = true)
            return
        }

        committingIncomingPlayer = true
        try {
            val job = crossfadeJob
            crossfadeJob = null
            job?.cancel()
            job?.join()
        } finally {
            committingIncomingPlayer = false
        }

        // Drop the outgoing track (A).
        currentPlayer.takeIf { it != NO_HANDLE }?.let { outgoing ->
            try {
                releaseHandle(outgoing)
            } catch (e: Exception) {
                Logger.w(TAG, "commitIncomingAsCurrent: error releasing outgoing player: ${e.message}")
            }
        }

        // Promote the incoming track (A+1).
        currentPlayer = incoming
        secondaryPlayer = NO_HANDLE

        // Until now it only carried the minimal crossfade error handling (see
        // triggerCrossfadeTransition); give it the full one now that it is the current player.
        setupPlayerEventsInternal(incoming)

        // It was mid fade-in, so its gain sits somewhere below full — open it back up. The user's
        // volume is untouched by the crossfade and already holds whatever the user set.
        fadeGains.remove(incoming)
        applyPlaybackLevels(incoming)

        crossfadeFromIndex = -1
        setCrossfading(false)
        Logger.d(TAG, "commitIncomingAsCurrent: committed track index $localCurrentMediaItemIndex")
    }

    /**
     * Perform the actual crossfade animation: a plain equal-power volume fade (spec section 3), the
     * outgoing gain following cos and the incoming sin over [effectiveDurationMs].
     *
     * @param effectiveDurationMs The actual crossfade duration to use. May be shorter than
     *   the configured [crossfadeDurationMs] if URL resolution / buffering consumed
     *   part of the crossfade window.
     */
    private suspend fun performCrossfade(
        nextIndex: Int,
        nextPlayer: Int,
        effectiveDurationMs: Int,
    ) {
        val steps = 50
        val delayPerStep = (effectiveDurationMs / steps).coerceAtLeast(20)
        val outgoingPlayer = currentPlayer

        Logger.d(
            TAG,
            "Crossfade animation: ${effectiveDurationMs}ms, $steps steps, ${delayPerStep}ms/step, " +
                "internalVolume=$internalVolume",
        )

        try {
            for (step in 0..steps) {
                currentCoroutineContext().ensureActive()

                val progress = step.toFloat() / steps
                val angle = progress * PI / 2.0

                // A pure 0..1 attenuation per handle: the user's level is multiplied in by
                // applyVolume and must come out of the crossfade exactly as it went in.
                setFadeGain(currentPlayer, cos(angle).toFloat())
                setFadeGain(nextPlayer, sin(angle).toFloat())

                delay(delayPerStep.toLong())
            }

            finalizeCrossfade(nextIndex, nextPlayer)
        } catch (e: CancellationException) {
            // Undo the fade on BOTH handles. Whichever one survives the cancel must not be left
            // attenuated; for the one that gets released it is harmless (the engine ignores calls
            // on released handles).
            endCrossfadeAudio(outgoingPlayer)
            endCrossfadeAudio(nextPlayer)

            if (committingIncomingPlayer) {
                // commitIncomingAsCurrent() is promoting nextPlayer to current — releasing it
                // here would kill the track the user just chose to keep.
                Logger.d(TAG, "Crossfade cancelled to commit incoming track — keeping its player")
            } else {
                Logger.d(TAG, "Crossfade cancelled")
                releaseHandle(nextPlayer)
                secondaryPlayer = NO_HANDLE
                setCrossfading(false)
            }
        }
    }

    /**
     * Finalize crossfade: swap players and cleanup
     */
    private fun finalizeCrossfade(
        nextIndex: Int,
        nextPlayer: Int,
    ) {
        Logger.d(TAG, "Crossfade complete, swapping players (index $nextIndex)")

        stopPositionUpdates()

        // Cleanup old current player
        currentPlayer.takeIf { it != NO_HANDLE }?.let { oldPlayer ->
            try {
                releaseHandle(oldPlayer)
            } catch (e: Exception) {
                Logger.w(TAG, "Error cleaning up old player: ${e.message}")
            }
        }

        // Promote secondary to current
        currentPlayer = nextPlayer
        secondaryPlayer = NO_HANDLE

        // Now set up the full event listener on the new current player
        // (replaces the minimal crossfade error handling)
        setupPlayerEventsInternal(nextPlayer)

        // Ensure correct volume: back to untouched playback.
        endCrossfadeAudio(nextPlayer)

        // Reset state
        setCrossfading(false)
        crossfadeFromIndex = -1
        transitionToState(InternalState.PLAYING)

        // Start position tracking
        startPositionUpdates()

        // Trigger next precache
        triggerPrecachingInternal()
    }

    // ========== DJ transitions ==========
    //
    // With crossfade and "DJ transitions" on, [djPipeline] renders the segment between the current
    // track A and the track the queue plays next, B (docs/ios-player-bridge.md §7). Once it is ready
    // the switch is ARMED: the segment (a local WAV) and B (its local file, at the resume point) are
    // loaded paused on two spare handles. At the join the engine starts the segment sample-aligned
    // to A's clock (playSynced), A fades out under it over DJ_OVERLAP_MS (the audio is the same on
    // both sides), and the segment becomes the current handle (MIXING). At B's melody entry the
    // track identity moves to B; at the end of the segment B starts aligned the same way and becomes
    // current. Anything unexpected falls back to the plain crossfade / advance.

    private fun djEnabled(): Boolean = crossfadeEnabled && djModeEnabled && !crossfadeSuppressed

    /** The item a DJ transition may go to from the current one: the actual next item, under the crossfade skip rules. */
    private fun djNextItem(): GenericMediaItem? {
        if (internalRepeatMode == PlayerConstants.REPEAT_MODE_ONE) return null
        val current = playlist.getOrNull(localCurrentMediaItemIndex) ?: return null
        val nextIndex = getNextMediaItemIndex()
        if (nextIndex == localCurrentMediaItemIndex) return null
        val next = playlist.getOrNull(nextIndex) ?: return null
        if (next.mediaId == current.mediaId) return null
        if (isCurrentTrackVideo() || isNextTrackVideo() || isCurrentTrackLive() || isNextTrackLive() || isWithinAlbum()) return null
        return next
    }

    /** After every serial task: tell the pipeline what to prepare when (current, next) changed. */
    private fun syncDjPipeline() {
        if (released) return
        val current = playlist.getOrNull(localCurrentMediaItemIndex)
        if (current !== smartFor) {
            // A new track: one smart-next decision for it. It is no longer anyone's "next" pick.
            smartFor = current
            smartDecided = false
            current?.let { userPlacedNext.remove(it) }
        }
        val pair =
            if (djEnabled()) {
                current?.mediaId to djNextItem()?.mediaId
            } else {
                null to null
            }
        val look =
            if (pair.second != null && djSmartOrderEnabled && !smartDecided) {
                smartWindow().drop(1).map { it.mediaId }
            } else {
                emptyList()
            }
        if (pair == djSentPair && look == djSentLook) return
        val pairChanged = pair != djSentPair
        djSentPair = pair
        djSentLook = look
        djPipeline.update(pair.first, pair.second, look)
        if (!pairChanged) return
        // Disarms a switch whose next item changed, or arms one already on disk.
        coroutineScope.launch { djRefreshInternal() }
    }

    // ----- Smart next -----
    //
    // With DJ transitions and "Smart order" on, the next SMART_WINDOW upcoming tracks (in play
    // order: the shuffled order under shuffle, so shuffle still decides what comes up and smart
    // next only picks among the next few) are analysed by the pipeline after the (current, next)
    // pair. Once they are all in, or the current track is down to SMART_DECIDE_BY_MS, the one that
    // mixes best with the current track (DjMatch) is moved to play next through the normal queue
    // move, when it is clearly better than the queue's own next. One decision per track; a user
    // edit of the queue asks for it again. Nothing is moved in front of, or looked for past, a
    // track the user put next themselves.

    /** The candidates in play order, [0] = the queue's own next (the DJ target). Serial context. */
    private fun smartWindow(): List<GenericMediaItem> {
        val current = playlist.getOrNull(localCurrentMediaItemIndex) ?: return emptyList()
        val next = djNextItem() ?: return emptyList()
        val upcoming: List<Int> =
            if (internalShuffleModeEnabled) {
                if (!shuffleOrderCovers(playlist.size)) return emptyList()
                val pos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: return emptyList()
                (pos + 1 until minOf(shuffleOrder.size, pos + 1 + SMART_SCAN_LIMIT)).map { shuffleOrder[it] }
            } else {
                (localCurrentMediaItemIndex + 1 until minOf(playlist.size, localCurrentMediaItemIndex + 1 + SMART_SCAN_LIMIT)).toList()
            }
        val out = ArrayList<GenericMediaItem>(SMART_WINDOW)
        val ids = HashSet<String>()
        for (index in upcoming) {
            if (out.size >= SMART_WINDOW) break
            val item = playlist.getOrNull(index) ?: break
            if (out.isEmpty()) {
                // Must be the track Next plays (not a wrap under Repeat All).
                if (item !== next) return emptyList()
                out.add(item)
                ids.add(item.mediaId)
                if (item in userPlacedNext) break
                continue
            }
            if (item in userPlacedNext) break
            val skip =
                item.mediaId == current.mediaId ||
                    item.mediaId in ids ||
                    (watchVideoEnabled && item.isVideo()) ||
                    LiveStreamRegistry.isLive(item.mediaId)
            if (skip) continue
            out.add(item)
            ids.add(item.mediaId)
        }
        return out
    }

    /** The queue changed by the user's hand: decide again (their own "next" stays where it is). */
    private fun smartQueueEditedByUser() {
        smartDecided = false
    }

    private fun smartOnAnalysed(
        id: String,
        analysis: DjTrackAnalysis?,
    ) {
        smartAnalyses.remove(id)
        smartAnalyses[id] = analysis
        while (smartAnalyses.size > SMART_MEMORY) smartAnalyses.remove(smartAnalyses.keys.first())
        smartMaybeDecideInternal()
    }

    /** Makes the decision for the current track once its candidates are in (or time is short). */
    private fun smartMaybeDecideInternal() {
        if (smartDecided || !djSmartOrderEnabled || !djEnabled()) return
        val current = playlist.getOrNull(localCurrentMediaItemIndex) ?: return
        if (current !== smartFor || dj?.phase == DjPhase.MIXING || isCrossfading) return
        val window = smartWindow()
        if (window.size < 2) return
        if (!smartAnalyses.containsKey(current.mediaId)) return
        val currentAnalysis = smartAnalyses[current.mediaId]
        val handle = currentPlayer
        val dur = if (handle != NO_HANDLE) effectiveDurationMs(handle) else 0L
        val left = if (dur > 0) dur - effectivePositionMs(handle) else Long.MAX_VALUE
        val allIn = window.all { smartAnalyses.containsKey(it.mediaId) }
        if (!allIn && left > SMART_DECIDE_BY_MS) return
        smartDecided = true
        if (currentAnalysis == null) return
        val next = window[0]
        fun bpm(a: DjTrackAnalysis?) = a?.let { ((it.bpm * 10).roundToInt() / 10.0).toString() } ?: "?"
        val head = "DJ next: current=${current.mediaId} (${bpm(currentAnalysis)},${currentAnalysis.keyName})"
        if (next in userPlacedNext) {
            Logger.i(TAG, "$head kept=${next.mediaId} (placed next by the user)")
            return
        }
        if (left < SMART_GIVE_UP_MS) {
            Logger.i(TAG, "$head kept=${next.mediaId} (only ${left / 1000} s left)")
            return
        }
        val analyses = window.map { smartAnalyses[it.mediaId] }
        val choice = DjMatch.choose(currentAnalysis.matchTraits(), analyses.map { it?.matchTraits() })
        fun fmt(x: Double?) = x?.let { ((it * 100).roundToInt() / 100.0).toString() } ?: "-"
        val list =
            window.indices.joinToString(" ") { i ->
                val a = analyses[i]
                val sc = choice.scores[i]
                "${window[i].mediaId}:${bpm(a)}/${a?.keyName ?: "?"}/${fmt(sc?.total)}" + if (sc?.matched == true) "m" else ""
            }
        val nextScore = fmt(choice.scores[0]?.total)
        if (!choice.moved) {
            Logger.i(TAG, "$head kept=${next.mediaId} score $nextScore; candidates: $list")
            return
        }
        val pick = window[choice.index]
        val from = playlist.indexOfFirst { it === pick }
        val moved =
            if (internalShuffleModeEnabled) {
                val to = (shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1) + 1
                moveShuffledItemInternal(shuffleIndices.getOrNull(from) ?: -1, to)
            } else {
                moveMediaItemInternal(from, localCurrentMediaItemIndex + 1)
            }
        Logger.i(
            TAG,
            "$head ${if (moved === pick) "moved" else "move failed"}=${pick.mediaId} from pos ${choice.index + 1} " +
                "score ${fmt(choice.scores[choice.index]?.total)} vs $nextScore; candidates: $list",
        )
    }

    /** Arms the ready transition for (current, next), or drops an armed one that no longer fits. */
    private fun djRefreshInternal() {
        val d = dj
        if (d != null && d.phase == DjPhase.MIXING) return
        val current = playlist.getOrNull(localCurrentMediaItemIndex)
        val next = if (djEnabled()) djNextItem() else null
        val ready = djPipeline.readyFor(current?.mediaId, next?.mediaId)
        if (d != null) {
            val stillValid =
                ready != null &&
                    d.ready.a == ready.a &&
                    d.ready.b == ready.b &&
                    d.toItem === next &&
                    d.fromItem === current &&
                    d.aHandle == currentPlayer
            if (stillValid) return
            abortDjInternal("queue or setting changed")
        }
        if (ready == null || current == null || next == null) return
        tryArmDjInternal(ready, current, next)
    }

    private fun tryArmDjInternal(
        ready: DjReadyTransition,
        current: GenericMediaItem,
        next: GenericMediaItem,
    ) {
        val a = currentPlayer
        if (a == NO_HANDLE || !isListened(a) || isCrossfading || !internalState.isInReadyState() || a in videoHandles) return
        val meta = ready.meta
        val pairKey = "${ready.a}>${ready.b}"
        val pos = engine.positionMs(a)
        if (pos > meta.aStartMs - DJ_ARM_LEAD_MS) {
            if (djLateLogged != pairKey) {
                djLateLogged = pairKey
                Logger.i(TAG, "DJ not armed ${ready.a}→${ready.b}: ready at $pos ms, join at ${meta.aStartMs} ms")
            }
            return
        }
        // The cached copy and the stream being played must be the same recording.
        val aDuration = effectiveDurationMs(a)
        val aTotalMs = meta.aTotalFrames * 1000L / meta.sampleRate
        if (aDuration > 0 && kotlin.math.abs(aDuration - aTotalMs) > DJ_DURATION_TOLERANCE_MS) {
            if (djLateLogged != pairKey) {
                djLateLogged = pairKey
                Logger.w(TAG, "DJ not armed ${ready.a}→${ready.b}: playing length $aDuration ms, analysed $aTotalMs ms")
            }
            return
        }
        val mix = engine.create(false)
        val b = engine.create(false)
        if (mix == NO_HANDLE || b == NO_HANDLE) {
            engine.release(mix)
            engine.release(b)
            return
        }
        // Both silent until their fade; the segment from its start, B from just before its resume
        // point so the two overlap at the end of the segment.
        fadeGains[mix] = 0f
        fadeGains[b] = 0f
        engine.load(mix, DjCache.fileUrl(ready.mixPath), emptyMap(), 0L, false, false)
        applyPlaybackLevels(mix)
        trueDurations = trueDurations + (b to meta.bTotalMs)
        engine.load(b, DjCache.fileUrl(ready.bAudioPath), emptyMap(), djBStartMs(meta), false, false)
        applyPlaybackLevels(b)
        // B keeps its place after A under shuffle, as a crossfade target does.
        if (internalShuffleModeEnabled) pinnedNextItems.add(next)
        val d =
            DjSwitch(
                ready = ready,
                fromItem = current,
                toItem = next,
                aHandle = a,
                mixHandle = mix,
                bHandle = b,
                aDurationMs = if (aDuration > 0) aDuration else aTotalMs,
            )
        dj = d
        d.job = coroutineScope.launch { runDjSwitch(d) }
        Logger.i(
            TAG,
            "DJ armed ${ready.a}→${ready.b}: join at A ${meta.aStartMs} ms, segment ${meta.mixLengthMs} ms, " +
                "B entry at +${meta.identityMs} ms, B resumes at ${meta.bResumeMs} ms",
        )
    }

    /** Where B's handle waits: just before its resume point, so it overlaps the segment's end. */
    private fun djBStartMs(meta: com.maxrave.data.mediaservice.dj.DjTransitionMeta): Long = (meta.bResumeMs - DJ_OVERLAP_MS).coerceAtLeast(0L)

    private fun djSpeed(): Float = internalPlaybackSpeed.coerceAtLeast(0.25f)

    /** Waits for [mediaMs] of media time at the playback speed. */
    private suspend fun djDelay(mediaMs: Long) {
        delay((mediaMs / djSpeed()).toLong().coerceAtLeast(1L))
    }

    /**
     * Whether [follow] (started at its [followFromMs]) runs in step with [lead] (which was at
     * [leadAtMs] when [follow] was meant to start), and has actually started.
     */
    private fun djAligned(
        lead: Int,
        leadAtMs: Long,
        follow: Int,
        followFromMs: Long,
    ): Boolean {
        val followed = engine.positionMs(follow) - followFromMs
        if (followed <= 0L) return false
        val led = engine.positionMs(lead) - leadAtMs
        return kotlin.math.abs(followed - led) <= DJ_ALIGN_TOLERANCE_MS
    }

    /** The switch's timeline, from ARMED to B being current. Runs on the serial context. */
    private suspend fun runDjSwitch(d: DjSwitch) {
        val meta = d.ready.meta
        // --- ARMED: wait for A to reach the join.
        while (true) {
            if (dj !== d) return
            if (internalState != InternalState.PLAYING || !internalPlayWhenReady || currentPlayer != d.aHandle) {
                delay(100)
                continue
            }
            val remaining = meta.aStartMs - engine.positionMs(d.aHandle)
            if (remaining < -DJ_JOIN_LATE_MS) {
                abortDjInternal("A is past the join (${-remaining} ms)")
                return
            }
            if (remaining > DJ_SYNC_LEAD_MS) {
                djDelay((remaining - DJ_SYNC_LEAD_MS).coerceIn(20L, 500L))
                continue
            }
            if (!d.mixIssued) {
                // The engine starts it at the join itself, from A's own clock.
                engine.playSynced(d.mixHandle, d.aHandle, meta.aStartMs)
                d.mixIssued = true
            }
            if (remaining > 0) {
                djDelay(remaining.coerceAtMost(10L))
                continue
            }
            break
        }
        // The segment has to be running, in step with A, before A goes; otherwise A just goes on.
        var waited = 0L
        while (!djAligned(d.aHandle, meta.aStartMs, d.mixHandle, 0L) && waited < DJ_START_TIMEOUT_MS) {
            delay(10)
            waited += 10
        }
        if (dj !== d) return
        if (!djAligned(d.aHandle, meta.aStartMs, d.mixHandle, 0L)) {
            abortDjInternal(
                "segment not in step at the join (A +${engine.positionMs(d.aHandle) - meta.aStartMs} ms, " +
                    "segment ${engine.positionMs(d.mixHandle)} ms)",
            )
            return
        }
        val joinError = (engine.positionMs(d.mixHandle)) - (engine.positionMs(d.aHandle) - meta.aStartMs)
        djRamp(d.aHandle, d.mixHandle)
        if (dj !== d) return

        // --- MIXING: the segment is the current handle; A stays paused (muted) until B's entry,
        // so a seek before it can still go back to A.
        d.phase = DjPhase.MIXING
        val view =
            DjView(
                mixHandle = d.mixHandle,
                aStartMs = meta.aStartMs,
                aDurationMs = d.aDurationMs,
                bResumeMs = meta.bResumeMs,
                bDurationMs = meta.bTotalMs,
                mixLengthMs = meta.mixLengthMs,
            )
        // The view first: readers on other threads map the segment's clock as soon as it is current.
        djView = view
        promoteDjHandleInternal(d.mixHandle)
        fadeGains[d.aHandle] = 0f
        applyVolume(d.aHandle)
        engine.pause(d.aHandle)
        Logger.i(TAG, "DJ switch ${d.ready.a}→${d.ready.b}: joined at A ${meta.aStartMs} ms, measured offset $joinError ms")

        val switchAt = meta.mixLengthMs - DJ_OVERLAP_MS
        while (true) {
            if (dj !== d) return
            if (internalState != InternalState.PLAYING || !internalPlayWhenReady) {
                delay(100)
                continue
            }
            val mixPos = engine.positionMs(d.mixHandle)
            if (!d.identityPassed && mixPos >= meta.identityMs) {
                if (!passDjIdentityInternal(d)) return
            }
            val remaining = switchAt - mixPos
            if (remaining > DJ_SYNC_LEAD_MS) {
                val untilIdentity = if (d.identityPassed) Long.MAX_VALUE else meta.identityMs - mixPos
                djDelay(minOf(remaining - DJ_SYNC_LEAD_MS, untilIdentity, 500L).coerceAtLeast(20L))
                continue
            }
            if (!d.bIssued && d.bHandle != NO_HANDLE) {
                engine.playSynced(d.bHandle, d.mixHandle, switchAt)
                d.bIssued = true
            }
            if (remaining > 0) {
                djDelay(remaining.coerceAtMost(10L))
                continue
            }
            break
        }
        finishDjInternal(d, switchAt)
    }

    /** Linear crossfade between two handles playing the same, aligned audio: the sum stays constant. */
    private suspend fun djRamp(
        out: Int,
        into: Int,
    ) {
        val steps = 5
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            setFadeGain(out, 1f - t)
            setFadeGain(into, t)
            if (i < steps) delay(DJ_OVERLAP_MS / steps)
        }
    }

    /** The track identity moves to B (its melody entry). False when B left the queue (handled here). */
    private fun passDjIdentityInternal(d: DjSwitch): Boolean {
        if (d.identityPassed) return true
        val index = playlist.indexOfFirst { it === d.toItem }
        if (index < 0) {
            Logger.w(TAG, "DJ ${d.ready.a}→${d.ready.b}: B left the queue mid-segment, moving on")
            val playing = internalPlayWhenReady
            abortDjInternal("B removed")
            val nextIndex = getNextMediaItemIndex()
            if (nextIndex != localCurrentMediaItemIndex) {
                localCurrentMediaItemIndex = nextIndex
                unpinCurrent()
                releaseHandle(currentPlayer)
                currentPlayer = NO_HANDLE
                loadAndPlayTrackInternal(nextIndex, 0L, playing)
            }
            return false
        }
        d.identityPassed = true
        djView?.identityPassed = true
        localCurrentMediaItemIndex = index
        unpinCurrent()
        notifyListeners {
            onMediaItemTransition(d.toItem, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        }
        if (d.aHandle != currentPlayer) releaseHandle(d.aHandle)
        // B plays from its local file now; the stream precached for it is no longer needed.
        precachedPlayers.remove(d.toItem.mediaId)?.let { cleanupPlayerInternal(it.player) }
        Logger.i(TAG, "DJ identity ${d.ready.a}→${d.ready.b}: now B at $currentPosition ms")
        return true
    }

    /** End of the segment: B (started in step) fades in, then becomes the current handle. */
    private suspend fun finishDjInternal(
        d: DjSwitch,
        switchAt: Long,
    ) {
        val b = d.bHandle
        if (b != NO_HANDLE) {
            val bStart = djBStartMs(d.ready.meta)
            var waited = 0L
            while (!djAligned(d.mixHandle, switchAt, b, bStart) && waited < DJ_START_TIMEOUT_MS) {
                delay(10)
                waited += 10
            }
            if (dj !== d) return
            if (djAligned(d.mixHandle, switchAt, b, bStart)) {
                d.finishing = true
                djRamp(d.mixHandle, b)
                if (dj !== d) return
            } else {
                // Not in step: B is put where the segment got to instead (a short cut, never a jump).
                Logger.w(TAG, "DJ ${d.ready.a}→${d.ready.b}: B not in step at the end of the segment, cutting to it")
            }
        }
        commitDjInternal(d, toB = true)
    }

    /**
     * Ends a running segment on the track the user is looking at, at its mapped position: B ([toB])
     * or A. ARMED: nothing audible changes, the switch is just dropped (A stays current).
     */
    private fun commitDjInternal(
        d: DjSwitch,
        toB: Boolean,
    ) {
        if (dj !== d) return
        if (d.phase == DjPhase.ARMED) {
            abortDjInternal("committed before the join")
            return
        }
        d.job?.cancel()
        val meta = d.ready.meta
        val playing = internalState == InternalState.PLAYING && internalPlayWhenReady
        val mixPos = engine.positionMs(d.mixHandle)
        if (toB) {
            if (!passDjIdentityInternal(d)) return
            val target = (meta.bResumeMs - (meta.mixLengthMs - mixPos)).coerceAtLeast(0L)
            val b = d.bHandle
            if (b == NO_HANDLE) {
                // B's handle failed: load B the normal way at the same point.
                abortDjInternal("B handle lost")
                releaseHandle(currentPlayer)
                currentPlayer = NO_HANDLE
                loadAndPlayTrackInternal(localCurrentMediaItemIndex, target, playing)
                return
            }
            if (!d.finishing) {
                // Not the natural end (Next, seek, error, B out of step): B goes where the segment had got to.
                engine.pause(b)
                engine.seek(b, target)
            }
            promoteDjHandleInternal(b)
            if (playing) engine.play(b)
        } else {
            val a = d.aHandle
            engine.seek(a, (meta.aStartMs + mixPos).coerceAtLeast(0L))
            promoteDjHandleInternal(a)
            if (playing) engine.play(a)
        }
        val kept = currentPlayer
        dj = null
        djView = null
        listOf(d.aHandle, d.mixHandle, d.bHandle).filter { it != NO_HANDLE && it != kept }.distinct().forEach { releaseHandle(it) }
        Logger.i(
            TAG,
            "DJ done ${d.ready.a}→${d.ready.b}: on ${if (toB) "B" else "A"} at ${engine.positionMs(kept)} ms" +
                if (toB && d.finishing) " (resume point ${meta.bResumeMs} ms)" else " (committed early)",
        )
        startPositionUpdates()
        triggerPrecachingInternal()
        // B→C may already be ready (it was prepared while the segment played).
        djRefreshInternal()
    }

    /** [handle] becomes the current, listened handle at full level. */
    private fun promoteDjHandleInternal(handle: Int) {
        currentPlayer = handle
        setupPlayerEventsInternal(handle)
        fadeGains.remove(handle)
        applyPlaybackLevels(handle)
    }

    /** Drops the switch: every DJ handle except the current one is released, A back at full level. */
    private fun abortDjInternal(reason: String) {
        val d = dj ?: return
        dj = null
        djView = null
        d.job?.cancel()
        listOf(d.aHandle, d.mixHandle, d.bHandle).filter { it != NO_HANDLE && it != currentPlayer }.distinct().forEach { releaseHandle(it) }
        if (currentPlayer == d.aHandle) {
            fadeGains.remove(d.aHandle)
            applyVolume(d.aHandle)
        }
        Logger.i(TAG, "DJ off ${d.ready.a}→${d.ready.b} (${d.phase}): $reason")
    }

    /** Pausing: a synced start already scheduled must not fire into the pause. */
    private fun djBeforePauseInternal() {
        val d = dj ?: return
        when (d.phase) {
            DjPhase.ARMED ->
                if (d.mixIssued) {
                    if (engine.positionMs(d.aHandle) >= d.ready.meta.aStartMs) {
                        // Inside the join: A carries on (it is the same audio), the switch is dropped.
                        abortDjInternal("paused at the join")
                    } else {
                        engine.pause(d.mixHandle)
                        engine.seek(d.mixHandle, 0L)
                        d.mixIssued = false
                    }
                }
            DjPhase.MIXING ->
                if (d.finishing) {
                    commitDjInternal(d, toB = true)
                } else if (d.bIssued) {
                    engine.pause(d.bHandle)
                    engine.seek(d.bHandle, djBStartMs(d.ready.meta))
                    d.bIssued = false
                }
        }
    }

    /** Seeking: ARMED re-arms the segment; MIXING commits to the track shown (A before B's entry, else B). */
    private fun djBeforeSeekInternal() {
        val d = dj ?: return
        when (d.phase) {
            DjPhase.ARMED -> {
                if (d.mixIssued) {
                    engine.pause(d.mixHandle)
                    engine.seek(d.mixHandle, 0L)
                    d.mixIssued = false
                }
                if (currentPlayer == d.aHandle) {
                    fadeGains.remove(d.aHandle)
                    applyVolume(d.aHandle)
                }
                fadeGains[d.mixHandle] = 0f
                applyVolume(d.mixHandle)
            }
            DjPhase.MIXING -> commitDjInternal(d, toB = d.identityPassed)
        }
    }

    /** Next while the segment still shows A: B is what Next means, and it is already playing. */
    private fun djCommitForNextInternal(): Boolean {
        val d = dj ?: return false
        if (d.phase != DjPhase.MIXING) return false
        val wasShowingA = !d.identityPassed
        commitDjInternal(d, toB = true)
        return wasShowingA
    }

    /** Commits a running segment to the track shown, before Previous evaluates the position. */
    private fun djCommitDisplayedInternal() {
        val d = dj ?: return
        if (d.phase == DjPhase.MIXING) commitDjInternal(d, toB = d.identityPassed)
    }

    /** A DJ handle failed: fall back. True when [handle] was one of them and is dealt with. */
    private fun djHandleFailedInternal(handle: Int): Boolean {
        val d = dj ?: return false
        if (handle != d.mixHandle && handle != d.bHandle && handle != d.aHandle) return false
        if (d.phase == DjPhase.ARMED) {
            if (handle == d.aHandle) return false // A's own error: the normal path (its reload drops the switch).
            abortDjInternal("DJ handle $handle failed while armed")
            return true
        }
        when (handle) {
            d.bHandle -> {
                Logger.w(TAG, "DJ: B's handle failed, B will be loaded normally")
                releaseHandle(handle)
                d.bHandle = NO_HANDLE
            }
            d.mixHandle -> {
                Logger.w(TAG, "DJ: the segment failed mid-way, going to B")
                commitDjInternal(d, toB = true)
            }
            // A is muted and paused by now; it is only kept for a seek back to it.
            else -> Unit
        }
        return true
    }

    // ========== Position Updates ==========

    /**
     * Start position updates (periodic polling for crossfade detection).
     *
     * Position, duration and buffered range are read from the engine on demand, so unlike the mpv
     * loop this one caches nothing: it only watches for the crossfade trigger near the end of the
     * track. Each iteration is a serial task, so it also lets syncSystemIntegration() pick up a
     * duration that has just become known for the lock screen.
     */
    private fun startPositionUpdates() {
        stopPositionUpdates()

        positionUpdateJob =
            coroutineScope.launch {
                while (isActive && currentPlayer != NO_HANDLE) {
                    try {
                        if (internalState.isInReadyState()) {
                            // The engine's own end comes too late for streams it over-measures.
                            checkTrueEndInternal()
                        }
                        // A transition that became ready while the track was still loading.
                        if (dj == null && djEnabled() && internalState.isInReadyState()) djRefreshInternal()
                        // Smart next: decide with what is analysed once the track nears its end.
                        if (!smartDecided && internalState.isInReadyState()) smartMaybeDecideInternal()
                        if (internalState.isInReadyState()) {
                            // Check if should trigger crossfade.
                            // Use currentPlayer's time (not the timeline player) for trigger detection.
                            // Block when paused (internalPlayWhenReady=false) to prevent
                            // crossfade during queue restore.
                            if (crossfadeEnabled &&
                                !crossfadeSuppressed &&
                                internalRepeatMode != PlayerConstants.REPEAT_MODE_ONE &&
                                !isCrossfading &&
                                // An armed or running DJ switch owns this transition.
                                dj == null &&
                                internalPlayWhenReady &&
                                !isCurrentTrackVideo() &&
                                !isNextTrackVideo() &&
                                !isCurrentTrackLive() &&
                                !isNextTrackLive() &&
                                !isCurrentTrackTooShortForCrossfade() &&
                                !isWithinAlbum()
                            ) {
                                val player = currentPlayer
                                if (player != NO_HANDLE) {
                                    val dur = effectiveDurationMs(player)
                                    val pos = effectivePositionMs(player)
                                    if (dur > 0 && pos > 0) {
                                        val timeRemaining = dur - pos
                                        val nextVideoId = playlist.getOrNull(getNextMediaItemIndex())?.mediaId
                                        val isPrecached = nextVideoId != null && precachedPlayers.containsKey(nextVideoId)
                                        val preparationBufferMs = if (isPrecached) 0L else 3000L
                                        val triggerThreshold = configuredCrossfadeDurationMs().toLong() + preparationBufferMs
                                        if (timeRemaining in 1..triggerThreshold) {
                                            if (hasNextMediaItem()) {
                                                val nextIndex = getNextMediaItemIndex()
                                                triggerCrossfadeTransition(nextIndex)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Ignore query errors
                    }

                    delay(POSITION_POLL_MS)
                }
            }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    /**
     * Trigger precaching
     */
    private fun triggerPrecachingInternal() {
        if (!precacheEnabled || playlist.isEmpty()) return

        cancelPrecaching()
        Logger.d(TAG, "Trigger precache")
        precacheJob =
            coroutineScope.launch {
                try {
                    // The tracks Next will actually play, in Next's own order: shuffle-aware, wrapping
                    // under Repeat All. Picking `index + i` in playlist order precached tracks a shuffled
                    // queue never reached, and nothing released them until the queue was edited (#2619).
                    // Read before the job first suspends, so an edit landing meanwhile cannot shift them.
                    val targets = mutableListOf<GenericMediaItem>()
                    var from = localCurrentMediaItemIndex
                    for (i in 1..maxPrecacheCount) {
                        val next = getNextMediaItemIndex(from)
                        if (next == from || next == localCurrentMediaItemIndex) break
                        playlist.getOrNull(next)?.let { targets += it }
                        from = next
                    }

                    // Release every precache Next no longer reaches: the shuffle picks it skipped, the
                    // tracks ahead of a Previous or a jump. The current track's entry stays: a trigger
                    // can run between the index moving and that track's load taking its entry.
                    val targetIds = targets.mapTo(HashSet()) { it.mediaId }
                    val currentId = currentMediaItem?.mediaId
                    val iterator = precachedPlayers.entries.iterator()
                    while (iterator.hasNext()) {
                        val (id, cached) = iterator.next()
                        if (id !in targetIds && id != currentId) {
                            cleanupPlayerInternal(cached.player)
                            iterator.remove()
                        }
                    }

                    for (mediaItem in targets) {
                        if (!isActive) break

                        // Checked here, not when the targets were picked: one track can be both targets
                        // (a radio repeats songs), and a second insert would overwrite the first player
                        // without releasing it. A live broadcast is not precached: buffered ahead, it
                        // would start behind the live edge by however long it waited in the queue.
                        if (precachedPlayers.containsKey(mediaItem.mediaId) || LiveStreamRegistry.isLive(mediaItem.mediaId)) {
                            continue
                        }

                        // Network extraction on IO, engine calls on the serial context
                        val source = withContext(Dispatchers.IO) { extractPlayableUrl(mediaItem) }

                        if (source != null && source.url.isNotEmpty()) {
                            try {
                                // Loading held paused is the engine's "prepare": it buffers ahead.
                                val player = createMediaPlayerInternal(source)
                                if (player == NO_HANDLE) {
                                    Logger.w(TAG, "Precaching skipped for ${mediaItem.mediaId}: could not create engine player")
                                } else {
                                    applyPlaybackLevels(player)
                                    loadFile(player, source, startPaused = true)
                                    precachedPlayers[mediaItem.mediaId] =
                                        PrecachedPlayer(player, mediaItem, source)
                                    Logger.d(TAG, "Precached player for ${mediaItem.mediaId}")
                                }
                            } catch (e: Exception) {
                                Logger.e(TAG, "Precaching error for ${mediaItem.mediaId}: ${e.message}")
                            }
                        }

                        delay(100)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e(TAG, "Precaching error: ${e.message}")
                    }
                }
            }
    }

    private fun cancelPrecaching() {
        precacheJob?.cancel()
        precacheJob = null
    }

    private fun clearPrecacheExceptCurrentInternal() {
        Logger.d(TAG, "Clearing precache")
        val currentId = currentMediaItem?.mediaId
        val iterator = precachedPlayers.entries.iterator()
        while (iterator.hasNext()) {
            val (videoId, cached) = iterator.next()
            if (videoId != currentId) {
                cleanupPlayerInternal(cached.player)
                iterator.remove()
            }
        }
    }

    private fun clearAllPrecacheInternal() {
        Logger.d(TAG, "Clearing all precache")
        precachedPlayers.values.forEach { cleanupPlayerInternal(it.player) }
        precachedPlayers.clear()
    }

    /**
     * Dispatch listener notifications on the serial context.
     * Listeners (the handler) use thread-safe StateFlow updates and may contain runBlocking calls
     * that would deadlock on Main.
     */
    private fun notifyListeners(block: MediaPlayerListener.() -> Unit) {
        coroutineScope.launch {
            listeners.forEach(block)
        }
    }

    // ========== Shuffle Management ==========

    private fun createShuffleOrder() {
        // A rebuilt order puts no track in any particular place, the pinned ones included.
        pinnedNextItems.clear()
        if (playlist.isEmpty()) {
            shuffleIndices.clear()
            shuffleOrder.clear()
            return
        }

        val indices = playlist.indices.toMutableList()
        val currentIndex = localCurrentMediaItemIndex
        if (currentIndex in indices) {
            indices.removeAt(currentIndex)
        }

        indices.shuffle()

        if (currentIndex in playlist.indices) {
            indices.add(0, currentIndex)
        }

        shuffleOrder.clear()
        shuffleOrder.addAll(indices)

        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, originalIndex ->
            shuffleIndices[originalIndex] = shuffledPos
        }

        Logger.d(TAG, "Created shuffle order: $shuffleOrder")
    }

    private fun clearShuffleOrder() {
        shuffleIndices.clear()
        shuffleOrder.clear()
        pinnedNextItems.clear()
    }

    private fun insertIntoShuffleOrder(
        insertedOriginalIndex: Int,
        afterShufflePos: Int,
    ) {
        if (playlist.isEmpty() || insertedOriginalIndex !in playlist.indices) return

        for (i in shuffleOrder.indices) {
            if (shuffleOrder[i] >= insertedOriginalIndex) {
                shuffleOrder[i]++
            }
        }

        val insertPos = (afterShufflePos + 1).coerceIn(0, shuffleOrder.size)
        shuffleOrder.add(insertPos, insertedOriginalIndex)

        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, origIndex ->
            if (origIndex < shuffleIndices.size) {
                shuffleIndices[origIndex] = shuffledPos
            }
        }
    }

    /**
     * Gives the track just appended to [playlist] a random place after the current one, leaving
     * every other track where it was. Rebuilding the order here (createShuffleOrder) reshuffled the
     * whole queue on every Add to queue and on every track an endless queue appended, and put tracks
     * already played back in front of the listener.
     *
     * The slots right after the current track that hold [pinnedNextItems] are skipped: a Play next
     * track, and the track a crossfade has picked. The fade chooses its next index first and only
     * makes it current after resolving and loading it, so a track inserted ahead of it meanwhile
     * would end up behind Now Playing. Every other slot stays open, so a queue built by appends is
     * still a fair shuffle, its second track included.
     *
     * The full rebuild stays as the fallback for an order that no longer covers the playlist. On
     * desktop appends arrived on the caller's thread while the player thread edited the order; here
     * both run on the serial context, so the fallback should never fire, but it is kept as the repair.
     */
    private fun insertAppendedIntoShuffleOrder() {
        val appendedIndex = playlist.lastIndex
        if (!shuffleOrderCovers(appendedIndex)) {
            createShuffleOrder()
            return
        }
        val orderSize = shuffleOrder.size
        val currentShufflePos =
            (shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1).coerceIn(-1, orderSize - 1)
        var firstOpenPos = currentShufflePos + 1
        while (firstOpenPos < orderSize) {
            val item = shuffleOrder.getOrNull(firstOpenPos)?.let { playlist.getOrNull(it) } ?: break
            if (item !in pinnedNextItems) break
            firstOpenPos++
        }
        insertIntoShuffleOrder(appendedIndex, Random.nextInt(firstOpenPos, orderSize + 1) - 1)
    }

    /** Whether [shuffleOrder] is a permutation of the playlist indices `0 until size`. */
    private fun shuffleOrderCovers(size: Int): Boolean {
        if (shuffleOrder.size != size || shuffleIndices.size != size) return false
        val seen = BooleanArray(size)
        for (i in 0 until size) {
            val index = shuffleOrder.getOrNull(i) ?: return false
            if (index !in 0 until size || seen[index]) return false
            seen[index] = true
        }
        return true
    }

    /**
     * Releases the pin of the track that just became current. It has had its turn: still pinned, it
     * would hold every later append back once the queue wraps around to it again.
     */
    private fun unpinCurrent() {
        playlist.getOrNull(localCurrentMediaItemIndex)?.let { pinnedNextItems.remove(it) }
    }

    /**
     * Takes [removedOriginalIndex] — already removed from the playlist — out of the shuffled order,
     * renumbering the playlist indices after it, so every other track keeps its place in the order.
     */
    private fun removeFromShuffleOrder(removedOriginalIndex: Int) {
        shuffleOrder.remove(removedOriginalIndex)
        for (i in shuffleOrder.indices) {
            if (shuffleOrder[i] > removedOriginalIndex) {
                shuffleOrder[i]--
            }
        }
        rebuildShuffleIndices()
    }

    /** Re-derives the playlist index → shuffled position map from [shuffleOrder]. */
    private fun rebuildShuffleIndices() {
        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, originalIndex ->
            if (originalIndex in shuffleIndices.indices) {
                shuffleIndices[originalIndex] = shuffledPos
            }
        }
    }

    private fun getShuffledMediaItemList(): List<GenericMediaItem> = liveQueueView().shuffledList()

    private fun notifyTimelineChanged(reason: String) {
        val list = getShuffledMediaItemList()
        notifyListeners { onTimelineChanged(list, reason) }
    }

    fun setPrecachingEnabled(enabled: Boolean) {
        runSerial {
            precacheEnabled = enabled
            if (!enabled) {
                clearPrecacheExceptCurrentInternal()
            } else {
                triggerPrecachingInternal()
            }
        }
    }

    // ========== URL Extraction ==========

    /**
     * Extract the playable URL for a media item. The engine takes one URL per load (spec section 3,
     * Sources), so there is never anything to merge: a video plays from its muxed stream
     * ([muxedVideoSource]), everything else from its AAC audio stream.
     */
    private suspend fun extractPlayableUrl(mediaItem: GenericMediaItem): PlayableSource? {
        Logger.w(TAG, "Extracting playable URL for ${mediaItem.mediaId}")
        val videoId = mediaItem.mediaId

        // A downloaded song plays from its file, no network. Its true length is the one recorded at
        // download time (0 = unknown, use the engine's): see IosDownloadFiles.
        com.maxrave.data.download.IosDownloadFiles.localFileUrl(videoId)?.let { fileUrl ->
            return PlayableSource(isVideo = false, url = fileUrl, trueDurationMs = com.maxrave.data.download.IosDownloadFiles.durationMs(videoId))
        }

        // A live broadcast is one HLS playlist carrying audio and video together, and nothing about
        // it is in the format table (see StreamRepositoryImpl).
        val shouldFindVideo =
            mediaItem.isVideo() &&
                dataStoreManager.watchVideoInsteadOfPlayingAudio.first() == DataStoreManager.TRUE
        if (LiveStreamRegistry.isLive(videoId)) return liveStreamSource(videoId, asVideo = shouldFindVideo)

        // A video is one muxed stream (spec section 3, Sources). Without one the track still plays,
        // as audio, through the path below.
        if (shouldFindVideo) {
            muxedVideoSource(videoId)?.let { return it }
            Logger.w(TAG, "No muxed video stream for $videoId, playing its audio")
        }

        // Try new format API
        streamRepository.getNewFormat(videoId).lastOrNull()?.let { format ->
            val audioUrl = format.audioUrl
            if (!audioUrl.isNullOrEmpty()) {
                val is403Url = streamRepository.is403Url(audioUrl).firstOrNull() != false
                if (!is403Url) {
                    Logger.w("Stream", "Audio from format")
                    return PlayableSource(
                        isVideo = false,
                        url = audioUrl,
                        trueDurationMs = trueDurationOf(audioUrl, isLive = false, lengthSeconds = format.lengthSeconds),
                    )
                }
            }
        }

        // Fallback to stream extraction
        val audioUrl =
            streamRepository
                .getStream(
                    dataStoreManager,
                    videoId,
                    isDownloading = false,
                    isVideo = false,
                ).lastOrNull()
        if (audioUrl != null) {
            Logger.d(TAG, "Stream Audio $audioUrl")
            // getStream above is also where a live broadcast is first recognised.
            val isLive = LiveStreamRegistry.isLive(videoId)
            // getStream has just stored the format row; its lengthSeconds backs up a URL without dur=.
            val lengthSeconds = if (isLive) null else streamRepository.getNewFormat(videoId).lastOrNull()?.lengthSeconds
            return PlayableSource(
                isVideo = false,
                url = audioUrl,
                isLive = isLive,
                trueDurationMs = trueDurationOf(audioUrl, isLive, lengthSeconds),
            )
        }

        return null
    }

    /**
     * A music video as AVPlayer plays it: the HLS manifest or the progressive H.264/AAC MP4 (itag
     * 18), which StreamRepositoryImpl picks on iOS. The cached format row is tried first; a row
     * written before that choice existed holds a video-only stream, so its URL is checked before use.
     */
    private suspend fun muxedVideoSource(videoId: String): PlayableSource? {
        streamRepository.getNewFormat(videoId).lastOrNull()?.let { format ->
            val videoUrl = format.videoUrl
            if (!videoUrl.isNullOrEmpty() && isMuxedForAvPlayer(videoUrl) &&
                streamRepository.is403Url(videoUrl).firstOrNull() == false
            ) {
                Logger.w("Stream", "Video from format")
                return PlayableSource(
                    isVideo = true,
                    url = videoUrl,
                    trueDurationMs = trueDurationOf(videoUrl, isLive = false, lengthSeconds = format.lengthSeconds),
                )
            }
        }
        val videoUrl =
            streamRepository
                .getStream(
                    dataStoreManager,
                    videoId,
                    isDownloading = false,
                    isVideo = true,
                ).lastOrNull()
        if (videoUrl.isNullOrEmpty()) return null
        // getStream is also where a live broadcast is first recognised; its playlist carries the audio.
        if (LiveStreamRegistry.isLive(videoId)) return PlayableSource(isVideo = true, url = videoUrl, isLive = true)
        if (!isMuxedForAvPlayer(videoUrl)) return null
        Logger.d(TAG, "Stream Video for $videoId")
        val lengthSeconds = streamRepository.getNewFormat(videoId).lastOrNull()?.lengthSeconds
        return PlayableSource(
            isVideo = true,
            url = videoUrl,
            trueDurationMs = trueDurationOf(videoUrl, isLive = false, lengthSeconds = lengthSeconds),
        )
    }

    /** An HLS manifest or the muxed itag 18: a URL AVPlayer plays with sound and picture. */
    private fun isMuxedForAvPlayer(url: String): Boolean =
        url.contains(".m3u8") || url.contains("manifest") || ITAG_PARAM.find(url)?.groupValues?.getOrNull(1) == MUXED_ITAG

    /** A live broadcast's HLS playlist, resolved afresh on every play — it expires within hours. */
    private suspend fun liveStreamSource(
        videoId: String,
        asVideo: Boolean,
    ): PlayableSource? =
        streamRepository
            .getStream(
                dataStoreManager,
                videoId,
                isDownloading = false,
                isVideo = asVideo,
            ).lastOrNull()
            ?.let { liveHlsUrl ->
                Logger.d(TAG, "Live stream $videoId: $liveHlsUrl")
                PlayableSource(isVideo = asVideo, url = liveHlsUrl, isLive = true)
            }

    // ========== Engine events ==========

    /**
     * The engine's listener. Every call arrives on Main and is re-dispatched onto the serial context
     * before any state is touched, in arrival order — the counterpart of the mpv listener's
     * `coroutineScope.launch` from the event pump thread.
     */
    private val engineListener =
        object : NativePlayerEngineListener {
            override fun onReady(
                handle: Int,
                durationMs: Long,
            ) {
                coroutineScope.launch { onEngineReady(handle) }
            }

            override fun onPlayingChanged(
                handle: Int,
                playing: Boolean,
            ) {
                coroutineScope.launch { if (playing) onEnginePlaying(handle) else onEnginePaused(handle) }
            }

            override fun onBufferingChanged(
                handle: Int,
                buffering: Boolean,
            ) {
                coroutineScope.launch { onEngineBuffering(handle, buffering) }
            }

            override fun onEnded(handle: Int) {
                coroutineScope.launch { onEngineEnded(handle) }
            }

            override fun onError(
                handle: Int,
                httpStatus: Int,
                code: Int,
                message: String,
            ) {
                coroutineScope.launch { onEngineError(handle, httpStatus, code, message) }
            }

            // Not sent by the engine: the video views read size and first frame off their own
            // AVPlayerLayer (spec section 5).
            override fun onVideoSize(
                handle: Int,
                width: Int,
                height: Int,
            ) = Unit

            override fun onFirstVideoFrame(handle: Int) = Unit

            override fun onRemoteCommand(
                command: Int,
                positionMs: Long,
            ) {
                coroutineScope.launch { onEngineRemoteCommand(command, positionMs) }
            }

            override fun onInterruption(
                began: Boolean,
                shouldResume: Boolean,
            ) {
                coroutineScope.launch { onEngineInterruption(began, shouldResume) }
            }

            override fun onOutputDeviceLost() {
                coroutineScope.launch {
                    Logger.d(TAG, "Output device lost — pausing")
                    pause()
                }
            }
        }

    /** mpv's `playing()` for a load that is meant to play; a paused load (precache, restore) stays READY. */
    private fun onEngineReady(handle: Int) {
        if (!isListened(handle)) return
        // A load meant to play is still loading until audio actually starts (onEnginePlaying).
        if (internalPlayWhenReady) markPlaying() else finishAwaitingEngineStart()
    }

    /** mpv's `playing()`. */
    private fun onEnginePlaying(handle: Int) {
        if (!isListened(handle)) return
        eventPlayerPlaying = true
        pendingPausedJob?.cancel()
        pendingPausedJob = null
        // A late "playing" from before a pause the user already made must not flip the UI back.
        if (!internalPlayWhenReady) return
        finishAwaitingEngineStart()
        markPlaying()
    }

    private fun markPlaying() {
        if (internalState != InternalState.PLAYING) {
            transitionToState(InternalState.PLAYING)
            // Reset retry counter on successful playback
            retryCount = 0
            retryVideoId = null
        }
    }

    /**
     * mpv's `paused()`. Our own pause() has already moved to PAUSED, so this only matters for a pause
     * the system made. AVPlayer also reports not-playing for a stall and just before the end of the
     * item, and neither is a pause: the transition waits [PAUSE_SETTLE_MS] and is dropped if
     * buffering, playing again or onEnded (ended wins) arrives meanwhile.
     */
    private fun onEnginePaused(handle: Int) {
        if (!isListened(handle)) return
        eventPlayerPlaying = false
        if (internalState != InternalState.PLAYING || eventPlayerBuffering) return
        pendingPausedJob?.cancel()
        pendingPausedJob =
            coroutineScope.launch {
                delay(PAUSE_SETTLE_MS)
                if (isListened(handle) &&
                    internalState == InternalState.PLAYING &&
                    !eventPlayerPlaying &&
                    !eventPlayerBuffering
                ) {
                    transitionToState(InternalState.PAUSED)
                }
            }
    }

    /** mpv's `buffering()`: report loading only while the user wants playback. */
    private fun onEngineBuffering(
        handle: Int,
        buffering: Boolean,
    ) {
        if (!isListened(handle)) return
        eventPlayerBuffering = buffering
        if (buffering) {
            // A stall, not a pause.
            pendingPausedJob?.cancel()
            pendingPausedJob = null
        }
        // The initial buffering of a fresh load is already reported as loading, and its end is
        // announced by onEnginePlaying (buffering=false arrives just before playing=true).
        if (awaitingEngineStart) return

        // During crossfade, ignore buffering events from old player
        if (isCrossfading) return

        // Ignore buffering while paused to avoid showing a loading spinner when the user has
        // explicitly paused (the engine never reports it then anyway).
        val isStalled = buffering && internalPlayWhenReady
        if (isStalled != cachedIsLoading) {
            Logger.w(TAG, "isLoading changed: $cachedIsLoading -> $isStalled")
            cachedIsLoading = isStalled
            notifyListeners { onIsLoadingChanged(isStalled) }
        }
    }

    /** mpv's `finished()`. */
    private fun onEngineEnded(handle: Int) {
        Logger.d(TAG, "End of stream reached")
        pendingPausedJob?.cancel()
        pendingPausedJob = null
        // Ignore events from a player that is no longer current
        if (!isListened(handle)) {
            Logger.w(TAG, "Ignoring onEnded() from stale player")
            return
        }
        // The DJ segment ran out before the switch to B was made: make it now.
        dj?.let { d ->
            if (handle == d.mixHandle) {
                if (!d.finishing) {
                    Logger.w(TAG, "DJ segment ended before the switch to B")
                    commitDjInternal(d, toB = true)
                }
                return
            }
        }
        // Already ended at its true length (checkTrueEndInternal); this is the over-long one.
        if (handle in trueEndedHandles) {
            Logger.d(TAG, "Ignoring onEnded() after the true end was handled")
            return
        }
        // During crossfade, the old player will naturally finish near the end.
        // Ignore it - the crossfade is already handling the transition.
        if (isCrossfading) {
            Logger.d(TAG, "Ignoring onEnded() during crossfade (old player ended)")
            return
        }
        transitionToState(InternalState.ENDED)
        handleTrackEndInternal()
    }

    /** mpv's `error()` for the current player, the secondary player's error listener, and dead precaches. */
    private suspend fun onEngineError(
        handle: Int,
        httpStatus: Int,
        code: Int,
        message: String,
    ) {
        Logger.e(TAG, "Engine playback error: handle=$handle httpStatus=$httpStatus code=$code message=$message")

        if (djHandleFailedInternal(handle)) return

        if (handle != NO_HANDLE && handle == secondaryPlayer) {
            Logger.e(TAG, "Secondary player error during crossfade")
            // The fade's target: triggerCrossfadeTransition moves the index to it in the same
            // serial task that makes this handle secondary, so no event can see one without the other.
            val nextIndex = localCurrentMediaItemIndex
            crossfadeJob?.cancel()
            releaseHandle(secondaryPlayer)
            secondaryPlayer = NO_HANDLE
            setCrossfading(false)
            seekTo(nextIndex, 0)
            return
        }

        // A precached handle is dead until reloaded; promoting it later would "play" silence.
        // (mpv has no listener on precached handles, so it never learns this.)
        precachedPlayers.entries.firstOrNull { it.value.player == handle }?.let { (videoId, cached) ->
            Logger.w(TAG, "Dropping failed precache for $videoId")
            precachedPlayers.remove(videoId)
            cleanupPlayerInternal(cached.player)
            return
        }

        // Ignore errors from a player that is no longer current.
        // This can happen when the old player fires error() after
        // a new track has started loading (playlist/index already updated).
        if (!isListened(handle)) {
            Logger.w(TAG, "Ignoring onError() from stale player")
            return
        }
        // During crossfade, ignore errors from the old player -
        // it's fading out and will be released soon anyway.
        if (isCrossfading) {
            Logger.w(TAG, "Ignoring onError() during crossfade (old player error)")
            return
        }
        val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
        if (currentVideoId != null) {
            // Reset retry count for new track
            if (retryVideoId != currentVideoId) {
                retryVideoId = currentVideoId
                retryCount = 0
            }
            if (retryCount < maxRetryCount) {
                retryCount++
                Logger.w(TAG, "Retrying playback (attempt $retryCount/$maxRetryCount) for $currentVideoId (httpStatus=$httpStatus)")
                try {
                    // Invalidate cached format so fresh URL is fetched
                    streamRepository.invalidateFormat(currentVideoId)
                    streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$currentVideoId")
                    // Evict stale precache
                    precachedPlayers.remove(currentVideoId)?.player?.let { releaseHandle(it) }
                    // Reload the track
                    loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0L, shouldPlay = true)
                    return
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.e(TAG, "Retry failed: ${e.message}", e)
                }
            }
            Logger.e(TAG, "Max retries ($maxRetryCount) exhausted for $currentVideoId")
        }
        reportPlaybackError()
    }

    private fun reportPlaybackError() {
        val error =
            PlayerError(
                errorCode = PlayerConstants.ERROR_CODE_TIMEOUT,
                errorCodeName = "AVPLAYER_ERROR",
                message = "Playback error",
            )
        listeners.forEach { it.onPlayerError(error) }
        transitionToState(InternalState.ERROR)
    }

    /** Lock screen, Control Centre and headset: forwarded to the adapter's own transport. */
    private fun onEngineRemoteCommand(
        command: Int,
        positionMs: Long,
    ) {
        Logger.d(TAG, "Remote command $command ($positionMs ms)")
        when (command) {
            NativePlayerConstants.REMOTE_PLAY -> play()
            NativePlayerConstants.REMOTE_PAUSE -> pause()
            NativePlayerConstants.REMOTE_TOGGLE -> {
                val running =
                    internalState == InternalState.PLAYING ||
                        (internalPlayWhenReady && (internalState == InternalState.PREPARING || internalState == InternalState.READY))
                if (running) pause() else play()
            }
            NativePlayerConstants.REMOTE_NEXT -> seekToNext()
            NativePlayerConstants.REMOTE_PREVIOUS -> seekToPrevious()
            NativePlayerConstants.REMOTE_SEEK -> seekTo(positionMs)
            else -> Logger.w(TAG, "Unknown remote command $command")
        }
    }

    /**
     * A call or another app took the audio. The system has already paused the AVPlayers; this moves
     * the adapter to PAUSED the way pause() does (so the crossfade trigger and a finishing load do
     * not start sound mid-call) and remembers whether the user had playback running. The sleep fade
     * survives. On the end, playback resumes only when it was running and the system says it may.
     */
    private suspend fun onEngineInterruption(
        began: Boolean,
        shouldResume: Boolean,
    ) {
        Logger.d(TAG, "Interruption began=$began shouldResume=$shouldResume (state=$internalState)")
        if (began) {
            val wasRunning =
                internalPlayWhenReady &&
                    (internalState == InternalState.PLAYING || internalState == InternalState.READY || internalState == InternalState.PREPARING)
            // A second "began" while already interrupted must not forget the first one's answer.
            resumeAfterInterruption = resumeAfterInterruption || wasRunning
            pauseInternal(clearSleepFade = false)
        } else {
            val resume = resumeAfterInterruption
            resumeAfterInterruption = false
            if (resume && shouldResume) play()
        }
    }

    // ========== After every serial task ==========

    /**
     * Runs after every serial task (see [SerialDispatcher]): publishes the queue snapshot other
     * threads read, then brings the engine's system state (active handle, lock screen, remote
     * command availability) in line with the adapter's. Doing it here rather than at each mutation
     * site means no index, timeline, shuffle or repeat change can forget either.
     */
    private fun onSerialTaskFinished() {
        queueSnapshot =
            QueueView(
                playlist = playlist.toList(),
                currentIndex = localCurrentMediaItemIndex,
                shuffleEnabled = internalShuffleModeEnabled,
                shuffleOrder = shuffleOrder.toList(),
                shuffleIndices = shuffleIndices.toList(),
                repeatMode = internalRepeatMode,
            )
        syncSystemIntegration()
        syncDjPipeline()
    }

    private fun syncSystemIntegration() {
        if (released) return

        val active = displayHandle()
        val handleChanged = active != sentActiveHandle
        if (handleChanged) {
            engine.setActiveHandle(active)
            sentActiveHandle = active
        }

        // The video views follow the handle the UI shows, when that handle carries video.
        val videoHandle = if (active != NO_HANDLE && active in videoHandles) active else NO_HANDLE
        if (videoHandle != sentVideoHandle) {
            NativeVideoOutput.publish(videoHandle)
            sentVideoHandle = videoHandle
        }

        val item = playlist.getOrNull(localCurrentMediaItemIndex)
        if (item == null) {
            if (sentNowPlayingItem != null) {
                engine.clearNowPlaying()
                sentNowPlayingItem = null
                sentNowPlayingHandle = Int.MIN_VALUE
                sentNowPlayingDurationMs = -1L
            }
        } else {
            val live = LiveStreamRegistry.isLive(item.mediaId)
            val djTimeline = djView?.takeIf { it.mixHandle == active }
            val durationMs =
                when {
                    live || active == NO_HANDLE -> 0L
                    djTimeline != null -> djTimeline.durationMs().coerceAtLeast(0L)
                    else -> effectiveDurationMs(active).coerceAtLeast(0L)
                }
            if (item !== sentNowPlayingItem ||
                active != sentNowPlayingHandle ||
                durationMs != sentNowPlayingDurationMs ||
                live != sentNowPlayingLive
            ) {
                val metadata = item.metadata
                engine.setNowPlaying(
                    metadata.title.orEmpty(),
                    metadata.artist.orEmpty(),
                    metadata.albumTitle.orEmpty(),
                    metadata.artworkUri.orEmpty(),
                    durationMs,
                    live,
                )
                sentNowPlayingItem = item
                sentNowPlayingHandle = active
                sentNowPlayingDurationMs = durationMs
                sentNowPlayingLive = live
            }
            // During a DJ segment the lock screen shows A's / B's clock, not the segment's.
            if (handleChanged) sentPositionOffset = 0L
            val offset = djTimeline?.offsetMs() ?: 0L
            if (offset != sentPositionOffset) {
                engine.setActivePositionOffset(offset)
                sentPositionOffset = offset
            }
        }

        val queue = liveQueueView()
        val hasNext = queue.hasNext()
        // Previous stays on whenever a track is loaded: on the first track it restarts the song
        // (seekToPrevious), and greying it out would take that away from the lock screen.
        val hasPrevious = item != null
        if (hasNext != sentRemoteNext || hasPrevious != sentRemotePrevious) {
            engine.setRemoteCommandsEnabled(hasNext, hasPrevious)
            sentRemoteNext = hasNext
            sentRemotePrevious = hasPrevious
        }
    }

    // Last in the class body on purpose: the launches below may start running on another thread
    // before the constructor returns, so every field they touch must already be initialised.
    init {
        engine.setListener(engineListener)

        // Load crossfade settings. DJ mode = pre-rendered transitions (docs/ios-player-bridge.md §7).
        coroutineScope.launch {
            dataStoreManager.watchVideoInsteadOfPlayingAudio.collect { enabled ->
                watchVideoEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Watch video enabled: $watchVideoEnabled")
            }
        }

        coroutineScope.launch {
            dataStoreManager.crossfadeEnabled.collect { enabled ->
                crossfadeEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Crossfade enabled: $crossfadeEnabled")
            }
        }

        coroutineScope.launch {
            dataStoreManager.crossfadeDuration.collect { duration ->
                crossfadeDurationMs = duration
                Logger.d(TAG, "Crossfade duration: $crossfadeDurationMs ms")
            }
        }

        coroutineScope.launch {
            dataStoreManager.crossfadeDjMode.collect { enabled ->
                djModeEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "DJ transitions enabled: $djModeEnabled")
            }
        }

        coroutineScope.launch {
            dataStoreManager.djSmartOrder.collect { enabled ->
                djSmartOrderEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "DJ smart order enabled: $djSmartOrderEnabled")
            }
        }

        djPipeline.onAnalysed = { _, id, analysis ->
            coroutineScope.launch { smartOnAnalysed(id, analysis) }
        }

        djPipeline.onReady = { ready ->
            coroutineScope.launch {
                Logger.d(TAG, "DJ transition ready: ${ready.a}→${ready.b}")
                djRefreshInternal()
            }
        }

        coroutineScope.launch {
            dataStoreManager.crossfadeSkipAlbum.collect { enabled ->
                skipCrossfadeInAlbum = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Skip crossfade inside album: $skipCrossfadeInAlbum")
            }
        }
    }

    companion object {
        /** Floor for the shortest track worth crossfading, in ms. */
        private const val MIN_CROSSFADE_TRACK_MS = 20_000L

        /** What `CROSSFADE_DURATION_AUTO` means on iOS: a fixed fade, no AutoMix analysis. */
        private const val AUTO_CROSSFADE_DURATION_MS = 5_000

        /** Crossfade trigger poll. Position itself is read from the engine on demand. */
        private const val POSITION_POLL_MS = 200L

        /** How long a system "not playing" waits for a stall or onEnded to explain it. */
        private const val PAUSE_SETTLE_MS = 400L

        /** How close to the true length counts as its end (under one position poll). */
        private const val TRUE_END_TOLERANCE_MS = 150L

        /** DJ: overlap of identical audio at each end of the segment (A→segment, segment→B). */
        private const val DJ_OVERLAP_MS = 50L

        /** DJ: how long before a join the engine is asked for the synced start (it prerolls first). */
        private const val DJ_SYNC_LEAD_MS = 1_500L

        /** DJ: a transition that becomes ready later than this before its join is not used. */
        private const val DJ_ARM_LEAD_MS = 3_000L

        /** DJ: A found this far past the join (a seek, a stall) drops the switch. */
        private const val DJ_JOIN_LATE_MS = 250L

        /** DJ: how long a synced start may take to show up in the engine's clock. */
        private const val DJ_START_TIMEOUT_MS = 400L

        /** DJ: largest disagreement between two handles' clocks still taken as "in step". */
        private const val DJ_ALIGN_TOLERANCE_MS = 80L

        /** Smart next: how many upcoming tracks (the queue's own next included) are compared. */
        private const val SMART_WINDOW = 6

        /** Smart next: how far into the play order to look for [SMART_WINDOW] usable tracks. */
        private const val SMART_SCAN_LIMIT = 20

        /** Smart next: with this little of the current track left, decide with what is analysed. */
        private const val SMART_DECIDE_BY_MS = 45_000L

        /** Smart next: past this, a move would leave no time to render the new transition. */
        private const val SMART_GIVE_UP_MS = 30_000L

        /** Smart next: analyses kept in memory. */
        private const val SMART_MEMORY = 64

        /** DJ: the analysed copy and the stream played may differ by this much in length. */
        private const val DJ_DURATION_TOLERANCE_MS = 2_500L

        /** googlevideo's `dur=` query parameter: the stream's length in seconds. */
        private val DUR_PARAM = Regex("""[?&]dur=([0-9]+(?:\.[0-9]+)?)""")

        /** googlevideo's `itag` query parameter. */
        private val ITAG_PARAM = Regex("""[?&]itag=([0-9]+)""")

        /** H.264 360p + AAC in one progressive MP4 (ITAG.MUXED_360P), as it appears in a URL. */
        private val MUXED_ITAG = com.maxrave.common.ITAG.MUXED_360P.toString()
    }
}
