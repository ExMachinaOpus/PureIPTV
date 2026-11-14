package app.pureiptv.ui.home

import app.pureiptv.ui.components.PureDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pureiptv.core.data.db.ChannelRow
import app.pureiptv.core.data.db.PosterRow
import app.pureiptv.core.data.library.ContinueItem
import app.pureiptv.core.data.library.HomeFeed
import app.pureiptv.core.data.library.HomeRepository
import app.pureiptv.core.data.library.LiveFilter
import app.pureiptv.core.data.library.LiveGroup
import app.pureiptv.core.data.library.UserRepository
import app.pureiptv.core.model.ItemKeys
import app.pureiptv.ui.R
import app.pureiptv.ui.components.ChannelLogo
import app.pureiptv.ui.components.FocusMemory
import app.pureiptv.ui.components.ListTile
import app.pureiptv.ui.components.PosterTile
import app.pureiptv.ui.components.Tile
import app.pureiptv.ui.components.isTv
import app.pureiptv.ui.components.languageName
import app.pureiptv.ui.components.rememberFocus
import app.pureiptv.ui.components.rememberListEntry
import app.pureiptv.ui.live.enterOn
import app.pureiptv.ui.player.PlayRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeFeedViewModel @Inject constructor(
    home: HomeRepository,
    private val user: UserRepository,
) : ViewModel() {
    /** null until loaded. */
    val feed: StateFlow<HomeFeed?> = home.feed().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val focus = FocusMemory()

}

/**
 * The Home tab: rows of what the user watches and keeps, so most sessions start and end here.
 * OK on a title opens its details (Resume is focused there, so OK twice resumes; removing from
 * Continue watching or My list is there too); OK on a channel plays it.
 */
@Composable
fun HomeFeedScreen(
    onPlay: (PlayRequest) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeFeedViewModel = hiltViewModel(),
) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val f = feed ?: return
    val tv = isTv

    fun go(key: Any, action: () -> Unit) {
        viewModel.focus.onFocused(key)
        viewModel.focus.markForRestore()
        action()
    }

    if (f.isEmpty) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.home_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    val sMyMovies = stringResource(R.string.home_my_movies)
    val sMySeries = stringResource(R.string.home_my_series)

    val details: (ContinueItem) -> Unit = { item ->
        go("c:" + item.itemKey) {
            item.movieId?.let(onOpenMovie) ?: item.seriesId?.let(onOpenSeries)
        }
    }
    val posterWidth = if (tv) 130.dp else 104.dp

    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (f.favouriteChannels.isNotEmpty()) {
            // A grid, four across: all favourites at a glance instead of a long sideways row.
            item(key = "fav-title") {
                Text(stringResource(R.string.home_favourite_channels), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }
            f.favouriteChannels.chunked(FAVOURITE_COLUMNS).forEachIndexed { rowIndex, rowChannels ->
                item(key = "fav-row-$rowIndex") {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        rowChannels.forEach { c ->
                            ChannelCard(
                                c,
                                onClick = { go("f:" + c.id) { onPlay(PlayRequest.Channel(c.id, LiveFilter(LiveGroup.Favorites))) } },
                                modifier = Modifier.weight(1f).rememberFocus(viewModel.focus, "f:" + c.id),
                            )
                        }
                        // Keep cards the same width in a short last row.
                        repeat(FAVOURITE_COLUMNS - rowChannels.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        listOf(
            Triple("movies", sMyMovies, f.myMovies),
            Triple("series", sMySeries, f.mySeries),
        ).filter { it.third.isNotEmpty() }.forEach { (id, title, rows) ->
            item(key = id) {
                Row(title, rows, key = { id + it.itemKey }) { _, p, m ->
                    val open = { go(id + p.itemKey) { if (id == "movies") onOpenMovie(p.id) else onOpenSeries(p.id) } }
                    PosterTile(
                        title = p.title,
                        subtitle = posterSubtitle(p),
                        image = p.image,
                        width = posterWidth,
                        onClick = open,
                        modifier = m.rememberFocus(viewModel.focus, id + p.itemKey),
                    )
                }
            }
        }
        if (f.continueWatching.isNotEmpty()) {
            item(key = "continue") {
                Row(stringResource(R.string.home_continue), f.continueWatching, key = { "c:" + it.itemKey }) { _, item, m ->
                    PosterTile(
                        title = item.title,
                        // Same details as everywhere else: "2020 · German", "S2 E5 · Swedish".
                        subtitle = listOfNotNull(item.subtitle, languageName(item.language), item.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) })
                            .joinToString(" · ").ifEmpty { null },
                        image = item.image,
                        width = posterWidth,
                        progress = item.progress.takeIf { it > 0f },
                        onClick = { details(item) },
                        modifier = m.rememberFocus(viewModel.focus, "c:" + item.itemKey),
                    )
                }
            }
        }
    }
}

/** A titled horizontal row. Items are keyed by position and entered like other lists (AGENTS.md). */
@Composable
private fun <T> Row(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    item: @Composable (index: Int, item: T, modifier: Modifier) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        val state = rememberLazyListState()
        val entry = rememberListEntry(state, items.firstOrNull()?.let(key))
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            modifier = Modifier.enterOn(entry.requester),
        ) {
            itemsIndexed(items, key = { i, _ -> i }) { i, value ->
                item(i, value, with(entry) { Modifier.entryItem(i) })
            }
        }
    }
}

private fun posterSubtitle(p: PosterRow): String? =
    listOfNotNull(p.year?.toString(), languageName(p.language), p.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) })
        .joinToString(" · ").ifEmpty { null }

private const val FAVOURITE_COLUMNS = 4

@Composable
private fun ChannelCard(c: ChannelRow, onClick: () -> Unit, modifier: Modifier) {
    Tile(onClick = onClick, modifier = modifier.height(118.dp)) { _ ->
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChannelLogo(c.logo, c.name, Modifier.size(width = 64.dp, height = 40.dp))
            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            c.nowTitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = LocalContentColor.current.copy(alpha = 0.7f))
            }
        }
    }
}
