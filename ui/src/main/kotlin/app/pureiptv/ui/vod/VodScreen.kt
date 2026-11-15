package app.pureiptv.ui.vod

import app.pureiptv.ui.components.PureDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.widthIn
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import app.pureiptv.core.data.library.VodSort
import app.pureiptv.ui.components.dpadLeavesTextField
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.key
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.pureiptv.core.data.library.VodGroup
import app.pureiptv.core.data.library.VodKind
import app.pureiptv.core.data.library.NO_LANGUAGE
import app.pureiptv.ui.R
import app.pureiptv.ui.components.ListTile
import app.pureiptv.ui.components.RemoteTextField
import app.pureiptv.ui.components.PosterTile
import app.pureiptv.ui.components.Tile
import app.pureiptv.ui.components.formatted
import app.pureiptv.ui.components.isTv
import app.pureiptv.ui.components.GroupOrderTile
import app.pureiptv.ui.components.languageName
import app.pureiptv.ui.components.rememberFocus
import app.pureiptv.ui.components.rememberGridEntry
import app.pureiptv.ui.live.enterOn
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

@Composable
fun VodScreen(
    kind: VodKind,
    onOpen: (id: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = hiltViewModel<VodViewModel, VodViewModel.Factory>(key = kind.name, creationCallback = { it.create(kind) })
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val group by viewModel.group.collectAsStateWithLifecycle()
    val languageList by viewModel.languages.collectAsStateWithLifecycle()
    val languages = languageList?.items.orEmpty()
    // Null: too few languages for the sort tile.
    val languagesByName = languageList?.takeIf { it.sortable }?.byName
    val language by viewModel.language.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    val tv = isTv

    if (groups?.isEmpty() == true && language == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.vod_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val open: (Long) -> Unit = { id ->
        viewModel.itemFocus.markForRestore()
        // Focus leaves the posters for the details page: that must not bring the lists back.
        viewModel.openedDetails = true
        onOpen(id)
    }

    // Each category/language keeps its own scroll position; a new list, sort or filter starts at the top.
    // Kept in the ViewModel: survives the trip to a details page (composition state did not).
    val gridKey = "$language|${group?.stableId()}|$sort|$filter"
    val gridState = remember(gridKey) { viewModel.gridState(gridKey) }
    // Saved: coming back from a details page, focus returns to the poster that was opened even if
    // the grid briefly drops focus while its pages reload.
    var focusedIndex by rememberSaveable(language, group?.stableId(), sort, filter) { mutableIntStateOf(-1) }
    val gridEntry = rememberGridEntry(gridState, gridState, initial = focusedIndex)
    // Up from the top row of posters goes to the filter, not to whatever tab is straight above.
    val filterFocus = remember { FocusRequester() }
    val postersFocus = remember { FocusRequester() }

    val grid: @Composable (Modifier) -> Unit = grid@{ m ->
        // Paged items are empty for the first frame after returning to this screen; a grid composed
        // then loses its saved scroll position and the poster to refocus. Wait for the first page.
        if (items.itemCount == 0 && items.loadState.refresh is LoadState.Loading) {
            Box(m.fillMaxHeight())
            return@grid
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(if (tv) 124.dp else 110.dp),
            modifier = m.fillMaxHeight().focusRequester(postersFocus).enterOn(gridEntry.requester),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            // Keyed by position: the grid is replaced when the category, filter or sort changes,
            // possibly right after focus entered it; the focused slot must survive the swap.
            items(items.itemCount) { i ->
                val item = items[i] ?: return@items
                PosterTile(
                    title = item.title,
                    subtitle = listOfNotNull(item.year?.toString(), languageName(item.language), item.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) })
                        .joinToString(" · ").ifEmpty { null },
                    image = item.image,
                    onClick = { open(item.id) },
                    width = if (tv) 120.dp else 110.dp,
                    progress = progress[item.itemKey]?.fraction,
                    modifier = with(gridEntry) { Modifier.rememberFocus(viewModel.itemFocus, item.id).entryItem(i) }
                        .onFocusChanged { if (it.isFocused) focusedIndex = i },
                )
            }
        }
    }

    val allLanguages = stringResource(R.string.vod_all_languages)
    val notStated = stringResource(R.string.vod_language_not_stated)
    val languageLabel: (String?) -> String = { code ->
        when (code) {
            null -> allLanguages
            NO_LANGUAGE -> notStated
            else -> languageName(code) ?: code
        }
    }

    if (tv) {
        // Moving into a column lands on its selected entry (selection follows focus).
        val selectedLanguageFocus = remember { FocusRequester() }
        val selectedGroupFocus = remember { FocusRequester() }
        // Posters get the full width once the user moves into them; Left from the first column
        // or Back brings the language and category columns back.
        var postersOnly by rememberSaveable { mutableStateOf(false) }
        // Hoisted: the columns leave the composition in poster mode and must come back where they were.
        val languageListState = rememberLazyListState()
        val groupListState = rememberLazyListState()
        var postersHadFocus by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        var returnToGroups by remember { mutableStateOf(false) }
        BackHandler(enabled = postersOnly) {
            postersOnly = false
            returnToGroups = true
        }
        LaunchedEffect(postersOnly, returnToGroups) {
            if (!postersOnly && returnToGroups) {
                returnToGroups = false
                runCatching { selectedGroupFocus.requestFocus() }
            }
        }
        // Coming in from the tabs lands on the selected category, whichever tab it came from: left to
        // geometry, Down from a tab above the posters (Series) would open the full-width view instead.
        Row(
            modifier.fillMaxSize().enterOn(selectedGroupFocus).focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(if (postersOnly) 0.dp else 16.dp),
        ) {
            if (languages.isNotEmpty() && !postersOnly) {
                LazyColumn(
                    state = languageListState,
                    modifier = Modifier.width(220.dp).fillMaxHeight().enterOn(selectedLanguageFocus),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    if (languagesByName != null) item(key = "order") {
                        GroupOrderTile(languagesByName, viewModel::toggleLanguageOrder, countLabel = R.string.vod_order_count)
                    }
                    item(key = "all") {
                        ListTile(
                            text = allLanguages,
                            selected = language == null,
                            onClick = { viewModel.selectLanguage(null) },
                            onFocused = { viewModel.selectLanguage(null) },
                            modifier = if (language == null) Modifier.focusRequester(selectedLanguageFocus) else Modifier,
                        )
                    }
                    items(languages, key = { it.key }) { l ->
                        ListTile(
                            text = languageLabel(l.key),
                            trailing = l.count.formatted(),
                            selected = language == l.key,
                            onClick = { viewModel.selectLanguage(l.key) },
                            onFocused = { viewModel.selectLanguage(l.key) },
                            modifier = Modifier.rememberFocus(viewModel.languageFocus, l.key)
                                .then(if (language == l.key) Modifier.focusRequester(selectedLanguageFocus) else Modifier),
                        )
                    }
                }
            }
            if (!postersOnly) LazyColumn(
                state = groupListState,
                modifier = Modifier.width(280.dp).fillMaxHeight().enterOn(selectedGroupFocus),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                // Keyed by position, not by group: this list is replaced when the language changes,
                // possibly just after focus entered it (fast Right after picking a language). With
                // position keys the focused slot survives the swap instead of vanishing and dropping
                // focus out of the screen.
                itemsIndexed(groups.orEmpty(), key = { i, _ -> i }) { _, g ->
                    val selected = g.stableId() == group?.stableId()
                    ListTile(
                        text = g.label(kind),
                        supporting = (g as? VodGroup.Category)?.sourceName,
                        trailing = g.count()?.formatted(),
                        selected = selected,
                        onClick = { viewModel.select(g) },
                        onFocused = { viewModel.select(g) },
                        modifier = Modifier.rememberFocus(viewModel.groupFocus, g.stableId())
                            .then(if (selected) Modifier.focusRequester(selectedGroupFocus) else Modifier),
                    )
                }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().onFocusChanged {
                    // In the posters: full width. Leaving them for the tabs: lists come back, so
                    // coming into the tab again starts at the lists.
                    if (it.hasFocus) {
                        postersOnly = true
                        postersHadFocus = true
                        // Back from details: the grid may drop focus once more while its pages
                        // reload (the safety net puts it back); only after that is a loss real.
                        if (viewModel.openedDetails) scope.launch {
                            delay(1_500)
                            viewModel.openedDetails = false
                        }
                    } else if (postersHadFocus) {
                        postersHadFocus = false
                        if (!viewModel.openedDetails) postersOnly = false
                    }
                }
                    // Coming from the category list (Right) lands on the posters, not the filter bar.
                    .focusProperties {
                        onEnter = {
                            if (requestedFocusDirection == FocusDirection.Right) runCatching { postersFocus.requestFocus() }
                        }
                    }
                    .focusGroup(),
            ) {
                // Only in the full-width view: with the lists showing, the remote can't reach the bar
                // (Right from the categories goes to the posters and widens them), so the posters get the
                // space instead. An active filter keeps it visible, so a filtered grid always says so.
                if (postersOnly || filter.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilterAndSort(
                        filter = filter,
                        sort = sort,
                        buttonFocus = filterFocus,
                        onFilter = viewModel::setFilter,
                        onNextSort = viewModel::nextSort,
                        // Left from the bar shows the lists again, like from the first poster column.
                        onLeftEdge = {
                            if (postersOnly) {
                                postersOnly = false
                                returnToGroups = true
                                true
                            } else {
                                false
                            }
                        },
                    )
                    if (postersOnly) {
                        Text(
                            listOfNotNull(if (languages.isNotEmpty()) languageLabel(language) else null, group?.label(kind)).joinToString("  ›  "),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                NoMatch(filter, items)
                grid(
                    Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            // Left from a poster in the first column shows the lists again (instead of
                            // letting focus wander up to the tabs).
                            val focused = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusedIndex }
                            when {
                                event.type != KeyEventType.KeyDown -> false
                                postersOnly && focused?.column == 0 && event.key == Key.DirectionLeft -> {
                                    postersOnly = false
                                    returnToGroups = true
                                    true
                                }
                                focused?.row == 0 && event.key == Key.DirectionUp -> runCatching { filterFocus.requestFocus() }.isSuccess
                                else -> false
                            }
                        },
                )
            }
        }
    } else {
        var picking by rememberSaveable { mutableStateOf(false) }
        var pickingLanguage by rememberSaveable { mutableStateOf(false) }
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (languages.isNotEmpty()) {
                    Tile(onClick = { pickingLanguage = true }, modifier = Modifier.weight(1f)) { _ ->
                        Text(
                            languageLabel(language) + "  ▾",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
                Tile(onClick = { picking = true }, modifier = Modifier.weight(1.4f)) { _ ->
                    Text(
                        (group?.label(kind) ?: "") + "  ▾",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterAndSort(filter, sort, viewModel::setFilter, viewModel::nextSort, onLeftEdge = { false }, buttonFocus = filterFocus)
            }
            NoMatch(filter, items)
            grid(Modifier.weight(1f))
        }
        if (pickingLanguage) {
            PureDialog(onDismissRequest = { pickingLanguage = false }) {
                LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.8f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (languagesByName != null) item(key = "order") {
                        GroupOrderTile(languagesByName, viewModel::toggleLanguageOrder, countLabel = R.string.vod_order_count)
                    }
                    item(key = "all") {
                        ListTile(allLanguages, selected = language == null, onClick = {
                            viewModel.selectLanguage(null)
                            pickingLanguage = false
                        })
                    }
                    items(languages, key = { it.key }) { l ->
                        ListTile(languageLabel(l.key), trailing = l.count.formatted(), selected = language == l.key, onClick = {
                            viewModel.selectLanguage(l.key)
                            pickingLanguage = false
                        })
                    }
                }
            }
        }
        if (picking) {
            PureDialog(onDismissRequest = { picking = false }) {
                LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.8f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(groups.orEmpty(), key = { it.stableId() }) { g ->
                        ListTile(
                            text = g.label(kind),
                            supporting = (g as? VodGroup.Category)?.sourceName,
                            trailing = g.count()?.formatted(),
                            selected = g.stableId() == group?.stableId(),
                            onClick = {
                                viewModel.select(g)
                                picking = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Filter + sort above a poster grid. TV: both are buttons (OK on Filter opens the keyboard), so moving
 * Up from the posters never pops the keyboard by accident. Touch: a text field and a sort button.
 */
@Composable
private fun RowScope.FilterAndSort(
    filter: String,
    sort: VodSort,
    onFilter: (String) -> Unit,
    onNextSort: () -> Unit,
    onLeftEdge: () -> Boolean,
    buttonFocus: FocusRequester,
) {
    val tv = isTv
    RemoteTextField(
        value = filter,
        onValueChange = onFilter,
        placeholder = stringResource(if (tv) R.string.vod_filter else R.string.vod_filter_hint),
        onLeftEdge = onLeftEdge,
        modifier = Modifier
            .then(if (tv) Modifier.widthIn(min = 220.dp, max = 360.dp) else Modifier.weight(1f))
            .focusRequester(buttonFocus),
    )
    Tile(onClick = onNextSort, shape = RoundedCornerShape(50)) { _ ->
        Text(
            stringResource(R.string.vod_sort, stringResource(sort.labelRes())),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

private fun VodSort.labelRes(): Int = when (this) {
    VodSort.RecentlyAdded -> R.string.vod_sort_recent
    VodSort.Title -> R.string.vod_sort_title
    VodSort.Year -> R.string.vod_sort_year
    VodSort.Rating -> R.string.vod_sort_rating
}

@Composable
private fun NoMatch(filter: String, items: LazyPagingItems<*>) {
    if (filter.isNotBlank() && items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading) {
        Text(
            stringResource(R.string.vod_no_match, filter.trim()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(8.dp),
        )
    }
}

private fun VodGroup.count(): Int? = when (this) {
    is VodGroup.Category -> count
    is VodGroup.Language -> count
    else -> null
}

@Composable
fun VodGroup.label(kind: VodKind): String = when (this) {
    VodGroup.ContinueWatching -> stringResource(R.string.vod_continue)
    VodGroup.Favorites -> stringResource(R.string.vod_favorites)
    VodGroup.RecentlyAdded -> stringResource(R.string.vod_recent)
    is VodGroup.Category -> name
    is VodGroup.Language -> if (code == NO_LANGUAGE) {
        stringResource(if (kind == VodKind.Movies) R.string.vod_all_movies_not_stated else R.string.vod_all_series_not_stated)
    } else {
        stringResource(
            if (kind == VodKind.Movies) R.string.vod_all_movies_in else R.string.vod_all_series_in,
            languageName(code) ?: code,
        )
    }
}
