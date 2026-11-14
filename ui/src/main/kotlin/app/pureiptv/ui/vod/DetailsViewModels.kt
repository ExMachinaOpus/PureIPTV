package app.pureiptv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pureiptv.core.data.db.EpisodeEntity
import app.pureiptv.core.data.db.MovieEntity
import app.pureiptv.core.data.db.SeriesEntity
import app.pureiptv.core.data.library.BrowseRepository
import app.pureiptv.core.data.library.MovieDetails
import app.pureiptv.core.data.library.PlaybackRepository
import app.pureiptv.core.data.library.Progress
import app.pureiptv.core.data.library.UserRepository
import app.pureiptv.core.data.library.continueAt
import app.pureiptv.core.model.ItemKeys
import app.pureiptv.core.model.SourceErrorKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the film page waits for the plot (a network call) before showing without it. */
private const val DETAILS_WAIT_MS = 1_500L

data class MovieUi(
    val movie: MovieEntity? = null,
    val details: MovieDetails? = null,
    val progress: Progress? = null,
    val favorite: Boolean = false,
    /** Everything the first frame shows is known: the page appears once, complete, with focus on Play. */
    val ready: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = MovieDetailsViewModel.Factory::class)
class MovieDetailsViewModel @AssistedInject constructor(
    @Assisted private val movieId: Long,
    browse: BrowseRepository,
    playback: PlaybackRepository,
    private val user: UserRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(movieId: Long): MovieDetailsViewModel
    }

    private val movie = MutableStateFlow<MovieEntity?>(null)
    private val details = MutableStateFlow<MovieDetails?>(null)
    /** Details came, failed, or took too long; the page then appears without waiting for them. */
    private val detailsSettled = MutableStateFlow(false)
    private val _missing = MutableStateFlow(false)
    /** The film is gone (e.g. removed by a refresh): the screen goes back. */
    val missing: StateFlow<Boolean> = _missing

    init {
        viewModelScope.launch {
            val m = browse.movie(movieId)
            movie.value = m
            _missing.value = m == null
            if (m == null) return@launch
            // The plot sits above the buttons: wait briefly for it so they don't move after appearing.
            launch {
                delay(DETAILS_WAIT_MS)
                detailsSettled.value = true
            }
            details.value = playback.movieDetails(movieId)
            detailsSettled.value = true
        }
    }

    private val key: Flow<String> = movie.filterNotNull().map { it.itemKey }

    val ui: StateFlow<MovieUi> = combine(
        movie,
        details,
        key.flatMapLatest { k -> user.progressWithPrefix(k).map { it[k]?.takeIf { p -> p.resumable } } },
        key.flatMapLatest { user.isFavorite(it) },
        detailsSettled,
    ) { m, d, p, fav, settled -> MovieUi(m, d, p, fav, ready = m != null && settled) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MovieUi())

    fun toggleFavorite() {
        val m = movie.value ?: return
        viewModelScope.launch { user.setFavorite(m.itemKey, !ui.value.favorite) }
    }

    /** Removes it from "Continue watching" (forgets progress and history). */
    fun forget() {
        val m = movie.value ?: return
        viewModelScope.launch { user.forget(m.itemKey) }
    }
}

data class SeriesUi(
    val series: SeriesEntity? = null,
    val seasons: List<Int> = emptyList(),
    val season: Int? = null,
    val episodes: List<EpisodeEntity> = emptyList(),
    val loading: Boolean = true,
    val error: SourceErrorKind? = null,
    val favorite: Boolean = false,
    /** The episode "Play"/"Resume" starts: last watched (if unfinished) or the one after it, else the first. */
    val upNext: EpisodeEntity? = null,
    val upNextResume: Boolean = false,
    val progress: Map<String, Progress> = emptyMap(),
    /**
     * Series, episodes (stored or fetched), progress and favourite are all known: the page appears
     * once, complete, with focus on Play/Resume. Nothing on it changes under the viewer afterwards.
     */
    val ready: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = SeriesDetailsViewModel.Factory::class)
