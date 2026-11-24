package com.maxrave.simpmusic.extension

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.maxrave.domain.data.model.ui.ScreenSizeInfo
import com.maxrave.simpmusic.expect.ui.IdleTimer
import kotlin.math.roundToInt

// The whole window, safe areas included — the same full-window bounds Android reads from WindowMetrics.
@Composable
actual fun getScreenSizeInfo(): ScreenSizeInfo {
    val density = LocalDensity.current
    val size = LocalWindowInfo.current.containerSize
    return ScreenSizeInfo(
        hDP = with(density) { size.height.toDp().value.roundToInt() },
        wDP = with(density) { size.width.toDp().value.roundToInt() },
        hPX = size.height,
        wPX = size.width,
    )
}

// Reference-counted: Now Playing and the fullscreen player can both hold it.
@Composable
actual fun KeepScreenOn() {
    DisposableEffect(Unit) {
        IdleTimer.acquire()
        onDispose { IdleTimer.release() }
    }
}

// iOS Picture in Picture (MediaPlayerView.ios.kt) shows the video in a system window while the
// app's own UI is in the background, so no screen ever needs a shrunken "in PiP" layout.
@Composable
actual fun rememberIsInPipMode(): Boolean = false
