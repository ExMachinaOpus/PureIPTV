package app.pureiptv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.pureiptv.core.data.db.PosterRow
import app.pureiptv.core.data.library.BrowseRepository
import app.pureiptv.core.data.library.Progress
import app.pureiptv.core.data.library.UserRepository
import app.pureiptv.core.data.library.VodGroup
import app.pureiptv.core.data.library.VodKind
import app.pureiptv.core.data.library.VodSort
import app.pureiptv.core.model.ItemKeys
import app.pureiptv.ui.components.FocusMemory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import app.pureiptv.core.data.db.GroupCount
import app.pureiptv.core.data.library.NO_LANGUAGE

/** Movies or Series tab: groups on the left (continue, favorites, recent, categories), posters on the right. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel(assistedFactory = VodViewModel.Factory::class)
class VodViewModel @AssistedInject constructor(
    @Assisted val kind: VodKind,
    private val browse: BrowseRepository,
    user: UserRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(kind: VodKind): VodViewModel
    }

    val groupFocus = FocusMemory()
    val itemFocus = FocusMemory()

    val languageFocus = FocusMemory()

    private val gridStates = HashMap<String, androidx.compose.foundation.lazy.grid.LazyGridState>()

    /** Scroll position per list (language, category, sort, filter): each keeps its own. */
    fun gridState(key: String) = gridStates.getOrPut(key) { androidx.compose.foundation.lazy.grid.LazyGridState() }

    /** A poster was opened: returning from its details goes back to the full-width posters. */
    var openedDetails = false

    private val chosen = MutableStateFlow<VodGroup?>(null)

    /** Chosen language; null = all languages (the default: nothing is hidden). */
    private val _language = MutableStateFlow<String?>(null)

    /** Languages with titles: the device language first, then by number of titles. */
    val languages: StateFlow<List<GroupCount>> = browse.vodLanguages(kind)
        .map { list ->
            val home = java.util.Locale.getDefault().language
            list.sortedWith(compareBy<GroupCount>({ it.key == NO_LANGUAGE }, { it.key != home }, { -it.count }))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Effective language: falls back to all languages when the chosen one is gone (source turned off). */
    val language: StateFlow<String?> = combine(_language, languages) { chosen, all -> chosen?.takeIf { c -> all.any { it.key == c } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val groups: StateFlow<List<VodGroup>?> = language.debounce(150).flatMapLatest { browse.vodGroups(kind, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectLanguage(code: String?) {
        if (language.value == code) return
        _language.value = code
        chosen.value = null
    }

    val group: StateFlow<VodGroup?> = combine(chosen, groups) { c, all ->
        when {
            all.isNullOrEmpty() -> c
            c != null && all.contains(c) -> c
            // Categories are data classes with counts: match by id when the count changed.
            c is VodGroup.Category -> all.firstOrNull { it is VodGroup.Category && it.id == c.id } ?: all.first()
            // Start on a real list: "Recently added" (or the first category / "All <language>"),
            // not the personal shelves, which may hold a single title.
            else -> all.firstOrNull { it !is VodGroup.ContinueWatching && it !is VodGroup.Favorites } ?: all.first()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Free-text filter for the current grid; cleared when another group is picked. */
    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter

    private val _sort = MutableStateFlow(VodSort.RecentlyAdded)
    val sort: StateFlow<VodSort> = _sort

    private val stableGroup = group.filterNotNull().distinctUntilChanged { a, b -> a.stableId() == b.stableId() }

    val items: Flow<PagingData<PosterRow>> = combine(stableGroup.debounce(150), _filter.debounce(250), _sort) { g, f, s -> Triple(g, f, s) }
        .flatMapLatest { (g, f, s) -> browse.vodItems(kind, g, f, s) }
        .cachedIn(viewModelScope)

    fun setFilter(text: String) {
        _filter.value = text
    }

    fun nextSort() {
        _sort.value = VodSort.entries[(_sort.value.ordinal + 1) % VodSort.entries.size]
    }

    /** Resume progress for movie posters. */
    val progress: StateFlow<Map<String, Progress>> = user.progressWithPrefix(ItemKeys.MOVIE_PREFIX)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun select(group: VodGroup) {
        if (chosen.value?.stableId() != group.stableId()) _filter.value = ""
        chosen.value = group
    }
}

fun VodGroup.stableId(): String = when (this) {
    is VodGroup.Category -> "cat:$id"
    is VodGroup.Language -> "lang:$code"
    else -> toString()
}
