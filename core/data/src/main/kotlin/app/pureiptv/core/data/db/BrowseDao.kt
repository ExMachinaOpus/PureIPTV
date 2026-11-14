package app.pureiptv.core.data.db

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A group (region, genre) and how many visible channels it has. */
data class GroupCount(val key: String, val count: Int)

/** A merged live channel as shown in lists. */
data class ChannelRow(
    val id: Long,
    @ColumnInfo(name = "identity_key") val identityKey: String,
    @ColumnInfo(name = "display_name") val name: String,
    val logo: String?,
    @ColumnInfo(name = "tvg_id") val tvgId: String?,
    val flags: Int,
    @ColumnInfo(name = "best_quality") val bestQuality: Int,
    /** Country / `@language` / "" (see ChannelEntity.region). */
    val region: String,
    /** What's on now, from the guide (null without guide data). */
    @ColumnInfo(name = "now_title") val nowTitle: String?,
)

/** A movie or series tile. */
data class PosterRow(
    val id: Long,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "item_key") val itemKey: String,
    val title: String,
    val year: Int?,
    val image: String?,
    val rating: Double?,
    /** Audio language (ISO 639-1) when known. */
    val language: String?,
)

/** A guide search result: a programme and the channel it is on. */
data class ProgrammeHit(
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "channel_name") val channelName: String,
    @ColumnInfo(name = "channel_logo") val channelLogo: String?,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val episode: String?,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
)

data class CategoryRow(
    val id: Long,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    val name: String,
    val count: Int,
    /** Language the category states ("DE - Kinder"), if any. */
    val language: String?,
)

/**
 * Read queries for browsing (content.db). Visibility rules are parameters because sources and
 * settings live in user.db: `src` = enabled source ids, `hide` = NameFlag bits to hide (adult).
 * Large lists are only ever returned as [PagingSource]s.
 */
@Dao
interface BrowseDao {

    // ---- Live --------------------------------------------------------------------------------

    @Query(
        """
        SELECT c.region AS `key`, COUNT(*) AS count FROM channel c
        WHERE (c.flags & :hide) = 0
          AND EXISTS (SELECT 1 FROM live_stream s WHERE s.channel_id = c.id AND s.source_id IN (:src))
        GROUP BY c.region ORDER BY count DESC
        """,
    )
    fun regions(src: List<Long>, hide: Int): Flow<List<GroupCount>>

    @Query(
        """
        SELECT c.genre AS `key`, COUNT(*) AS count FROM channel c
        WHERE c.region = :region AND (c.flags & :hide) = 0
          AND EXISTS (SELECT 1 FROM live_stream s WHERE s.channel_id = c.id AND s.source_id IN (:src))
        GROUP BY c.genre ORDER BY count DESC
        """,
    )
    fun genres(region: String, src: List<Long>, hide: Int): Flow<List<GroupCount>>

    @Query(
        """
        SELECT c.id, c.identity_key, c.display_name, c.logo, c.tvg_id, c.flags, c.best_quality, c.region,
               (SELECT p.title FROM programme p
                WHERE p.channel_key IN (SELECT s.epg_key FROM live_stream s WHERE s.channel_id = c.id AND s.epg_key IS NOT NULL)
                  AND p.start_ms <= :now AND p.end_ms > :now
                ORDER BY p.start_ms DESC LIMIT 1) AS now_title
        FROM channel c
        WHERE c.region = :region AND (:genre IS NULL OR c.genre = :genre) AND (c.flags & :hide) = 0
          AND EXISTS (SELECT 1 FROM live_stream s WHERE s.channel_id = c.id AND s.source_id IN (:src))
        ORDER BY c.stream_count DESC, c.provider_rank, c.id
        """,
    )
    fun channels(region: String, genre: String?, src: List<Long>, hide: Int, now: Long): PagingSource<Int, ChannelRow>

    /** Every visible channel (the guide's "All channels"), most popular first. */
    @Query(
        """
        SELECT c.id, c.identity_key, c.display_name, c.logo, c.tvg_id, c.flags, c.best_quality, c.region,
               NULL AS now_title
        FROM channel c
        WHERE (c.flags & :hide) = 0
          AND EXISTS (SELECT 1 FROM live_stream s WHERE s.channel_id = c.id AND s.source_id IN (:src))
        ORDER BY c.stream_count DESC, c.provider_rank, c.id
        """,
    )
    fun allChannels(src: List<Long>, hide: Int): PagingSource<Int, ChannelRow>

