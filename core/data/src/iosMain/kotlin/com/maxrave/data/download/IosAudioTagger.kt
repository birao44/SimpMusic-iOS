package com.maxrave.data.download

import com.maxrave.logger.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetExportPresetPassthrough
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetExportSessionStatusCompleted
import platform.AVFoundation.AVFileTypeAppleM4A
import platform.AVFoundation.AVMetadataCommonIdentifierAlbumName
import platform.AVFoundation.AVMetadataCommonIdentifierArtist
import platform.AVFoundation.AVMetadataCommonIdentifierArtwork
import platform.AVFoundation.AVMetadataCommonIdentifierTitle
import platform.AVFoundation.AVMutableMetadataItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVURLAssetPreferPreciseDurationAndTimingKey
import platform.AVFoundation.setMetadata
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.darwin.NSObject
import kotlin.coroutines.resume

private const val TAG = "IosAudioTagger"

/**
 * Rewrites a downloaded stream as a regular, tagged `.m4a` without re-encoding.
 *
 * YouTube's itag 140 is DASH-style fragmented MP4: no usable overall duration, which is what makes
 * AVPlayer over-report its length. A passthrough export copies the AAC samples into a plain MP4
 * with a real `mvhd` duration and an iTunes-style tag block (title, artist, album, cover art).
 * Nothing is decoded, so it takes well under a second for a song.
 *
 * Returns false on any failure; the caller then keeps the untouched stream, which plays just as
 * well (with the recorded true length as the backstop for its duration).
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosAudioTagger {
    suspend fun exportTagged(
        sourcePath: String,
        destinationPath: String,
        title: String,
        artist: String?,
        album: String?,
        artworkJpeg: ByteArray?,
    ): Boolean {
        val asset =
            AVURLAsset.URLAssetWithURL(
                NSURL.fileURLWithPath(sourcePath),
                options = mapOf<Any?, Any?>(AVURLAssetPreferPreciseDurationAndTimingKey to true),
            )
        val session =
            AVAssetExportSession.exportSessionWithAsset(asset, presetName = AVAssetExportPresetPassthrough)
                ?: run {
                    Logger.w(TAG, "No passthrough export session for $sourcePath")
                    return false
                }
        IosDownloadFiles.delete(destinationPath)
        session.outputURL = NSURL.fileURLWithPath(destinationPath)
        session.outputFileType = AVFileTypeAppleM4A
        session.setMetadata(
            buildList {
                add(metadataItem(AVMetadataCommonIdentifierTitle, title))
                if (!artist.isNullOrBlank()) add(metadataItem(AVMetadataCommonIdentifierArtist, artist))
                if (!album.isNullOrBlank()) add(metadataItem(AVMetadataCommonIdentifierAlbumName, album))
                if (artworkJpeg != null && artworkJpeg.isNotEmpty()) {
                    add(metadataItem(AVMetadataCommonIdentifierArtwork, artworkJpeg.toNSData()))
                }
            },
        )
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { session.cancelExport() }
            session.exportAsynchronouslyWithCompletionHandler { cont.resume(Unit) }
        }
        val ok = session.status == AVAssetExportSessionStatusCompleted && IosDownloadFiles.sizeOf(destinationPath) > 0L
        if (!ok) {
            Logger.w(TAG, "Export failed (status ${session.status}): ${session.error?.localizedDescription}")
            IosDownloadFiles.delete(destinationPath)
        }
        return ok
    }

    private fun metadataItem(
        identifier: String?,
        value: String,
    ): AVMutableMetadataItem {
        @Suppress("CAST_NEVER_SUCCEEDS") // Kotlin String bridges to NSString
        return metadataItem(identifier, value as NSString)
    }

    private fun metadataItem(
        identifier: String?,
        value: NSObject,
    ): AVMutableMetadataItem =
        AVMutableMetadataItem().apply {
            setIdentifier(identifier)
            setValue(value)
            setExtendedLanguageTag("und")
        }
}
