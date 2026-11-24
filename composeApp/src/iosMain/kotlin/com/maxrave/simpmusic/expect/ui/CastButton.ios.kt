package com.maxrave.simpmusic.expect.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVKit.AVRoutePickerView
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor

/**
 * The AirPlay button: the system route picker, which also lists Bluetooth and wired outputs. There
 * is no Google Cast sender on iOS; AirPlay is what this slot means here.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformCastButton(
    modifier: Modifier,
    tint: Color,
) {
    UIKitView(
        factory = {
            AVRoutePickerView(frame = CGRectMake(0.0, 0.0, 40.0, 40.0)).apply {
                backgroundColor = UIColor.clearColor
                prioritizesVideoDevices = false
            }
        },
        modifier = modifier.size(40.dp),
        update = { picker ->
            val color = tint.toUIColor()
            picker.tintColor = color
            picker.activeTintColor = color
        },
    )
}

actual fun isPlatformCastAvailable(): Boolean = true

// The AirPlay picker is the system's own sheet; there is no receiver list for the app to show.
@Composable
actual fun rememberCastReceivers(discover: Boolean): CastReceivers = NoCastReceivers

private val NoCastReceivers = CastReceivers(receivers = emptyList(), connect = {}, disconnect = {})

private fun Color.toUIColor(): UIColor = UIColor(red = red.toDouble(), green = green.toDouble(), blue = blue.toDouble(), alpha = alpha.toDouble())
