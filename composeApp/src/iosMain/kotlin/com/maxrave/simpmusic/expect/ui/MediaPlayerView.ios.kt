package com.maxrave.simpmusic.expect.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import coil3.compose.AsyncImage
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.streams.TimeLine
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.player.ios.NativePlayerConstants
import com.maxrave.domain.mediaservice.player.ios.NativeVideoOutput
import com.maxrave.simpmusic.extension.getScreenSizeInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLooper
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.allowsExternalPlayback
import platform.AVFoundation.currentItem
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.presentationSize
import platform.AVFoundation.preventsDisplaySleepDuringVideoPlayback
import platform.AVKit.AVPictureInPictureController
import platform.AVKit.AVPictureInPictureControllerDelegateProtocol
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.darwin.NSObject

// ========== Canvas: the short looping muted video behind the player ==========

/**
 * Its own small player, never the engine: muted, never on the lock screen (only the engine writes
 * MPNowPlayingInfoCenter), no AirPlay, does not keep the screen awake, paused in the background.
 */
@OptIn(ExperimentalForeignApi::class)
private class CanvasPlayer(
    url: String,
) {
    val player = AVQueuePlayer()
    val view =
        PlayerLayerView().apply {
            playerLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
            playerLayer.player = player
        }
    private val looper: AVPlayerLooper?
    private val backgroundObserver = AppBackgroundObserver { inBackground -> if (inBackground) player.pause() else player.play() }

    init {
        player.muted = true
        player.allowsExternalPlayback = false
        player.preventsDisplaySleepDuringVideoPlayback = false
        val item = NSURL.URLWithString(url)?.let { AVPlayerItem(uRL = it) }
        looper = item?.let { AVPlayerLooper.playerLooperWithPlayer(player, it) }
        if (!isAppInBackground()) player.play()
    }

    /** Width / height of the video once known. */
    fun aspectRatio(): Float? = player.currentItem?.presentationSize?.useContents { if (width > 0.0 && height > 0.0) (width / height).toFloat() else null }

    fun release() {
        backgroundObserver.release()
        looper?.disableLooping()
        player.pause()
        player.removeAllItems()
        view.playerLayer.player = null
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun MediaPlayerView(
    url: String,
    modifier: Modifier,
    cropToBounds: Boolean,
    onFirstFrame: () -> Unit,
) {
    val canvas = remember(url) { CanvasPlayer(url) }
    DisposableEffect(canvas) { onDispose { canvas.release() } }

    var aspectRatio by remember(canvas) { mutableStateOf<Float?>(null) }
    val currentOnFirstFrame by rememberUpdatedState(onFirstFrame)
    LaunchedEffect(canvas) {
        var firstFrameSent = false
        while (!firstFrameSent || aspectRatio == null) {
            if (!firstFrameSent && canvas.view.playerLayer.readyForDisplay) {
                firstFrameSent = true
                currentOnFirstFrame()
            }
            if (aspectRatio == null) aspectRatio = canvas.aspectRatio()
            delay(50)
        }
    }

    if (cropToBounds) {
        // Scale to cover the caller's frame, overflow clipped (ContentScale.Crop).
        Box(modifier.clipToBounds()) {
            VideoLayerHost(canvas.view, Modifier.fillMaxSize())
        }
    } else {
        // The legacy shape, as on Android: full height, width from the video's own ratio against
        // the screen height (the screen width until it is known), centred and clipped.
        val screen = getScreenSizeInfo()
        val widthPx = aspectRatio?.let { it * screen.hPX } ?: screen.wPX.toFloat()
        val widthDp = with(LocalDensity.current) { widthPx.toDp() }
        Box(modifier.clipToBounds()) {
            VideoLayerHost(
                canvas.view,
                Modifier
                    .fillMaxHeight()
                    .width(widthDp)
                    .align(Alignment.Center),
            )
        }
    }
}

/** The layer view in the Compose tree: drawn under Compose, never taking touches. */
@Composable
private fun VideoLayerHost(
    view: PlayerLayerView,
    modifier: Modifier,
) {
    UIKitView(
        factory = { view },
        modifier = modifier,
        properties = UIKitInteropProperties(interactionMode = null, isNativeAccessibilityEnabled = false),
    )
}

// ========== Main player video ==========

/**
 * Shows the engine's AVPlayer for the handle in [NativeVideoOutput] through one AVPlayerLayer.
 *
 * Background: AVFoundation pauses a player whose video is attached to a layer when the app leaves
 * the foreground, which would stop the music. The layer lets go of the player on
 * didEnterBackground (synchronously) and takes it back on willEnterForeground, unless Picture in
 * Picture is showing it.
 *
 * Picture in Picture ([enablePip], the fullscreen player): starts by itself when the app goes to
 * the background while the video plays (canStartPictureInPictureAutomaticallyFromInline).
 */
@OptIn(ExperimentalForeignApi::class)
private class MainVideoBinding(
    enablePip: Boolean,
) {
    val view = PlayerLayerView().apply { playerLayer.videoGravity = AVLayerVideoGravityResizeAspect }

    var player: AVPlayer? = null
        set(value) {
            field = value
            apply()
        }

    private var inBackground = isAppInBackground()
    private var pipActive = false

    private val backgroundObserver =
        AppBackgroundObserver { background ->
            inBackground = background
            apply()
        }

    private val pipDelegate =
        PipDelegate { active ->
            pipActive = active
            apply()
        }

    private val pip: AVPictureInPictureController? =
        if (enablePip && AVPictureInPictureController.isPictureInPictureSupported()) {
            AVPictureInPictureController(playerLayer = view.playerLayer).also {
                it.canStartPictureInPictureAutomaticallyFromInline = true
                it.delegate = pipDelegate
            }
        } else {
            null
        }

    private fun apply() {
        val target = if (!inBackground || pipActive) player else null
        if (view.playerLayer.player !== target) view.playerLayer.player = target
    }

    fun release() {
        backgroundObserver.release()
        pip?.let {
            it.delegate = null
            if (it.pictureInPictureActive) it.stopPictureInPicture()
        }
        player = null
    }
}

private class PipDelegate(
    private val onActive: (Boolean) -> Unit,
) : NSObject(),
    AVPictureInPictureControllerDelegateProtocol {
    override fun pictureInPictureControllerWillStartPictureInPicture(pictureInPictureController: AVPictureInPictureController) {
        onActive(true)
    }

    override fun pictureInPictureControllerDidStopPictureInPicture(pictureInPictureController: AVPictureInPictureController) {
        onActive(false)
    }

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        failedToStartPictureInPictureWithError: NSError,
    ) {
        onActive(false)
    }
}

private val RICH_SYNC_TIMESTAMP_REGEX = Regex("""<\d{2}:\d{2}\.\d{2,3}>\s*""")

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun MediaPlayerViewWithSubtitle(
    modifier: Modifier,
    playerName: String,
    shouldPip: Boolean,
    shouldShowSubtitle: Boolean,
    shouldScaleDownSubtitle: Boolean,
    isInPipMode: Boolean,
    timelineState: TimeLine,
    lyricsData: Lyrics?,
    translatedLyricsData: Lyrics?,
    mainTextStyle: TextStyle,
    translatedTextStyle: TextStyle,
) {
    val handle by NativeVideoOutput.handle.collectAsState()
    val binding = remember(shouldPip) { MainVideoBinding(enablePip = shouldPip) }
    DisposableEffect(binding) { onDispose { binding.release() } }
    val player = remember(handle) { engineVideoPlayer(handle) }
    SideEffect { binding.player = player }

    // The artwork stays up until the layer has a frame to show.
    var showingVideo by remember(binding) { mutableStateOf(false) }
    LaunchedEffect(binding) {
        while (true) {
            val layer = binding.view.playerLayer
            showingVideo = layer.player != null && layer.readyForDisplay
            delay(100)
        }
    }

    val mediaPlayerHandler = koinInject<MediaPlayerHandler>()
    val state by mediaPlayerHandler.nowPlayingState.collectAsState()
    val artworkUri = state.songEntity?.thumbnails

    // Subtitles over the video are lyrics like any other, so they take the same audio-delay
    // correction the lyrics sheet does.
    val lyricsOffsetMs by koinInject<DataStoreManager>().lyricsOffsetMs.collectAsState(0)
    var currentLineIndex by remember { mutableIntStateOf(-1) }
    var currentTranslatedLineIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(timelineState, lyricsOffsetMs, lyricsData, translatedLyricsData) {
        val lines = lyricsData?.lines ?: return@LaunchedEffect
        val nowMs = timelineState.current - lyricsOffsetMs
        if (nowMs > 0L) {
            currentLineIndex = lineIndexAt(lines.map { it.startTimeMs }, nowMs, currentLineIndex)
            translatedLyricsData?.lines?.let { translated ->
                currentTranslatedLineIndex = lineIndexAt(translated.map { it.startTimeMs }, nowMs, currentTranslatedLineIndex)
            }
            if (lines.isNotEmpty() && nowMs in 0..(lines[0].startTimeMs.toLongOrNull() ?: 0L)) {
                currentLineIndex = -1
                currentTranslatedLineIndex = -1
            }
        } else {
            currentLineIndex = -1
            currentTranslatedLineIndex = -1
        }
    }

    Box(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        if (player != null) {
            VideoLayerHost(binding.view, Modifier.fillMaxSize())
        }
        if (!showingVideo) {
            AsyncImage(
                model = artworkUri,
                contentDescription = null,
                contentScale = ContentScale.FillHeight,
                modifier = Modifier.fillMaxHeight().align(Alignment.Center),
            )
        }
        if (lyricsData != null && shouldShowSubtitle) {
            Crossfade(currentLineIndex != -1, modifier = Modifier.align(Alignment.BottomCenter).fillMaxSize()) { visible ->
                val lines = lyricsData.lines ?: return@Crossfade
                if (!visible) return@Crossfade
                val scale = if (shouldScaleDownSubtitle) 0.8f else 1f
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = if (shouldScaleDownSubtitle) 10.dp else 40.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(Modifier.fillMaxWidth(0.7f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text =
                                lines
                                    .getOrNull(currentLineIndex)
                                    ?.words
                                    ?.replace(RICH_SYNC_TIMESTAMP_REGEX, "")
                                    ?.trim() ?: return@Column,
                            style = mainTextStyle.copy(fontSize = mainTextStyle.fontSize * scale),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier
                                    .padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .wrapContentWidth(),
                        )
                        translatedLyricsData?.lines?.getOrNull(currentTranslatedLineIndex)?.words?.let { translated ->
                            Text(
                                text = translated,
                                style = translatedTextStyle.copy(fontSize = translatedTextStyle.fontSize * scale),
                                color = Color.Yellow,
                                textAlign = TextAlign.Center,
                                modifier =
                                    Modifier
                                        .background(Color.Black.copy(alpha = 0.5f))
                                        .wrapContentWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The line playing at [nowMs]: the last one whose span (to the next start, or 60 s) holds it. */
private fun lineIndexAt(
    starts: List<String>,
    nowMs: Long,
    fallback: Int,
): Int {
    var found = fallback
    starts.indices.forEach { i ->
        val start = starts[i].toLongOrNull() ?: return@forEach
        val end = starts.getOrNull(i + 1)?.toLongOrNull() ?: (start + 60_000)
        if (nowMs in start..end) found = i
    }
    return found
}

/**
 * Width / height of the main player's video, or null while it has none. Read off the engine's
 * AVPlayer for the handle in [NativeVideoOutput], so a track change re-points it by itself.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberVideoAspectRatio(playerName: String): Float? {
    val handle by NativeVideoOutput.handle.collectAsState()
    val ratio by produceState<Float?>(null, handle) {
        value = null
        if (handle == NativePlayerConstants.NO_HANDLE) return@produceState
        while (true) {
            val size =
                engineVideoPlayer(handle)?.currentItem?.presentationSize?.useContents {
                    if (width > 0.0 && height > 0.0) (width / height).toFloat() else null
                }
            if (size != null) value = size
            delay(if (value == null) 250 else 2_000)
        }
    }
    return ratio
}
