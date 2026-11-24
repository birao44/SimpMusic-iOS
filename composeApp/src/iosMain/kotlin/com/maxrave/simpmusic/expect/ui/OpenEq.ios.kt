package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

// iOS has no system equalizer screen to hand an audio session to.
@Composable
actual fun openEqResult(audioSessionId: Int): OpenEqLauncher = NoOpEqLauncher

private object NoOpEqLauncher : OpenEqLauncher {
    override fun launch() = Unit
}
