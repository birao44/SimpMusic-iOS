package com.maxrave.data.di.loader

import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.data.download.IosCacheRepository
import com.maxrave.data.download.IosDownloadHandler
import com.maxrave.domain.data.model.librarysync.LibrarySyncState
import com.maxrave.domain.data.model.librarysync.PendingSettings
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.LibrarySyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.maxrave.logger.Logger
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

// The service scope is what createMediaServiceHandler hands to IosMediaPlayerHandlerImpl (and through
// it to AvPlayerAdapter, which derives its own serial context from it). Unlike Desktop, no
// MediaPlayerInterface is bound: the iOS handler builds its AvPlayerAdapter itself from
// NativePlayerRegistry.engine and makes no Koin lookups. Shared code still resolves the cache
// repository and the download handler: file-based, see com.maxrave.data.download.
actual fun loadMediaService() {
    loadKoinModules(
        module {
            single<CoroutineScope>(qualifier = named(SERVICE_SCOPE)) {
                // One writer at a time, like Desktop's single player thread.
                CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob())
            }
            single<CacheRepository> { IosCacheRepository }
            single<DownloadHandler> {
                IosDownloadHandler(
                    dataStoreManager = get(),
                    streamRepository = get(),
                    songRepository = get(),
                )
            }
        },
    )
    // Built now, not on first injection: its init resumes interrupted downloads and reconciles the
    // song rows with the files, which must happen even if no download screen is opened. Off the
    // calling thread, after this returns; the database and repositories are already loaded.
    CoroutineScope(Dispatchers.Default).launch {
        runCatching { KoinPlatform.getKoin().get<DownloadHandler>() }
            .onFailure { Logger.e("Loader", "Download handler failed to start: ${it.message}") }
    }
}

// iOS takes no part in login or library sync. Shared screens (Settings) still inject
// LibrarySyncRepository, so bind one that is never paired and does nothing.
actual fun loadLoginSyncModule() {
    loadKoinModules(
        module {
            single<LibrarySyncRepository> { UnsupportedLibrarySyncRepository }
        },
    )
}

private object UnsupportedLibrarySyncRepository : LibrarySyncRepository {
    override val state: StateFlow<LibrarySyncState> = MutableStateFlow(LibrarySyncState())
    override val pendingSettings: StateFlow<PendingSettings?> = MutableStateFlow(null)

    override suspend fun syncNow() = Unit

    override suspend fun unpairAll() = Unit

    override suspend fun applyPendingSettings() = Unit

    override suspend fun dismissPendingSettings() = Unit
}
