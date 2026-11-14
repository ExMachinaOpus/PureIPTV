package app.pureiptv.core.data.source

import app.pureiptv.core.data.db.EpisodeEntity
import app.pureiptv.core.data.db.ContentStore
import app.pureiptv.core.data.db.MovieEntity
import app.pureiptv.core.data.db.SeriesEntity
import app.pureiptv.core.data.source.ImportState.Phase
import app.pureiptv.core.model.ContentKind
import app.pureiptv.core.model.ItemKeys
import app.pureiptv.core.model.NameFlag
import app.pureiptv.core.parsing.m3u.M3uClassifier
import app.pureiptv.core.parsing.m3u.M3uEntry
import app.pureiptv.core.parsing.m3u.M3uHeader
import app.pureiptv.core.parsing.m3u.M3uParser
import app.pureiptv.core.parsing.names.ChannelNameParser
import app.pureiptv.core.parsing.names.EpisodeNameParser
import app.pureiptv.core.parsing.names.TitleParser
import okhttp3.OkHttpClient
import javax.inject.Inject

/** Imports a plain M3U playlist while it downloads. One pass, constant memory per chunk. */
class M3uImporter @Inject constructor(
    private val http: OkHttpClient,
    private val content: ContentStore,
) {

    /** @return guide (XMLTV) URL advertised in the playlist header, if any. */
    suspend fun import(
        sourceId: Long,
        url: String,
        generation: Long,
        onProgress: (ImportState.Running) -> Unit,
    ): Pair<ImportCounts, String?> {
        val dao = content.importDao
        val writer = ImportWriter(dao, sourceId, generation)
        val seriesIds = HashMap<String, Long>()
        var counts = ImportCounts()
        var epgUrl: String? = null

        http.streamChunks(url) { response -> M3uParser.parse(response.body.charStream()) }.collect { chunk ->
            val live = ArrayList<LiveEntry>()
            val movies = ArrayList<MovieEntity>()
            val episodes = ArrayList<EpisodeEntity>()

            for (item in chunk) {
                if (item is M3uHeader) {
                    epgUrl = epgUrl ?: item.epgUrls.firstOrNull()
                    continue
                }
                val entry = item as M3uEntry
                if (entry.url.isBlank()) continue
                when (M3uClassifier.classify(entry)) {
                    ContentKind.Live -> live += liveEntry(writer, entry)
                    ContentKind.Movie -> movies += movie(writer, sourceId, generation, entry)
                    ContentKind.Series -> episode(dao, writer, sourceId, generation, entry, seriesIds)?.let(episodes::add)
                }
            }
            val addedLive = writer.addLive(live)
            dao.upsertMovies(sourceId, movies)
            if (episodes.isNotEmpty()) dao.insertEpisodes(episodes)

            counts = counts.copy(live = counts.live + addedLive, movies = counts.movies + movies.size, series = seriesIds.size)
            onProgress(ImportState.Running(Phase.LiveTv, counts))
        }

        onProgress(ImportState.Running(Phase.Finishing, counts))
        writer.finish()
        dao.deleteStaleEpisodes(sourceId, generation)
        return counts to epgUrl
    }

    // Provider keys are a hash of the URL, never the URL: they become part of keys stored in user.db
    // (favourites, progress, per-feed settings), and playlist URLs carry the provider login.
    private suspend fun liveEntry(writer: ImportWriter, e: M3uEntry) = LiveEntry(
        providerKey = ItemKeys.urlKey(e.url),
        rawName = e.name,
        categoryName = e.group,
        categoryId = writer.categoryId(ContentKind.Live, e.group),
        tvgId = e.tvgId,
        declaredLanguage = e.declaredLanguage,
        logo = e.tvgLogo,
        url = e.url,
        headers = e.headers,
        adult = false,
        catchupDays = (e.attributes["catchup-days"] ?: e.attributes["timeshift"])?.toIntOrNull() ?: 0,
    )

    private suspend fun movie(writer: ImportWriter, sourceId: Long, generation: Long, e: M3uEntry): MovieEntity {
        val title = TitleParser.parse(e.name, e.group, e.declaredLanguage)
        val providerKey = ItemKeys.urlKey(e.url)
        return MovieEntity(
            sourceId = sourceId,
            categoryId = writer.categoryId(ContentKind.Movie, e.group),
            providerKey = providerKey,
            itemKey = ItemKeys.movie(sourceId, providerKey, null, title.language),
            searchKey = ChannelNameParser.matchKey(title.title),
            title = title.title,
            year = title.year,
            language = title.language,
            tmdbId = null,
            poster = e.tvgLogo,
            rating = null,
            addedSec = null,
            url = e.url,
            containerExtension = null,
            adult = NameFlag.Adult in ChannelNameParser.parse(e.name, e.group).flags,
            generation = generation,
        )
    }

    /** Flat M3U episodes are grouped into series by their parsed title. */
    private suspend fun episode(
        dao: app.pureiptv.core.data.db.ImportDao,
        writer: ImportWriter,
        sourceId: Long,
        generation: Long,
        e: M3uEntry,
        seriesIds: HashMap<String, Long>,
    ): EpisodeEntity? {
        val parsed = EpisodeNameParser.parse(e.name) ?: return null
        val title = TitleParser.parse(parsed.seriesTitle, e.group, e.declaredLanguage)
        val key = ChannelNameParser.matchKey(title.title)
        val seriesId = seriesIds[key] ?: dao.upsertSeries(
            sourceId,
            listOf(
                SeriesEntity(
                    sourceId = sourceId,
                    categoryId = writer.categoryId(ContentKind.Series, e.group),
                    providerKey = key,
                    itemKey = ItemKeys.series(sourceId, key, null),
                    searchKey = key,
                    title = title.title,
                    year = title.year,
                    language = title.language,
                    tmdbId = null,
                    cover = e.tvgLogo,
                    plot = null,
                    genre = null,
                    rating = null,
                    lastModifiedSec = null,
                    generation = generation,
                ),
            ),
        ).getValue(key).also { seriesIds[key] = it }
        return EpisodeEntity(
            seriesId = seriesId,
            providerKey = ItemKeys.urlKey(e.url),
            season = parsed.season,
            episode = parsed.episode,
            title = parsed.episodeTitle,
            url = e.url,
            containerExtension = null,
            durationSec = e.durationSeconds.takeIf { it > 0 },
            plot = null,
            image = e.tvgLogo,
            generation = generation,
        )
    }
}
