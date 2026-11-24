package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.model.spotifyimport.MatchCandidate
import com.maxrave.domain.data.model.spotifyimport.SpotifyImportState
import com.maxrave.domain.data.model.spotifyimport.SpotifyPlaylistInfo
import com.maxrave.domain.data.model.spotifyimport.UnmatchedTrack
import com.maxrave.domain.repository.SpotifyImportRepository
import com.maxrave.logger.LogLevel
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.spotify_import_added
import simpmusic.composeapp.generated.resources.spotify_import_invalid_link

/**
 * The Spotify import screen: choosing playlists here, the import itself in [SpotifyImportRepository]
 * (application-scoped), so this view model only reflects [importState] and can die with the screen.
 */
class SpotifyImportViewModel(
    private val repository: SpotifyImportRepository,
) : BaseViewModel() {
    data class PickerState(
        val loading: Boolean = false,
        val error: String? = null,
        /** Library playlists, with playlists added from links on top. */
        val playlists: List<SpotifyPlaylistInfo> = emptyList(),
        val selected: Set<String> = emptySet(),
        val resolvingLink: Boolean = false,
    )

    data class SearchState(
        val target: UnmatchedTrack,
        val query: String,
        val searching: Boolean = false,
        val results: List<MatchCandidate> = emptyList(),
        val error: String? = null,
    )

    val importState: StateFlow<SpotifyImportState> = repository.state

    val loggedIn: StateFlow<Boolean> =
        repository.isLoggedIn.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _picker = MutableStateFlow(PickerState())
    val picker: StateFlow<PickerState> = _picker.asStateFlow()

    private val _search = MutableStateFlow<SearchState?>(null)
    val search: StateFlow<SearchState?> = _search.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Load (and reload after a login) whenever the session appears.
        viewModelScope.launch {
            repository.isLoggedIn.distinctUntilChanged().collect { loggedIn ->
                if (loggedIn) loadLibrary() else _picker.update { it.copy(playlists = it.playlists.filterNot { p -> p.isLikedSongs }) }
            }
        }
    }

    fun loadLibrary() {
        viewModelScope.launch {
            _picker.update { it.copy(loading = true, error = null) }
            repository
                .getLibraryPlaylists()
                .onSuccess { library ->
                    _picker.update { state ->
                        val fromLinks = state.playlists.filter { p -> library.none { it.uri == p.uri } && !p.isLikedSongs }
                        state.copy(loading = false, playlists = fromLinks + library)
                    }
                }.onFailure { error ->
                    log("library: ${error.message}", LogLevel.WARN)
                    _picker.update { it.copy(loading = false, error = error.message) }
                }
        }
    }

    fun addLink(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _picker.update { it.copy(resolvingLink = true) }
            repository
                .resolvePlaylistLink(text)
                .onSuccess { info ->
                    _picker.update { state ->
                        state.copy(
                            resolvingLink = false,
                            playlists = listOf(info) + state.playlists.filterNot { it.uri == info.uri },
                            selected = state.selected + info.uri,
                        )
                    }
                }.onFailure { error ->
                    _picker.update { it.copy(resolvingLink = false) }
                    makeToast(if (error is IllegalArgumentException) getString(Res.string.spotify_import_invalid_link) else error.message)
                }
        }
    }

    fun toggle(uri: String) {
        _picker.update { it.copy(selected = if (uri in it.selected) it.selected - uri else it.selected + uri) }
    }

    fun toggleAll() {
        _picker.update { state ->
            val all = state.playlists.map { it.uri }.toSet()
            state.copy(selected = if (state.selected.containsAll(all)) emptySet() else all)
        }
    }

    fun startImport() {
        val state = _picker.value
        val chosen = state.playlists.filter { it.uri in state.selected }
        if (repository.startImport(chosen)) _picker.update { it.copy(selected = emptySet()) }
    }

    fun cancelImport() = repository.cancel()

    fun dismissResult() = repository.dismissResult()

    // Manual search for a track the import could not match.

    fun openSearch(target: UnmatchedTrack) {
        val query = listOf(target.track.title, target.track.primaryArtist).filter { it.isNotBlank() }.joinToString(" ")
        _search.value = SearchState(target = target, query = query)
        runSearch(query)
    }

    fun closeSearch() {
        searchJob?.cancel()
        _search.value = null
    }

    fun runSearch(query: String) {
        val current = _search.value ?: return
        searchJob?.cancel()
        _search.value = current.copy(query = query, searching = true, error = null)
        searchJob =
            viewModelScope.launch {
                repository
                    .searchCandidates(query, current.target)
                    .onSuccess { results -> _search.update { it?.copy(searching = false, results = results) } }
                    .onFailure { error -> _search.update { it?.copy(searching = false, error = error.message) } }
            }
    }

    fun pick(
        target: UnmatchedTrack,
        candidate: MatchCandidate,
    ) {
        viewModelScope.launch {
            repository
                .resolveUnmatched(target, candidate)
                .onSuccess {
                    if (_search.value?.target === target) closeSearch()
                    makeToast(getString(Res.string.spotify_import_added))
                }.onFailure { makeToast(it.message) }
        }
    }
}