    @Query(
        """
        SELECT c.id FROM channel c
        WHERE (c.flags & :hide) = 0
          AND EXISTS (SELECT 1 FROM live_stream s WHERE s.channel_id = c.id AND s.source_id IN (:src))
        ORDER BY c.stream_count DESC, c.provider_rank, c.id
        """,
    )
    suspend fun allChannelIds(src: List<Long>, hide: Int): List<Long>

    /** Same order as [channels]; ids only, for Up/Down zapping in the player. */
    @Query(
        """
        SELECT c.id FROM channel c
        WHERE c.region = :region AND (:genre IS NULL OR c.genre = :genre) AND (c.flags & :hide) = 0
          AND EXISTS (SELECT 1 FROM live_stream s WHERE s.channel_id = c.id AND s.source_id IN (:src))
        ORDER BY c.stream_count DESC, c.provider_rank, c.id
        """,
    )
    suspend fun channelIds(region: String, genre: String?, src: List<Long>, hide: Int): List<Long>

    @Query(
        """
        SELECT c.id, c.identity_key, c.display_name, c.logo, c.tvg_id, c.flags, c.best_quality, c.region,
               (SELECT p.title FROM programme p
                WHERE p.channel_key IN (SELECT s.epg_key FROM live_stream s WHERE s.channel_id = c.id AND s.epg_key IS NOT NULL)
                  AND p.start_ms <= :now AND p.end_ms > :now
                ORDER BY p.start_ms DESC LIMIT 1) AS now_title
        FROM channel c
        WHERE c.identity_key IN (:keys) AND (c.flags & :hide) = 0
          AND EXISTS (SELECT 1 FROM live_stream s WHERE s.channel_id = c.id AND s.source_id IN (:src))
        """,
    )
    fun channelsByKey(keys: List<String>, src: List<Long>, hide: Int, now: Long): Flow<List<ChannelRow>>

    @Query(
        """
        SELECT c.id, c.identity_key, c.display_name, c.logo, c.tvg_id, c.flags, c.best_quality, c.region,
               (SELECT p.title FROM programme p
                WHERE p.channel_key IN (SELECT s.epg_key FROM live_stream s WHERE s.channel_id = c.id AND s.epg_key IS NOT NULL)
                  AND p.start_ms <= :now AND p.end_ms > :now
                ORDER BY p.start_ms DESC LIMIT 1) AS now_title
        FROM channel c
        WHERE c.id = :id
        """,
    )
    suspend fun channel(id: Long, now: Long): ChannelRow?

    /** Now and next (and a bit more) for one channel; one row per start time across sources. */
    @Query(
        """
        SELECT * FROM programme
        WHERE channel_key IN (SELECT epg_key FROM live_stream WHERE channel_id = :channelId AND epg_key IS NOT NULL)
          AND end_ms > :now
        GROUP BY start_ms ORDER BY start_ms LIMIT :limit
        """,
    )
    suspend fun upcoming(channelId: Long, now: Long, limit: Int): List<ProgrammeEntity>

    /** Guide ids of a channel's streams, best stream first. */
    @Query("SELECT DISTINCT epg_key FROM live_stream WHERE channel_id = :channelId AND epg_key IS NOT NULL ORDER BY quality DESC, sort_order")
    suspend fun epgKeysOf(channelId: Long): List<String>

    @Query("SELECT * FROM programme WHERE channel_key = :epgKey AND end_ms > :now ORDER BY start_ms LIMIT :limit")
    suspend fun upcomingFor(epgKey: String, now: Long, limit: Int): List<ProgrammeEntity>

    @Query("SELECT * FROM programme WHERE channel_key = :epgKey AND end_ms > :from AND start_ms < :until ORDER BY start_ms")
    suspend fun programmesFor(epgKey: String, from: Long, until: Long): List<ProgrammeEntity>

    @Query("SELECT * FROM live_stream WHERE channel_id = :channelId AND source_id IN (:src)")
    suspend fun streamsOf(channelId: Long, src: List<Long>): List<LiveStreamEntity>

    @Query(
        """
        SELECT c.id, c.identity_key, c.display_name, c.logo, c.tvg_id, c.flags, c.best_quality, c.region,
               (SELECT p.title FROM programme p
                WHERE p.channel_key IN (SELECT s.epg_key FROM live_stream s WHERE s.channel_id = c.id AND s.epg_key IS NOT NULL)
                  AND p.start_ms <= :now AND p.end_ms > :now
                ORDER BY p.start_ms DESC LIMIT 1) AS now_title
        FROM channel c
        WHERE c.match_key LIKE '%' || :q || '%' AND (c.flags & :hide) = 0
          AND EXISTS (SELECT 1 FROM live_stream s WHERE s.channel_id = c.id AND s.source_id IN (:src))
        ORDER BY (c.match_key LIKE :q || '%') DESC, c.stream_count DESC, length(c.match_key)
        LIMIT :limit
        """,
    )
    suspend fun searchChannels(q: String, src: List<Long>, hide: Int, limit: Int, now: Long): List<ChannelRow>

