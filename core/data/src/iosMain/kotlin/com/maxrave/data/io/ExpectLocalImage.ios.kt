package com.maxrave.data.io

import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSURL

/**
 * The iOS photo picker hands back a plain path to a JPEG it wrote into the app's tmp folder; a
 * `file://` url is accepted too.
 */
actual suspend fun readLocalImageBytes(uri: String): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            val path = if (uri.startsWith("file:")) NSURL.URLWithString(uri)?.path ?: uri.removePrefix("file://") else uri
            val file = path.toPath()
            if (FileSystem.SYSTEM.metadataOrNull(file)?.isRegularFile == true) {
                FileSystem.SYSTEM.read(file) { readByteArray() }
            } else {
                null
            }
        }.onFailure {
            Logger.w("LocalImage", "Could not read $uri: ${it.message}")
        }.getOrNull()
    }
