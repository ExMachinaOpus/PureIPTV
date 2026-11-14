package app.pureiptv.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.pureiptv.core.data.db.ChannelRow
import app.pureiptv.core.data.db.GroupCount
import app.pureiptv.core.data.library.BrowseRepository
import app.pureiptv.core.data.library.LiveFilter
import app.pureiptv.core.data.library.LiveGroup
import app.pureiptv.core.data.library.UserRepository
import app.pureiptv.ui.components.FocusMemory
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject

data class GroupItem(val group: LiveGroup, val count: Int)

/**
 * Live TV browsing: group (favorites, recent, country/language) -> genre -> channels.
 * Selection follows focus on TV, so lists update while the user moves; the channel query is
 * debounced so fast scrolling through countries doesn't run a query per step.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val browse: BrowseRepository,
    user: UserRepository,
) : ViewModel() {

    val channelFocus = FocusMemory()
    val groupFocus = FocusMemory()

    private val chosenGroup = MutableStateFlow<LiveGroup?>(null)
    private val chosenGenre = MutableStateFlow<String?>(null)

    val groups: StateFlow<List<GroupItem>?> = combine(
        browse.regions(),
        browse.favoriteChannels().map { it.size },
        browse.recentChannels().map { it.size },
    ) { regions, favorites, recent ->
        val home = Locale.getDefault().country
        buildList {
            if (favorites > 0) add(GroupItem(LiveGroup.Favorites, favorites))
            if (recent > 0) add(GroupItem(LiveGroup.Recent, recent))
            regions
                .sortedWith(compareBy<GroupCount>({ it.key != home }, { it.key.isEmpty() }, { -it.count }))
                .forEach { add(GroupItem(LiveGroup.Region(it.key), it.count)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The selected group; defaults to Favorites, else the device's country, else the biggest group (never Recent). */
    val group: StateFlow<LiveGroup?> = combine(chosenGroup, groups) { chosen, all ->
        when {
            all.isNullOrEmpty() -> chosen
            chosen != null && all.any { it.group == chosen } -> chosen
            // "Recently watched" is a shortcut, not a place to start browsing.
            else -> (all.firstOrNull { it.group != LiveGroup.Recent } ?: all.first()).group
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Genres of the selected region; empty when there is nothing to choose. */
    val genres: StateFlow<List<GroupCount>> = group.debounce(150).flatMapLatest { g ->
        if (g is LiveGroup.Region) browse.genres(g.code) else flowOf(emptyList())
    }.map { if (it.size > 1) it else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val genre: StateFlow<String?> = combine(chosenGenre, genres) { chosen, all ->
        chosen?.takeIf { c -> all.any { it.key == c } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val filter: StateFlow<LiveFilter?> = combine(group, genre) { g, genre -> g?.let { LiveFilter(it, genre) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val channels: Flow<PagingData<ChannelRow>> = filter.filterNotNull().debounce(120).distinctUntilChanged()
        .flatMapLatest { browse.channels(it) }
        .cachedIn(viewModelScope)

    val favoriteKeys: StateFlow<Set<String>> = user.favoriteKeys.map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun selectGroup(group: LiveGroup) {
        if (chosenGroup.value == group) return
        chosenGroup.value = group
        chosenGenre.value = null
    }

    fun selectGenre(genre: String?) {
        chosenGenre.value = genre
    }
}
