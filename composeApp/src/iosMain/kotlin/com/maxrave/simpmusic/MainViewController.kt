package com.maxrave.simpmusic

import androidx.compose.ui.window.ComposeUIViewController
import com.eygraber.uri.Uri
import com.maxrave.domain.data.model.intent.GenericIntent
import com.maxrave.domain.mediaservice.player.ios.NativePlayerEngine
import com.maxrave.simpmusic.viewModel.SharedViewModel
import org.koin.mp.KoinPlatform
import com.maxrave.domain.mediaservice.player.ios.NativePlayerRegistry
import com.maxrave.simpmusic.expect.ui.OrientationLock
import com.maxrave.simpmusic.ui.screen.player.LandscapeSongController
import platform.UIKit.UIViewController

/**
 * Entry point for the Swift app (SceneDelegate). Starts Koin and Coil once before the first App(),
 * and the landscape song view's device-orientation watcher (the main UI itself stays portrait on
 * iPhone, see OrientationLock).
 */
fun MainViewController(): UIViewController {
    startIosApp()
    LandscapeSongController.start()
    return ComposeUIViewController { App() }
}

/**
 * Called by the Swift app before [MainViewController]: hands over the AVPlayer-backed engine the
 * iOS player drives. Also what puts the two bridge interfaces into the framework header.
 */
fun registerNativePlayerEngine(engine: NativePlayerEngine) {
    NativePlayerRegistry.engine = engine
}

/**
 * The `UIInterfaceOrientationMask` the app allows right now, for the Swift AppDelegate's
 * `supportedInterfaceOrientationsFor`. Portrait on iPhone; landscape only while the landscape song
 * view (LandscapeSongController) covers the main UI.
 */
fun orientationLockMask(): Int = OrientationLock.mask.toInt()

/**
 * A `simpmusic://` link opened from outside (Siri Shortcuts, Safari, another app), handed over by
 * the Swift SceneDelegate. It goes through the same router as on Android and desktop (App.kt); on a
 * cold launch it waits in the shared view model until the first composition reads it.
 */
fun openAppUrl(url: String) {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
    KoinPlatform
        .getKoin()
        .get<SharedViewModel>()
        .setIntent(GenericIntent(action = "android.intent.action.VIEW", data = uri))
}
