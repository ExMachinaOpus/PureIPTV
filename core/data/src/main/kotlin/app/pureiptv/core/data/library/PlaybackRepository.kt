package app.pureiptv.core.data.library

import app.pureiptv.core.data.credentials.CredentialStore
import app.pureiptv.core.data.credentials.SourceSecret
import app.pureiptv.core.data.db.ContentStore
import app.pureiptv.core.data.db.EpisodeEntity
import app.pureiptv.core.data.db.SeriesEntity
import app.pureiptv.core.data.db.SourceEntity
import app.pureiptv.core.data.source.SourceException
import app.pureiptv.core.data.source.XtreamClient
import app.pureiptv.core.model.ItemKeys
import app.pureiptv.core.model.NameFlag
import app.pureiptv.core.model.Quality
import app.pureiptv.core.model.SourceErrorKind
import app.pureiptv.core.model.StreamHeaders
import app.pureiptv.core.parsing.xtream.XtreamCredentials
import app.pureiptv.core.parsing.xtream.XtreamUrls
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** One concrete way to play something. The player tries [PlayItem.streams] in order (failover). */
data class PlayableStream(
    val url: String,
    val headers: StreamHeaders,
    val sourceName: String,
    val quality: Quality,
    /**
     * Stable key of this feed (source + provider id), for per-feed settings such as zoom: copies of
     * one channel can be encoded differently. The TS and HLS forms of one stream share it.
     */
    val streamKey: String,
    /** What tells this copy apart from the others: the provider's stream name or category. */
    val label: String = "",
)

/**
 * One version of what plays: another copy, quality or source of the same channel, film or episode.
 * The viewer cycles through them in the player; the last one picked is remembered per title.
 */
data class PlayVersion(
    /** [PlayableStream.streamKey] of this version's stream(s). */
    val streamKey: String,
    val label: String,
    /** Remembered for the title: the stream key, or for episodes the series copy (it holds every episode). */
    val choiceKey: String = streamKey,
    /** Episodes: this episode in that copy of the series (a different row, played by loading it). */
    val episodeId: Long? = null,
)

data class PlayItem(
    /** Stable key for favorites/progress/history. */
    val itemKey: String,
    val title: String,
    val subtitle: String?,
    val artwork: String?,
    val isLive: Boolean,
    val streams: List<PlayableStream>,
    /** Where to resume (0 = start). */
    val resumeMs: Long = 0,
    /** Also recorded in history when this plays (the series of an episode). */
    val parentKey: String? = null,
    /** Next episode, for "play next" (may be the first of the next season). */
    val nextEpisodeId: Long? = null,
    /** "S2 E1" for the next episode. */
    val nextEpisodeLabel: String? = null,
    /** The versions to cycle through, in order (empty or one: nothing to choose). */
    val versions: List<PlayVersion> = emptyList(),
)

data class MovieDetails(
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val durationSecs: Long?,
    val backdrop: String?,
)

/**
 * Turns content rows into playable streams: builds Xtream URLs from stored credentials (never
 * stored in content.db), orders alternatives by source priority and quality, and loads Xtream
 * episodes / movie details on demand.
 */
