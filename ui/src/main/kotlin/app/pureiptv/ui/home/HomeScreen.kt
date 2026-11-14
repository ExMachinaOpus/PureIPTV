package app.pureiptv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pureiptv.core.data.library.BrowseRepository
import app.pureiptv.core.data.library.HomeRepository
import kotlinx.coroutines.flow.map
import app.pureiptv.core.data.library.ContentPresence
import app.pureiptv.core.data.library.VodKind
import app.pureiptv.ui.BuildConfig
import app.pureiptv.ui.R
import app.pureiptv.ui.components.KeepLeftToRight
import app.pureiptv.ui.components.Screen
import app.pureiptv.ui.components.Tile
import app.pureiptv.ui.components.isTv
import app.pureiptv.ui.settings.SettingsScreen
import app.pureiptv.ui.guide.GuideScreen
import app.pureiptv.ui.live.LiveScreen
import app.pureiptv.ui.player.PlayRequest
import app.pureiptv.ui.search.SearchScreen
import app.pureiptv.ui.vod.VodScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class HomeTab { Home, Live, Guide, Movies, Series, Search, Settings }

/** The current tab on TV, for screens that send focus straight back to it; null on touch. */
val LocalTabsFocus = compositionLocalOf<FocusRequester?> { null }

@HiltViewModel
class HomeViewModel @Inject constructor(browse: BrowseRepository, home: HomeRepository) : ViewModel() {
    /** Decides the start tab; read once at start (later changes don't move the user). */
    val homeHasContent: StateFlow<Boolean> = home.feed().map { !it.isEmpty }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Which content kinds exist in enabled sources; tabs without content are not shown. */
    val presence: StateFlow<ContentPresence?> = browse.hasContent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun HomeScreen(
    onPlay: (PlayRequest) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onAddSource: () -> Unit,
    onAbout: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val presence by viewModel.presence.collectAsStateWithLifecycle()
    val p = presence ?: return
    val homeHasContent by viewModel.homeHasContent.collectAsStateWithLifecycle()
    val tabs = listOfNotNull(
        HomeTab.Home,
        HomeTab.Live.takeIf { p.live },
        HomeTab.Guide.takeIf { p.live },
        HomeTab.Movies.takeIf { p.movies },
        HomeTab.Series.takeIf { p.series },
        HomeTab.Search.takeIf { p.live || p.movies || p.series },
        HomeTab.Settings,
    )
    // null until the user picks a tab: while the first import runs, Live TV appears as soon as it has content.
    var chosen by rememberSaveable { mutableStateOf<HomeTab?>(null) }
    // Home first once it has something; a new user starts on Live TV (or whatever exists).
    val tab = chosen?.takeIf { it in tabs } ?: (if (homeHasContent) HomeTab.Home else tabs.getOrElse(1) { tabs.first() })
    val holder = rememberSaveableStateHolder()

    val content: @Composable (Modifier) -> Unit = { m ->
        holder.SaveableStateProvider(tab.name) {
            when (tab) {
                HomeTab.Home -> HomeFeedScreen(onPlay = onPlay, onOpenMovie = onOpenMovie, onOpenSeries = onOpenSeries, modifier = m)
                HomeTab.Live -> LiveScreen(onPlay = { id, filter -> onPlay(PlayRequest.Channel(id, filter)) }, modifier = m)
                HomeTab.Guide -> KeepLeftToRight { GuideScreen(onPlay = onPlay, modifier = m) }
                HomeTab.Movies -> VodScreen(VodKind.Movies, onOpen = onOpenMovie, modifier = m)
                HomeTab.Series -> VodScreen(VodKind.Series, onOpen = onOpenSeries, modifier = m)
                HomeTab.Search -> SearchScreen(
                    onPlayChannel = { onPlay(PlayRequest.Channel(it, null)) },
                    onOpenMovie = onOpenMovie,
                    onOpenSeries = onOpenSeries,
                    modifier = m,
                )
                HomeTab.Settings -> SettingsScreen(
                    // Nothing to browse yet (e.g. the first import): what matters is on the Sources page.
                    startOnSources = !p.live && !p.movies && !p.series,
                    onAddSource = onAddSource,
                    onAbout = onAbout,
                    modifier = m,
                )
            }
        }
    }

    if (isTv) {
        TvHome(tabs, tab, onSelect = { if (it != tab) chosen = it }, content)
    } else {
        Column(Modifier.fillMaxSize()) {
            Screen(Modifier.weight(1f)) { content(Modifier.fillMaxSize()) }
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { chosen = t },
                        icon = { Icon(t.icon(), contentDescription = null) },
                        label = { Text(t.label()) },
                    )
                }
            }
        }
    }
}

/**
 * Google TV style: tabs on top, focusing a tab switches to it. Back from the content returns to
 * the tabs; Back on the tabs leaves the app.
 */
