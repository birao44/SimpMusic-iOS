package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.expect.clearDirectory
import com.maxrave.simpmusic.expect.iosTempDir
import com.maxrave.simpmusic.expect.onMain
import com.maxrave.simpmusic.expect.presentFromTop
import com.maxrave.simpmusic.expect.releaseDelegate
import com.maxrave.simpmusic.expect.retainDelegate
import com.maxrave.simpmusic.expect.safeFileName
import com.maxrave.simpmusic.expect.uniqueStamp
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject

private const val TAG = "FilePicker"

/**
 * The Files document picker, for one file of any type. The file is imported as a copy and moved
 * into the app's own tmp/picked folder under a plain name, so [onResultUri] gets an ordinary
 * filesystem path — the same shape Desktop hands back, and what readPickedFile / restoreNative
 * open on iOS. Each new pick clears the previous one. A cancel reports null.
 */
@Composable
actual fun filePickerResult(onResultUri: (String?) -> Unit): FilePickerLauncher {
    val currentOnResultUri by rememberUpdatedState(onResultUri)
    return remember {
        object : FilePickerLauncher {
            override fun launch() {
                onMain { presentDocumentPicker { path -> currentOnResultUri(path) } }
            }
        }
    }
}

/**
 * iOS cannot ask for a destination before the file exists: the Files "save" sheet exports a file
 * that is already written. So this hands back a fresh path in tmp/export named [fileName]; the
 * caller writes the file there, and the writer (backupNative, the only user) then shows the
 * export sheet with exportFileToUser().
 */
@Composable
actual fun fileSaverResult(
    fileName: String,
    mimeType: String,
    onResultUri: (String?) -> Unit,
): FilePickerLauncher {
    val currentOnResultUri by rememberUpdatedState(onResultUri)
    val currentFileName by rememberUpdatedState(fileName)
    return remember {
        object : FilePickerLauncher {
            @OptIn(ExperimentalForeignApi::class)
            override fun launch() {
                val dir = iosTempDir("export")
                clearDirectory(dir)
                currentOnResultUri("$dir/${safeFileName(currentFileName)}")
            }
        }
    }
}

private fun presentDocumentPicker(onResult: (String?) -> Unit) {
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
    picker.allowsMultipleSelection = false
    lateinit var delegate: DocumentPickerDelegate
    delegate =
        DocumentPickerDelegate { url ->
            releaseDelegate(delegate)
            onResult(url?.let(::copyIntoPickedFolder))
        }
    retainDelegate(delegate)
    picker.delegate = delegate
    if (!presentFromTop(picker)) {
        releaseDelegate(delegate)
        Logger.w(TAG, "No view controller to present the document picker from")
    }
}

/** Moves the picked copy into tmp/picked under a safe name; returns its path, or null on failure. */
@OptIn(ExperimentalForeignApi::class)
private fun copyIntoPickedFolder(url: NSURL): String? {
    // asCopy already hands over a private copy; the scope call is harmless for it and keeps an
    // in-place url (should a provider ever send one) readable while it is copied.
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        val dir = iosTempDir("picked")
        clearDirectory(dir)
        val extension = url.pathExtension?.let(::safeFileName)?.takeIf { it.isNotEmpty() && it != "file" }
        val target = NSURL.fileURLWithPath("$dir/picked_${uniqueStamp()}" + (extension?.let { ".$it" } ?: ""))
        val fm = NSFileManager.defaultManager
        val ok =
            fm.moveItemAtURL(url, toURL = target, error = null) ||
                fm.copyItemAtURL(url, toURL = target, error = null)
        if (!ok) Logger.w(TAG, "Could not copy picked file ${url.lastPathComponent}")
        return if (ok) target.path else null
    } finally {
        if (scoped) url.stopAccessingSecurityScopedResource()
    }
}

private class DocumentPickerDelegate(
    private val onDone: (NSURL?) -> Unit,
) : NSObject(),
    UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) = onDone(didPickDocumentsAtURLs.firstOrNull() as? NSURL)

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onDone(null)
}
