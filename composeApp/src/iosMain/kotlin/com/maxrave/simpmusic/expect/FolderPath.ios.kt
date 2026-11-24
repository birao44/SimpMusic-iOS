package com.maxrave.simpmusic.expect

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

// The app's Documents folder: the one place an iOS app writes files the user can reach (Files app,
// once UIFileSharingEnabled is set).
actual fun getDownloadFolderPath(): String = iosDocumentsPath()

internal fun iosDocumentsPath(): String =
    NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: ""
