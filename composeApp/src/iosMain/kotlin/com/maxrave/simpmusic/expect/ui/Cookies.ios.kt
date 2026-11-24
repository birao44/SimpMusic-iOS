package com.maxrave.simpmusic.expect.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.maxrave.logger.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSinceNow
import platform.UIKit.UIDevice
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKWebsiteDataTypeCookies
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile

private const val TAG = "PlatformWebView"

/*
 * WKHTTPCookieStore is asynchronous and main-thread only, while WebViewCookieManager.getCookie is
 * synchronous. So every finished page load first copies the WebView's cookies (HttpOnly ones
 * included) into [cookieSnapshot], and only then reports the page: by the time a login screen asks
 * for the cookies of the URL it was just handed, the snapshot already holds them.
 */
private data class SnapshotCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
)

@Volatile
private var cookieSnapshot: List<SnapshotCookie> = emptyList()

private fun onMain(block: () -> Unit) {
    if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
}

/** Copies every cookie of the shared WebKit store into [cookieSnapshot], then calls [done] on main. */
private fun refreshCookieSnapshot(done: () -> Unit) {
    onMain {
        WKWebsiteDataStore.defaultDataStore().httpCookieStore.getAllCookies { cookies ->
            cookieSnapshot =
                cookies
                    .orEmpty()
                    .filterIsInstance<NSHTTPCookie>()
                    .filter { cookie -> cookie.expiresDate?.let { it.timeIntervalSinceNow > 0 } ?: true }
                    .map { SnapshotCookie(it.name, it.value, it.domain, it.path) }
            onMain(done)
        }
    }
}

/** Wipes the WebKit cookie store, then calls [done] on main. */
private fun clearWebKitCookies(done: () -> Unit = {}) {
    cookieSnapshot = emptyList()
    onMain {
        WKWebsiteDataStore.defaultDataStore().removeDataOfTypes(
            setOf(WKWebsiteDataTypeCookies),
            modifiedSince = NSDate.dateWithTimeIntervalSince1970(0.0),
        ) {
            cookieSnapshot = emptyList()
            onMain(done)
        }
    }
}

// RFC 6265 domain and path matching, which is what Android's CookieManager.getCookie(url) applies.
private fun SnapshotCookie.matches(
    host: String,
    path: String,
): Boolean {
    val domainOk =
        if (domain.startsWith(".")) {
            val bare = domain.removePrefix(".")
            host == bare || host.endsWith(".$bare")
        } else {
            host == domain
        }
    if (!domainOk) return false
    val cookiePath = this.path.ifEmpty { "/" }
    return cookiePath == "/" ||
        path == cookiePath ||
        path.startsWith(if (cookiePath.endsWith("/")) cookiePath else "$cookiePath/")
}

actual fun createWebViewCookieManager(): WebViewCookieManager =
    object : WebViewCookieManager {
        override fun getCookie(url: String): String {
            val nsUrl = NSURL.URLWithString(url) ?: return ""
            val host = nsUrl.host?.lowercase() ?: return ""
            val path = nsUrl.path?.ifEmpty { "/" } ?: "/"
            return cookieSnapshot
                .filter { it.matches(host, path) }
                .joinToString("; ") { "${it.name}=${it.value}" }
        }

        override fun removeAllCookies() = clearWebKitCookies()
    }

/**
 * Safari's own user agent. WKWebView's default one leaves out `Version/… Safari/…`, which is how
 * Google spots an embedded browser and refuses the sign-in; the prefix (iPhone or iPad, OS version)
 * is still WebKit's.
 */
private fun safariApplicationName(): String {
    val version =
        UIDevice.currentDevice.systemVersion
            .split(".")
            .take(2)
            .joinToString(".")
            .ifEmpty { "17.0" }
    return "Version/$version Mobile/15E148 Safari/604.1"
}

@OptIn(ExperimentalForeignApi::class)
private fun createLoginWebView(): WKWebView {
    val configuration =
        WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.defaultDataStore()
            applicationNameForUserAgent = safariApplicationName()
        }
    return WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration)
}

/**
 * Navigation and UI delegate for the login web views. WKWebView holds both delegates weakly, so the
 * composable keeps this object in `remember` for as long as the web view is on screen.
 */
private class LoginWebViewDelegate(
    var onFinished: (WKWebView) -> Unit = {},
    var shouldCancel: (WKWebView, String) -> Boolean = { _, _ -> false },
) : NSObject(),
    WKNavigationDelegateProtocol,
    WKUIDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        didFinishNavigation: WKNavigation?,
    ) {
        onFinished(webView)
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString
        val cancel = url != null && decidePolicyForNavigationAction.targetFrame?.mainFrame == true && shouldCancel(webView, url)
        decisionHandler(
            if (cancel) {
                WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            } else {
                WKNavigationActionPolicy.WKNavigationActionPolicyAllow
            },
        )
    }

    // target="_blank" links and window.open would otherwise do nothing: open them in place.
    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        if (forNavigationAction.targetFrame == null) {
            webView.loadRequest(forNavigationAction.request)
        }
        return null
    }
}

@Composable
private fun LoginWebViewHost(
    webView: WKWebView,
    delegate: LoginWebViewDelegate,
) {
    DisposableEffect(webView, delegate) {
        webView.navigationDelegate = delegate
        webView.UIDelegate = delegate
        onDispose {
            webView.stopLoading()
            webView.navigationDelegate = null
            webView.UIDelegate = null
        }
    }
    UIKitView(
        factory = { webView },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
actual fun PlatformWebView(
    state: MutableState<WebViewState>,
    initUrl: String,
    aboveContent: @Composable (BoxScope.() -> Unit),
    clearCookies: Boolean,
    onPageFinished: (String) -> Unit,
) {
    val currentOnPageFinished by rememberUpdatedState(onPageFinished)
    val webView = remember { createLoginWebView() }
    val delegate =
        remember {
            LoginWebViewDelegate(
                onFinished = { view ->
                    state.value = WebViewState.Finished
                    // Report the page only once its cookies can be read synchronously.
                    view.URL?.absoluteString?.let { url -> refreshCookieSnapshot { currentOnPageFinished(url) } }
                },
            )
        }
    LaunchedEffect(webView) {
        val load = {
            state.value = WebViewState.Loading(0)
            NSURL.URLWithString(initUrl)?.let { webView.loadRequest(NSURLRequest.requestWithURL(it)) }
                ?: Logger.e(TAG, "Bad login url: $initUrl")
        }
        // Clearing is asynchronous: load only once the store is empty, or the old session survives.
        if (clearCookies) clearWebKitCookies { load() } else load()
    }
    Box(Modifier.fillMaxSize()) {
        LoginWebViewHost(webView, delegate)
        aboveContent()
    }
}

/** Disabled until an authorised OAuth implementation is ready. */
@Composable
actual fun DiscordWebView(
    state: MutableState<WebViewState>,
    aboveContent: @Composable (BoxScope.() -> Unit),
    onLoginDone: (token: String) -> Unit,
) {
    LaunchedEffect(Unit) {
        state.value = WebViewState.Finished
    }

    Box(Modifier.fillMaxSize()) {
        androidx.compose.material3.Text(
            "Discord login is unavailable in this beta."
        )
        aboveContent()
    }
}
