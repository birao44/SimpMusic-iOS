package com.maxrave.simpmusic.expect

import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortrait
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown

/**
 * The orientation the UI is laid out in, from the key window's scene. UIDevice's orientation is the
 * fallback only: it is the physical one (face up / down when flat, unknown unless orientation
 * notifications are running) and can disagree with the screen when rotation is locked.
 */
actual fun currentOrientation(): Orientation {
    when (keyWindow()?.windowScene?.interfaceOrientation) {
        UIInterfaceOrientationPortrait, UIInterfaceOrientationPortraitUpsideDown -> return Orientation.PORTRAIT
        UIInterfaceOrientationLandscapeLeft, UIInterfaceOrientationLandscapeRight -> return Orientation.LANDSCAPE
        else -> Unit
    }
    return when (UIDevice.currentDevice.orientation) {
        UIDeviceOrientation.UIDeviceOrientationPortrait,
        UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown,
        -> Orientation.PORTRAIT

        UIDeviceOrientation.UIDeviceOrientationLandscapeLeft,
        UIDeviceOrientation.UIDeviceOrientationLandscapeRight,
        -> Orientation.LANDSCAPE

        else -> Orientation.UNSPECIFIED
    }
}