@Singleton
class PlaybackRepository @Inject constructor(
    private val content: ContentStore,
    private val browse: BrowseRepository,
    private val credentials: CredentialStore,
    private val xtream: XtreamClient,
    private val user: UserRepository,
) {
    private val dao get() = content.database.value.browseDao()

    /**
     * All streams of a merged channel across enabled sources, best first: source priority, then
     * primary before backup, then quality (HEVC last when the device can't decode it well).
     */
    suspend fun channel(channelId: Long, hevcSupported: Boolean = true): PlayItem? {
        val channel = dao.channel(channelId, System.currentTimeMillis()) ?: return null
        val v = browse.visibility.first()
        val order = v.sourceIds.withIndex().associate { it.value to it.index }
        val sources = v.sources.associateBy { it.id }
        val backup = 1 shl NameFlag.Backup.ordinal
        val hevc = 1 shl NameFlag.Hevc.ordinal
        val rows = dao.streamsOf(channelId, v.sourceIds)
        val manySources = rows.map { it.sourceId }.distinct().size > 1
        val best = rows
            .sortedWith(
                compareBy(
                    { order[it.sourceId] ?: Int.MAX_VALUE },
                    { if (!hevcSupported && it.flags and hevc != 0) 1 else 0 },
                    { if (it.flags and backup != 0) 1 else 0 },
                    { -it.quality },
                    { it.sortOrder },
                ),
            )
            .flatMap { s ->
                val source = sources[s.sourceId] ?: return@flatMap emptyList()
                val headers = StreamHeaders(s.userAgent, s.referrer)
                val quality = Quality.entries.getOrElse(s.quality) { Quality.Unknown }
                // The provider's own name tells its copies apart ("SVT1 FHD", "SVT1 HEVC").
                val label = listOfNotNull(s.rawName.trim(), source.name.takeIf { manySources }).joinToString(" · ")
                val urls = when {
                    s.url != null -> listOf(s.url)
                    else -> {
                        val xtream = xtreamUrls(source) ?: return@flatMap emptyList()
                        val id = s.providerKey.toLongOrNull() ?: return@flatMap emptyList()
                        // MPEG-TS first: every panel serves it. Many advertise HLS but answer its
                        // segments with 403, so HLS is only the fallback.
                        listOfNotNull(xtream.live(id, "ts"), if (source.liveFormat == "m3u8") xtream.live(id, "m3u8") else null)
                    }
                }
                urls.map { PlayableStream(it, headers, source.name, quality, "stream:${s.sourceId}:${s.providerKey}", label) }
            }
        return PlayItem(
            itemKey = ItemKeys.channel(channel.identityKey),
            title = channel.name,
            subtitle = null,
            artwork = channel.logo,
            isLive = true,
            // Versions keep the best-first order so cycling through them is always the same.
            streams = preferredFirst(ItemKeys.channel(channel.identityKey), best),
            versions = versionsOf(best),
        )
    }

    suspend fun movie(movieId: Long): PlayItem? {
        val movie = dao.movie(movieId) ?: return null
        val v = browse.visibility.first()
        val sources = v.sources.associateBy { it.id }
        val order = v.sourceIds.withIndex().associate { it.value to it.index }
        // The chosen copy first, then the same title from other sources as fallbacks.
        val copies = listOf(movie) + dao.movieCopies(movie.itemKey, v.sourceIds)
            .filter { it.id != movie.id }
            .sortedBy { order[it.sourceId] ?: Int.MAX_VALUE }
        val manySources = copies.map { it.sourceId }.distinct().size > 1
        val all = copies.mapNotNull { m ->
            val source = sources[m.sourceId] ?: return@mapNotNull null
            val url = m.url ?: xtreamUrls(source)?.movie(m.providerKey.toLongOrNull() ?: return@mapNotNull null, m.containerExtension)
                ?: return@mapNotNull null
            PlayableStream(url, StreamHeaders.None, source.name, Quality.Unknown, "stream:${m.sourceId}:${m.providerKey}", copyLabel(m.categoryId, source.name, manySources))
        }
        return PlayItem(
            itemKey = movie.itemKey,
            title = movie.title,
            subtitle = movie.year?.toString(),
            artwork = movie.poster,
            isLive = false,
            streams = preferredFirst(movie.itemKey, all),
            resumeMs = user.progress(movie.itemKey)?.takeIf { it.resumable }?.positionMs ?: 0,
            versions = versionsOf(all.sortedBy { it.streamKey }),
        )
    }

    /**
     * An episode, in the copy of the series the viewer picked before when that copy has it ([preferred]
     * false: exactly this one, e.g. when the viewer just switched to it).
     */
    suspend fun episode(episodeId: Long, preferred: Boolean = true): PlayItem? {
        var episode = dao.episode(episodeId) ?: return null
        var series = dao.series(episode.seriesId) ?: return null
        if (preferred) {
            user.versionChoice(series.itemKey)?.takeIf { it != copyKey(series) }?.let { choice ->
                val copy = dao.seriesCopies(series.itemKey, browse.visibility.first().sourceIds).firstOrNull { copyKey(it) == choice }
                val other = copy?.let { episodeIn(it, episode.season, episode.episode, fetch = true) }
                if (copy != null && other != null) {
                    series = copy
                    episode = other
                }
            }
        }
        val source = browse.visibility.first().sources.firstOrNull { it.id == series.sourceId } ?: return null
        val url = episode.url ?: xtreamUrls(source)?.episode(episode.providerKey, episode.containerExtension) ?: return null
        val key = ItemKeys.episode(series.itemKey, episode.season, episode.episode)
        val all = dao.episodes(series.id).first()
        val next = all.getOrNull(all.indexOfFirst { it.id == episode.id } + 1)
        return PlayItem(
            itemKey = key,
            title = series.title,
            subtitle = episodeLabel(episode, series.title),
            artwork = episode.image ?: series.cover,
            isLive = false,
            streams = listOf(PlayableStream(url, StreamHeaders.None, source.name, Quality.Unknown, episodeStreamKey(series, episode))),
            resumeMs = user.progress(key)?.takeIf { it.resumable }?.positionMs ?: 0,
            parentKey = series.itemKey,
            nextEpisodeId = next?.id,
            nextEpisodeLabel = next?.let { "S${it.season} E${it.episode}" },
            versions = episodeVersions(episode.id, fetch = false),
        )
    }

    /**
     * The same episode in every copy of its series (other qualities and sources), in a fixed order.
     * [fetch]: also load the episode lists of copies not opened yet (Xtream, one request per copy);
     * without it only copies whose episodes are stored count.
     */
    suspend fun episodeVersions(episodeId: Long, fetch: Boolean): List<PlayVersion> {
        val episode = dao.episode(episodeId) ?: return emptyList()
        val series = dao.series(episode.seriesId) ?: return emptyList()
        val v = browse.visibility.first()
        val sources = v.sources.associateBy { it.id }
        val copies = dao.seriesCopies(series.itemKey, v.sourceIds).filter { it.sourceId in sources }
        val manySources = copies.map { it.sourceId }.distinct().size > 1
        val found = copies.mapNotNull { copy ->
            val e = if (copy.id == series.id) episode else episodeIn(copy, episode.season, episode.episode, fetch) ?: return@mapNotNull null
            PlayVersion(
                streamKey = episodeStreamKey(copy, e),
                label = copyLabel(copy.categoryId, sources.getValue(copy.sourceId).name, manySources),
                choiceKey = copyKey(copy),
                episodeId = e.id,
            )
        }
        return found.zip(uniqueLabels(found.map { it.label })) { version, label -> version.copy(label = label) }
    }

    /** Episode [season]/[episode] in [copy]; loads that copy's episodes first if allowed and needed. */
    private suspend fun episodeIn(copy: SeriesEntity, season: Int, episode: Int, fetch: Boolean): EpisodeEntity? {
        dao.episodeAt(copy.id, season, episode)?.let { return it }
        if (!fetch || dao.episodeCount(copy.id) > 0) return null
        loadEpisodes(copy.id)
        return dao.episodeAt(copy.id, season, episode)
    }

    /** Puts the version the viewer picked for [itemKey] first; the others stay behind it as fallbacks. */
    private suspend fun preferredFirst(itemKey: String, streams: List<PlayableStream>): List<PlayableStream> {
        val choice = user.versionChoice(itemKey) ?: return streams
        return streams.sortedBy { if (it.streamKey == choice) 0 else 1 }
    }

    private fun versionsOf(streams: List<PlayableStream>): List<PlayVersion> {
        val distinct = streams.distinctBy { it.streamKey }
        return distinct.zip(uniqueLabels(distinct.map { it.label })) { s, label -> PlayVersion(s.streamKey, label) }
    }

    /** A film's or series' copy: its category (where providers put "4K", "Multi-sub" and the like), and the source when there are several. */
    private suspend fun copyLabel(categoryId: Long?, sourceName: String, withSource: Boolean): String {
        val category = categoryId?.let { dao.categoryName(it) }?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(category, sourceName.takeIf { withSource || category == null }).joinToString(" · ")
    }

    /**
     * Makes sure episodes of an Xtream series are stored (they are fetched per series, on demand).
     * M3U series already have their episodes from the playlist.
     */
    suspend fun loadEpisodes(seriesId: Long): SourceErrorKind? {
        val series = dao.series(seriesId) ?: return null
        val source = browse.visibility.first().sources.firstOrNull { it.id == series.sourceId } ?: return null
        val secret = credentials.get(source.id) as? SourceSecret.Xtream ?: return null
        val info = try {
            xtream.seriesInfo(XtreamCredentials(secret.serverUrl, secret.username, secret.password), series.providerKey)
        } catch (e: SourceException) {
            return e.kind
        }
        val rows = info.episodesBySeason().flatMap { (season, episodes) ->
            episodes.mapIndexedNotNull { i, e ->
                val id = e.id ?: return@mapIndexedNotNull null
                val details = e.details
                EpisodeEntity(
                    seriesId = series.id,
                    providerKey = id,
                    season = season,
                    episode = e.episodeNum ?: (i + 1),
                    title = e.title,
                    url = null,
                    containerExtension = e.containerExtension,
                    durationSec = details.durationSecs,
                    plot = details.plot,
                    image = details.image,
                )
            }
        }
        if (rows.isNotEmpty()) content.importDao.insertEpisodesOf(series.id, rows)
        return null
    }

    /** Extra details for a movie (Xtream only; M3U has nothing beyond the title). */
    suspend fun movieDetails(movieId: Long): MovieDetails? {
        val movie = dao.movie(movieId) ?: return null
        val secret = credentials.get(movie.sourceId) as? SourceSecret.Xtream ?: return null
        val details = runCatching {
            xtream.vodInfo(XtreamCredentials(secret.serverUrl, secret.username, secret.password), movie.providerKey).details
        }.getOrNull() ?: return null
        return MovieDetails(
            plot = details.plot ?: details.description,
            genre = details.genre,
            cast = details.cast,
            director = details.director,
            durationSecs = details.durationSecs,
            backdrop = details.backdrops.firstOrNull(),
        )
    }

    private fun copyKey(series: SeriesEntity) = "series:${series.sourceId}:${series.providerKey}"

    private fun episodeStreamKey(series: SeriesEntity, episode: EpisodeEntity) = "stream:${series.sourceId}:episode:${episode.providerKey}"

    private suspend fun xtreamUrls(source: SourceEntity): XtreamUrls? =
        (credentials.get(source.id) as? SourceSecret.Xtream)?.let { XtreamUrls(XtreamCredentials(it.serverUrl, it.username, it.password)) }

    companion object {
        /** Labels that read the same get a number ("Films (2)"), so the viewer can tell the versions apart. */
        internal fun uniqueLabels(labels: List<String>): List<String> {
            val seen = HashMap<String, Int>()
            val repeated = labels.groupingBy { it }.eachCount()
            return labels.map { label ->
                if ((repeated[label] ?: 0) < 2) return@map label
                val n = (seen[label] ?: 0) + 1
                seen[label] = n
                "$label ($n)"
            }
        }

        fun episodeLabel(e: EpisodeEntity, seriesTitle: String? = null): String {
            val code = "S%02d E%02d".format(e.season, e.episode)
            return cleanEpisodeTitle(e.title, seriesTitle)?.let { "$code · $it" } ?: code
        }

        private val episodeCode = Regex("""\bS\d{1,2}\s*[ .x-]?\s*E\d{1,3}\b|\b\d{1,2}x\d{1,3}\b""", RegexOption.IGNORE_CASE)

        /** Providers often title episodes "Show - S01E02 - Name": keep just "Name" (or nothing). */
        internal fun cleanEpisodeTitle(title: String?, seriesTitle: String?): String? {
            var t = title?.trim() ?: return null
            if (seriesTitle != null && t.startsWith(seriesTitle, ignoreCase = true)) t = t.substring(seriesTitle.length)
            t = t.replace(episodeCode, " ").trim().trim('-', '–', ':', '|', '.', ' ')
            return t.takeIf { it.isNotBlank() && !it.equals("episode", ignoreCase = true) }
        }
    }
}
