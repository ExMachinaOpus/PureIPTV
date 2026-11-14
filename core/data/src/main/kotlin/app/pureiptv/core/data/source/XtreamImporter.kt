package app.pureiptv.core.data.source

import app.pureiptv.core.data.db.ContentStore
import app.pureiptv.core.data.db.MovieEntity
import app.pureiptv.core.data.db.SeriesEntity
import app.pureiptv.core.data.source.ImportState.Phase
import app.pureiptv.core.model.ContentKind
import app.pureiptv.core.model.ItemKeys
import app.pureiptv.core.parsing.names.ChannelNameParser
import app.pureiptv.core.model.StreamHeaders
import app.pureiptv.core.parsing.names.TitleParser
import app.pureiptv.core.parsing.xtream.XtreamCategory
import app.pureiptv.core.parsing.xtream.XtreamCredentials
import app.pureiptv.core.parsing.xtream.XtreamLiveStream
import app.pureiptv.core.parsing.xtream.XtreamSeries
import app.pureiptv.core.parsing.xtream.XtreamVodStream
import kotlinx.coroutines.flow.toList
import javax.inject.Inject

/**
 * Imports live, movie and series lists from an Xtream panel. Episodes are not imported here:
 * they are fetched per series when the user opens it (`get_series_info`), like every major client.
 */
class XtreamImporter @Inject constructor(
    private val client: XtreamClient,
    private val content: ContentStore,
) {

    suspend fun import(
        sourceId: Long,
        credentials: XtreamCredentials,
        generation: Long,
        onProgress: (ImportState.Running) -> Unit,
    ): ImportCounts {
        val dao = content.importDao
        val writer = ImportWriter(dao, sourceId, generation)
        var counts = ImportCounts()

        onProgress(ImportState.Running(Phase.Connecting, counts))
        val liveCategories = categories(credentials, "get_live_categories")
        val vodCategories = categories(credentials, "get_vod_categories")
        val seriesCategories = categories(credentials, "get_series_categories")
        writer.addCategories(ContentKind.Live, liveCategories.toList())
        writer.addCategories(ContentKind.Movie, vodCategories.toList())
        writer.addCategories(ContentKind.Series, seriesCategories.toList())

        // Live TV
        onProgress(ImportState.Running(Phase.LiveTv, counts))
        client.list(credentials, "get_live_streams", XtreamLiveStream.serializer()).collect { chunk ->
            val entries = chunk.mapNotNull { s ->
                val id = s.streamId ?: return@mapNotNull null
                val name = s.name ?: return@mapNotNull null
                LiveEntry(
                    providerKey = id.toString(),
                    rawName = name,
                    categoryName = s.categoryId?.let(liveCategories::get),
                    categoryId = writer.categoryId(ContentKind.Live, s.categoryId),
                    tvgId = s.epgChannelId,
                    logo = s.icon,
                    url = null,
                    headers = StreamHeaders.None,
                    adult = s.isAdult == true,
                    catchupDays = if (s.tvArchive == true) s.tvArchiveDays ?: 0 else 0,
                )
            }
            counts = counts.copy(live = counts.live + writer.addLive(entries))
            onProgress(ImportState.Running(Phase.LiveTv, counts))
        }

        // Movies
        onProgress(ImportState.Running(Phase.Movies, counts))
        client.list(credentials, "get_vod_streams", XtreamVodStream.serializer()).collect { chunk ->
            val rows = chunk.mapNotNull { v ->
                val id = v.streamId ?: return@mapNotNull null
                val raw = v.name ?: return@mapNotNull null
                val categoryName = v.categoryId?.let(vodCategories::get)
                val title = TitleParser.parse(raw, categoryName)
                MovieEntity(
                    sourceId = sourceId,
                    categoryId = writer.categoryId(ContentKind.Movie, v.categoryId),
                    providerKey = id.toString(),
                    itemKey = ItemKeys.movie(sourceId, id.toString(), v.tmdbAny, title.language),
                    searchKey = ChannelNameParser.matchKey(title.title),
                    title = title.title,
                    year = TitleParser.yearFromField(v.year) ?: title.year,
                    language = title.language,
                    tmdbId = v.tmdbAny,
                    poster = v.icon,
                    rating = v.rating,
                    addedSec = v.added,
                    url = null,
                    containerExtension = v.containerExtension,
                    adult = v.isAdult == true,
                    generation = generation,
                )
            }
            dao.upsertMovies(sourceId, rows)
            counts = counts.copy(movies = counts.movies + rows.size)
            onProgress(ImportState.Running(Phase.Movies, counts))
        }

        // Series
        onProgress(ImportState.Running(Phase.Series, counts))
        client.list(credentials, "get_series", XtreamSeries.serializer()).collect { chunk ->
            val rows = chunk.mapNotNull { s ->
                val id = s.seriesId ?: return@mapNotNull null
                val raw = s.name ?: return@mapNotNull null
                val title = TitleParser.parse(raw, s.categoryId?.let(seriesCategories::get))
                SeriesEntity(
                    sourceId = sourceId,
                    categoryId = writer.categoryId(ContentKind.Series, s.categoryId),
                    providerKey = id.toString(),
                    itemKey = ItemKeys.series(sourceId, id.toString(), s.tmdbAny, title.language),
                    searchKey = ChannelNameParser.matchKey(title.title),
                    title = title.title,
                    year = TitleParser.yearFromField(s.release) ?: title.year,
                    language = title.language,
                    tmdbId = s.tmdbAny,
                    cover = s.cover,
                    plot = s.plot,
                    genre = s.genre,
                    rating = s.rating,
                    lastModifiedSec = s.lastModified,
                    generation = generation,
                )
            }
            dao.upsertSeries(sourceId, rows)
            counts = counts.copy(series = counts.series + rows.size)
            onProgress(ImportState.Running(Phase.Series, counts))
        }

        onProgress(ImportState.Running(Phase.Finishing, counts))
        writer.finish()
        return counts
    }

    /** provider category id -> name. Small lists, fine to hold. */
    private suspend fun categories(credentials: XtreamCredentials, action: String): Map<String, String> =
        client.list(credentials, action, XtreamCategory.serializer()).toList().flatten()
            .mapNotNull { c -> c.id?.let { id -> id to (c.name ?: id) } }
            .toMap(LinkedHashMap())
}
