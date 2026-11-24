package com.maxrave.simpmusic.expect

import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.Foundation.writeToFile
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceCreationOptions
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIActivityViewController
import kotlin.coroutines.resume

private const val TAG = "ShareImage"

/**
 * Adds the image to the Photos library (add-only access; NSPhotoLibraryAddUsageDescription is in
 * Info.plist). Asks for that access first if it was never asked, so a caller that skipped
 * rememberSaveImagePermission still works.
 */
actual suspend fun saveImageToDevice(
    bytes: ByteArray,
    fileName: String,
): Boolean {
    if (bytes.isEmpty() || !requestPhotoAddAccess()) return false
    val data = bytes.toNSData()
    return suspendCancellableCoroutine { continuation ->
        PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            {
                val options = PHAssetResourceCreationOptions().apply { originalFilename = fileName }
                PHAssetCreationRequest
                    .creationRequestForAsset()
                    .addResourceWithType(PHAssetResourceTypePhoto, data = data, options = options)
            },
            completionHandler = { success, error ->
                if (!success) Logger.w(TAG, "Could not save image: ${error?.localizedDescription}")
                if (continuation.isActive) continuation.resume(success)
            },
        )
    }
}

/**
 * Writes the bytes to tmp/share under [fileName] and opens the system share sheet on that file, so
 * the receiving app gets a real file with a name and type ("Save Image" / "Save to Files" are in
 * the sheet too). True once the sheet is up.
 */
actual suspend fun shareImage(
    bytes: ByteArray,
    fileName: String,
    chooserTitle: String,
    mimeType: String,
): Boolean {
    val path =
        withContext(Dispatchers.IO) {
            val dir = iosTempDir("share")
            clearDirectory(dir)
            val name = safeFileName(fileName).let { if ('.' in it) it else it + extensionFor(mimeType) }
            "$dir/$name".takeIf { bytes.toNSData().writeToFile(it, atomically = true) }
        } ?: return false
    return withContext(Dispatchers.Main) {
        val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(path)), applicationActivities = null)
        presentFromTop(sheet)
    }
}

private fun extensionFor(mimeType: String): String =
    when (mimeType.lowercase()) {
        "image/png" -> ".png"
        "image/jpeg", "image/jpg" -> ".jpg"
        "text/plain" -> ".txt"
        else -> ""
    }

/** True when the app may add to the photo library, asking the user first if it never has. */
internal suspend fun requestPhotoAddAccess(): Boolean {
    val status = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelAddOnly)
    if (status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited) return true
    if (status != PHAuthorizationStatusNotDetermined) return false
    return suspendCancellableCoroutine { continuation ->
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { newStatus ->
            if (continuation.isActive) {
                continuation.resume(newStatus == PHAuthorizationStatusAuthorized || newStatus == PHAuthorizationStatusLimited)
            }
        }
    }
}
