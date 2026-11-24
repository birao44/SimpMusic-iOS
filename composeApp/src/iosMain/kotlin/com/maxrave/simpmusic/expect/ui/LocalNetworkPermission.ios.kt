package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

// iOS asks for local network access by itself on the first connection (NSLocalNetworkUsageDescription).
@Composable
actual fun rememberLocalNetworkPermission(): LocalNetworkPermissionRequester = NoOpLocalNetworkPermission

private object NoOpLocalNetworkPermission : LocalNetworkPermissionRequester {
    override fun requestIfNeeded(url: String) = Unit
}
