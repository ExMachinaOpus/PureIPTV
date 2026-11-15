package app.pureiptv.ui.live

import app.pureiptv.ui.components.PureDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.paging.LoadState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.pureiptv.core.data.db.ChannelRow
import app.pureiptv.core.data.library.LiveFilter
import app.pureiptv.core.data.library.LiveGroup
import app.pureiptv.core.model.ItemKeys
import app.pureiptv.core.model.Quality
import app.pureiptv.ui.R
import app.pureiptv.ui.components.ChannelLogo
import app.pureiptv.ui.components.ListTile
import app.pureiptv.ui.components.Tile
import app.pureiptv.ui.components.badge
import app.pureiptv.ui.components.formatted
import app.pureiptv.ui.components.genreLabel
import app.pureiptv.ui.components.isTv
import app.pureiptv.ui.components.label
import app.pureiptv.ui.components.regionName
import app.pureiptv.ui.components.rememberFocus
import app.pureiptv.ui.components.restoringFocus
import app.pureiptv.ui.components.GroupOrderTile
import app.pureiptv.ui.components.rememberListEntry

/** Live TV tab. TV: three columns (group | genre | channels). Touch: pickers on top of the list. */
@Composable
fun LiveScreen(
    onPlay: (channelId: Long, filter: LiveFilter) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LiveViewModel = hiltViewModel(),
) {
    val groupList by viewModel.groups.collectAsStateWithLifecycle()
    val groups = groupList?.items
    val group by viewModel.group.collectAsStateWithLifecycle()
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val genre by viewModel.genre.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteKeys.collectAsStateWithLifecycle()
    val channels = viewModel.channels.collectAsLazyPagingItems()

    val play: (Long) -> Unit = { id ->
        filter?.let {
            viewModel.channelFocus.markForRestore()
            onPlay(id, it)
        }
    }

    if (groups?.isEmpty() == true) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.live_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    if (isTv) {
        // Moving into a column lands on its selected entry, never on whatever is geometrically next
        // (which would silently change the selection, since selection follows focus).
        val selectedGroupFocus = remember { FocusRequester() }
        val selectedGenreFocus = remember { FocusRequester() }
        Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(
                Modifier.width(250.dp).fillMaxHeight().enterOn(selectedGroupFocus),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                // Above the groups, so it's one Up away from the top and never between them. Focusing it
                // doesn't change the selection; the group keys keep the selected one in place when re-sorted.
                groupList?.takeIf { it.sortable }?.let { list -> item(key = "order") { GroupOrderTile(list.byName, viewModel::toggleGroupOrder) } }
                items(groups.orEmpty(), key = { it.group.toString() }) { item ->
                    ListTile(
                        text = item.group.label(),
                        trailing = item.count.formatted(),
                        selected = item.group == group,
                        onClick = { viewModel.selectGroup(item.group) },
                        onFocused = { viewModel.selectGroup(item.group) },
                        modifier = Modifier.rememberFocus(viewModel.groupFocus, item.group.toString())
                            .then(if (item.group == group) Modifier.focusRequester(selectedGroupFocus) else Modifier),
                    )
                }
            }
            if (genres.isNotEmpty()) {
                LazyColumn(
                    Modifier.width(190.dp).fillMaxHeight().enterOn(selectedGenreFocus),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    item(key = "all") {
                        ListTile(
                            genreLabel(null),
                            onClick = { viewModel.selectGenre(null) },
                            selected = genre == null,
                            onFocused = { viewModel.selectGenre(null) },
                            modifier = if (genre == null) Modifier.focusRequester(selectedGenreFocus) else Modifier,
                        )
                    }
                    // Position keys: see ChannelList.
                    itemsIndexed(genres, key = { i, _ -> "g$i" }) { _, g ->
                        ListTile(
                            genreLabel(g.key),
                            trailing = g.count.formatted(),
                            selected = genre == g.key,
                            onClick = { viewModel.selectGenre(g.key) },
                            onFocused = { viewModel.selectGenre(g.key) },
                            modifier = if (genre == g.key) Modifier.focusRequester(selectedGenreFocus) else Modifier,
                        )
                    }
                }
            }
            ChannelList(channels, favorites, viewModel, play, showRegion = group !is LiveGroup.Region, modifier = Modifier.weight(1f))
        }
    } else {
        TouchLive(groups.orEmpty(), groupList?.takeIf { it.sortable }?.byName, group, genres.map { it.key }, genre, viewModel, channels, favorites, play, modifier)
    }
}

