package com.maxrave.simpmusic.viewModel

import com.eygraber.uri.Uri
import com.maxrave.common.DB_NAME
import com.maxrave.common.SETTINGS_FILENAME
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.CommonRepository
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.expect.exportFileToUser
import com.maxrave.simpmusic.expect.iosDocumentsPath
import com.maxrave.simpmusic.expect.pickedPathOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import multiplatform.network.cmptoast.ToastGravity
import multiplatform.network.cmptoast.showToast
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults
import platform.posix.exit
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.restore_reopen_ios
import simpmusic.composeapp.generated.resources.restore_success

private const val TAG = "BackupRestore"
private const val SETTINGS_ENTRY = "$SETTINGS_FILENAME.preferences_pb"

actual suspend fun calculateDataFraction(cacheRepository: CacheRepository): SettingsStorageSectionFraction? = null

/** The picked file is a plain path in the app's tmp folder (see filePickerResult on iOS). */
actual suspend fun readPickedFile(uri: Uri): ByteArray =
    withContext(Dispatchers.IO) {
        FileSystem.SYSTEM.read(pickedPathOf(uri.toString()).toPath()) { readByteArray() }
    }

private fun settingsFile(): Path = "${iosDocumentsPath()}/$SETTINGS_ENTRY".toPath()

/**
 * Same archive as Desktop: the DataStore settings file and the Room database, under the entry names
 * Android and Desktop use, so each platform restores the others' backups. Downloads are not
 * included (Desktop does not either), whatever "backup downloaded" says.
 *
 * [uri] is the tmp path fileSaverResult handed out. Once the zip is written the Files export sheet
 * opens so the user can choose where it goes; this returns when they saved it and throws when they
 * cancelled, so the caller's success toast only shows for a backup that really left the app.
 */
actual suspend fun backupNative(
    commonRepository: CommonRepository,
    uri: Uri,
    backupDownloaded: Boolean,
) {
    val target = pickedPathOf(uri.toString()).toPath()
    val fs = FileSystem.SYSTEM
    try {
        withContext(Dispatchers.IO) {
            commonRepository.databaseDaoCheckpoint()
            val database = commonRepository.getDatabasePath()?.toPath() ?: error("No database path")
            StoredZipWriter(fs.sink(target).buffer()).use { zip ->
                if (fs.exists(settingsFile())) zip.addFile(SETTINGS_ENTRY, settingsFile())
                zip.addFile(DB_NAME, database)
            }
        }
        check(exportFileToUser(target.toString())) { "Backup export was cancelled" }
    } finally {
        // The export sheet saved its own copy; the tmp one is not needed either way.
        fs.delete(target, mustExist = false)
    }
}

/**
 * Reads a backup made on any platform (okio's zip reader handles the STORED entries iOS writes
 * and the DEFLATE ones Android and Desktop write), checks it before touching anything, then
 * replaces the settings file and the database. Entries only Android uses (the ExoPlayer database
 * and download folder) are skipped, as on Desktop.
 *
 * The database is closed for the swap and DataStore keeps its old values in memory, so the app
 * has to start again — as on Android and Desktop, which both exit. iOS cannot relaunch an app, so
 * the user is told to reopen it before it exits.
 */
actual suspend fun restoreNative(
    commonRepository: CommonRepository,
    uri: Uri,
    getData: () -> Unit,
) {
    val fs = FileSystem.SYSTEM
    val archivePath = pickedPathOf(uri.toString()).toPath()
    withContext(Dispatchers.IO) {
        val zip = fs.openZip(archivePath)
        val settingsEntry = "/$SETTINGS_ENTRY".toPath()
        val databaseEntry = "/$DB_NAME".toPath()
        val hasSettings = zip.exists(settingsEntry)
        val hasDatabase = zip.exists(databaseEntry)
        check(hasSettings || hasDatabase) { "Not a SimpMusic backup: no settings or database entry" }

        if (hasSettings) {
            Logger.d(TAG, "Restoring $SETTINGS_ENTRY")
            copyAtomically(zip, settingsEntry, settingsFile())
        }
        if (hasDatabase) {
            Logger.d(TAG, "Restoring $DB_NAME")
            val database = commonRepository.getDatabasePath()?.toPath() ?: error("No database path")
            // Unpack beside the database first, so a broken archive never leaves half a database.
            val staged = "$database.restore".toPath()
            zip.source(databaseEntry).use { source -> fs.sink(staged).buffer().use { it.writeAll(source) } }
            commonRepository.databaseDaoCheckpoint()
            commonRepository.closeDatabase()
            fs.atomicMove(staged, database)
            // WAL files left from the closed database belong to the old one; SQLite would replay them.
            fs.delete("$database-wal".toPath(), mustExist = false)
            fs.delete("$database-shm".toPath(), mustExist = false)
        }
    }
    fs.delete(archivePath, mustExist = false)

    withContext(Dispatchers.Main) {
        showToast(getString(Res.string.restore_success), ToastGravity.Bottom)
        showToast(getString(Res.string.restore_reopen_ios), ToastGravity.Bottom)
        delay(2500)
        exit(0)
    }
}

private fun copyAtomically(
    zip: FileSystem,
    entry: Path,
    target: Path,
) {
    val fs = FileSystem.SYSTEM
    val staged = "$target.restore".toPath()
    zip.source(entry).use { source -> fs.sink(staged).buffer().use { it.writeAll(source) } }
    fs.atomicMove(staged, target)
}

actual fun getPackageName(): String = NSBundle.mainBundle.bundleIdentifier ?: ""

actual fun getFileDir(): String = iosDocumentsPath()

/**
 * iOS has a per-app language too: the app's own AppleLanguages default, which is what Settings ›
 * SimpMusic › Language writes. Setting it here makes NSLocale.preferredLanguages — and so Compose's
 * Locale.current and the string resources — follow the in-app choice. Text already on screen
 * switches as it recomposes; everything is in the new language from the next launch on.
 *
 * The app's codes are Java-style; iOS wants the ISO ones for Hebrew and Indonesian.
 */
actual fun changeLanguageNative(code: String) {
    val tag =
        when {
            code.startsWith("iw") -> "he" + code.removePrefix("iw")
            code.startsWith("in-") || code == "in" -> "id" + code.removePrefix("in")
            else -> code
        }
    NSUserDefaults.standardUserDefaults.setObject(listOf(tag), forKey = "AppleLanguages")
    Logger.d(TAG, "AppleLanguages set to $tag")
}