    /**
     * Guide search: programmes (title or sub-title) on visible channels that haven't ended, what's on
     * now first, then by start time. One row per channel and start time across sources.
     * Scope: [region] (null = any) and, when [onlyKeys], the channels with identity keys in [keys].
     */
    @Query(
        """
        SELECT c.id AS channel_id, c.display_name AS channel_name, c.logo AS channel_logo,
               p.title, p.subtitle, p.description, p.episode, p.start_ms, p.end_ms
        FROM programme p
        JOIN live_stream s ON s.epg_key = p.channel_key AND s.source_id = p.source_id
        JOIN channel c ON c.id = s.channel_id
        WHERE p.search_key LIKE '%' || :q || '%' AND p.end_ms > :now
          AND s.source_id IN (:src) AND (c.flags & :hide) = 0
          AND (:region IS NULL OR c.region = :region)
          AND (:onlyKeys = 0 OR c.identity_key IN (:keys))
        GROUP BY c.id, p.start_ms
        ORDER BY (p.start_ms <= :now) DESC, p.start_ms, c.stream_count DESC
        LIMIT :limit
        """,
    )
    suspend fun searchProgrammes(
        q: String,
        src: List<Long>,
        hide: Int,
        now: Long,
        limit: Int,
        region: String? = null,
        onlyKeys: Boolean = false,
        keys: List<String> = emptyList(),
    ): List<ProgrammeHit>

    // ---- Movies ------------------------------------------------------------------------------

    @Query(
        """
        SELECT cat.id, cat.source_id, cat.name, cat.language,
               (SELECT COUNT(*) FROM movie m WHERE m.category_id = cat.id AND (m.adult = 0 OR :adult)) AS count
        FROM category cat
        WHERE cat.kind = 'Movie' AND cat.source_id IN (:src) AND (cat.hidden = 0 OR :adult)
        ORDER BY cat.sort_order
        """,
    )
    fun movieCategories(src: List<Long>, adult: Boolean): Flow<List<CategoryRow>>

    @Query(
        """
        SELECT id, source_id, item_key, title, year, poster AS image, rating, language FROM movie
        WHERE category_id = :categoryId AND (adult = 0 OR :adult) AND search_key LIKE '%' || :q || '%'
        ORDER BY CASE WHEN :sort = 0 THEN IFNULL(added_sec, 0) END DESC, CASE WHEN :sort = 1 THEN search_key END ASC,
                 CASE WHEN :sort = 2 THEN IFNULL(year, 0) END DESC, CASE WHEN :sort = 3 THEN IFNULL(rating, 0) END DESC, id
        """,
    )
    fun moviesIn(categoryId: Long, adult: Boolean, q: String, sort: Int): PagingSource<Int, PosterRow>

    @Query(
        """
        SELECT id, source_id, item_key, title, year, poster AS image, rating, language FROM movie
        WHERE source_id IN (:src) AND added_sec IS NOT NULL AND (adult = 0 OR :adult) AND search_key LIKE '%' || :q || '%'
        GROUP BY item_key
        ORDER BY CASE WHEN :sort = 0 THEN MAX(IFNULL(added_sec, 0)) END DESC, CASE WHEN :sort = 1 THEN search_key END ASC,
                 CASE WHEN :sort = 2 THEN MAX(IFNULL(year, 0)) END DESC, CASE WHEN :sort = 3 THEN MAX(IFNULL(rating, 0)) END DESC, id
        """,
    )
    fun recentMovies(src: List<Long>, adult: Boolean, q: String, sort: Int): PagingSource<Int, PosterRow>

    /** Languages with how many titles each (distinct titles, not copies); "" = not stated. */
    @Query(
        """
        SELECT IFNULL(language, '') AS `key`, COUNT(DISTINCT item_key) AS count FROM movie
        WHERE source_id IN (:src) AND (adult = 0 OR :adult)
        GROUP BY language ORDER BY count DESC
        """,
    )
    fun movieLanguages(src: List<Long>, adult: Boolean): Flow<List<GroupCount>>

