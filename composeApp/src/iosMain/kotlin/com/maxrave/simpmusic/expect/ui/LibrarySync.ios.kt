package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

// iOS takes no part in library sync (see :data's loadLoginSyncModule), so the dialog closes itself.
@Composable
actual fun LibrarySyncDialog(onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { onDismiss() }
}

// Nothing to restart: no synced settings are ever written on iOS.
actual fun restartAfterSyncedSettings() {
}
