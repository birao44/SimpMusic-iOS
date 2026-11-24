package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.outputVolume
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.MediaPlayer.MPVolumeView
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UISlider

/**
 * The system volume. Read from `AVAudioSession.outputVolume`; set through the slider inside an
 * MPVolumeView, the only public way an app can move it. The MPVolumeView sits, invisible, in the
 * key window while the controller is on screen, which also keeps the system volume HUD away.
 */
@Composable
actual fun rememberDeviceVolumeController(): DeviceVolumeController? {
    val controller = remember { IosDeviceVolumeController() }
    DisposableEffect(controller) {
        controller.attach()
        onDispose { controller.detach() }
    }
    // outputVolume is KVO-observable, but a slow poll while the row is on screen is simpler.
    LaunchedEffect(controller) {
        while (true) {
            controller.poll()
            delay(250)
        }
    }
    return controller
}

@OptIn(ExperimentalForeignApi::class)
private class IosDeviceVolumeController : DeviceVolumeController {
    private var fraction by mutableFloatStateOf(AVAudioSession.sharedInstance().outputVolume)
    private var volumeView: MPVolumeView? = null

    /** Until then the system has not caught up with our own set; polling would snap the slider back. */
    private var holdPollUntil = 0.0

    override val volumeFraction: Float
        get() = fraction

    override fun setVolumeFraction(fraction: Float) {
        val value = fraction.coerceIn(0f, 1f)
        this.fraction = value
        holdPollUntil = NSDate().timeIntervalSince1970 + 0.5
        val slider = volumeView?.subviews?.firstOrNull { it is UISlider } as? UISlider ?: return
        slider.setValue(value, animated = false)
        slider.sendActionsForControlEvents(UIControlEventValueChanged)
    }

    fun attach() {
        if (volumeView != null) return
        val window = keyWindow() ?: return
        volumeView =
            MPVolumeView(frame = CGRectMake(-200.0, -200.0, 100.0, 40.0)).also {
                it.alpha = 0.01
                it.userInteractionEnabled = false
                window.addSubview(it)
            }
    }

    fun detach() {
        volumeView?.removeFromSuperview()
        volumeView = null
    }

    fun poll() {
        if (volumeView == null) attach()
        if (NSDate().timeIntervalSince1970 < holdPollUntil) return
        val current = AVAudioSession.sharedInstance().outputVolume
        if (current != fraction) fraction = current
    }
}
