package app.pureiptv.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Bulk writes used by the importers (content.db). Every list insert runs as one transaction. */
data class StreamEpgKey(val providerKey: String, val epgKey: String)

/** A row's provider key and its row id. */
data class KeyId(val key: String, val id: Long)

/**
 * Writes import rows so that a row the provider sends again keeps its row id: existing rows are
 * updated in place, new ones inserted. Nothing disappears while a refresh runs (other rows point at
 * these ids: streams at categories, episodes at series); rows the provider dropped are deleted at the
 * end (ImportDao.deleteStale*). Within one batch the last row for a key wins. Returns key -> id.
 */
internal suspend fun <T> upsertByKey(
    rows: List<T>,
    key: (T) -> String,
    existingIds: suspend (List<String>) -> List<KeyId>,
    withId: (T, Long) -> T,
    update: suspend (List<T>) -> Unit,
    insert: suspend (List<T>) -> List<Long>,
): Map<String, Long> {
    if (rows.isEmpty()) return emptyMap()
    val byKey = rows.associateBy(key)
    val ids = HashMap<String, Long>(byKey.size * 2)
    byKey.keys.chunked(LOOKUP_CHUNK).forEach { keys -> existingIds(keys).forEach { ids[it.key] = it.id } }
    val (old, new) = byKey.values.partition { key(it) in ids }
    if (old.isNotEmpty()) update(old.map { withId(it, ids.getValue(key(it))) })
    if (new.isNotEmpty()) insert(new).forEachIndexed { i, id -> ids[key(new[i])] = id }
    return ids
}

/** Keys per lookup query (well under SQLite's bound-parameter limit). */
private const val LOOKUP_CHUNK = 500

@Dao
interface ImportDao {

    /** Cheap query used to verify the database opens and is readable. */
    @Query("SELECT COUNT(*) FROM category WHERE id = -1")
    suspend fun ping(): Int

    @Query("SELECT provider_id AS `key`, id FROM category WHERE source_id = :sourceId AND kind = :kind AND provider_id IN (:keys)")
    suspend fun categoryIds(sourceId: Long, kind: String, keys: List<String>): List<KeyId>

    @Insert
    suspend fun insertNewCategories(rows: List<CategoryEntity>): List<Long>

    @Update
    suspend fun updateCategories(rows: List<CategoryEntity>)

    /** Categories of one kind; returns provider id -> row id. */
    @Transaction
    suspend fun upsertCategories(sourceId: Long, kind: String, rows: List<CategoryEntity>): Map<String, Long> =
        upsertByKey(rows, { it.providerId }, { categoryIds(sourceId, kind, it) }, { r, id -> r.copy(id = id) }, ::updateCategories, ::insertNewCategories)

    /** Existing channels keep their id (and therefore their streams/merges). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChannelsIfAbsent(rows: List<ChannelEntity>)

    @Query("SELECT id, identity_key FROM channel WHERE identity_key IN (:keys)")
    suspend fun channelIds(keys: List<String>): List<ChannelKeyId>

    @Query("SELECT provider_key AS `key`, id FROM live_stream WHERE source_id = :sourceId AND provider_key IN (:keys)")
    suspend fun liveStreamIds(sourceId: Long, keys: List<String>): List<KeyId>

    @Insert
    suspend fun insertNewLiveStreams(rows: List<LiveStreamEntity>): List<Long>

    @Update
    suspend fun updateLiveStreams(rows: List<LiveStreamEntity>)

    @Transaction
    suspend fun upsertLiveStreams(sourceId: Long, rows: List<LiveStreamEntity>) {
        upsertByKey(rows, { it.providerKey }, { liveStreamIds(sourceId, it) }, { r, id -> r.copy(id = id) }, ::updateLiveStreams, ::insertNewLiveStreams)
    }

    /** Guide ids streams already have (e.g. matched by name in an earlier guide import). */
    @Query("SELECT provider_key AS providerKey, epg_key AS epgKey FROM live_stream WHERE source_id = :sourceId AND provider_key IN (:providerKeys) AND epg_key IS NOT NULL")
    suspend fun existingEpgKeys(sourceId: Long, providerKeys: List<String>): List<StreamEpgKey>

