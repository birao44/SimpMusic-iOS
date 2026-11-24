package com.maxrave.simpmusic.ui.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kmpalette.rememberPaletteState
import com.maxrave.common.Config.MAIN_PLAYER
import com.maxrave.domain.data.model.streams.TimeLine
import com.maxrave.simpmusic.expect.ui.MediaPlayerView
import com.maxrave.simpmusic.expect.ui.MediaPlayerViewWithSubtitle
import com.maxrave.simpmusic.extension.elapsedLabel
import com.maxrave.simpmusic.extension.lengthLabel
import com.maxrave.simpmusic.extension.toImmersiveBackground
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.Favorite
import com.maxrave.simpmusic.ui.icon.FavoriteBorder
import com.maxrave.simpmusic.ui.icon.Pause
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.SkipNext
import com.maxrave.simpmusic.ui.icon.SkipPrevious
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

private val RICH_SYNC_TIMESTAMP = Regex("""<\d{2}:\d{2}\.\d{2,3}>\s*""")

/**
 * The landscape song view (see [LandscapeSongController]). A music video in video mode plays full
 * bleed with tap-to-show controls; anything else is artwork (or the looping canvas) beside the
 * title, the current synced lyric line, a seek bar and the transport.
 *
 * Deliberately flat: no blur, backdrop, haze or glass anywhere, because this screen is the one
 * that is resized by rotation. The background is a plain gradient from the artwork's colour.
 *
 * @param videoOnly opened from the video's fullscreen button: always the video layout.
 */
