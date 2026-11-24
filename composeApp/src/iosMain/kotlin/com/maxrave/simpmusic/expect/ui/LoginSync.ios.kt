package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

// iOS takes no part in login sync, so the dialog closes itself.
@Composable
actual fun LoginSyncDialog(onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { onDismiss() }
}
