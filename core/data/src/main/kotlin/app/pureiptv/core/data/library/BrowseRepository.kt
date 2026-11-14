package app.pureiptv.core.data.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import app.pureiptv.core.data.db.BrowseDao
import app.pureiptv.core.data.db.ChannelRow
import app.pureiptv.core.data.db.ContentStore
import app.pureiptv.core.data.db.EpisodeEntity
import app.pureiptv.core.data.db.GroupCount
import app.pureiptv.core.data.db.PosterRow
import app.pureiptv.core.data.db.ProgrammeEntity
import app.pureiptv.core.data.db.ProgrammeHit
import app.pureiptv.core.data.db.SourceDao
import app.pureiptv.core.data.db.SourceEntity
import app.pureiptv.core.model.ItemKeys
import app.pureiptv.core.model.NameFlag
import app.pureiptv.core.parsing.names.ChannelNameParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** What is visible right now: enabled sources (in priority order) and whether adult content shows. */
data class Visibility(val sources: List<SourceEntity>, val showAdult: Boolean) {
    val sourceIds: List<Long> = sources.map { it.id }
    val hideFlags: Int = if (showAdult) 0 else 1 shl NameFlag.Adult.ordinal
}

/** Left-hand groups in Live TV. */
sealed interface LiveGroup {
    data object Favorites : LiveGroup
    /** Every channel (the guide; Live TV browses by country instead). */
    data object All : LiveGroup
    data object Recent : LiveGroup
    /** Country code, `@language`, or "" for channels without a known region. */
    data class Region(val code: String) : LiveGroup
}

data class LiveFilter(val group: LiveGroup, val genre: String? = null)

/** Left-hand groups in Movies / Series. */
sealed interface VodGroup {
    data object ContinueWatching : VodGroup
    data object Favorites : VodGroup
    data object RecentlyAdded : VodGroup
    data class Category(val id: Long, val name: String, val sourceName: String?, val count: Int) : VodGroup
    /** Every title in one language ("All German movies"); code [NO_LANGUAGE] = language not stated. */
    data class Language(val code: String, val count: Int) : VodGroup
}

enum class VodKind { Movies, Series }

/** Order of a poster grid. Ordinals are used in SQL (BrowseDao): keep the order. */
enum class VodSort { RecentlyAdded, Title, Year, Rating }

/** Language code used for titles whose language the provider doesn't state. */
const val NO_LANGUAGE = ""

