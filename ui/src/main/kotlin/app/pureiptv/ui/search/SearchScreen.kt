package app.pureiptv.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pureiptv.core.data.db.PosterRow
import app.pureiptv.core.data.db.ProgrammeHit
import app.pureiptv.ui.guide.ProgrammeCard
import app.pureiptv.ui.guide.ProgrammeDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.pureiptv.core.data.library.BrowseRepository
import app.pureiptv.core.data.library.SearchResults
import app.pureiptv.core.data.library.UserRepository
import app.pureiptv.core.model.ItemKeys
import app.pureiptv.ui.R
import app.pureiptv.ui.components.FocusMemory
import app.pureiptv.ui.components.PosterTile
import app.pureiptv.ui.components.dpadLeavesTextField
import app.pureiptv.ui.components.isTv
import app.pureiptv.ui.components.languageName
import app.pureiptv.ui.components.regionName
import app.pureiptv.ui.components.rememberFocus
import app.pureiptv.ui.live.ChannelTile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(browse: BrowseRepository, user: UserRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    val focus = FocusMemory()

    /** null = nothing searched yet. */
    val results: StateFlow<SearchResults?> = _query.debounce(250).map { it.trim() }.distinctUntilChanged()
        .mapLatest { q -> if (q.length < 2) null else browse.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val favoriteKeys = user.favoriteKeys.map { it.toSet() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setQuery(q: String) {
        _query.value = q
    }
}

/** Search over channels, the guide, movies and series of all enabled sources. */
@Composable
fun SearchScreen(
    onPlayChannel: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteKeys.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val tv = isTv
    val open: (() -> Unit) -> Unit = { action ->
        viewModel.focus.markForRestore()
        action()
    }

    var details by remember { mutableStateOf<ProgrammeHit?>(null) }
    details?.let { hit -> ProgrammeDialog(hit, onWatch = { open { onPlayChannel(hit.channelId) } }, onDismiss = { details = null }) }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            label = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().dpadLeavesTextField(focusManager),
        )
        val r = results
        when {
            r == null -> Hint(stringResource(R.string.search_intro))
            r.isEmpty -> Hint(stringResource(R.string.search_nothing, query.trim()))
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (r.programmes.isNotEmpty()) {
                    item(key = "programmes") {
                        ProgrammeRowSection(r.programmes, viewModel.focus, onOpen = { hit ->
                            if (hit.startMs <= System.currentTimeMillis()) open { onPlayChannel(hit.channelId) } else details = hit
                        })
                    }
                }
                if (r.movies.isNotEmpty()) {
                    item(key = "movies") { PosterRowSection(stringResource(R.string.tab_movies), r.movies, tv, viewModel.focus) { id -> open { onOpenMovie(id) } } }
                }
                if (r.series.isNotEmpty()) {
                    item(key = "series") { PosterRowSection(stringResource(R.string.tab_series), r.series, tv, viewModel.focus) { id -> open { onOpenSeries(id) } } }
                }
                if (r.channels.isNotEmpty()) {
                    item(key = "channels-title") { Section(stringResource(R.string.tab_live)) }
                    // Position keys: results are replaced while typing; a focused row must survive the swap.
                    itemsIndexed(r.channels, key = { i, _ -> "c$i" }) { _, c ->
                        ChannelTile(
                            c,
                            favorite = ItemKeys.channel(c.identityKey) in favorites,
                            supporting = listOfNotNull(regionName(c.region), c.nowTitle).joinToString("  ·  ").ifEmpty { null },
                            onClick = { open { onPlayChannel(c.id) } },
                            modifier = Modifier.widthIn(max = 720.dp).rememberFocus(viewModel.focus, "c" + c.id),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterRowSection(title: String, items: List<PosterRow>, tv: Boolean, focus: FocusMemory, onOpen: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section(title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(8.dp), modifier = Modifier.focusRestorer()) {
            itemsIndexed(items, key = { i, _ -> title + i }) { _, p ->
                PosterTile(
                    title = p.title,
                    subtitle = listOfNotNull(p.year?.toString(), languageName(p.language)).joinToString(" · ").ifEmpty { null },
                    image = p.image,
                    onClick = { onOpen(p.id) },
                    width = if (tv) 130.dp else 100.dp,
                    modifier = Modifier.rememberFocus(focus, title + p.id),
                )
            }
        }
    }
}

@Composable
private fun ProgrammeRowSection(items: List<ProgrammeHit>, focus: FocusMemory, onOpen: (ProgrammeHit) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section(stringResource(R.string.search_on_tv))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(8.dp), modifier = Modifier.focusRestorer()) {
            itemsIndexed(items, key = { i, _ -> "p$i" }) { _, hit ->
                ProgrammeCard(hit, onClick = { onOpen(hit) }, modifier = Modifier.rememberFocus(focus, "p${hit.channelId}:${hit.startMs}"))
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
}