@Composable
internal fun LandscapeSongScreen(
    videoOnly: Boolean,
    onClose: () -> Unit,
    sharedViewModel: SharedViewModel = koinInject(),
) {
    val screenData by sharedViewModel.nowPlayingScreenData.collectAsStateWithLifecycle()
    val controllerState by sharedViewModel.controllerState.collectAsStateWithLifecycle()
    val timeline by sharedViewModel.timeline.collectAsStateWithLifecycle()
    val nowPlaying by sharedViewModel.nowPlayingState.collectAsStateWithLifecycle()
    val showVideo by sharedViewModel.getVideo.collectAsStateWithLifecycle()

    // The queue was cleared while this was up: nothing left to show.
    val songLoaded = nowPlaying?.isNotEmpty() == true
    LaunchedEffect(songLoaded) {
        if (!songLoaded) {
            delay(500)
            onClose()
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            // The view is presented in portrait and rotated right after; black until it is wide,
            // so the landscape layout is never drawn squeezed (unless the rotation never comes).
            var waitedForRotation by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(1500)
                waitedForRotation = true
            }
            val ready = maxWidth > maxHeight || waitedForRotation
            val videoMode = videoOnly || (screenData.isVideo && showVideo)
            if (!ready) {
                Spacer(Modifier.fillMaxSize())
            } else if (videoMode) {
                LandscapeVideo(
                    sharedViewModel = sharedViewModel,
                    title = screenData.nowPlayingTitle,
                    artist = screenData.artistName,
                    lyrics = screenData.lyricsData,
                    timeline = timeline,
                    isPlaying = controllerState.isPlaying,
                    isLiked = controllerState.isLiked,
                    onClose = onClose,
                )
            } else {
                LandscapeAudio(
                    sharedViewModel = sharedViewModel,
                    timeline = timeline,
                    isPlaying = controllerState.isPlaying,
                    isLiked = controllerState.isLiked,
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun LandscapeAudio(
    sharedViewModel: SharedViewModel,
    timeline: TimeLine,
    isPlaying: Boolean,
    isLiked: Boolean,
    onClose: () -> Unit,
) {
    val screenData by sharedViewModel.nowPlayingScreenData.collectAsStateWithLifecycle()

    // Background colour from the artwork (the bitmap the view model already decoded).
    val paletteState = rememberPaletteState()
    val bitmap = screenData.bitmap
    LaunchedEffect(bitmap) {
        if (bitmap != null) runCatching { paletteState.generate(bitmap) }
    }
    val baseColor by animateColorAsState(
        targetValue = paletteState.palette?.toImmersiveBackground() ?: Color(0xFF202020),
        animationSpec = tween(600),
        label = "landscapeBackground",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(baseColor, Color.Black))),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Artwork, or the canvas clip looping in its place.
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x33FFFFFF)),
            ) {
                AsyncImage(
                    model = screenData.thumbnailURL,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                val canvas = screenData.canvasData
                if (canvas != null) {
                    if (canvas.isVideo) {
                        MediaPlayerView(
                            url = canvas.url,
                            cropToBounds = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        AsyncImage(
                            model = canvas.url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 28.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = screenData.nowPlayingTitle,
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(animationMode = MarqueeAnimationMode.Immediately),
                        )
                        Text(
                            text = screenData.artistName,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    PlainIconButton(SimpIcons.Close, "Close", 24.dp, onClose)
                }

                CurrentLyricLine(
                    sharedViewModel = sharedViewModel,
                    timeline = timeline,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 8.dp),
                )

                SeekBar(sharedViewModel, timeline)

                TransportRow(
                    sharedViewModel = sharedViewModel,
                    isPlaying = isPlaying,
                    isLiked = isLiked,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun LandscapeVideo(
    sharedViewModel: SharedViewModel,
    title: String,
    artist: String,
    lyrics: com.maxrave.simpmusic.viewModel.NowPlayingScreenData.LyricsData?,
    timeline: TimeLine,
    isPlaying: Boolean,
    isLiked: Boolean,
    onClose: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableStateOf(0) }
    LaunchedEffect(controlsVisible, interactionTick) {
        if (controlsVisible) {
            delay(3500)
            controlsVisible = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                controlsVisible = !controlsVisible
                interactionTick++
            },
    ) {
        MediaPlayerViewWithSubtitle(
            modifier = Modifier.fillMaxSize(),
            playerName = MAIN_PLAYER,
            shouldPip = true,
            shouldShowSubtitle = true,
            isInPipMode = false,
            timelineState = timeline,
            lyricsData = lyrics?.lyrics,
            translatedLyricsData = lyrics?.translatedLyrics?.first,
            mainTextStyle = MaterialTheme.typography.bodyLarge,
            translatedTextStyle = MaterialTheme.typography.bodyMedium,
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                        ),
                    ),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(artist, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        PlainIconButton(SimpIcons.Close, "Close", 24.dp, onClose)
                    }
                    Spacer(Modifier.weight(1f))
                    SeekBar(sharedViewModel, timeline, onInteract = { interactionTick++ })
                    TransportRow(
                        sharedViewModel = sharedViewModel,
                        isPlaying = isPlaying,
                        isLiked = isLiked,
                        onInteract = { interactionTick++ },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** The synced line at the playhead, with the user's lyrics offset; nothing for unsynced lyrics. */
@Composable
private fun CurrentLyricLine(
    sharedViewModel: SharedViewModel,
    timeline: TimeLine,
    modifier: Modifier,
) {
    val screenData by sharedViewModel.nowPlayingScreenData.collectAsStateWithLifecycle()
    val offsetMs by sharedViewModel.getLyricsOffsetMs().collectAsStateWithLifecycle(0)
    val lyrics = screenData.lyricsData?.lyrics
    val synced = lyrics?.syncType == "LINE_SYNCED" || lyrics?.syncType == "RICH_SYNCED"
    val lines = if (synced) lyrics?.lines.orEmpty() else emptyList()
    val starts = remember(lines) { lines.map { it.startTimeMs.toLongOrNull() ?: Long.MAX_VALUE } }
    val now = timeline.current - offsetMs.toLong()
    val index = if (now <= 0L) -1 else starts.indexOfLast { it <= now }
    val text =
        lines
            .getOrNull(index)
            ?.words
            ?.replace(RICH_SYNC_TIMESTAMP, "")
            ?.trim()
            .orEmpty()

    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Crossfade(targetState = text, animationSpec = tween(250), label = "lyricLine") { line ->
            Text(
                text = line,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
private fun SeekBar(
    sharedViewModel: SharedViewModel,
    timeline: TimeLine,
    onInteract: () -> Unit = {},
) {
    var sliding by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    val progress =
        if (timeline.total > 0L) (timeline.current.toFloat() / timeline.total.toFloat()).coerceIn(0f, 1f) else 0f
    val shown = if (sliding) sliderValue else progress

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { value ->
                sliding = true
                sliderValue = value
                onInteract()
            },
            onValueChangeFinished = {
                sharedViewModel.onUIEvent(UIEvent.UpdateProgress(sliderValue * 100f))
                sliding = false
            },
            enabled = !timeline.isLive && timeline.total > 0L,
            colors =
                SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                ),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(timeline.elapsedLabel(shown), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(timeline.lengthLabel(), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun TransportRow(
    sharedViewModel: SharedViewModel,
    isPlaying: Boolean,
    isLiked: Boolean,
    modifier: Modifier,
    onInteract: () -> Unit = {},
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlainIconButton(
            icon = if (isLiked) SimpIcons.Favorite else SimpIcons.FavoriteBorder,
            description = "Like",
            size = 26.dp,
        ) {
            onInteract()
            sharedViewModel.onUIEvent(UIEvent.ToggleLike)
        }
        PlainIconButton(SimpIcons.SkipPrevious, "Previous", 36.dp) {
            onInteract()
            sharedViewModel.onUIEvent(UIEvent.Previous)
        }
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable {
                    onInteract()
                    sharedViewModel.onUIEvent(UIEvent.PlayPause)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.Black,
                modifier = Modifier.size(34.dp),
            )
        }
        PlainIconButton(SimpIcons.SkipNext, "Next", 36.dp) {
            onInteract()
            sharedViewModel.onUIEvent(UIEvent.Next)
        }
        // Keeps the row symmetric around play/pause.
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun PlainIconButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size))
    }
}