    /** All titles in one language, newest first, one entry per title. */
    @Query(
        """
        SELECT id, source_id, item_key, title, year, poster AS image, rating, language FROM movie
        WHERE language = :language AND source_id IN (:src) AND (adult = 0 OR :adult) AND search_key LIKE '%' || :q || '%'
        GROUP BY item_key
        ORDER BY CASE WHEN :sort = 0 THEN MAX(IFNULL(added_sec, 0)) END DESC, CASE WHEN :sort = 1 THEN search_key END ASC,
                 CASE WHEN :sort = 2 THEN MAX(IFNULL(year, 0)) END DESC, CASE WHEN :sort = 3 THEN MAX(IFNULL(rating, 0)) END DESC, id
        """,
    )
    fun moviesInLanguage(language: String, src: List<Long>, adult: Boolean, q: String, sort: Int): PagingSource<Int, PosterRow>

    @Query(
        """
        SELECT id, source_id, item_key, title, year, poster AS image, rating, language FROM movie
        WHERE language IS NULL AND source_id IN (:src) AND (adult = 0 OR :adult) AND search_key LIKE '%' || :q || '%'
        GROUP BY item_key
        ORDER BY CASE WHEN :sort = 0 THEN MAX(IFNULL(added_sec, 0)) END DESC, CASE WHEN :sort = 1 THEN search_key END ASC,
                 CASE WHEN :sort = 2 THEN MAX(IFNULL(year, 0)) END DESC, CASE WHEN :sort = 3 THEN MAX(IFNULL(rating, 0)) END DESC, id
        """,
    )
    fun moviesWithoutLanguage(src: List<Long>, adult: Boolean, q: String, sort: Int): PagingSource<Int, PosterRow>

    @Query("SELECT COUNT(*) FROM movie WHERE source_id IN (:src) AND added_sec IS NOT NULL")
    fun recentMovieCount(src: List<Long>): Flow<Int>

    @Query(
        """
        SELECT id, source_id, item_key, title, year, poster AS image, rating, language FROM movie
        WHERE item_key IN (:keys) AND source_id IN (:src) GROUP BY item_key
        """,
    )
    fun moviesByKey(keys: List<String>, src: List<Long>): Flow<List<PosterRow>>

    @Query("SELECT * FROM movie WHERE id = :id")
    suspend fun movie(id: Long): MovieEntity?

    /** All copies of one movie across enabled sources (same TMDB id), for failover. */
    @Query("SELECT * FROM movie WHERE item_key = :itemKey AND source_id IN (:src)")
    suspend fun movieCopies(itemKey: String, src: List<Long>): List<MovieEntity>

    @Query(
        """
        SELECT id, source_id, item_key, title, year, poster AS image, rating, language FROM movie
        WHERE search_key LIKE '%' || :q || '%' AND source_id IN (:src) AND (adult = 0 OR :adult)
        GROUP BY item_key
        ORDER BY (search_key LIKE :q || '%') DESC, length(search_key), (language = :lang) DESC, (language IS NULL) DESC
        LIMIT :limit
        """,
    )
    suspend fun searchMovies(q: String, src: List<Long>, adult: Boolean, limit: Int, lang: String): List<PosterRow>

    // ---- Series ------------------------------------------------------------------------------

    @Query(
        """
        SELECT cat.id, cat.source_id, cat.name, cat.language,
               (SELECT COUNT(*) FROM series s WHERE s.category_id = cat.id) AS count
        FROM category cat
        WHERE cat.kind = 'Series' AND cat.source_id IN (:src) AND (cat.hidden = 0 OR :adult)
        ORDER BY cat.sort_order
        """,
    )
    fun seriesCategories(src: List<Long>, adult: Boolean): Flow<List<CategoryRow>>

    @Query(
        """
        SELECT id, source_id, item_key, title, year, cover AS image, rating, language FROM series
        WHERE category_id = :categoryId AND search_key LIKE '%' || :q || '%'
        ORDER BY CASE WHEN :sort = 0 THEN IFNULL(last_modified_sec, 0) END DESC, CASE WHEN :sort = 1 THEN search_key END ASC,
                 CASE WHEN :sort = 2 THEN IFNULL(year, 0) END DESC, CASE WHEN :sort = 3 THEN IFNULL(rating, 0) END DESC, id
        """,
    )
    fun seriesIn(categoryId: Long, q: String, sort: Int): PagingSource<Int, PosterRow>

    @Query(
        """
        SELECT id, source_id, item_key, title, year, cover AS image, rating, language FROM series
        WHERE source_id IN (:src) AND last_modified_sec IS NOT NULL AND search_key LIKE '%' || :q || '%'
        GROUP BY item_key
        ORDER BY CASE WHEN :sort = 0 THEN MAX(IFNULL(last_modified_sec, 0)) END DESC, CASE WHEN :sort = 1 THEN search_key END ASC,
                 CASE WHEN :sort = 2 THEN MAX(IFNULL(year, 0)) END DESC, CASE WHEN :sort = 3 THEN MAX(IFNULL(rating, 0)) END DESC, id
        """,
    )
    fun recentSeries(src: List<Long>, q: String, sort: Int): PagingSource<Int, PosterRow>

