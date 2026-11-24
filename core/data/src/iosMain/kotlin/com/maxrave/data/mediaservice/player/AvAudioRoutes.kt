package com.maxrave.data.mediaservice.player

import com.maxrave.domain.data.player.AudioOutput
import com.maxrave.domain.data.player.AudioOutputKind
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortAirPlay
import platform.AVFAudio.AVAudioSessionPortBluetoothA2DP
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortBluetoothLE
import platform.AVFAudio.AVAudioSessionPortBuiltInReceiver
import platform.AVFAudio.AVAudioSessionPortBuiltInSpeaker
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortHDMI
import platform.AVFAudio.AVAudioSessionPortHeadphones
import platform.AVFAudio.AVAudioSessionPortLineOut
import platform.AVFAudio.AVAudioSessionPortUSBAudio
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.currentRoute
import platform.AVKit.AVRoutePickerView
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Audio outputs on iOS. The system owns routing: an app cannot send its sound to one speaker while
 * the rest of the device uses another, and only the system picker can switch to AirPlay. So the list
 * is where the sound goes now (every output of the current route, active), plus one "AirPlay &
 * Bluetooth" entry while the route is not AirPlay; picking any entry opens the system route picker.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvAudioRoutes {
    private val _outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
    val outputs: StateFlow<List<AudioOutput>> = _outputs.asStateFlow()

    private val routeObserver: NSObjectProtocol =
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            queue = null,
        ) { _ -> refresh() }

    /** Kept in the window between uses so a second pick does not rebuild it. Main thread only. */
    private var pickerView: AVRoutePickerView? = null

    init {
        refresh()
    }

    /** Re-reads the current route. AVAudioSession may be read from any thread. */
    fun refresh() {
        val ports =
            AVAudioSession
                .sharedInstance()
                .currentRoute
                .outputs
                .mapNotNull { it as? AVAudioSessionPortDescription }
        val current =
            ports.map { port ->
                AudioOutput(id = port.UID, name = port.portName, kind = kindOf(port.portType), isActive = true)
            }
        val onAirPlay = ports.any { it.portType == AVAudioSessionPortAirPlay }
        _outputs.value =
            if (onAirPlay) current else current + AudioOutput(PICKER_ID, PICKER_NAME, AudioOutputKind.OTHER, isActive = false)
    }

    /** Opens the system AirPlay / Bluetooth picker, as tapping an AVRoutePickerView does. */
    fun showRoutePicker() {
        dispatch_async(dispatch_get_main_queue()) {
            val window = keyWindow() ?: return@dispatch_async
            val picker =
                pickerView?.takeIf { it.window != null } ?: AVRoutePickerView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0)).also {
                    // Invisible and untouchable; it only anchors the picker (a popover on iPad).
                    it.alpha = 0.02
                    it.userInteractionEnabled = false
                    it.center = window.center
                    window.addSubview(it)
                    pickerView = it
                }
            // The picker view's only control is its button; its action presents the picker.
            picker.subviews
                .firstOrNull { it is UIButton }
                ?.let { (it as UIButton).sendActionsForControlEvents(UIControlEventTouchUpInside) }
        }
    }

    fun release() {
        NSNotificationCenter.defaultCenter.removeObserver(routeObserver)
        dispatch_async(dispatch_get_main_queue()) {
            pickerView?.removeFromSuperview()
            pickerView = null
        }
    }

    private fun kindOf(type: String?): AudioOutputKind =
        when (type) {
            AVAudioSessionPortBuiltInSpeaker, AVAudioSessionPortBuiltInReceiver -> AudioOutputKind.DEVICE_SPEAKER
            AVAudioSessionPortHeadphones, AVAudioSessionPortLineOut -> AudioOutputKind.WIRED
            AVAudioSessionPortBluetoothA2DP, AVAudioSessionPortBluetoothLE, AVAudioSessionPortBluetoothHFP -> AudioOutputKind.BLUETOOTH
            AVAudioSessionPortUSBAudio -> AudioOutputKind.USB
            AVAudioSessionPortHDMI -> AudioOutputKind.HDMI
            else -> AudioOutputKind.OTHER
        }

    private fun keyWindow(): UIWindow? {
        val windows =
            UIApplication.sharedApplication.connectedScenes
                .filterIsInstance<UIWindowScene>()
                .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
        return windows.firstOrNull { it.keyWindow } ?: windows.firstOrNull()
    }

    private companion object {
        const val PICKER_ID = "ios-route-picker"

        // Not localised: the data layer has no string resources, and the name is Apple's.
        const val PICKER_NAME = "AirPlay & Bluetooth"
    }
}
