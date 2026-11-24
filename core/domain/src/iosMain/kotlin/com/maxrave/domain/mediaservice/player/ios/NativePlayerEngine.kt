package com.maxrave.domain.mediaservice.player.ios

/**
 * The Swift side of the iOS player: AVPlayer objects, the audio session and the lock screen.
 * Implemented by `PlayerEngine` in the xtool app shell, driven by `AvPlayerAdapter`.
 *
 * The contract (threads, event order, position, volume) is frozen in `docs/ios-player-bridge.md`;
 * read it before changing anything here. Primitive types only, so the generated Objective-C
 * header stays simple.
 */
interface NativePlayerEngine {
    /** New idle player. [video] = false means video tracks are never decoded or displayed. */
    fun create(video: Boolean): Int

    /**
     * Replace whatever [handle] holds with [url] (http(s):// or file://), starting at [startMs].
     * With [autoPlay] it plays as soon as it can, otherwise it buffers and holds paused (precache).
     * [live] is an endless HLS playlist: no duration, no onEnded.
     */
    fun load(
        handle: Int,
        url: String,
        headers: Map<String, String>,
        startMs: Long,
        autoPlay: Boolean,
        live: Boolean,
    )

    fun play(handle: Int)

    fun pause(handle: Int)

    fun seek(
        handle: Int,
        positionMs: Long,
    )

    /** 0f..1f, already the product of user volume, sleep fade and crossfade gain. */
    fun setVolume(
        handle: Int,
        volume: Float,
    )

    /** Playback speed, pitch preserved. Applied now if playing, remembered for the next play(). */
    fun setRate(
        handle: Int,
        rate: Float,
    )

    /** Stop, drop the item, forget the handle. Unknown handles are ignored. No event follows. */
    fun release(handle: Int)

    /**
     * DJ join (`docs/ios-player-bridge.md` §7): start [handle] — loaded, ready and paused at its start
     * position — so that position is heard exactly when [refHandle]'s item reaches [refMs], using
     * `AVPlayer.setRate(_:time:atHostTime:)` after a preroll. Late (the moment has passed) it starts
     * at once with its position advanced by the lateness, so the two stay aligned. Nothing happens
     * while [refHandle] is not playing; a not-yet-ready [handle] starts like [play]. pause() cancels it.
     */
    fun playSynced(
        handle: Int,
        refHandle: Int,
        refMs: Long,
    )

    // Reads: callable from any thread, never block.

    /** Extrapolated between observer ticks, smooth enough for word-by-word lyrics. */
    fun positionMs(handle: Int): Long

    /** 0 until known, and always 0 for live. */
    fun durationMs(handle: Int): Long

    /** End of the loaded range that contains the playhead; 0 when unknown. */
    fun bufferedMs(handle: Int): Long

    /**
     * The handle the user is listening to: it feeds the lock screen's position and rate and, from
     * Phase 3, the player video view. [NativePlayerConstants.NO_HANDLE] = none. During a crossfade
     * this is the incoming handle, matching what the UI shows.
     */
    fun setActiveHandle(handle: Int)

    /** Lock screen text. The engine downloads [artworkUrl] itself; "" = none. */
    fun setNowPlaying(
        title: String,
        artist: String,
        album: String,
        artworkUrl: String,
        durationMs: Long,
        live: Boolean,
    )

    fun clearNowPlaying()

    /**
     * Lock-screen elapsed time = the active handle's position + [offsetMs], until the next
     * [setActiveHandle] (which resets it to 0). Used while a rendered DJ segment plays.
     */
    fun setActivePositionOffset(offsetMs: Long)

    /** Greys out next/previous on the lock screen. Play, pause, toggle and seek are always on. */
    fun setRemoteCommandsEnabled(
        next: Boolean,
        previous: Boolean,
    )

    /**
     * The `AVPlayer` behind [handle], for the video views to attach an `AVPlayerLayer` to; null for
     * an unknown or released handle, or when called off the main thread. Main thread only. Callers
     * must never change its item, rate or volume: the engine owns the playback.
     */
    fun nativePlayer(handle: Int): Any?

    /** One listener; a second call replaces the first. Events arrive on the main thread. */
    fun setListener(listener: NativePlayerEngineListener)

    /** Release every handle and deactivate the audio session. */
    fun releaseAll()
}

interface NativePlayerEngineListener {
    /** The item can play. [durationMs] is 0 for live. Fires once per load(). */
    fun onReady(
        handle: Int,
        durationMs: Long,
    )

    /** Sound is / is not actually coming out. */
    fun onPlayingChanged(
        handle: Int,
        playing: Boolean,
    )

    /** Wants to play but is waiting for data. Never true while paused. */
    fun onBufferingChanged(
        handle: Int,
        buffering: Boolean,
    )

    /** Reached the end. Never fires for live, never after release(). */
    fun onEnded(handle: Int)

    /**
     * Load or playback failed; the handle is dead until the next load(). [httpStatus] is the HTTP
     * status when a server answered (403 = expired stream URL), otherwise 0.
     */
    fun onError(
        handle: Int,
        httpStatus: Int,
        code: Int,
        message: String,
    )

    fun onVideoSize(
        handle: Int,
        width: Int,
        height: Int,
    )

    fun onFirstVideoFrame(handle: Int)

    /** Lock screen, Control Centre, headset. [command] is a NativePlayerConstants.REMOTE_* value. */
    fun onRemoteCommand(
        command: Int,
        positionMs: Long,
    )

    /**
     * Another app or a call took the audio; on [began] the system has already paused us. On end,
     * [shouldResume] is the system's hint and Kotlin decides whether to play again.
     */
    fun onInterruption(
        began: Boolean,
        shouldResume: Boolean,
    )

    /** Headphones unplugged or Bluetooth gone: the system has already paused us. */
    fun onOutputDeviceLost()
}

object NativePlayerConstants {
    const val REMOTE_PLAY = 1
    const val REMOTE_PAUSE = 2
    const val REMOTE_TOGGLE = 3
    const val REMOTE_NEXT = 4
    const val REMOTE_PREVIOUS = 5

    /** positionMs carries the target. */
    const val REMOTE_SEEK = 6
    const val NO_HANDLE = -1
}

/** Set once by Swift before MainViewController() is created; read by createMediaServiceHandler. */
object NativePlayerRegistry {
    @kotlin.concurrent.Volatile
    var engine: NativePlayerEngine? = null
}

/**
 * Which engine handle the player video views show: the handle the user is listening to when it was
 * created with `video = true`, else [NativePlayerConstants.NO_HANDLE] (the views then show the
 * artwork). Written by `AvPlayerAdapter`, read by the iOS video composables in composeApp.
 */
object NativeVideoOutput {
    private val _handle = kotlinx.coroutines.flow.MutableStateFlow(NativePlayerConstants.NO_HANDLE)
    val handle: kotlinx.coroutines.flow.StateFlow<Int> = _handle

    fun publish(handle: Int) {
        _handle.value = handle
    }
}
