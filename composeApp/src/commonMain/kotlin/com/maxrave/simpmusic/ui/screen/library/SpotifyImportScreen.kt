package com.maxrave.simpmusic.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.domain.data.model.spotifyimport.MatchCandidate
import com.maxrave.domain.data.model.spotifyimport.PlaylistImportResult
import com.maxrave.domain.data.model.spotifyimport.SpotifyImportPhase
import com.maxrave.domain.data.model.spotifyimport.SpotifyImportProgress
import com.maxrave.domain.data.model.spotifyimport.SpotifyImportState
import com.maxrave.domain.data.model.spotifyimport.SpotifyPlaylistInfo
import com.maxrave.domain.data.model.spotifyimport.UnmatchedTrack
import com.maxrave.simpmusic.expect.copyToClipboard
import com.maxrave.simpmusic.expect.ui.layerBackdrop
import com.maxrave.simpmusic.expect.ui.rememberBackdrop
import com.maxrave.simpmusic.ui.component.EndOfModalBottomSheet
import com.maxrave.simpmusic.ui.component.LiquidGlassIconButton
import com.maxrave.simpmusic.ui.component.rememberHolderPainter
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.Check
import com.maxrave.simpmusic.ui.icon.ContentCopy
import com.maxrave.simpmusic.ui.icon.Search
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.list.LocalPlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.SpotifyLoginDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SpotifyImportViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.log_in_to_spotify
import simpmusic.composeapp.generated.resources.retry
import simpmusic.composeapp.generated.resources.spotify_import_add_guess
import simpmusic.composeapp.generated.resources.spotify_import_add_link
import simpmusic.composeapp.generated.resources.spotify_import_background_note
import simpmusic.composeapp.generated.resources.spotify_import_best_guess
import simpmusic.composeapp.generated.resources.spotify_import_by
import simpmusic.composeapp.generated.resources.spotify_import_cancelled
import simpmusic.composeapp.generated.resources.spotify_import_copied
import simpmusic.composeapp.generated.resources.spotify_import_counters
import simpmusic.composeapp.generated.resources.spotify_import_description
import simpmusic.composeapp.generated.resources.spotify_import_done
import simpmusic.composeapp.generated.resources.spotify_import_fetching
import simpmusic.composeapp.generated.resources.spotify_import_find
import simpmusic.composeapp.generated.resources.spotify_import_link_hint
import simpmusic.composeapp.generated.resources.spotify_import_login_needed
import simpmusic.composeapp.generated.resources.spotify_import_matching
import simpmusic.composeapp.generated.resources.spotify_import_new_import
import simpmusic.composeapp.generated.resources.spotify_import_no_playlists
import simpmusic.composeapp.generated.resources.spotify_import_no_results
import simpmusic.composeapp.generated.resources.spotify_import_not_found
import simpmusic.composeapp.generated.resources.spotify_import_open_playlist
import simpmusic.composeapp.generated.resources.spotify_import_playlist_progress
import simpmusic.composeapp.generated.resources.spotify_import_result_line
import simpmusic.composeapp.generated.resources.spotify_import_result_updated
import simpmusic.composeapp.generated.resources.spotify_import_saving
import simpmusic.composeapp.generated.resources.spotify_import_search_title
import simpmusic.composeapp.generated.resources.spotify_import_select_all
import simpmusic.composeapp.generated.resources.spotify_import_skipped_note
import simpmusic.composeapp.generated.resources.spotify_import_start
import simpmusic.composeapp.generated.resources.spotify_import_title
import simpmusic.composeapp.generated.resources.spotify_import_tracks
import simpmusic.composeapp.generated.resources.spotify_import_your_playlists

/** A phone-width column, centred in a wide window, like the Listen Together pages. */
private const val CONTENT_MAX_WIDTH_DP = 600

