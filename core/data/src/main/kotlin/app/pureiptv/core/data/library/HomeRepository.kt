package app.pureiptv.core.data.library

import app.pureiptv.core.data.db.ChannelRow
import app.pureiptv.core.data.db.ContentStore
import app.pureiptv.core.data.db.PosterRow
import app.pureiptv.core.data.di.AppScope
import app.pureiptv.core.model.ItemKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class ContinueItem(
    /** Movie or series key (what "Remove" forgets). */
    val itemKey: String,
    val title: String,
    val image: String?,
    /** "S2 E5" for series, the year for movies. */
    val subtitle: String?,
    /** Audio language (ISO 639-1) when known. */
    val language: String? = null,
    val rating: Double? = null,
    /** Progress of what plays next (0 for a next episode not started yet). */
    val progress: Float,
    /** For "Show details". */
    val movieId: Long? = null,
    val seriesId: Long? = null,
)

data class HomeFeed(
    val continueWatching: List<ContinueItem> = emptyList(),
    val favouriteChannels: List<ChannelRow> = emptyList(),
    val myMovies: List<PosterRow> = emptyList(),
    val mySeries: List<PosterRow> = emptyList(),
) {
    val isEmpty: Boolean
        get() = continueWatching.isEmpty() && favouriteChannels.isEmpty() && myMovies.isEmpty() && mySeries.isEmpty()
}