class SeriesDetailsViewModel @AssistedInject constructor(
    @Assisted private val seriesId: Long,
    browse: BrowseRepository,
    private val playback: PlaybackRepository,
    private val user: UserRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(seriesId: Long): SeriesDetailsViewModel
    }

    private val series = MutableStateFlow<SeriesEntity?>(null)
    private val loading = MutableStateFlow(true)
    private val error = MutableStateFlow<SourceErrorKind?>(null)
    private val chosenSeason = MutableStateFlow<Int?>(null)
    private var shown = false
    private val _missing = MutableStateFlow(false)
    /** The series is gone (e.g. removed by a refresh): the screen goes back. */
    val missing: StateFlow<Boolean> = _missing

    init {
        viewModelScope.launch {
            val s = browse.series(seriesId)
            series.value = s
            _missing.value = s == null
            if (s != null) error.value = playback.loadEpisodes(seriesId)
            loading.value = false
        }
    }

    private val episodes = browse.episodes(seriesId)

    private val episodeProgress: Flow<Map<String, Progress>> = series.filterNotNull().flatMapLatest {
        user.progressWithPrefix(ItemKeys.episodePrefix(it.itemKey))
    }

    private val lastWatchedKey: Flow<String?> = series.filterNotNull().flatMapLatest { s ->
        user.recentKeys(ItemKeys.episodePrefix(s.itemKey), 300).map { keys -> keys.firstOrNull { ItemKeys.seasonAndEpisode(it, s.itemKey) != null } }
    }

    private val base = combine(series, episodes, loading, error) { s, eps, load, err ->
        SeriesUi(series = s, episodes = eps, loading = load && eps.isEmpty(), error = err.takeIf { eps.isEmpty() })
    }

    private val favorite = series.filterNotNull().flatMapLatest { user.isFavorite(it.itemKey) }

    // The user flows start once the series is known, so this emits only when all of them have a value.
    val ui: StateFlow<SeriesUi> = combine(base, chosenSeason, favorite, episodeProgress, lastWatchedKey) { b, chosen, fav, progress, last ->
        val seasons = b.episodes.map { it.season }.distinct().sorted()
        val s = b.series
        val keyOf = { e: EpisodeEntity -> s?.let { ItemKeys.episode(it.itemKey, e.season, e.episode) } }
        val lastIndex = b.episodes.indexOfFirst { keyOf(it) == last }
        val lastEp = b.episodes.getOrNull(lastIndex)
        val (upNext, resume) = when {
            lastEp == null -> b.episodes.firstOrNull() to false
            // Everything finished: offer the last one again.
            else -> continueAt(b.episodes, lastIndex, progress[keyOf(lastEp)])?.let { it.episode to it.resume } ?: (lastEp to false)
        }
        val season = chosen?.takeIf { it in seasons } ?: upNext?.season ?: seasons.firstOrNull()
        b.copy(
            seasons = seasons,
            season = season,
            episodes = b.episodes.filter { it.season == season },
            favorite = fav,
            upNext = upNext,
            upNextResume = resume,
            progress = progress,
            // Once shown, the page stays (a Retry that loads again must not remove the focused button).
            ready = (s != null && !b.loading).also { if (it) shown = true } || shown,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesUi())

    fun episodeKey(e: EpisodeEntity): String? = series.value?.let { ItemKeys.episode(it.itemKey, e.season, e.episode) }

    fun selectSeason(season: Int) {
        chosenSeason.value = season
    }

    fun toggleFavorite() {
        val s = series.value ?: return
        viewModelScope.launch { user.setFavorite(s.itemKey, !ui.value.favorite) }
    }

    /** Removes it from "Continue watching" (forgets all its episodes' progress and history). */
    fun forget() {
        val s = series.value ?: return
        viewModelScope.launch { user.forget(s.itemKey) }
    }

    fun retry() {
        viewModelScope.launch {
            loading.value = true
            error.value = playback.loadEpisodes(seriesId)
            loading.value = false
        }
    }
}
