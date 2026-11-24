package com.maxrave.simpmusic.ui.screen.player

import androidx.compose.ui.window.ComposeUIViewController
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.expect.ui.IdleTimer
import com.maxrave.simpmusic.expect.ui.OrientationLock
import com.maxrave.simpmusic.expect.ui.keyWindow
import com.maxrave.simpmusic.expect.ui.keyWindowScene
import com.maxrave.simpmusic.viewModel.SharedViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UIInterfaceOrientationMaskLandscapeLeft
import platform.UIKit.UIInterfaceOrientationMaskLandscapeRight
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIModalTransitionStyleCrossDissolve
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIViewController
import platform.darwin.NSObjectProtocol

/**
 * The landscape song view on iPhone: turn the phone sideways while a song is loaded and a
 * full-screen landscape player ([LandscapeSongScreen]) opens; turn it back and it closes, leaving
 * the app where it was.
 *
 * Why it is a separate view controller. The main UI is portrait only on iPhone
 * ([OrientationLock.DEFAULT_MASK]). Rotating it used to crash in Skia (SIGSEGV in
 * `SkCanvas::saveLayer` with a blur + colour-filter image filter, and in `SkNWayCanvas::willSave`
 * from `clipRRect`): the liquid-glass tab bar draws the Kyant backdrop through `vibrancy()` +
 * `colorControls()` + `blur()` clipped to a capsule, and on the size change the window-size class
 * flips (a landscape iPhone is wider than 600dp, so App switches to its tablet layout and back),
 * which detaches and re-creates the backdrop's source layer (`Modifier.layerBackdrop` is applied
 * only `!isTablet`) and the glass bar while frames are still being recorded against them.
 *
 * So the main UI never changes size: the landscape view is its own ComposeUIViewController,
 * presented full screen (UIKit takes the presenter's view out of the window), and only once that
 * presentation has finished is landscape allowed and requested. Closing reverses it: portrait is
 * requested first, the landscape view rotates (it has no blur, backdrop or glass, so resizing it is
 * safe), and only then is it dismissed, so the main UI comes back at the size it left with.
 *
 * Device orientation is read from UIDevice notifications, which keep coming while the interface is
 * portrait locked (debounced, face up / down and unknown ignored). With the system Portrait
 * Orientation Lock on, iOS sends none, so the view does not open by itself then; the fullscreen
 * button of a video still opens it ([presentForced]).
 */
internal object LandscapeSongController {
    private const val TAG = "LandscapeSong"
    private const val DEBOUNCE_MS = 300L

    private val scope = MainScope()
    private var debounce: Job? = null
    private val observers = mutableListOf<NSObjectProtocol>()

    /** The presented landscape view, from present() until its dismissal has finished. */
    private var host: UIViewController? = null
    private var closing = false

    /** Opened by the fullscreen button: plays the video, and leaving it pops the fullscreen route. */
    private var forced = false
    private var onForcedExit: (() -> Unit)? = null

    /** A forced view closes on portrait only after the phone has actually been held sideways. */
    private var seenLandscape = false

    /** Closed by hand while sideways: do not reopen until the phone has been upright again. */
    private var waitForPortrait = false

    private val isPad: Boolean
        get() = UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad

