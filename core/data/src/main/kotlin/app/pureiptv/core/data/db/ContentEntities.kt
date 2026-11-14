package app.pureiptv.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Provider content, stored in content.db (rebuildable). All rows belong to a source by `source_id`
// only: sources live in user.db, so "delete source -> delete its content" is done in code
// (ImportDao.deleteSourceContent), not by a foreign key. Rows are replaced on refresh ("generation").
// Row ids are NOT stable across refreshes; user state must use stable keys (see dev/architecture.md).

@Entity(
    tableName = "category",
    indices = [Index(value = ["source_id", "kind", "provider_id"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    /** ContentKind name. */
    val kind: String,
    /** Xtream category_id, or the group name for M3U. */
    @ColumnInfo(name = "provider_id") val providerId: String,
    val name: String,
    val country: String?,
    val language: String?,
    val genre: String,
    val hidden: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val generation: Long,
)

/**
 * A logical live channel ("what you watch"), merged across sources and qualities.
 * Survives refreshes: rows are only deleted when no stream points at them any more.
 */
@Entity(
    tableName = "channel",
    indices = [
        Index(value = ["identity_key"], unique = true),
        Index(value = ["region", "genre", "stream_count"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Duplicate-detection key, see dev/architecture.md "Duplicate detection". */
    @ColumnInfo(name = "identity_key") val identityKey: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "match_key") val matchKey: String,
    val country: String?,
    val language: String?,
    /** Browse group: country code, `@<language>` when only the language is known, or "" (unknown). */
    val region: String,
    /** Genre name. */
    val genre: String,
    val logo: String?,
    @ColumnInfo(name = "tvg_id") val tvgId: String?,
    /** NameFlag bitmask (from the first stream seen). */
    val flags: Int,
    // Precomputed at the end of every import (ImportDao.updateChannelStats) so browsing stays an index scan.
    /** Streams pointing at this channel: more copies across providers ~ more popular channel. */
    @ColumnInfo(name = "stream_count") val streamCount: Int = 0,
    @ColumnInfo(name = "best_quality") val bestQuality: Int = 0,
    /** Lowest provider position among its streams (provider order as tie breaker). */
    @ColumnInfo(name = "provider_rank") val providerRank: Int = 0,
)

/** One way to play a [ChannelEntity]: a specific source/quality/URL. */
@Entity(
    tableName = "live_stream",
    foreignKeys = [ForeignKey(ChannelEntity::class, ["id"], ["channel_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["source_id", "provider_key"], unique = true),
        Index("channel_id"),
        Index("category_id"),
        Index("epg_key"),
    ],
)
data class LiveStreamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    /** Stable per source: Xtream stream id; M3U: a hash of the URL (ItemKeys.urlKey), never the URL. */
    @ColumnInfo(name = "provider_key") val providerKey: String,
    @ColumnInfo(name = "raw_name") val rawName: String,
    /** M3U: full URL. Xtream: null, the URL is built at play time from [providerKey]. */
    val url: String?,
    val quality: Int,
    val flags: Int,
    @ColumnInfo(name = "user_agent") val userAgent: String?,
    val referrer: String?,
    @ColumnInfo(name = "catchup_days") val catchupDays: Int,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val generation: Long,
    /**
     * Guide id this stream's programmes are stored under (normalized tvg-id, or an XMLTV channel
     * matched by exact cleaned name in the same source's guide). Per stream, not per channel:
     * each provider has its own guide ids. Null = no guide.
     */
    @ColumnInfo(name = "epg_key") val epgKey: String? = null,
)

@Entity(
    tableName = "movie",
    indices = [
        Index(value = ["source_id", "provider_key"], unique = true),
        Index("category_id"),
        Index("item_key"),
        Index("added_sec"),
        Index("language"),
    ],
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "provider_key") val providerKey: String,
    /** Stable key for user state (ItemKeys.movie). */
    @ColumnInfo(name = "item_key") val itemKey: String,
    /** ChannelNameParser.matchKey(title): accent/case/punctuation-free, for search. */
    @ColumnInfo(name = "search_key") val searchKey: String,
    val title: String,
    val year: Int?,
    /** ISO 639-1 audio language when the title or category says so ("DE - ...", "EN - Comedy"). */
    val language: String?,
    @ColumnInfo(name = "tmdb_id") val tmdbId: String?,
    val poster: String?,
    val rating: Double?,
    @ColumnInfo(name = "added_sec") val addedSec: Long?,
    /** M3U: full URL. Xtream: null (built from [providerKey] + [containerExtension]). */
    val url: String?,
    @ColumnInfo(name = "container_extension") val containerExtension: String?,
    val adult: Boolean,
    val generation: Long,
)

@Entity(
    tableName = "series",
    indices = [
        Index(value = ["source_id", "provider_key"], unique = true),
        Index("category_id"),
        Index("item_key"),
        Index("last_modified_sec"),
        Index("language"),
    ],
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    /** Xtream series_id, or the parsed series title for M3U. */
    @ColumnInfo(name = "provider_key") val providerKey: String,
    /** Stable key for user state (ItemKeys.series). */
    @ColumnInfo(name = "item_key") val itemKey: String,
    @ColumnInfo(name = "search_key") val searchKey: String,
    val title: String,
    val year: Int?,
    /** ISO 639-1 audio language when the title or category says so. */
    val language: String?,
    @ColumnInfo(name = "tmdb_id") val tmdbId: String?,
    val cover: String?,
    val plot: String?,
    val genre: String?,
    val rating: Double?,
    @ColumnInfo(name = "last_modified_sec") val lastModifiedSec: Long?,
    val generation: Long,
)

/** Episodes. M3U episodes are imported with the playlist; Xtream episodes are fetched on demand. */
@Entity(
    tableName = "episode",
    foreignKeys = [ForeignKey(SeriesEntity::class, ["id"], ["series_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["series_id", "provider_key"], unique = true)],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "series_id") val seriesId: Long,
    @ColumnInfo(name = "provider_key") val providerKey: String,
    val season: Int,
    val episode: Int,
    val title: String?,
    val url: String?,
    @ColumnInfo(name = "container_extension") val containerExtension: String?,
    @ColumnInfo(name = "duration_sec") val durationSec: Long?,
    val plot: String?,
    val image: String?,
    /** Import run for M3U episodes (stale ones are deleted); null for episodes fetched on demand. */
    val generation: Long? = null,
)

/**
 * TV guide entries (XMLTV), keyed by [channelKey] = LiveStreamEntity.epgKey. Only a short window
 * (2 h back, 3 days ahead) is kept: the guide grid and guide search; small enough for big providers.
 */
@Entity(
    tableName = "programme",
    indices = [
        Index(value = ["channel_key", "end_ms"]),
        Index(value = ["source_id", "generation"]),
        // One programme per channel and start time: a guide refresh overwrites in place instead of
        // showing old and new side by side until the old ones are deleted at the end.
        Index(value = ["source_id", "channel_key", "start_ms"], unique = true),
    ],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "channel_key") val channelKey: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    val title: String,
    /** XMLTV sub-title ("Monaco Grand Prix" under "Formula 1"). */
    val subtitle: String? = null,
    val description: String?,
    val episode: String?,
    /** ChannelNameParser.matchKey(title + subtitle), for guide search. */
    @ColumnInfo(name = "search_key") val searchKey: String = "",
    val generation: Long,
)
