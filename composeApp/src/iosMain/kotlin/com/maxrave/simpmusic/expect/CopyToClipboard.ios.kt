package com.maxrave.simpmusic.expect

import platform.UIKit.UIPasteboard

actual fun copyToClipboard(
    label: String,
    text: String,
) {
    // iOS has no clip label; the pasteboard only carries the text itself.
    UIPasteboard.generalPasteboard.string = text
}