    /** Starts listening. Called once from MainViewController(), main thread. */
    fun start() {
        if (observers.isNotEmpty() || isPad) return
        UIDevice.currentDevice.beginGeneratingDeviceOrientationNotifications()
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(UIDeviceOrientationDidChangeNotification, null, NSOperationQueue.mainQueue) { _ -> schedule() }
        // Orientation changes made while the app was in the background arrive as nothing at all.
        observers += center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ -> schedule() }
    }

    private fun schedule() {
        debounce?.cancel()
        debounce =
            scope.launch {
                delay(DEBOUNCE_MS)
                onDeviceOrientation(UIDevice.currentDevice.orientation)
            }
    }

    private fun onDeviceOrientation(orientation: UIDeviceOrientation) {
        when (orientation) {
            UIDeviceOrientation.UIDeviceOrientationLandscapeLeft,
            UIDeviceOrientation.UIDeviceOrientationLandscapeRight,
            -> {
                if (host != null) {
                    seenLandscape = true
                    return
                }
                if (waitForPortrait || !isSongLoaded()) return
                present(forcedMode = false)
            }

            UIDeviceOrientation.UIDeviceOrientationPortrait -> {
                waitForPortrait = false
                if (host != null && (!forced || seenLandscape)) close()
            }

            // Face up / down, upside down, unknown: keep whatever is showing.
            else -> Unit
        }
    }

    private fun isSongLoaded(): Boolean =
        runCatching {
            KoinPlatform
                .getKoin()
                .get<SharedViewModel>()
                .nowPlayingState.value
                ?.isNotEmpty() == true
        }.getOrDefault(false)

    /** The fullscreen button of the video player (FullScreenRotationImmersive). */
    fun presentForced(onExit: () -> Unit): Boolean {
        if (host != null) {
            // Already showing (opened by rotation): it takes over the route's exit.
            if (!closing) {
                forced = true
                onForcedExit = onExit
            }
            return !closing
        }
        if (!present(forcedMode = true)) return false
        onForcedExit = onExit
        return true
    }

    /** The fullscreen route went away by itself: close without popping it again. */
    fun dismissForced() {
        if (!forced) return
        onForcedExit = null
        close()
    }

    private fun present(forcedMode: Boolean): Boolean {
        if (host != null || closing) return false
        if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) return false
        val root = keyWindow()?.rootViewController ?: return false
        // Something else is up (share sheet, picker, the player test bench): leave it alone.
        if (root.presentedViewController != null) return false

        val controller =
            ComposeUIViewController {
                LandscapeSongScreen(
                    videoOnly = forcedMode,
                    onClose = { userClose() },
                )
            }
        controller.modalPresentationStyle = UIModalPresentationFullScreen
        controller.modalTransitionStyle = UIModalTransitionStyleCrossDissolve

        host = controller
        forced = forcedMode
        seenLandscape = !forcedMode || isDeviceLandscape()
        IdleTimer.acquire()
        root.presentViewController(controller, animated = true) {
            // Only now is the main UI out of the window, so only now may the interface rotate.
            if (host === controller && !closing) {
                OrientationLock.set(
                    UIInterfaceOrientationMaskLandscape,
                    rotateTo = landscapeMaskForDevice(),
                    onRefused = { if (host === controller) close() },
                )
            }
        }
        return true
    }

    /** The close button of the landscape view. */
    private fun userClose() {
        if (isDeviceLandscape()) waitForPortrait = true
        close()
    }

    private fun close() {
        val controller = host ?: return
        if (closing) return
        closing = true
        val exit = onForcedExit
        onForcedExit = null
        OrientationLock.set(OrientationLock.DEFAULT_MASK)
        scope.launch {
            // Back to portrait while the landscape view still covers the main UI; give the
            // rotation up to 1.5s, then a moment for its animation to finish.
            var waited = 0
            while (!isInterfacePortrait() && waited < 1500) {
                delay(50)
                waited += 50
            }
            if (waited > 0) delay(350)
            controller.dismissViewControllerAnimated(true) {
                if (host === controller) host = null
                closing = false
                forced = false
                seenLandscape = false
                IdleTimer.release()
                Logger.d(TAG, "Landscape song view closed")
                exit?.invoke()
            }
        }
    }

    private fun isInterfacePortrait(): Boolean =
        when (keyWindowScene()?.effectiveGeometry?.interfaceOrientation) {
            UIInterfaceOrientationLandscapeLeft, UIInterfaceOrientationLandscapeRight -> false
            else -> true
        }

    private fun isDeviceLandscape(): Boolean =
        when (UIDevice.currentDevice.orientation) {
            UIDeviceOrientation.UIDeviceOrientationLandscapeLeft,
            UIDeviceOrientation.UIDeviceOrientationLandscapeRight,
            -> true

            else -> false
        }

    /** Device landscape-left (top of the phone to the left) is interface landscape-right. */
    private fun landscapeMaskForDevice(): ULong =
        when (UIDevice.currentDevice.orientation) {
            UIDeviceOrientation.UIDeviceOrientationLandscapeLeft -> UIInterfaceOrientationMaskLandscapeRight
            UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> UIInterfaceOrientationMaskLandscapeLeft
            else -> UIInterfaceOrientationMaskLandscape
        }
}