    @Query("SELECT provider_key AS `key`, id FROM movie WHERE source_id = :sourceId AND provider_key IN (:keys)")
    suspend fun movieIds(sourceId: Long, keys: List<String>): List<KeyId>

    @Insert
    suspend fun insertNewMovies(rows: List<MovieEntity>): List<Long>

    @Update
    suspend fun updateMovies(rows: List<MovieEntity>)

    @Transaction
    suspend fun upsertMovies(sourceId: Long, rows: List<MovieEntity>) {
        upsertByKey(rows, { it.providerKey }, { movieIds(sourceId, it) }, { r, id -> r.copy(id = id) }, ::updateMovies, ::insertNewMovies)
    }

    @Query("SELECT provider_key AS `key`, id FROM series WHERE source_id = :sourceId AND provider_key IN (:keys)")
    suspend fun seriesIds(sourceId: Long, keys: List<String>): List<KeyId>

    @Insert
    suspend fun insertNewSeries(rows: List<SeriesEntity>): List<Long>

    /** An update, never a replace: a replace would delete the series' episodes (foreign key cascade). */
    @Update
    suspend fun updateSeries(rows: List<SeriesEntity>)

    /** Returns provider key -> row id. */
    @Transaction
    suspend fun upsertSeries(sourceId: Long, rows: List<SeriesEntity>): Map<String, Long> =
        upsertByKey(rows, { it.providerKey }, { seriesIds(sourceId, it) }, { r, id -> r.copy(id = id) }, ::updateSeries, ::insertNewSeries)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(rows: List<EpisodeEntity>)

    @Query("SELECT EXISTS(SELECT 1 FROM series WHERE id = :seriesId)")
    suspend fun seriesExists(seriesId: Long): Boolean

    @Query("DELETE FROM episode WHERE series_id = :seriesId AND provider_key NOT IN (:keep)")
    suspend fun deleteEpisodesExcept(seriesId: Long, keep: List<String>)

    /**
     * Episodes fetched on demand for one series: the provider's current list replaces the stored one.
     * A refresh may have removed the series while they were downloading; then there is nothing to
     * store them under.
     */
    @Transaction
    suspend fun insertEpisodesOf(seriesId: Long, rows: List<EpisodeEntity>): Boolean {
        if (!seriesExists(seriesId)) return false
        deleteEpisodesExcept(seriesId, rows.map { it.providerKey })
        insertEpisodes(rows)
        return true
    }

    /** M3U episodes the playlist no longer has. Episodes fetched on demand (no generation) are kept. */
    @Query("DELETE FROM episode WHERE generation < :generation AND series_id IN (SELECT id FROM series WHERE source_id = :sourceId)")
    suspend fun deleteStaleEpisodes(sourceId: Long, generation: Long)

    // ---- end of import: drop what the provider no longer has ----------------------------------

    @Query("DELETE FROM category WHERE source_id = :sourceId AND generation < :generation")
    suspend fun deleteStaleCategories(sourceId: Long, generation: Long)

    @Query("DELETE FROM live_stream WHERE source_id = :sourceId AND generation < :generation")
    suspend fun deleteStaleLiveStreams(sourceId: Long, generation: Long)

    @Query("DELETE FROM movie WHERE source_id = :sourceId AND generation < :generation")
    suspend fun deleteStaleMovies(sourceId: Long, generation: Long)

    @Query("DELETE FROM series WHERE source_id = :sourceId AND generation < :generation")
    suspend fun deleteStaleSeries(sourceId: Long, generation: Long)

    @Query("DELETE FROM channel WHERE NOT EXISTS (SELECT 1 FROM live_stream s WHERE s.channel_id = channel.id)")
    suspend fun deleteOrphanChannels()

    /** Precomputes browse ordering/badges per channel (see ChannelEntity). */
    @Query(
        """
        UPDATE channel SET
            stream_count = (SELECT COUNT(*) FROM live_stream s WHERE s.channel_id = channel.id),
            best_quality = (SELECT IFNULL(MAX(s.quality), 0) FROM live_stream s WHERE s.channel_id = channel.id),
            provider_rank = (SELECT IFNULL(MIN(s.sort_order), 0) FROM live_stream s WHERE s.channel_id = channel.id)
        """,
    )
    suspend fun updateChannelStats()

    // ---- guide (EPG) -------------------------------------------------------------------------