@Composable
private fun TvHome(tabs: List<HomeTab>, tab: HomeTab, onSelect: (HomeTab) -> Unit, content: @Composable (Modifier) -> Unit) {
    val tabFocus = remember { FocusRequester() }
    var tabsFocused by remember { mutableStateOf(false) }
    var anyFocused by remember { mutableStateOf(false) }
    // A tab switches screens only when the user moves to it with Left/Right inside the tab row (or
    // presses OK on it). Focus that merely lands on a tab, e.g. after the focused item vanished,
    // must never switch screens.
    var switchOnFocus by remember { mutableStateOf(false) }

    // Safety net: if the focused item vanishes (a list was replaced under it), Android drops focus
    // and the next key would land on the first tab. Put focus back into the screen instead.
    val contentFocus = remember { FocusRequester() }
    var contentFocused by remember { mutableStateOf(false) }
    var contentLostAt by remember { mutableStateOf(0L) }
    LaunchedEffect(contentLostAt) {
        if (contentLostAt == 0L) return@LaunchedEffect
        delay(120)
        if (!contentFocused && !tabsFocused) {
            if (BuildConfig.DEBUG) android.util.Log.w("PureFocus", "focus lost from content, restoring")
            runCatching { contentFocus.requestFocus() }
        }
    }

    // Content restores its own focus (e.g. the channel that was playing); otherwise start on the tabs.
    LaunchedEffect(Unit) {
        delay(350)
        if (!anyFocused) runCatching { tabFocus.requestFocus() }
    }
    BackHandler(enabled = !tabsFocused) { runCatching { tabFocus.requestFocus() } }

    Screen(Modifier.onFocusChanged { anyFocused = it.hasFocus }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                // Entering the tab row from below lands on the current tab, not the geometrically nearest one.
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { tabsFocused = it.hasFocus }
                    .onPreviewKeyEvent { e ->
                        if (tabsFocused && e.type == KeyEventType.KeyDown && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight)) {
                            switchOnFocus = true
                        }
                        false
                    }
                    .focusRestorer(tabFocus),
            ) {
                // Keyed: tabs appear while importing, and focus must stay with its tab, not its slot.
                @Composable
                fun TabTile(t: HomeTab, modifier: Modifier = Modifier, label: @Composable () -> Unit) = key(t) {
                    Tile(
                        onClick = { onSelect(t) },
                        onFocused = {
                            if (switchOnFocus) onSelect(t)
                            switchOnFocus = false
                        },
                        selected = t == tab,
                        transparent = true,
                        shape = RoundedCornerShape(50),
                        modifier = modifier.then(if (t == tab) Modifier.focusRequester(tabFocus) else Modifier),
                    ) { _ -> label() }
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabs.filter { it != HomeTab.Settings }.forEach { t ->
                        TabTile(t) {
                            Text(t.label(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                        }
                    }
                }
                // Still the last tab for the remote (Right from the last one), just placed in the corner.
                TabTile(HomeTab.Settings, Modifier.align(Alignment.CenterEnd)) {
                    Icon(
                        HomeTab.Settings.icon(),
                        contentDescription = HomeTab.Settings.label(),
                        modifier = Modifier.padding(10.dp).size(24.dp),
                    )
                }
            }
            // Content reaches the tabs only with Up (or Back). Left/Right at the edge of a screen must
            // not slip into the tab row: a focused tab switches screens.
            Box(
                Modifier
                    .weight(1f)
                    .onFocusChanged {
                        if (contentFocused && !it.hasFocus) contentLostAt = System.nanoTime()
                        contentFocused = it.hasFocus
                    }
                    .focusRequester(contentFocus)
                    .focusProperties {
                        onExit = {
                            when (requestedFocusDirection) {
                                FocusDirection.Left, FocusDirection.Right -> cancelFocusChange()
                                // The current tab, not whichever one happens to be above.
                                FocusDirection.Up -> tabFocus.requestFocus()
                                else -> Unit
                            }
                        }
                    }
                    .focusGroup(), // the properties belong to the whole content, not to its first child
            ) { CompositionLocalProvider(LocalTabsFocus provides tabFocus) { content(Modifier.fillMaxSize()) } }
        }
    }
}

@Composable
private fun HomeTab.label(): String = stringResource(
    when (this) {
        HomeTab.Home -> R.string.tab_home
        HomeTab.Live -> R.string.tab_live
        HomeTab.Guide -> R.string.tab_guide
        HomeTab.Movies -> R.string.tab_movies
        HomeTab.Series -> R.string.tab_series
        HomeTab.Search -> R.string.tab_search
        HomeTab.Settings -> R.string.tab_settings
    },
)

private fun HomeTab.icon(): ImageVector = when (this) {
    HomeTab.Home -> Icons.Filled.Home
    HomeTab.Live -> Icons.Filled.LiveTv
    HomeTab.Guide -> Icons.Filled.DateRange
    HomeTab.Movies -> Icons.Filled.Movie
    HomeTab.Series -> Icons.Filled.VideoLibrary
    HomeTab.Search -> Icons.Filled.Search
    HomeTab.Settings -> Icons.Filled.Settings
}