/**
 * Library → + → Import from Spotify (also Settings → Spotify).
 *
 * Three faces over one screen: pick playlists, watch the import, read the summary. The import lives
 * in the application scope ([com.maxrave.domain.repository.SpotifyImportRepository]), so leaving
 * and coming back shows it still running, or its summary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyImportScreen(
    innerPadding: PaddingValues,
    navController: NavController,
    viewModel: SpotifyImportViewModel = koinViewModel(),
) {
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val loggedIn by viewModel.loggedIn.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val backdrop = rememberBackdrop(MaterialTheme.colorScheme.background)

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val contentWidth = minOf(maxWidth.value, CONTENT_MAX_WIDTH_DP.toFloat()).dp
        Box(Modifier.matchParentSize().layerBackdrop(backdrop)) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.background))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(48.dp))
                    Text(
                        text = stringResource(Res.string.spotify_import_title),
                        style = typo().titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(48.dp))
                }
            }
            when (val state = importState) {
                is SpotifyImportState.Running ->
                    item(key = "progress") {
                        Box(Modifier.width(contentWidth).padding(24.dp)) {
                            ProgressContent(state.progress, onCancel = viewModel::cancelImport)
                        }
                    }

                is SpotifyImportState.Finished -> {
                    item(key = "summary") {
                        Box(Modifier.width(contentWidth).padding(horizontal = 24.dp, vertical = 12.dp)) {
                            SummaryHeader(state, onDone = viewModel::dismissResult)
                        }
                    }
                    state.results.forEach { result ->
                        item(key = "result-${result.spotifyUri}") {
                            Box(Modifier.width(contentWidth).padding(horizontal = 24.dp, vertical = 8.dp)) {
                                ResultCard(result) { id -> navController.navigate(LocalPlaylistDestination(id)) }
                            }
                        }
                        // Keyed by identity: the same track can be unmatched twice in one playlist.
                        itemsIndexed(result.unmatched, key = { i, it -> "u-${result.spotifyUri}-$i-${it.track.title}" }) { _, unmatched ->
                            Box(Modifier.width(contentWidth).padding(horizontal = 24.dp, vertical = 4.dp)) {
                                UnmatchedRow(
                                    unmatched = unmatched,
                                    onFind = { viewModel.openSearch(unmatched) },
                                    onAddGuess = { guess -> viewModel.pick(unmatched, guess) },
                                    onCopy = { copyToClipboard("Spotify track", unmatched.track.display) },
                                )
                            }
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(96.dp)) }
                }

                SpotifyImportState.Idle -> {
                    item(key = "picker-top") {
                        Column(
                            Modifier.width(contentWidth).padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(
                                stringResource(Res.string.spotify_import_description),
                                style = typo().bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LinkField(resolving = picker.resolvingLink, onAdd = viewModel::addLink)
                            if (!loggedIn) {
                                LoginCard { navController.navigate(SpotifyLoginDestination) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(Res.string.spotify_import_your_playlists),
                                    style = typo().titleSmall,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.weight(1f),
                                )
                                if (picker.playlists.isNotEmpty()) {
                                    SmallAction(stringResource(Res.string.spotify_import_select_all), onClick = viewModel::toggleAll)
                                }
                            }
                            when {
                                picker.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                picker.error != null && loggedIn ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(picker.error.orEmpty(), style = typo().bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                                        SmallAction(stringResource(Res.string.retry), onClick = viewModel::loadLibrary)
                                    }
                                picker.playlists.isEmpty() && loggedIn ->
                                    Text(stringResource(Res.string.spotify_import_no_playlists), style = typo().bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(picker.playlists, key = { "p-${it.uri}" }) { playlist ->
                        Box(Modifier.width(contentWidth).padding(horizontal = 24.dp, vertical = 4.dp)) {
                            PlaylistRow(playlist, selected = playlist.uri in picker.selected) { viewModel.toggle(playlist.uri) }
                        }
                    }
                    item(key = "picker-end") { Spacer(Modifier.height(120.dp)) }
                }
            }
        }

        // Import button, floating above the list while something is selected.
        if (importState is SpotifyImportState.Idle && picker.selected.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = innerPadding.calculateBottomPadding() + 16.dp)
                    .width(contentWidth)
                    .padding(horizontal = 24.dp),
            ) {
                PrimaryButton(stringResource(Res.string.spotify_import_start, picker.selected.size), onClick = viewModel::startImport)
            }
        }

        LiquidGlassIconButton(
            backdrop = backdrop,
            imageVector = SimpIcons.ArrowBackIosNew,
            tint = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(24.dp),
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 12.dp, top = 8.dp)
                    .size(48.dp),
        ) {
            navController.navigateUp()
        }
    }

    search?.let { current ->
        ModalBottomSheet(
            onDismissRequest = viewModel::closeSearch,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color.Transparent,
            contentColor = Color.Transparent,
            dragHandle = null,
            scrimColor = Color.Black.copy(alpha = .5f),
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                colors = CardDefaults.cardColors().copy(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(Res.string.spotify_import_search_title), style = typo().titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(current.target.track.display, style = typo().bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var query by remember(current.target) { mutableStateOf(current.query) }
                    InputField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "",
                        onSubmit = { viewModel.runSearch(query) },
                        trailing = { SmallAction(stringResource(Res.string.spotify_import_find)) { viewModel.runSearch(query) } },
                    )
                    when {
                        current.searching -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        current.error != null -> Text(current.error.orEmpty(), style = typo().bodySmall, color = MaterialTheme.colorScheme.error)
                        current.results.isEmpty() ->
                            Text(stringResource(Res.string.spotify_import_no_results), style = typo().bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else ->
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                                items(current.results, key = { it.videoId }) { candidate ->
                                    CandidateRow(candidate) { viewModel.pick(current.target, candidate) }
                                }
                            }
                    }
                    EndOfModalBottomSheet()
                }
            }
        }
    }
}

@Composable
private fun ProgressContent(
    progress: SpotifyImportProgress,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(Res.string.spotify_import_playlist_progress, progress.playlistIndex, progress.playlistCount),
            style = typo().labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(progress.playlistName, style = typo().titleMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val label =
            when (progress.phase) {
                SpotifyImportPhase.FETCHING -> stringResource(Res.string.spotify_import_fetching, progress.processed, progress.total)
                SpotifyImportPhase.MATCHING -> stringResource(Res.string.spotify_import_matching, progress.processed, progress.total)
                SpotifyImportPhase.SAVING -> stringResource(Res.string.spotify_import_saving)
            }
        Text(label, style = typo().bodyMedium, color = MaterialTheme.colorScheme.onBackground)
        if (progress.total > 0 && progress.phase != SpotifyImportPhase.SAVING) {
            LinearProgressIndicator(
                progress = { (progress.processed.toFloat() / progress.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            stringResource(Res.string.spotify_import_counters, progress.matched, progress.unmatched, progress.skipped),
            style = typo().bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(Res.string.spotify_import_background_note), style = typo().bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SmallAction(stringResource(Res.string.cancel), onClick = onCancel)
    }
}

@Composable
private fun SummaryHeader(
    state: SpotifyImportState.Finished,
    onDone: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(if (state.cancelled) Res.string.spotify_import_cancelled else Res.string.spotify_import_done),
            style = typo().titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stringResource(Res.string.spotify_import_counters, state.matched, state.unmatched, state.skipped),
            style = typo().bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SmallAction(stringResource(Res.string.spotify_import_new_import), onClick = onDone)
    }
}

@Composable
private fun ResultCard(
    result: PlaylistImportResult,
    onOpen: (Long) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f), RoundedCornerShape(16.dp))
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                result.name,
                style = typo().titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            result.localPlaylistId?.let { id -> SmallAction(stringResource(Res.string.spotify_import_open_playlist)) { onOpen(id) } }
        }
        val error = result.error?.takeIf { it != "cancelled" }
        if (error != null) {
            Text(error, style = typo().bodySmall, color = MaterialTheme.colorScheme.error)
        } else {
            val tracks = result.totalTracks - result.skipped
            Text(
                if (result.updatedExisting) {
                    stringResource(Res.string.spotify_import_result_updated, result.added)
                } else {
                    stringResource(Res.string.spotify_import_result_line, result.matched, tracks)
                },
                style = typo().bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (result.skipped > 0) {
                Text(stringResource(Res.string.spotify_import_skipped_note, result.skipped), style = typo().bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (result.unmatched.isNotEmpty()) {
                Text(
                    "${stringResource(Res.string.spotify_import_not_found)}: ${result.unmatched.size}",
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UnmatchedRow(
    unmatched: UnmatchedTrack,
    onFind: () -> Unit,
    onAddGuess: (MatchCandidate) -> Unit,
    onCopy: () -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                unmatched.track.display,
                style = typo().bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                SimpIcons.ContentCopy,
                contentDescription = stringResource(Res.string.spotify_import_copied),
                tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.size(32.dp).clip(CircleShape).clickable {
                        onCopy()
                        copied = true
                    }.padding(7.dp),
            )
            Icon(
                SimpIcons.Search,
                contentDescription = stringResource(Res.string.spotify_import_find),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onFind).padding(6.dp),
            )
        }
        unmatched.bestGuess?.let { guess ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(Res.string.spotify_import_best_guess, guess.display),
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unmatched.localPlaylistId != null) {
                    SmallAction(stringResource(Res.string.spotify_import_add_guess)) { onAddGuess(guess) }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: MatchCandidate,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(candidate.thumbnailUrl, 44)
        Column(Modifier.weight(1f)) {
            Text(candidate.title, style = typo().bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val duration = candidate.durationSeconds?.takeIf { it > 0 }?.let { " · ${it / 60}:${(it % 60).toString().padStart(2, '0')}" }.orEmpty()
            Text(
                candidate.artists.joinToString(", ") + duration,
                style = typo().bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: SpotifyPlaylistInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)) else Modifier)
                .border(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.40f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f),
                    RoundedCornerShape(16.dp),
                ).clickable(onClick = onClick)
                .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Cover(playlist.imageUrl, 52)
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = typo().bodyMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts =
                listOfNotNull(
                    playlist.owner?.let { stringResource(Res.string.spotify_import_by, it) },
                    playlist.trackCount?.let { stringResource(Res.string.spotify_import_tracks, it) },
                )
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString(" · "),
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) {
                            Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                        } else {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.26f), CircleShape)
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(SimpIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun Cover(
    url: String?,
    sizeDp: Int,
) {
    AsyncImage(
        model =
            ImageRequest
                .Builder(LocalPlatformContext.current)
                .data(url)
                .diskCachePolicy(CachePolicy.ENABLED)
                .diskCacheKey(url)
                .crossfade(300)
                .build(),
        contentDescription = null,
        placeholder = rememberHolderPainter(square = true),
        error = rememberHolderPainter(square = true),
        modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun LinkField(
    resolving: Boolean,
    onAdd: (String) -> Unit,
) {
    var link by remember { mutableStateOf("") }
    InputField(
        value = link,
        onValueChange = { link = it },
        placeholder = stringResource(Res.string.spotify_import_link_hint),
        onSubmit = {
            onAdd(link)
            link = ""
        },
        trailing = {
            if (resolving) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else if (link.isNotBlank()) {
                SmallAction(stringResource(Res.string.spotify_import_add_link)) {
                    onAdd(link)
                    link = ""
                }
            }
        },
    )
}

@Composable
private fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = typo().bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f), RoundedCornerShape(14.dp)),
        decorationBox = { inner ->
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 15.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = typo().bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    inner()
                }
                trailing()
            }
        },
    )
}

@Composable
private fun LoginCard(onLogin: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(Res.string.spotify_import_login_needed), style = typo().bodySmall, color = MaterialTheme.colorScheme.onBackground)
        PrimaryButton(stringResource(Res.string.log_in_to_spotify), onClick = onLogin)
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = typo().labelLarge, color = MaterialTheme.colorScheme.onPrimary)
    }
}

/** The outlined pill the Listen Together settings use for secondary actions. */
@Composable
private fun SmallAction(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f), RoundedCornerShape(15.dp))
                .clickable { onClick() }
                .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = typo().labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