    @Query(
        """
        SELECT IFNULL(language, '') AS `key`, COUNT(DISTINCT item_key) AS count FROM series
        WHERE source_id IN (:src)
        GROUP BY language ORDER BY count DESC
        """,
    )
    fun seriesLanguages(src: List<Long>): Flow<List<GroupCount>>

    @Query(
        """
        SELECT id, source_id, item_key, title, year, cover AS image, rating, language FROM series
        WHERE language = :language AND source_id IN (:src) AND search_key LIKE '%' || :q || '%'
        GROUP BY item_key
        ORDER BY CASE WHEN :sort = 0 THEN MAX(IFNULL(last_modified_sec, 0)) END DESC, CASE WHEN :sort = 1 THEN search_key END ASC,
                 CASE WHEN :sort = 2 THEN MAX(IFNULL(year, 0)) END DESC, CASE WHEN :sort = 3 THEN MAX(IFNULL(rating, 0)) END DESC, id
        """,
    )
    fun seriesInLanguage(language: String, src: List<Long>, q: String, sort: Int): PagingSource<Int, PosterRow>

    @Query(
        """
        SELECT id, source_id, item_key, title, year, cover AS image, rating, language FROM series
        WHERE language IS NULL AND source_id IN (:src) AND search_key LIKE '%' || :q || '%'
        GROUP BY item_key
        ORDER BY CASE WHEN :sort = 0 THEN MAX(IFNULL(last_modified_sec, 0)) END DESC, CASE WHEN :sort = 1 THEN search_key END ASC,
                 CASE WHEN :sort = 2 THEN MAX(IFNULL(year, 0)) END DESC, CASE WHEN :sort = 3 THEN MAX(IFNULL(rating, 0)) END DESC, id
        """,
    )
    fun seriesWithoutLanguage(src: List<Long>, q: String, sort: Int): PagingSource<Int, PosterRow>

    @Query("SELECT COUNT(*) FROM series WHERE source_id IN (:src) AND last_modified_sec IS NOT NULL")
    fun recentSeriesCount(src: List<Long>): Flow<Int>

    @Query(
        """
        SELECT id, source_id, item_key, title, year, cover AS image, rating, language FROM series
        WHERE item_key IN (:keys) AND source_id IN (:src) GROUP BY item_key
        """,
    )
    fun seriesByKey(keys: List<String>, src: List<Long>): Flow<List<PosterRow>>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun series(id: Long): SeriesEntity?

    @Query("SELECT * FROM episode WHERE series_id = :seriesId ORDER BY season, episode")
    fun episodes(seriesId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episode WHERE series_id = :seriesId ORDER BY season, episode")
    suspend fun episodeList(seriesId: Long): List<EpisodeEntity>

    /** The ids of all copies of a series: one item key can have several rows (qualities, one row per season). */
    @Query("SELECT id FROM series WHERE item_key = :key AND source_id IN (:src)")
    suspend fun seriesCopyIds(key: String, src: List<Long>): List<Long>

    /** The copy of a series that holds this episode; the fullest one if several do. */
    @Query(
        """
        SELECT s.id FROM series s JOIN episode e ON e.series_id = s.id
        WHERE s.item_key = :key AND s.source_id IN (:src) AND e.season = :season AND e.episode = :episode
        ORDER BY (SELECT COUNT(*) FROM episode x WHERE x.series_id = s.id) DESC LIMIT 1
        """,
    )
    suspend fun seriesCopyWith(key: String, src: List<Long>, season: Int, episode: Int): Long?

    @Query("SELECT COUNT(*) FROM episode WHERE series_id = :seriesId")
    suspend fun episodeCount(seriesId: Long): Int

    @Query("SELECT * FROM episode WHERE id = :id")
    suspend fun episode(id: Long): EpisodeEntity?

    @Query(
        """
        SELECT id, source_id, item_key, title, year, cover AS image, rating, language FROM series
        WHERE search_key LIKE '%' || :q || '%' AND source_id IN (:src)
        GROUP BY item_key
        ORDER BY (search_key LIKE :q || '%') DESC, length(search_key), (language = :lang) DESC, (language IS NULL) DESC
        LIMIT :limit
        """,
    )
    suspend fun searchSeries(q: String, src: List<Long>, limit: Int, lang: String): List<PosterRow>
}
