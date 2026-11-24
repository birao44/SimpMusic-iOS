package com.maxrave.simpmusic.expect.ui

import com.maxrave.domain.mediaservice.player.ios.NativePlayerConstants
import com.maxrave.domain.mediaservice.player.ios.NativePlayerRegistry
import com.maxrave.logger.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.QuartzCore.CATransaction
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIColor
import platform.UIKit.UIDevice
import platform.UIKit.UIInterfaceOrientationMaskAllButUpsideDown
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIViewController
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS
import platform.UIKit.setNeedsUpdateOfSupportedInterfaceOrientations
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.NSObjectProtocol

/**
 * A plain UIView carrying an [AVPlayerLayer] that always fills it. UIKitView sets the view's frame
 * (layout, rotation); [layoutSubviews] follows it without the layer's implicit resize animation.
 */
@OptIn(ExperimentalForeignApi::class)
internal class PlayerLayerView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    val playerLayer: AVPlayerLayer = AVPlayerLayer()

    init {
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
        userInteractionEnabled = false
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = bounds
        CATransaction.commit()
    }
}

/** The engine's AVPlayer for [handle], or null. Main thread only (the engine's rule). */
internal fun engineVideoPlayer(handle: Int): AVPlayer? {
    if (handle == NativePlayerConstants.NO_HANDLE) return null
    return NativePlayerRegistry.engine?.nativePlayer(handle) as? AVPlayer
}

internal fun isAppInBackground(): Boolean = UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground

/**
 * Calls [onChange] with true when the app goes to the background and false when it comes back.
 * Synchronous on the posting (main) thread: a video layer must let go of its player before the
 * system sees it attached in the background, and Compose does not recompose while backgrounded.
 */
internal class AppBackgroundObserver(
    onChange: (inBackground: Boolean) -> Unit,
) {
    private val center = NSNotificationCenter.defaultCenter
    private val tokens: List<NSObjectProtocol> =
        listOf(
            center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, null) { _ -> onChange(true) },
            center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, null) { _ -> onChange(false) },
        )

    fun release() {
        tokens.forEach { center.removeObserver(it) }
    }
}

internal fun keyWindowScene(): UIWindowScene? {
    val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
    return scenes.firstOrNull { scene -> scene.windows.any { (it as? UIWindow)?.keyWindow == true } } ?: scenes.firstOrNull()
}

internal fun keyWindow(): UIWindow? {
    val windows = keyWindowScene()?.windows?.filterIsInstance<UIWindow>() ?: return null
    return windows.firstOrNull { it.keyWindow } ?: windows.firstOrNull()
}

/**
 * The interface orientations the app allows right now, read by the Swift AppDelegate's
 * `supportedInterfaceOrientationsFor` through `orientationLockMask()` (MainViewController.kt).
 *
 * On iPhone the app itself is portrait only ([DEFAULT_MASK]): rotating the main Compose UI resized
 * the liquid-glass tab bar's backdrop layers mid-frame and crashed in Skia (see
 * LandscapeSongController). Landscape is allowed only while the landscape song view, a separate
 * full-screen view controller, covers the main UI. iPad keeps every orientation but upside down.
 */
internal object OrientationLock {
    val DEFAULT_MASK: ULong =
        if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
            UIInterfaceOrientationMaskAllButUpsideDown
        } else {
            UIInterfaceOrientationMaskPortrait
        }

    var mask: ULong = DEFAULT_MASK
        private set

    /**
     * Allows [newMask] and rotates the window into one of [rotateTo] (or [newMask] when null).
     * [onRefused] runs (main thread) when UIKit turns the rotation down.
     */
    fun set(
        newMask: ULong,
        rotateTo: ULong? = null,
        onRefused: () -> Unit = {},
    ) {
        mask = newMask
        val scene = keyWindowScene() ?: return
        scene.windows.filterIsInstance<UIWindow>().forEach { window ->
            var controller: UIViewController? = window.rootViewController
            while (controller != null) {
                controller.setNeedsUpdateOfSupportedInterfaceOrientations()
                controller = controller.presentedViewController
            }
        }
        scene.requestGeometryUpdateWithPreferences(UIWindowSceneGeometryPreferencesIOS(interfaceOrientations = rotateTo ?: newMask)) { error ->
            Logger.w("OrientationLock", "Geometry update refused: ${error?.localizedDescription}")
            dispatch_async(dispatch_get_main_queue()) { onRefused() }
        }
    }
}

/**
 * Keeps the screen awake while at least one holder wants it, so two screens that both ask
 * (Now Playing and the fullscreen player) cannot switch it back off for each other.
 */
internal object IdleTimer {
    private var holders = 0

    fun acquire() {
        holders++
        UIApplication.sharedApplication.idleTimerDisabled = true
    }

    fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) UIApplication.sharedApplication.idleTimerDisabled = false
    }
}