/**
 * The Home tab: what the user is watching and their lists, merged over enabled sources.
 *
 * Continue watching rules:
 * - a movie shows while it is resumable (watched > 30 s, < 95 %); finishing it removes it;
 * - a series shows the episode it continues with ([continueAt]): the last watched one unless that was
 *   finished, then the next. Not shown when everything is finished, or when only a few seconds of
 *   one episode were ever seen;
 * - "Remove" forgets progress and history ([UserRepository.forget]);
 * - Xtream episodes are stored on demand and every refresh drops them, so a series without stored
 *   episodes still shows (from the watched episode's key) while they are fetched in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class HomeRepository @Inject constructor(
    private val content: ContentStore,
    private val browse: BrowseRepository,
    private val user: UserRepository,
    private val playback: PlaybackRepository,
    @AppScope private val appScope: CoroutineScope,
) {
    /** Series whose episodes were asked for. Row ids change on refresh, so a refresh asks again. */
    private val episodeFetches = ConcurrentHashMap.newKeySet<Long>()

    /** Bumped when episodes arrive, so Continue watching is worked out again. */
    private val episodesFetched = MutableStateFlow(0)

    fun feed(): Flow<HomeFeed> = combine(
        continueWatching(),
        browse.favoriteChannels(),
        myList(movies = true),
        myList(movies = false),
    ) { cont, favChannels, movies, series -> HomeFeed(cont, favChannels, movies, series) }
        .distinctUntilChanged()

    /** Stores episodes of the copies of a series, until the copy with the watched episode turns up. */
    private fun fetchEpisodes(row: PosterRow, season: Int, episode: Int) {
        if (!episodeFetches.add(row.id)) return
        appScope.launch {
            // Runs on the app scope: anything thrown here would close the app. A failure (provider
            // down, content.db swapped mid-fetch) is tried again the next time the feed changes.
            try {
                val dao = content.database.value.browseDao()
                val sources = browse.visibility.first().sourceIds
                for (id in dao.seriesCopyIds(row.itemKey, sources)) {
                    if (dao.episodeCount(id) > 0) continue
                    if (playback.loadEpisodes(id) != null) {
                        episodeFetches.remove(row.id)
                        return@launch
                    }
                    if (dao.seriesCopyWith(row.itemKey, sources, season, episode) != null) break
                }
                episodesFetched.update { it + 1 }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                episodeFetches.remove(row.id)
            }
        }
    }

    private fun myList(movies: Boolean): Flow<List<PosterRow>> {
        val prefix = if (movies) ItemKeys.MOVIE_PREFIX else ItemKeys.SERIES_PREFIX
        return user.favoriteKeys.map { keys -> keys.filter { it.startsWith(prefix) }.reversed() } // newest first
            .flatMapLatest { keys -> postersByKeys(keys, movies) }
    }

    private fun postersByKeys(keys: List<String>, movies: Boolean): Flow<List<PosterRow>> =
        if (keys.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(content.database, browse.visibility) { db, v -> db to v }.flatMapLatest { (db, v) ->
                val dao = db.browseDao()
                (if (movies) dao.moviesByKey(keys, v.sourceIds) else dao.seriesByKey(keys, v.sourceIds)).map { rows ->
                    val byKey = rows.associateBy { it.itemKey }
                    keys.mapNotNull(byKey::get)
                }
            }
        }

    private fun continueWatching(): Flow<List<ContinueItem>> = combine(
        user.recentTitleKeys(),
        user.progressWithPrefix(ItemKeys.MOVIE_PREFIX),
        user.progressWithPrefix(ItemKeys.EPISODE_PREFIX),
        user.recentKeys(ItemKeys.EPISODE_PREFIX, 300),
    ) { titles, movieProgress, episodeProgress, recentEpisodes -> Inputs(titles, movieProgress, episodeProgress, recentEpisodes) }
        .flatMapLatest { input ->
            val movieKeys = input.titles.filter { it.startsWith(ItemKeys.MOVIE_PREFIX) && input.movieProgress[it]?.resumable == true }
            val seriesKeys = input.titles.filter { it.startsWith(ItemKeys.SERIES_PREFIX) }
            combine(postersByKeys(movieKeys, movies = true), postersByKeys(seriesKeys, movies = false), episodesFetched) { m, s, _ -> m to s }
                .mapLatest { (movies, series) ->
                    val items = HashMap<String, ContinueItem>()
                    movies.forEach { row ->
                        items[row.itemKey] = ContinueItem(
                            itemKey = row.itemKey,
                            title = row.title,
                            image = row.image,
                            subtitle = row.year?.toString(),
                            language = row.language,
                            rating = row.rating,
                            progress = input.movieProgress[row.itemKey]?.fraction ?: 0f,
                            movieId = row.id,
                        )
                    }
                    series.forEach { row -> seriesItem(row, input)?.let { items[row.itemKey] = it } }
                    input.titles.mapNotNull(items::get)
                }
        }

    private data class Inputs(
        val titles: List<String>,
        val movieProgress: Map<String, Progress>,
        val episodeProgress: Map<String, Progress>,
        val recentEpisodes: List<String>,
    )

    /** The episode to continue with, or null when the series is done (or was only sampled). */
    private suspend fun seriesItem(row: PosterRow, input: Inputs): ContinueItem? {
        val lastKey = input.recentEpisodes.firstOrNull { ItemKeys.seasonAndEpisode(it, row.itemKey) != null } ?: return null
        val (season, number) = ItemKeys.seasonAndEpisode(lastKey, row.itemKey) ?: return null
        val last = input.episodeProgress[lastKey]
        // Opened for a few seconds and nothing else of it seen: not "watching". Leaving an episode
        // early in a series you are following is still watching it.
        val watched = input.episodeProgress.any { (key, p) -> (p.resumable || p.finished) && ItemKeys.seasonAndEpisode(key, row.itemKey) != null }
        if (!watched) return null
        val dao = content.database.value.browseDao()
        val sources = browse.visibility.first().sourceIds
        // A series can have several rows under one key, some with only one season; any of them may be
        // in [row], so find the one that holds the episode that was watched.
        val copy = dao.seriesCopyWith(row.itemKey, sources, season, number)
        val (seriesId, label, progress) = if (copy == null) {
            fetchEpisodes(row, season, number)
            // Which episode comes after a finished one isn't known yet: no label until it is.
            if (last?.finished == true) Triple(row.id, null, 0f) else Triple(row.id, "S$season E$number", last?.fraction ?: 0f)
        } else {
            val episodes = dao.episodeList(copy)
            val index = episodes.indexOfFirst { it.season == season && it.episode == number }
            val next = continueAt(episodes, index, last) ?: return null
            Triple(copy, "S${next.episode.season} E${next.episode.episode}", if (next.resume) last?.fraction ?: 0f else 0f)
        }
        return ContinueItem(
            itemKey = row.itemKey,
            title = row.title,
            image = row.image,
            subtitle = label,
            language = row.language,
            rating = row.rating,
            progress = progress,
            seriesId = seriesId,
        )
    }
}