/**
 * Read model for browsing, merged over enabled sources. Combines user.db (sources, favorites,
 * settings) with content.db and follows content.db when it is rebuilt. Lists are paged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class BrowseRepository @Inject constructor(
    private val content: ContentStore,
    sourceDao: SourceDao,
    private val user: UserRepository,
) {
    val visibility: Flow<Visibility> = combine(sourceDao.observeAll(), user.showAdult) { all, adult ->
        Visibility(all.filter { it.enabled }, adult)
    }.distinctUntilChanged()

    private val dao: Flow<BrowseDao> = content.database.map { it.browseDao() }

    private fun <T> query(block: (BrowseDao, Visibility) -> Flow<T>): Flow<T> =
        combine(dao, visibility) { d, v -> d to v }.flatMapLatest { (d, v) -> block(d, v) }

    /** Guide-dependent lists ("now: ...") are re-queried every few minutes. */
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(GUIDE_TICK_MS)
        }
    }

    private fun <T> guideQuery(block: (BrowseDao, Visibility, Long) -> Flow<T>): Flow<T> =
        combine(dao, visibility, clock) { d, v, now -> Triple(d, v, now) }.flatMapLatest { (d, v, now) -> block(d, v, now) }

    private fun <T : Any> pager(factory: () -> androidx.paging.PagingSource<Int, T>): Flow<PagingData<T>> =
        Pager(PagingConfig(pageSize = 60, prefetchDistance = 60, enablePlaceholders = false), pagingSourceFactory = factory).flow

    // ---- Live --------------------------------------------------------------------------------

    fun regions(): Flow<List<GroupCount>> = query { d, v -> d.regions(v.sourceIds, v.hideFlags) }

    fun genres(region: String): Flow<List<GroupCount>> = query { d, v -> d.genres(region, v.sourceIds, v.hideFlags) }

    fun favoriteChannels(): Flow<List<ChannelRow>> = byKeys(user.favoriteKeys)

    fun recentChannels(): Flow<List<ChannelRow>> = byKeys(user.recentKeys(ItemKeys.CHANNEL_PREFIX))

    /** Channels for stable keys, in the order of [keys]. */
    private fun byKeys(keys: Flow<List<String>>): Flow<List<ChannelRow>> =
        keys.map { list -> list.filter { it.startsWith(ItemKeys.CHANNEL_PREFIX) }.map { it.removePrefix(ItemKeys.CHANNEL_PREFIX) } }
            .flatMapLatest { identityKeys ->
                if (identityKeys.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    guideQuery { d, v, now -> d.channelsByKey(identityKeys, v.sourceIds, v.hideFlags, now) }.map { rows ->
                        val byKey = rows.associateBy { it.identityKey }
                        identityKeys.mapNotNull(byKey::get)
                    }
                }
            }

    fun channels(filter: LiveFilter): Flow<PagingData<ChannelRow>> = when (val g = filter.group) {
        LiveGroup.Favorites -> favoriteChannels().map { PagingData.from(it) }
        LiveGroup.Recent -> recentChannels().map { PagingData.from(it) }
        LiveGroup.All -> query { d, v -> pager { d.allChannels(v.sourceIds, v.hideFlags) } }
        is LiveGroup.Region -> guideQuery { d, v, now -> pager { d.channels(g.code, filter.genre, v.sourceIds, v.hideFlags, now) } }
    }

    /** Channel ids in list order, for Up/Down zapping. */
    suspend fun channelIds(filter: LiveFilter): List<Long> = when (val g = filter.group) {
        LiveGroup.Favorites -> favoriteChannels().first().map { it.id }
        LiveGroup.Recent -> recentChannels().first().map { it.id }
        LiveGroup.All -> {
            val v = visibility.first()
            content.database.value.browseDao().allChannelIds(v.sourceIds, v.hideFlags)
        }
        is LiveGroup.Region -> {
            val v = visibility.first()
            content.database.value.browseDao().channelIds(g.code, filter.genre, v.sourceIds, v.hideFlags)
        }
    }

    // ---- Movies & series ---------------------------------------------------------------------

    fun hasContent(): Flow<ContentPresence> = combine(content.database, visibility) { db, v -> db to v }
        .flatMapLatest { (db, v) ->
            val lib = db.libraryDao()
            combine(lib.observeLiveStreamCounts(), lib.observeMovieCounts(), lib.observeSeriesCounts()) { l, m, s ->
                fun List<app.pureiptv.core.data.db.SourceCount>.any() = any { it.sourceId in v.sourceIds && it.count > 0 }
                ContentPresence(live = l.any(), movies = m.any(), series = s.any())
            }
        }.distinctUntilChanged()

    /**
     * Languages to browse by, most titles first, with [NO_LANGUAGE] ("not stated") last. Empty when
     * the provider states too little for a language filter to help (fewer than two languages with
     * a handful of titles each): browsing then works exactly as without languages.
     */
    fun vodLanguages(kind: VodKind): Flow<List<GroupCount>> = query { d, v ->
        if (kind == VodKind.Movies) d.movieLanguages(v.sourceIds, v.showAdult) else d.seriesLanguages(v.sourceIds)
    }.map { all ->
        val known = all.filter { it.key != NO_LANGUAGE }
        if (known.count { it.count >= MIN_TITLES_PER_LANGUAGE } < 2) emptyList() else known + all.filter { it.key == NO_LANGUAGE }
    }

    /**
     * Groups for the Movies/Series tab. [language] null = everything: special shelves, then all
     * provider categories. A language = "All <language>" plus the categories in that language.
     */
    fun vodGroups(kind: VodKind, language: String?): Flow<List<VodGroup>> =
        if (language == null) vodGroups(kind) else query { d, v ->
            val categories = if (kind == VodKind.Movies) d.movieCategories(v.sourceIds, v.showAdult) else d.seriesCategories(v.sourceIds, v.showAdult)
            combine(categories, vodLanguages(kind)) { cats, langs ->
                val multiSource = cats.map { it.sourceId }.distinct().size > 1
                val names = v.sources.associate { it.id to it.name }
                val order = v.sourceIds.withIndex().associate { it.value to it.index }
                buildList {
                    langs.firstOrNull { it.key == language }?.let { add(VodGroup.Language(language, it.count)) }
                    cats.filter { it.count > 0 && (it.language ?: NO_LANGUAGE) == language }
                        .sortedBy { order[it.sourceId] ?: Int.MAX_VALUE }
                        .forEach { add(VodGroup.Category(it.id, it.name, if (multiSource) names[it.sourceId] else null, it.count)) }
                }
            }
        }

    /** Groups for the Movies/Series tab: special shelves first, then provider categories. */
    fun vodGroups(kind: VodKind): Flow<List<VodGroup>> {
        val prefix = if (kind == VodKind.Movies) ItemKeys.MOVIE_PREFIX else ItemKeys.SERIES_PREFIX
        val continueKeys = continueKeys(kind)
        return query { d, v ->
            val categories = if (kind == VodKind.Movies) d.movieCategories(v.sourceIds, v.showAdult) else d.seriesCategories(v.sourceIds, v.showAdult)
            val recentCount = if (kind == VodKind.Movies) d.recentMovieCount(v.sourceIds) else d.recentSeriesCount(v.sourceIds)
            val favorites = user.favoriteKeys.map { keys -> keys.count { it.startsWith(prefix) } }
            combine(categories, recentCount, favorites, continueKeys) { cats, recent, favs, cont ->
                val multiSource = cats.map { it.sourceId }.distinct().size > 1
                val names = v.sources.associate { it.id to it.name }
                val order = v.sourceIds.withIndex().associate { it.value to it.index }
                buildList {
                    if (cont.isNotEmpty()) add(VodGroup.ContinueWatching)
                    if (favs > 0) add(VodGroup.Favorites)
                    if (recent > 0) add(VodGroup.RecentlyAdded)
                    cats.filter { it.count > 0 }
                        .sortedBy { order[it.sourceId] ?: Int.MAX_VALUE } // stable: keeps provider order inside a source
                        .forEach { add(VodGroup.Category(it.id, it.name, if (multiSource) names[it.sourceId] else null, it.count)) }
                }
            }
        }
    }

    private fun continueKeys(kind: VodKind): Flow<List<String>> = when (kind) {
        VodKind.Movies -> combine(user.recentKeys(ItemKeys.MOVIE_PREFIX), user.progressWithPrefix(ItemKeys.MOVIE_PREFIX)) { recent, progress ->
            recent.filter { progress[it]?.resumable == true }
        }
        VodKind.Series -> user.recentKeys(ItemKeys.SERIES_PREFIX)
    }

    /**
     * Titles of a group, filtered by [filter] (free text, matched anywhere in the cleaned title) and
     * ordered by [sort]. Provider order breaks ties.
     */
    fun vodItems(kind: VodKind, group: VodGroup, filter: String = "", sort: VodSort = VodSort.RecentlyAdded): Flow<PagingData<PosterRow>> {
        val q = ChannelNameParser.matchKey(filter)
        val o = sort.ordinal
        val movies = kind == VodKind.Movies
        return when (group) {
            VodGroup.ContinueWatching -> byPosterKeys(kind, continueKeys(kind), q, sort, keepOrder = true)
            VodGroup.Favorites -> byPosterKeys(kind, user.favoriteKeys, q, sort, keepOrder = false)
            VodGroup.RecentlyAdded -> query { d, v ->
                pager { if (movies) d.recentMovies(v.sourceIds, v.showAdult, q, o) else d.recentSeries(v.sourceIds, q, o) }
            }
            is VodGroup.Category -> query { d, v ->
                pager { if (movies) d.moviesIn(group.id, v.showAdult, q, o) else d.seriesIn(group.id, q, o) }
            }
            is VodGroup.Language -> query { d, v ->
                pager {
                    when {
                        movies && group.code == NO_LANGUAGE -> d.moviesWithoutLanguage(v.sourceIds, v.showAdult, q, o)
                        movies -> d.moviesInLanguage(group.code, v.sourceIds, v.showAdult, q, o)
                        group.code == NO_LANGUAGE -> d.seriesWithoutLanguage(v.sourceIds, q, o)
                        else -> d.seriesInLanguage(group.code, v.sourceIds, q, o)
                    }
                }
            }
        }
    }

    /** Small user lists (continue watching, my list): filtered and sorted in memory. */
    private fun byPosterKeys(
        kind: VodKind,
        keys: Flow<List<String>>,
        q: String,
        sort: VodSort,
        keepOrder: Boolean,
    ): Flow<PagingData<PosterRow>> {
        val prefix = if (kind == VodKind.Movies) ItemKeys.MOVIE_PREFIX else ItemKeys.SERIES_PREFIX
        return keys.map { list -> list.filter { it.startsWith(prefix) } }.flatMapLatest { k ->
            if (k.isEmpty()) {
                flowOf(PagingData.empty())
            } else {
                query { d, v -> if (kind == VodKind.Movies) d.moviesByKey(k, v.sourceIds) else d.seriesByKey(k, v.sourceIds) }
                    .map { rows ->
                        val byKey = rows.associateBy { it.itemKey }
                        var list = k.mapNotNull(byKey::get).filter { q.isEmpty() || q in ChannelNameParser.matchKey(it.title) }
                        // "Recently added" keeps the list's own order (most recent activity first).
                        if (!(keepOrder && sort == VodSort.RecentlyAdded)) list = list.sortedBy(sort)
                        PagingData.from(list)
                    }
            }
        }
    }

    private fun List<PosterRow>.sortedBy(sort: VodSort): List<PosterRow> = when (sort) {
        VodSort.RecentlyAdded -> this
        VodSort.Title -> sortedBy { ChannelNameParser.matchKey(it.title) }
        VodSort.Year -> sortedByDescending { it.year ?: 0 }
        VodSort.Rating -> sortedByDescending { it.rating ?: 0.0 }
    }

    /** What's on now and next on a channel (empty without guide data). */
    suspend fun upcoming(channelId: Long, limit: Int = 3): List<ProgrammeEntity> {
        val dao = content.database.value.browseDao()
        val now = System.currentTimeMillis()
        val guides = dao.epgKeysOf(channelId).map { dao.upcomingFor(it, now, limit * 3) }
        return pickGuide(guides, now).take(limit)
    }

    /** A channel's programmes overlapping [fromMs, untilMs), from one guide (the TV guide grid). */
    suspend fun schedule(channelId: Long, fromMs: Long, untilMs: Long): List<ProgrammeEntity> {
        val dao = content.database.value.browseDao()
        val guides = dao.epgKeysOf(channelId).map { dao.programmesFor(it, fromMs, untilMs) }
        return pickGuide(guides, System.currentTimeMillis())
    }

    /**
     * One guide only: a merged channel can have streams from several providers, each with its own
     * guide, and mixing them gives a "now" from one and a "next" from another that don't line up.
     * Prefers the first guide (best stream first) that covers [now]. Guides also overlap themselves
     * sometimes: the result is a clean sequence.
     */
    private fun pickGuide(guides: List<List<ProgrammeEntity>>, now: Long): List<ProgrammeEntity> {
        val nonEmpty = guides.filter { it.isNotEmpty() }
        val chosen = nonEmpty.firstOrNull { list -> list.any { it.startMs <= now && it.endMs > now } } ?: nonEmpty.firstOrNull() ?: return emptyList()
        val result = ArrayList<ProgrammeEntity>(chosen.size)
        for (p in chosen) {
            if (result.isEmpty() || p.startMs >= result.last().endMs - OVERLAP_TOLERANCE_MS) result += p
        }
        return result
    }

    /** Guide search within [group] (all channels, favourites, recent, a country): on now first, then by start time. */
    suspend fun searchGuide(text: String, group: LiveGroup = LiveGroup.All, limit: Int = 120): List<ProgrammeHit> {
        val q = ChannelNameParser.matchKey(text)
        if (q.length < 2) return emptyList()
        val v = visibility.first()
        val dao = content.database.value.browseDao()
        val now = System.currentTimeMillis()
        fun keys(list: List<String>) = list.filter { it.startsWith(ItemKeys.CHANNEL_PREFIX) }.map { it.removePrefix(ItemKeys.CHANNEL_PREFIX) }
        return when (group) {
            LiveGroup.All -> dao.searchProgrammes(q, v.sourceIds, v.hideFlags, now, limit)
            is LiveGroup.Region -> dao.searchProgrammes(q, v.sourceIds, v.hideFlags, now, limit, region = group.code)
            LiveGroup.Favorites -> dao.searchProgrammes(q, v.sourceIds, v.hideFlags, now, limit, onlyKeys = true, keys = keys(user.favoriteKeys.first()))
            LiveGroup.Recent -> dao.searchProgrammes(
                q, v.sourceIds, v.hideFlags, now, limit, onlyKeys = true, keys = keys(user.recentKeys(ItemKeys.CHANNEL_PREFIX).first()),
            )
        }
    }

    suspend fun movie(id: Long) = content.database.value.browseDao().movie(id)

    suspend fun series(id: Long) = content.database.value.browseDao().series(id)

    fun episodes(seriesId: Long): Flow<List<EpisodeEntity>> = dao.flatMapLatest { it.episodes(seriesId) }

    // ---- Search ------------------------------------------------------------------------------

    suspend fun search(text: String): SearchResults {
        val q = ChannelNameParser.matchKey(text)
        if (q.isEmpty()) return SearchResults()
        val v = visibility.first()
        val d = content.database.value.browseDao()
        val now = System.currentTimeMillis()
        val started = android.os.SystemClock.elapsedRealtime()
        return SearchResults(
            channels = d.searchChannels(q, v.sourceIds, v.hideFlags, 40, now),
            programmes = if (q.length < 2) emptyList() else d.searchProgrammes(q, v.sourceIds, v.hideFlags, now, 60),
            // The device language's versions first.
            movies = d.searchMovies(q, v.sourceIds, v.showAdult, 40, java.util.Locale.getDefault().language),
            series = d.searchSeries(q, v.sourceIds, 40, java.util.Locale.getDefault().language),
        ).also {
            // Counts and time only (no query text).
            android.util.Log.i(
                "PureSearch",
                "search channels=${it.channels.size} programmes=${it.programmes.size} movies=${it.movies.size} " +
                    "series=${it.series.size} in ${android.os.SystemClock.elapsedRealtime() - started} ms",
            )
        }
    }
}

private const val GUIDE_TICK_MS = 5 * 60 * 1000L
private const val OVERLAP_TOLERANCE_MS = 2 * 60 * 1000L
private const val MIN_TITLES_PER_LANGUAGE = 10

data class ContentPresence(val live: Boolean = false, val movies: Boolean = false, val series: Boolean = false)

data class SearchResults(
    val channels: List<ChannelRow> = emptyList(),
    /** What's on now and later in the guide. */
    val programmes: List<ProgrammeHit> = emptyList(),
    val movies: List<PosterRow> = emptyList(),
    val series: List<PosterRow> = emptyList(),
) {
    val isEmpty: Boolean get() = channels.isEmpty() && programmes.isEmpty() && movies.isEmpty() && series.isEmpty()
}
