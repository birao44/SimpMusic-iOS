package com.maxrave.simpmusic

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.preferEndFirstIntrinsicSize
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.maxrave.common.AppIdentity
import com.maxrave.data.di.loader.loadAllModules
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.di.viewModelModule
import com.maxrave.simpmusic.expect.iosDocumentsPath
import com.maxrave.simpmusic.expect.platformHttpClient
import com.maxrave.simpmusic.expect.startNetworkPathMonitor
import com.maxrave.simpmusic.utils.ComposeResUtils
import com.maxrave.simpmusic.utils.NetworkFirstInterceptor
import com.maxrave.simpmusic.utils.VersionManager
import kotlinx.coroutines.runBlocking
import multiplatform.network.cmptoast.showToast
import okio.FileSystem
import org.jetbrains.compose.resources.getString
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import org.simpmusic.discord.configDiscord
import org.simpmusic.lastfm.configLastfm
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.UIKit.UIDevice
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.explicit_content_blocked
import simpmusic.composeapp.generated.resources.time_out_check_internet_connection_or_change_piped_instance_in_settings

private const val TAG = "IosAppStartup"

/**
 * The iOS counterpart of Desktop's runDesktopApp() startup and Android's SimpMusicApplication:
 * everything App() expects to exist before its first composition. Safe to call more than once —
 * a scene can be torn down and recreated — and only the first call does anything.
 */
internal fun startIosApp() {
    // Koin is the one thing a second start would break, and it knows whether it is running.
    if (KoinPlatform.getKoinOrNull() != null) return

    // First, so the rest of startup reaches the App log too (Settings → Developer option).
    Logger.enableFileLogging("${iosDocumentsPath()}/logs")
    configLastfm(BuildKonfig.lastfmApiKey, BuildKonfig.lastfmSecret)
    // Before Koin: DiscordRepository is created at start and reads which login this build uses.
    configDiscord(BuildKonfig.discordClientId)

    startKoin {
        loadAllModules(
            AppIdentity(
                applicationId = "com.maxrave.simpmusic",
                versionName = BuildKonfig.versionName,
                platform = "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}",
            ),
        )
        loadKoinModules(viewModelModule)
    }

    VersionManager.initialize()
    setUpImageLoader()
    startNetworkPathMonitor()

    val mediaPlayerHandler = KoinPlatform.getKoin().get<MediaPlayerHandler>()
    mediaPlayerHandler.showToast = { type ->
        val message =
            when (type) {
                ToastType.ExplicitContent -> {
                    runBlocking { getString(Res.string.explicit_content_blocked) }
                }

                is ToastType.PlayerError -> {
                    runBlocking { getString(Res.string.time_out_check_internet_connection_or_change_piped_instance_in_settings, type.error) }
                }

                is ToastType.SponsorBlockSkip -> {
                    runBlocking { ComposeResUtils.getResString(ComposeResUtils.StringType.SPONSOR_BLOCK_SKIP, type.category) }
                }
            }
        // The player calls this from its own thread, and the toast adds a UIView: UIKit throws
        // (and the app aborts) for a view added off the main thread.
        dispatch_async(dispatch_get_main_queue()) { showToast(message) }
    }
    mediaPlayerHandler.pushPlayerError = { error ->
        Logger.e(TAG, "Player Error: ${error.message}, code: ${error.errorCode}, code name: ${error.errorCodeName}")
    }
}

/** Desktop's image loader, with Ktor (Darwin engine) in place of OkHttp, which has no iOS build. */
@OptIn(ExperimentalCoilApi::class)
private fun setUpImageLoader() {
    SingletonImageLoader.setSafe { context: PlatformContext ->
        lateinit var imageLoader: ImageLoader
        imageLoader =
            ImageLoader
                .Builder(context)
                .components {
                    add(NetworkFirstInterceptor { imageLoader })
                    // Name the engine: commonMain also ships CIO, which has no TLS on native, and a bare
                    // HttpClient() may pick it — every https image then fails.
                    add(KtorNetworkFetcherFactory(httpClient = { platformHttpClient() }))
                }.memoryCache {
                    // Coil's default on native is a share of a guessed total, not of this device's RAM.
                    // Long sessions of scrolling artwork otherwise grow until iOS kills the app.
                    MemoryCache
                        .Builder()
                        .maxSizeBytes(imageMemoryCacheBytes())
                        .build()
                }.diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .diskCache(
                    DiskCache
                        .Builder()
                        .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "image_cache")
                        .maxSizeBytes(512L * 1024 * 1024)
                        .build(),
                ).crossfade(true)
                // CrossfadePainter otherwise sizes itself as max(placeholder, image) once and keeps it,
                // so a square holder over a wide banner leaves the banner zoomed after the fade.
                .preferEndFirstIntrinsicSize(true)
                .build()
        imageLoader
    }
    // iOS's low-memory signal: drop decoded artwork (it is all on disk) before the system has to
    // terminate the app. The token is kept so the observer lives as long as the process.
    memoryWarningObserver =
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidReceiveMemoryWarningNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            Logger.w(TAG, "Memory warning: clearing the image memory cache")
            SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.clear()
        }
}

private var memoryWarningObserver: Any? = null

/** About 15% of the device's RAM, kept between 64 and 384 MB. */
private fun imageMemoryCacheBytes(): Long {
    val physical = NSProcessInfo.processInfo.physicalMemory.toLong()
    return (physical * 15 / 100).coerceIn(64L * 1024 * 1024, 384L * 1024 * 1024)
}