    /** Replaces the programme at the same channel and start time (programme ids are never referenced). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgrammes(rows: List<ProgrammeEntity>)

    @Query("DELETE FROM programme WHERE source_id = :sourceId AND generation < :generation")
    suspend fun deleteStaleProgrammes(sourceId: Long, generation: Long)

    @Query("SELECT COUNT(*) FROM programme WHERE source_id = :sourceId")
    suspend fun programmeCount(sourceId: Long): Int

    @Query("DELETE FROM programme WHERE end_ms < :beforeMs")
    suspend fun deleteEndedProgrammes(beforeMs: Long)

    /** Guide keys of this source's streams. */
    @Query("SELECT DISTINCT epg_key FROM live_stream WHERE source_id = :sourceId AND epg_key IS NOT NULL")
    suspend fun epgKeysOf(sourceId: Long): List<String>

    /** Cleaned names of this source's channels whose streams have no guide id (for exact-name matching). */
    @Query(
        """
        SELECT DISTINCT c.match_key FROM channel c JOIN live_stream s ON s.channel_id = c.id
        WHERE s.source_id = :sourceId AND s.epg_key IS NULL
        """,
    )
    suspend fun unguidedMatchKeys(sourceId: Long): List<String>

    @Query(
        """
        UPDATE live_stream SET epg_key = :epgKey
        WHERE source_id = :sourceId AND epg_key IS NULL
          AND channel_id IN (SELECT id FROM channel WHERE match_key = :matchKey)
        """,
    )
    suspend fun setEpgKeyByName(sourceId: Long, matchKey: String, epgKey: String)

    // ---- source removal (no cross-database foreign keys: enforced here) -----------------------

    @Query("DELETE FROM category WHERE source_id = :sourceId")
    suspend fun deleteCategoriesOf(sourceId: Long)

    @Query("DELETE FROM live_stream WHERE source_id = :sourceId")
    suspend fun deleteLiveStreamsOf(sourceId: Long)

    @Query("DELETE FROM movie WHERE source_id = :sourceId")
    suspend fun deleteMoviesOf(sourceId: Long)

    /** Episodes go with their series (foreign key inside content.db). */
    @Query("DELETE FROM series WHERE source_id = :sourceId")
    suspend fun deleteSeriesOf(sourceId: Long)

    @Query("DELETE FROM programme WHERE source_id = :sourceId")
    suspend fun deleteProgrammesOf(sourceId: Long)

    @Transaction
    suspend fun deleteSourceContent(sourceId: Long) {
        deleteProgrammesOf(sourceId)
        deleteCategoriesOf(sourceId)
        deleteLiveStreamsOf(sourceId)
        deleteMoviesOf(sourceId)
        deleteSeriesOf(sourceId)
        deleteOrphanChannels()
    }

    /** Source ids that still have content; used to clean up after sources deleted elsewhere. */
    @Query(
        """
        SELECT source_id FROM category UNION SELECT source_id FROM live_stream
        UNION SELECT source_id FROM movie UNION SELECT source_id FROM series
        """,
    )
    suspend fun sourceIdsWithContent(): List<Long>
}

data class ChannelKeyId(
    val id: Long,
    @ColumnInfo(name = "identity_key") val identityKey: String,
)

/**
 * Read side for the UI (content.db). Enabled-source filtering is done by passing the enabled
 * source ids from user.db: the databases are separate, so there are no joins on `source`.
 */
@Dao
interface LibraryDao {

    @Query("SELECT source_id AS sourceId, COUNT(*) AS count FROM live_stream GROUP BY source_id")
    fun observeLiveStreamCounts(): Flow<List<SourceCount>>

    @Query("SELECT source_id AS sourceId, COUNT(*) AS count FROM movie GROUP BY source_id")
    fun observeMovieCounts(): Flow<List<SourceCount>>

    @Query("SELECT source_id AS sourceId, COUNT(*) AS count FROM series GROUP BY source_id")
    fun observeSeriesCounts(): Flow<List<SourceCount>>

    @Query("SELECT COUNT(DISTINCT channel_id) FROM live_stream WHERE source_id IN (:sourceIds)")
    fun observeMergedChannelCount(sourceIds: List<Long>): Flow<Int>
}

data class SourceCount(val sourceId: Long, val count: Int)
