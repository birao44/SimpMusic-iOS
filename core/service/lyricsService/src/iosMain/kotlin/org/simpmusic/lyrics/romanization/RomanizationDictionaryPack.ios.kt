package org.simpmusic.lyrics.romanization

/**
 * iOS analyzes Japanese with the system's own CFStringTokenizer (see [PlatformRomanizer]'s ios
 * actual), so there is no dictionary to be missing. Reporting ready keeps the settings screen from
 * ever offering a download that iOS does not need.
 */
actual object RomanizationDictionaryPack {
    actual fun configure(directoryPath: String) {
        // No analyzer, no dictionary, nothing to configure.
    }

    actual fun isReady(): Boolean = true

    actual suspend fun download(): Result<Unit> = Result.success(Unit)
}
