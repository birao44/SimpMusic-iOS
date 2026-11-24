package com.maxrave.simpmusic.expect

import io.ktor.client.HttpClient

/**
 * A plain Ktor client for UI-side fetches (palette extraction, share cards). Android and Desktop
 * use CIO as before; iOS uses Darwin, because CIO cannot do TLS on Kotlin/Native.
 */
expect fun platformHttpClient(): HttpClient
