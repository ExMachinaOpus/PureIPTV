package app.pureiptv.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Favorites, progress, history and settings (user.db). Everything is keyed by stable item keys. */
@Dao
interface UserDao {

    @Query("SELECT item_key FROM favorite ORDER BY position")
    fun favoriteKeys(): Flow<List<String>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM favorite")
    suspend fun nextFavoritePosition(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE item_key = :itemKey")
    suspend fun removeFavorite(itemKey: String)

    @Upsert
    suspend fun putProgress(progress: WatchProgressEntity)

    @Query("SELECT * FROM watch_progress WHERE item_key = :itemKey")
    suspend fun progress(itemKey: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE item_key LIKE :prefix || '%'")
    fun progressWithPrefix(prefix: String): Flow<List<WatchProgressEntity>>

    @Query("DELETE FROM watch_progress WHERE item_key = :itemKey")
    suspend fun clearProgress(itemKey: String)

    @Insert
    suspend fun addHistory(entry: HistoryEntity)

    /** Most recently watched distinct keys with [prefix] (e.g. "channel:"), newest first. */
    @Query(
        """
        SELECT item_key FROM history WHERE item_key LIKE :prefix || '%'
        GROUP BY item_key ORDER BY MAX(watched_ms) DESC LIMIT :limit
        """,
    )
    fun recentKeys(prefix: String, limit: Int): Flow<List<String>>

    /** History and progress of an item and everything under it (a series' episodes). */
    @Query("DELETE FROM history WHERE item_key = :itemKey OR (:prefix != '' AND item_key LIKE :prefix || '%')")
    suspend fun forgetHistory(itemKey: String, prefix: String)

    @Query("DELETE FROM watch_progress WHERE item_key = :itemKey OR (:prefix != '' AND item_key LIKE :prefix || '%')")
    suspend fun forgetProgress(itemKey: String, prefix: String)

    @Query("DELETE FROM history WHERE item_key = :itemKey")
    suspend fun deleteHistory(itemKey: String)

    /** Movies and series watched, newest first (one entry each). */
    @Query(
        """
        SELECT item_key FROM history WHERE item_key LIKE 'movie:%' OR item_key LIKE 'series:%'
        GROUP BY item_key ORDER BY MAX(watched_ms) DESC LIMIT :limit
        """,
    )
    fun recentTitleKeys(limit: Int): Flow<List<String>>

    /** Keeps history bounded. */
    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY watched_ms DESC LIMIT 500)")
    suspend fun trimHistory()

    @Query("SELECT value FROM setting WHERE `key` = :key")
    fun setting(key: String): Flow<String?>

    @Upsert
    suspend fun putSetting(setting: SettingEntity)

    @Query("DELETE FROM setting WHERE `key` = :key")
    suspend fun deleteSetting(key: String)
}
