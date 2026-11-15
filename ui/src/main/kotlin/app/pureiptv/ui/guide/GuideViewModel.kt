package app.pureiptv.ui.guide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.pureiptv.core.data.db.ChannelRow
import app.pureiptv.core.data.db.ProgrammeEntity
import app.pureiptv.core.data.db.ProgrammeHit
import app.pureiptv.core.data.library.BrowseRepository
import app.pureiptv.core.data.library.LiveFilter
import app.pureiptv.core.data.library.LiveGroup
import app.pureiptv.core.data.library.UserRepository
import app.pureiptv.ui.components.FocusMemory
import app.pureiptv.ui.live.GroupList
import app.pureiptv.ui.live.groupItems
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The TV guide: a time grid of one channel group (favourites by default) and a search over the
 * whole guide. The time position lives here so it survives tab switches and playing a channel.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val browse: BrowseRepository,
    private val user: UserRepository,
) : ViewModel() {

    val rowFocus = FocusMemory()

    /** Here, not in the screen, so the programme info stays up when coming back from the player. */
    var gridFocused by mutableStateOf(false)

    private val chosenGroup = MutableStateFlow<LiveGroup?>(null)

    /** Countries and languages in the order picked in Live TV or here (one shared setting). */
    val groups: StateFlow<GroupList?> = groupItems(browse, user, all = true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleGroupOrder() {
        viewModelScope.launch { user.toggleGroupsByName() }
    }

    /** Favourites by default, else all channels. */
    val group: StateFlow<LiveGroup?> = combine(chosenGroup, groups) { chosen, list ->
        val all = list?.items
        when {
            all.isNullOrEmpty() -> chosen
            chosen != null && all.any { it.group == chosen } -> chosen
            all.any { it.group == LiveGroup.Favorites } -> LiveGroup.Favorites
            else -> LiveGroup.All
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val channels: Flow<PagingData<ChannelRow>> = group.filterNotNull().distinctUntilChanged()
        .flatMapLatest { browse.channels(LiveFilter(it)) }
        .cachedIn(viewModelScope)

    fun selectGroup(group: LiveGroup) {
        chosenGroup.value = group
    }

    // ---- Search -------------------------------------------------------------------------------

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** null = not searching (fewer than two characters): the grid shows. */
    /** Searches within the picked group (all channels, favourites, a country...). */
    val results: StateFlow<List<ProgrammeHit>?> = combine(_query.debounce(250).map { it.trim() }.distinctUntilChanged(), group.filterNotNull()) { q, g -> q to g }
        .distinctUntilChanged()
        .mapLatest { (q, g) -> if (q.length < MIN_QUERY) null else browse.searchGuide(q, g) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(text: String) {
        _query.value = text
    }

    // ---- Time -----------------------------------------------------------------------------------

    /** Refreshed every few minutes: moves "now", and reloads schedules (a guide refresh may have run). */
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set

    /** Left edge of the visible time range. */
    var viewStart by mutableLongStateOf(halfHour(now))
        private set

    /** The time the selection follows when moving between channels. */
    var focusTime by mutableLongStateOf(now)
        private set

    /** The focused channel and its selected programme (the panel above the grid). */
    var selected by mutableStateOf<Pair<ChannelRow, ProgrammeEntity?>?>(null)

    val earliest: Long get() = halfHour(now) - PAST_MS
    val latest: Long get() = now + FUTURE_MS
    val isAtNow: Boolean get() = viewStart == halfHour(now)

    init {
        viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                val atNow = isAtNow
                now = System.currentTimeMillis()
                cache.clear()
                // A user who hasn't moved keeps seeing "now" as time passes.
                if (atNow) backToNow()
            }
        }
    }

    /** Selects time [t] and scrolls so that [visible] (a programme start, or t) is on screen. */
    fun moveTo(t: Long, visible: Long = t) {
        focusTime = t.coerceIn(earliest, latest)
        val v = visible.coerceIn(earliest, latest)
        if (v < viewStart) viewStart = halfHour(v)
        else if (v >= viewStart + SPAN_MS - STEP_MS) viewStart = halfHour(v) - STEP_MS
        viewStart = viewStart.coerceAtLeast(halfHour(earliest))
    }

    /** Fast-forward / rewind: page the whole grid. */
    fun page(forward: Boolean) {
        val delta = if (forward) SPAN_MS else -SPAN_MS
        viewStart = (viewStart + delta).coerceIn(halfHour(earliest), halfHour(latest))
        focusTime = maxOf(viewStart, if (halfHour(now) == viewStart) now else viewStart)
    }

    /** Touch: move the visible range by [steps] half hours. */
    fun shift(steps: Int) {
        viewStart = (viewStart + steps * STEP_MS).coerceIn(halfHour(earliest), halfHour(latest))
        focusTime = focusTime.coerceIn(viewStart, viewStart + SPAN_MS - 1)
    }

    fun backToNow() {
        viewStart = halfHour(now)
        focusTime = now
    }

    private val cache = HashMap<Long, List<ProgrammeEntity>>()

    suspend fun schedule(channelId: Long): List<ProgrammeEntity> =
        cache[channelId] ?: browse.schedule(channelId, earliest, latest).also {
            if (cache.size > 300) cache.clear()
            cache[channelId] = it
        }

    companion object {
        const val STEP_MS = 30 * 60 * 1000L
        const val SPAN_MS = 2 * 60 * 60 * 1000L
        private const val PAST_MS = 2 * 60 * 60 * 1000L
        private const val FUTURE_MS = 3 * 24 * 60 * 60 * 1000L
        private const val TICK_MS = 5 * 60 * 1000L
        private const val MIN_QUERY = 2

        fun halfHour(t: Long): Long = t - Math.floorMod(t, STEP_MS)
    }
}
