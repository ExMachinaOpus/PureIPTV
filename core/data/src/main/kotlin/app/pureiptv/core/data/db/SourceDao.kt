package app.pureiptv.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {

    @Query("SELECT * FROM source ORDER BY priority, id")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT COUNT(*) FROM source")
    suspend fun count(): Int

    @Query("SELECT * FROM source WHERE id = :id")
    suspend fun get(id: Long): SourceEntity?

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Query("UPDATE source SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE source SET last_error = :error WHERE id = :id")
    suspend fun setError(id: Long, error: String?)

    @Query("UPDATE source SET last_attempt_ms = :atMs WHERE id = :id")
    suspend fun markAttempt(id: Long, atMs: Long)

    @Query("UPDATE source SET generation = :generation, last_refresh_ms = :atMs, last_error = NULL WHERE id = :id")
    suspend fun markImported(id: Long, generation: Long, atMs: Long)

    @Query("SELECT COALESCE(MAX(priority), -1) + 1 FROM source")
    suspend fun nextPriority(): Int

    @Query("DELETE FROM source WHERE id = :id")
    suspend fun delete(id: Long)
}
