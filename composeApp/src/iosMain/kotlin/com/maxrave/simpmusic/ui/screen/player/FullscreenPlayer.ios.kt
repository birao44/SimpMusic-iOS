package com.maxrave.simpmusic.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * On iPhone the main UI never rotates (rotating it crashed in Skia, see [LandscapeSongController]),
 * so the fullscreen video does not turn the app sideways the way Android does. It opens the
 * landscape song view in its video layout instead, a separate full-screen view controller that may
 * rotate safely; closing that view (its close button, or turning the phone back upright) leaves the
 * fullscreen route through [onExit]. The route itself stays underneath, in portrait, and works on
 * its own if the landscape view could not be shown (another sheet already up).
 */
@Composable
actual fun FullScreenRotationImmersive(
    onLaunch: () -> Unit,
    onDispose: () -> Unit,
    onExit: () -> Unit,
) {
    val currentOnExit by rememberUpdatedState(onExit)
    DisposableEffect(true) {
        onLaunch()
        LandscapeSongController.presentForced(onExit = { currentOnExit() })
        onDispose {
            LandscapeSongController.dismissForced()
            onDispose()
        }
    }
}
