package app.pureiptv.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-added source. Secrets (M3U URL, guide URL, Xtream username/password) are NOT stored here:
 * they live in [app.pureiptv.core.data.credentials.CredentialStore], keyed by [id]. This table is
 * backed up, so nothing in it may contain a login: no URLs, no usernames.
 */
@Entity(tableName = "source")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** [SourceType] name. */
    val type: String,
    /** Host shown to the user ("example.com"); never contains credentials. */
    val host: String,
    val enabled: Boolean = true,
    /** Lower wins when the same channel/title exists in several sources. */
    val priority: Int,
    /** Xtream only: live container to request ("m3u8" when the account allows HLS, else "ts"). */
    @ColumnInfo(name = "live_format") val liveFormat: String? = null,
    @ColumnInfo(name = "last_refresh_ms") val lastRefreshMs: Long? = null,
    /** When the last import started, finished or not. Automatic refreshes wait a while after it. */
    @ColumnInfo(name = "last_attempt_ms") val lastAttemptMs: Long? = null,
    /** SourceErrorKind name of the last failed refresh, null when the last refresh succeeded. */
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    /** Increments on every successful import; rows from older generations are deleted. */
    val generation: Long = 0,
)

enum class SourceType { M3u, Xtream }
