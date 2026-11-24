package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.maxrave.simpmusic.expect.requestPhotoAddAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Add-only Photos access: answers at once when it was already decided, otherwise shows the system
 * prompt. [onResult] runs on the main thread either way.
 */
@Composable
actual fun rememberSaveImagePermission(onResult: (granted: Boolean) -> Unit): SaveImagePermissionRequester {
    val currentOnResult by rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    return remember {
        object : SaveImagePermissionRequester {
            override fun requestIfNeeded() {
                scope.launch(Dispatchers.Main) { currentOnResult(requestPhotoAddAccess()) }
            }
        }
    }
}
