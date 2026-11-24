package com.maxrave.simpmusic.expect

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/*
 * Small UIKit plumbing shared by the iOS actuals that show system UI (document picker, photo
 * picker, share sheet): where to present from, the main-thread hop, and somewhere to keep the
 * delegates alive. UIKit holds its delegates weakly, so a delegate nobody else references is
 * collected before the user has picked anything and the callback never arrives.
 */

/** Delegates waiting for a UIKit callback. Only touched on the main thread. */
private val retainedDelegates = mutableSetOf<Any>()

internal fun retainDelegate(delegate: Any) {
    retainedDelegates += delegate
}

internal fun releaseDelegate(delegate: Any) {
    retainedDelegates -= delegate
}

/** Runs [block] on the main thread: now when already there, otherwise on the next main-queue turn. */
internal fun onMain(block: () -> Unit) {
    if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
}

/** The app's key window — the one the Compose root lives in. */
internal fun keyWindow(): UIWindow? {
    val windows =
        UIApplication.sharedApplication.connectedScenes
            .mapNotNull { it as? UIWindowScene }
            .flatMap { scene -> scene.windows.mapNotNull { it as? UIWindow } }
    return windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull()
}

/** The view controller currently on top, i.e. the one a new modal has to be presented from. */
internal fun topViewController(): UIViewController? {
    var top = keyWindow()?.rootViewController ?: return null
    while (true) {
        val presented = top.presentedViewController ?: break
        if (presented.isBeingDismissed()) break
        top = presented
    }
    return top
}

/**
 * Presents [controller] modally over whatever is on screen. On iPad an activity sheet or picker
 * shown as a popover needs an anchor or UIKit throws, so it is anchored to the middle of the
 * presenting view with no arrow. Must be called on the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun presentFromTop(controller: UIViewController): Boolean {
    val top = topViewController() ?: return false
    controller.popoverPresentationController?.let { popover ->
        popover.sourceView = top.view
        popover.sourceRect =
            top.view.bounds.useContents { CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
        popover.permittedArrowDirections = 0uL
    }
    top.presentViewController(controller, animated = true, completion = null)
    return true
}

/** The app's temporary directory with [name] beneath it, created if missing. No trailing slash. */
@OptIn(ExperimentalForeignApi::class)
internal fun iosTempDir(name: String): String {
    val path = NSTemporaryDirectory().trimEnd('/') + "/" + name
    NSFileManager.defaultManager.createDirectoryAtPath(
        path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return path
}

/** Removes everything inside [path] (but not [path] itself). Failures are ignored. */
@OptIn(ExperimentalForeignApi::class)
internal fun clearDirectory(path: String) {
    val fm = NSFileManager.defaultManager
    fm.contentsOfDirectoryAtPath(path, error = null)?.forEach { child ->
        fm.removeItemAtPath("$path/$child", error = null)
    }
}

/**
 * A file name that is safe both on disk and when the path later goes through a KMP Uri
 * (`toKmpUri()` would read `#` or `?` as a fragment or query): letters, digits, `.`, `-`, `_`.
 */
internal fun safeFileName(name: String): String {
    val cleaned = name.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }.joinToString("")
    return cleaned.trim('.').ifEmpty { "file" }
}

internal fun uniqueStamp(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/**
 * The plain filesystem path behind what the iOS pickers hand back: they return paths, but a
 * `file://` url (percent-encoded) is accepted too.
 */
internal fun pickedPathOf(uri: String): String =
    if (uri.startsWith("file:")) NSURL.URLWithString(uri)?.path ?: uri.removePrefix("file://") else uri

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
    }

/**
 * Shows the system "Save to Files" sheet for the file at [path] (exported as a copy). Suspends until
 * the user saved it (true) or cancelled (false).
 */
internal suspend fun exportFileToUser(path: String): Boolean =
    suspendCancellableCoroutine { continuation ->
        onMain {
            val picker =
                UIDocumentPickerViewController(
                    forExportingURLs = listOf(NSURL.fileURLWithPath(path)),
                    asCopy = true,
                )
            lateinit var delegate: ExportDelegate
            delegate =
                ExportDelegate { saved ->
                    releaseDelegate(delegate)
                    if (continuation.isActive) continuation.resume(saved)
                }
            retainDelegate(delegate)
            picker.delegate = delegate
            if (!presentFromTop(picker)) {
                releaseDelegate(delegate)
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

private class ExportDelegate(
    private val onDone: (Boolean) -> Unit,
) : NSObject(),
    UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) = onDone(true)

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onDone(false)
}
