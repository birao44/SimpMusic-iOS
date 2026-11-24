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
import com.maxrave.simpmusic.expect.uniqueStamp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.writeToFile
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImageOrientation
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.darwin.NSObject
import kotlin.math.max
import kotlin.math.min

private const val TAG = "PhotoPicker"

/** Longest side a picked photo is kept at: plenty for a cropped playlist cover. */
private const val MAX_SIDE = 2048.0

/**
 * The system photo picker (PHPicker: no library permission needed). The picked photo is decoded by
 * UIKit — so HEIC works — turned upright, capped at [MAX_SIDE] and written as a JPEG to
 * tmp/picked; [onResultUri] gets that plain path (what readLocalImageBytes reads on iOS), on the
 * main thread. Skia, which decodes it next, neither reads HEIC nor applies EXIF orientation.
 */
@Composable
actual fun photoPickerResult(onResultUri: (String?) -> Unit): PhotoPickerLauncher {
    val currentOnResultUri by rememberUpdatedState(onResultUri)
    return remember {
        object : PhotoPickerLauncher {
            override fun launch() {
                onMain { presentPhotoPicker { path -> currentOnResultUri(path) } }
            }
        }
    }
}

private fun presentPhotoPicker(onResult: (String?) -> Unit) {
    val configuration =
        PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = 1
        }
    val picker = PHPickerViewController(configuration)
    lateinit var delegate: PhotoPickerDelegate
    delegate =
        PhotoPickerDelegate { result ->
            releaseDelegate(delegate)
            if (result == null) {
                onResult(null)
            } else {
                result.itemProvider.loadDataRepresentationForTypeIdentifier(UTTypeImage.identifier) { data, error ->
                    // Called on a background queue; the file work stays here, the answer goes to main.
                    val path = data?.let(::writePickedImage)
                    if (path == null) Logger.w(TAG, "Could not load picked image: ${error?.localizedDescription}")
                    onMain { onResult(path) }
                }
            }
        }
    retainDelegate(delegate)
    picker.delegate = delegate
    if (!presentFromTop(picker)) {
        releaseDelegate(delegate)
        Logger.w(TAG, "No view controller to present the photo picker from")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writePickedImage(data: NSData): String? {
    val image = UIImage.imageWithData(data) ?: return null
    val (width, height) = image.size.useContents { width to height }
    if (width <= 0.0 || height <= 0.0) return null
    val scale = min(1.0, MAX_SIDE / max(width, height))
    val upright =
        if (image.imageOrientation == UIImageOrientation.UIImageOrientationUp && scale >= 1.0) {
            image
        } else {
            val target = CGSizeMake(width * scale, height * scale)
            val format = UIGraphicsImageRendererFormat.preferredFormat().apply { this.scale = 1.0 }
            // drawInRect applies the image's orientation, so the result is upright.
            UIGraphicsImageRenderer(size = target, format = format).imageWithActions { _ ->
                image.drawInRect(CGRectMake(0.0, 0.0, width * scale, height * scale))
            }
        }
    val jpeg = UIImageJPEGRepresentation(upright, 0.92) ?: return null
    val dir = iosTempDir("picked")
    clearDirectory(dir)
    val path = "$dir/photo_${uniqueStamp()}.jpg"
    return if (jpeg.writeToFile(path, atomically = true)) path else null
}

private class PhotoPickerDelegate(
    private val onDone: (PHPickerResult?) -> Unit,
) : NSObject(),
    PHPickerViewControllerDelegateProtocol {
    override fun picker(
        picker: PHPickerViewController,
        didFinishPicking: List<*>,
    ) {
        picker.dismissViewControllerAnimated(true, completion = null)
        onDone(didFinishPicking.firstOrNull() as? PHPickerResult)
    }
}
