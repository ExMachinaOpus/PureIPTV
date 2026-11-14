package app.pureiptv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import app.pureiptv.ui.components.TileColors
import app.pureiptv.ui.components.LocalTileBackground
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.BiasAlignment
import coil3.compose.AsyncImage
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.animation.EnterExitState
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import app.pureiptv.ui.components.restoringFocus
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.focusGroup
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureiptv.core.data.library.PlaybackRepository
import app.pureiptv.ui.R
import app.pureiptv.ui.components.FocusMemory
import app.pureiptv.ui.components.ListTile
import app.pureiptv.ui.components.PrimaryButton
import app.pureiptv.ui.components.Screen
import app.pureiptv.ui.components.SecondaryButton
import app.pureiptv.ui.components.isTv
import app.pureiptv.ui.components.languageName
import app.pureiptv.ui.components.messageRes
import app.pureiptv.ui.components.rememberFocus
import app.pureiptv.ui.live.Chip
import app.pureiptv.ui.player.PlayRequest
import coil3.compose.SubcomposeAsyncImage

@Composable
fun MovieDetailsScreen(movieId: Long, onPlay: (PlayRequest) -> Unit, onBack: () -> Unit) {
    val viewModel = hiltViewModel<MovieDetailsViewModel, MovieDetailsViewModel.Factory>(
        key = "movie$movieId",
        creationCallback = { it.create(movieId) },
    )
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(missing) { if (missing) onBack() }

    DetailsPage(ready = ui.ready, primary = playFocus) {
        val movie = ui.movie ?: return@DetailsPage
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Poster(movie.poster, width = if (isTv) 260 else 130)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(movie.title, style = MaterialTheme.typography.headlineLarge)
                val meta = listOfNotNull(
                    movie.year?.toString(),
                    languageName(movie.language),
                    movie.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                    ui.details?.durationSecs?.takeIf { it > 0 }?.let { "${it / 60} min" },
                    ui.details?.genre,
                )
                if (meta.isNotEmpty()) Meta(meta.joinToString("  ·  "))
                ui.details?.plot?.let { Plot(it) }
                ui.details?.cast?.takeIf { it.isNotBlank() }?.let { Meta(stringResource(R.string.details_cast, it)) }
                ActionStack {
                    val resume = ui.progress
                    PrimaryButton(
                        text = if (resume != null) stringResource(R.string.details_resume, minutes(resume.positionMs)) else stringResource(R.string.details_play),
                        onClick = { onPlay(PlayRequest.Movie(movie.id)) },
                        modifier = Modifier.fillMaxWidth().focusRequester(playFocus),
                    )
                    if (resume != null) {
                        SecondaryButton(
                            stringResource(R.string.details_start_over),
                            { onPlay(PlayRequest.Movie(movie.id, fromStart = true)) },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    SecondaryButton(
                        stringResource(if (ui.favorite) R.string.details_unfavorite else R.string.details_favorite),
                        viewModel::toggleFavorite,
                        Modifier.fillMaxWidth(),
                    )
                    if (resume != null) {
                        SecondaryButton(stringResource(R.string.home_remove_continue), {
                            // The button disappears: move focus first so it never drops to nowhere.
                            runCatching { playFocus.requestFocus() }
                            viewModel.forget()
                        }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesDetailsScreen(seriesId: Long, onPlay: (PlayRequest) -> Unit, onBack: () -> Unit) {
    val viewModel = hiltViewModel<SeriesDetailsViewModel, SeriesDetailsViewModel.Factory>(
        key = "series$seriesId",
        creationCallback = { it.create(seriesId) },
    )
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusMemory() }
    val series = ui.series
    val tv = isTv
    LaunchedEffect(missing) { if (missing) onBack() }
    if (!ui.ready || series == null) {
        DetailsPage(ready = false, primary = playFocus) {}
        return
    }

    // From the list: back from the player, focus returns to that episode. From Play: to Play.
    val playFromList: (Long) -> Unit = { id ->
        episodeFocus.markForRestore()
        onPlay(PlayRequest.Episode(id, false))
    }

    val info: @Composable ColumnScope.() -> Unit = {
        Text(series.title, style = MaterialTheme.typography.headlineLarge)
        val meta = listOfNotNull(series.year?.toString(), languageName(series.language), series.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }, series.genre)
        if (meta.isNotEmpty()) Meta(meta.joinToString("  ·  "))
        series.plot?.let { Plot(it, maxLines = 7) }
        ActionStack {
            val next = ui.upNext
            // Never disabled (TV focus rule): before episodes load it just does nothing.
            PrimaryButton(
                text = when {
                    next == null -> stringResource(R.string.details_play)
                    ui.upNextResume -> stringResource(R.string.details_resume_episode, "S${next.season} E${next.episode}")
                    else -> stringResource(R.string.details_play_episode, "S${next.season} E${next.episode}")
                },
                onClick = { next?.let { onPlay(PlayRequest.Episode(it.id, false)) } },
                modifier = Modifier.fillMaxWidth().focusRequester(playFocus),
            )
            SecondaryButton(
                stringResource(if (ui.favorite) R.string.details_unfavorite else R.string.details_favorite),
                viewModel::toggleFavorite,
                Modifier.fillMaxWidth(),
            )
            if (ui.progress.isNotEmpty()) {
                SecondaryButton(stringResource(R.string.home_remove_continue), {
                    // The button disappears: move focus first so it never drops to nowhere.
                    runCatching { playFocus.requestFocus() }
                    viewModel.forget()
                }, Modifier.fillMaxWidth())
            }
        }
    }

    val episodes: @Composable ColumnScope.() -> Unit = {
        when {
            ui.loading -> CircularProgressIndicator(Modifier.padding(24.dp))
            ui.error != null -> {
                Text(stringResource(ui.error!!.messageRes()), color = MaterialTheme.colorScheme.error)
                SecondaryButton(stringResource(R.string.player_retry), viewModel::retry)
            }
            ui.episodes.isEmpty() -> Meta(stringResource(R.string.details_no_episodes))
            else -> {
                if (ui.seasons.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusRestorer()) {
                        items(ui.seasons) { s ->
                            Chip(stringResource(R.string.details_season, s), s == ui.season) { viewModel.selectSeason(s) }
                        }
                    }
                }
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false).focusRestorer(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(ui.episodes, key = { it.id }) { e ->
                        val progress = viewModel.episodeKey(e)?.let { ui.progress[it] }
                        ListTile(
                            text = PlaybackRepository.episodeLabel(e, series.title),
                            supporting = e.plot?.takeIf { it.isNotBlank() },
                            trailing = when {
                                progress?.resumable == true -> "${(progress.fraction * 100).toInt()}%"
                                e.durationSec != null && e.durationSec!! > 0 -> "${e.durationSec!! / 60} min"
                                else -> null
                            },
                            onClick = { playFromList(e.id) },
                            modifier = Modifier.rememberFocus(episodeFocus, e.id),
                        )
                    }
                }
            }
        }
    }

    DetailsPage(ready = ui.ready, primary = playFocus, restorePending = { episodeFocus.restorePending }, backdrop = series.cover) {
        if (tv) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                // Sized to fit the screen (the artwork is the backdrop); the scroll is only a safety net.
                Column(Modifier.width(480.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    info()
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) { episodes() }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                info()
                episodes()
            }
        }
    }
}

/**
 * A details page appears in one go: nothing (a spinner if it takes a while) until [ready], then the
 * whole page, complete, with focus on [primary]. When a screen appears Android may focus something
 * by itself (the topmost button); that is sent to [primary] too, so focus never visits another
 * control first. [restorePending]: an item is getting its focus back (back from the player).
 */
@Composable
private fun DetailsPage(
    ready: Boolean,
    primary: FocusRequester,
    restorePending: () -> Boolean = { false },
    /** Artwork filling the screen behind the page (TV); tiles over it become see-through. */
    backdrop: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!ready) {
        Screen(contentAlignment = Alignment.Center) { DelayedSpinner() }
        return
    }
    // Focus given while the screen is still sliding in is lost when the transition ends (the old screen
    // goes away), which shows as a blink. So: no focus during the transition, then [primary], once.
    val transition = LocalNavAnimatedContentScope.current.transition
    val settled = !transition.isRunning && transition.currentState == EnterExitState.Visible
    // The window may not have focus yet (just launched, or another window was on top): wait for it.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    // Once only: later (a dialog closing, the window coming back) focus stays where the viewer put it.
    var given by remember { mutableStateOf(false) }
    LaunchedEffect(settled, windowFocused) {
        if (given || !settled || !windowFocused || restorePending()) return@LaunchedEffect
        // Every frame for up to ~2 s, in case the button is not placed yet.
        repeat(120) {
            withFrameNanos { }
            if (runCatching { primary.requestFocus() }.getOrDefault(false)) {
                given = true
                return@LaunchedEffect
            }
        }
    }
    val art = backdrop?.takeIf { it.isNotBlank() && isTv }
    Screen(
        Modifier
            .focusProperties {
                onEnter = {
                    when {
                        !settled -> cancelFocusChange()
                        !restoringFocus && !restorePending() -> primary.requestFocus()
                    }
                }
            }
            .focusGroup(),
        backdrop = art?.let { { Backdrop(it) } },
    ) {
        if (art == null) {
            content()
        } else {
            CompositionLocalProvider(LocalTileBackground provides TileColors.normal.copy(alpha = 0.8f)) { content() }
        }
    }
}

/**
 * The cover, edge to edge on the right, fading into the background towards the left where the text
 * is, so title and description stay readable whatever the picture.
 */
@Composable
private fun BoxScope.Backdrop(url: String) {
    val bg = MaterialTheme.colorScheme.background
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        // Portrait posters: keep the upper part (faces, title) rather than the middle.
        alignment = BiasAlignment(0f, -0.4f),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.72f),
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to bg, 0.3f to bg, 0.55f to bg.copy(alpha = 0.6f), 1f to bg.copy(alpha = 0.2f)),
        ),
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.65f to Color.Transparent, 1f to bg.copy(alpha = 0.75f))))
}

/** Only for a wait you would notice (episodes from the network); a quick one shows nothing. */
@Composable
private fun DelayedSpinner() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(400)
        visible = true
    }
    if (visible) CircularProgressIndicator()
}

/** The page's buttons: one under the other, all as wide as the widest, the main action first. */
@Composable
private fun ActionStack(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(top = 8.dp).width(IntrinsicSize.Max),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun Poster(url: String?, width: Int = 260) {
    if (url.isNullOrBlank()) return
    Box(Modifier.size(width.dp, (width * 1.5f).dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF262A35))) {
        SubcomposeAsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun Meta(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Plot(text: String, maxLines: Int = 6) {
    Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

private fun minutes(ms: Long): String {
    val total = ms / 60_000
    return if (total >= 60) "%d:%02d".format(total / 60, total % 60) else "$total min"
}
