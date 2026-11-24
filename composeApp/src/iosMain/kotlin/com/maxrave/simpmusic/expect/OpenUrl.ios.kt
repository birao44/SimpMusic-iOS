package com.maxrave.simpmusic.expect

import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

actual fun openUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    onMain { UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null) }
}

/**
 * The system share sheet with the link, as Android's ACTION_SEND chooser. [title] is the chooser's
 * heading on Android; the iOS sheet has no heading, so it is not shown.
 */
actual fun shareUrl(
    title: String,
    url: String,
) {
    val item: Any = NSURL.URLWithString(url) ?: url
    onMain {
        presentFromTop(UIActivityViewController(activityItems = listOf(item), applicationActivities = null))
    }
}
