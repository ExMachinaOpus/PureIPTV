package app.pureiptv.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// The user's own data, stored in user.db. Must survive every app update (proper migrations).
//
// Items are referenced by STABLE item keys, never by content.db row ids (those change on every
// refresh and the whole content.db can be rebuilt). Key format, see dev/architecture.md:
//   channel:<identity key>          e.g. channel:GB|bbcone
//   movie:<tmdb id>                 or movie:<source id>:<provider key> when there is no TMDB id
//   series:<tmdb id>                or series:<source id>:<provider key>
//   episode:<series key>:<season>:<episode>

@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey @ColumnInfo(name = "item_key") val itemKey: String,
    @ColumnInfo(name = "added_ms") val addedMs: Long,
    /** Manual order (favorites double as the numbered "Your channels" list). */
    val position: Int,
)

@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    @PrimaryKey @ColumnInfo(name = "item_key") val itemKey: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "updated_ms") val updatedMs: Long,
)

@Entity(tableName = "history", indices = [Index("item_key"), Index("watched_ms")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "item_key") val itemKey: String,
    @ColumnInfo(name = "watched_ms") val watchedMs: Long,
)

/** Small key/value settings (few by design: zero-config). */
@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