@Composable
private fun ChannelList(
    channels: LazyPagingItems<ChannelRow>,
    favorites: Set<String>,
    viewModel: LiveViewModel,
    play: (Long) -> Unit,
    showRegion: Boolean,
    modifier: Modifier,
) {
    val filterKey = viewModel.filter.collectAsStateWithLifecycle().value
    // Each country/genre list keeps its own scroll position (a newly picked one starts at the top).
    val listState = key(filterKey) { rememberLazyListState() }
    StickToTopUntilScrolled(listState, channels, key = filterKey)
    val entry = rememberListEntry(listState, filterKey)
    LazyColumn(
        modifier.fillMaxHeight().enterOn(entry.requester),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // Keyed by position: this list is replaced when the country/genre changes, possibly right after
        // focus entered it. A position key keeps the focused slot alive across the swap (an id key
        // would remove it and drop focus out of the screen, to the tab row).
        items(channels.itemCount) { i ->
            val c = channels[i] ?: return@items
            ChannelTile(
                channel = c,
                favorite = ItemKeys.channel(c.identityKey) in favorites,
                onClick = { play(c.id) },
                supporting = listOfNotNull(if (showRegion) regionName(c.region) else null, c.nowTitle).joinToString("  ·  ").ifEmpty { null },
                modifier = with(entry) { Modifier.rememberFocus(viewModel.channelFocus, c.id).entryItem(i) },
            )
        }
    }
}

@Composable
fun ChannelTile(channel: ChannelRow, favorite: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, supporting: String? = null) {
    val quality = Quality.entries.getOrElse(channel.bestQuality) { Quality.Unknown }.badge()
    ListTile(
        text = channel.name,
        onClick = onClick,
        modifier = modifier,
        supporting = supporting,
        trailing = listOfNotNull(if (favorite) "★" else null, quality).joinToString("  ").ifEmpty { null },
        leading = { ChannelLogo(channel.logo, channel.name) },
    )
}

@Composable
private fun TouchLive(
    groups: List<GroupItem>,
    /** Null: too few groups for the sort tile. */
    groupsByName: Boolean?,
    group: LiveGroup?,
    genres: List<String>,
    genre: String?,
    viewModel: LiveViewModel,
    channels: LazyPagingItems<ChannelRow>,
    favorites: Set<String>,
    play: (Long) -> Unit,
    modifier: Modifier,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { _ ->
            Text(
                (group?.label() ?: "") + "  ▾",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        if (genres.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Chip(genreLabel(null), genre == null) { viewModel.selectGenre(null) } }
                items(genres) { g -> Chip(genreLabel(g), genre == g) { viewModel.selectGenre(g) } }
            }
        }
        val filterKey = viewModel.filter.collectAsStateWithLifecycle().value
        val listState = key(filterKey) { rememberLazyListState() }
        StickToTopUntilScrolled(listState, channels, key = filterKey)
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Keyed by position: this list is replaced when the country/genre changes, possibly right after
        // focus entered it. A position key keeps the focused slot alive across the swap (an id key
        // would remove it and drop focus out of the screen, to the tab row).
        items(channels.itemCount) { i ->
                val c = channels[i] ?: return@items
                ChannelTile(c, ItemKeys.channel(c.identityKey) in favorites, onClick = { play(c.id) }, supporting = c.nowTitle)
            }
        }
    }
    if (picking) {
        PureDialog(onDismissRequest = { picking = false }) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (groupsByName != null) item(key = "order") { GroupOrderTile(groupsByName, viewModel::toggleGroupOrder) }
                    items(groups, key = { it.group.toString() }) { item ->
                        ListTile(
                            item.group.label(),
                            trailing = item.count.formatted(),
                            selected = item.group == group,
                            onClick = {
                                viewModel.selectGroup(item.group)
                                picking = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Tile(onClick, selected = selected, shape = androidx.compose.foundation.shape.RoundedCornerShape(50)) { _ ->
        Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

/** Focus entering this container goes to [target] (the selected entry), unless an item is getting its focus back. */
fun Modifier.enterOn(target: FocusRequester): Modifier = focusProperties {
    onEnter = { if (!restoringFocus) target.requestFocus() }
}

/**
 * Lists reorder while a source imports (rankings are computed at the end). LazyColumn keeps its
 * first visible item in place, which would leave the list scrolled; stay at the top until the user
 * (or remote focus) actually scrolls.
 */
@Composable
fun StickToTopUntilScrolled(state: LazyListState, items: LazyPagingItems<*>, key: Any?) {
    var userScrolled by rememberSaveable(key) { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress) { if (state.isScrollInProgress) userScrolled = true }
    val refresh = items.loadState.refresh
    LaunchedEffect(refresh, items.itemCount, key) {
        if (!userScrolled && refresh is LoadState.NotLoading && items.itemCount > 0) state.scrollToItem(0)
    }
}
