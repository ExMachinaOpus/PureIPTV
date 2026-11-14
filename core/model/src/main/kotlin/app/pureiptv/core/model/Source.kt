package app.pureiptv.core.model

@JvmInline
value class SourceId(val value: Long)

/**
 * A user-added content source. Several can exist at once; each can be enabled/disabled
 * independently. Disabling hides content everywhere but keeps it stored (instant re-enable).
 * Lower [priority] wins when the same channel/title is available from several sources.
 */
data class Source(
    val id: SourceId,
    val name: String,
    val config: SourceConfig,
    val enabled: Boolean,
    val priority: Int,
    /** Optional user-provided XMLTV URL; overrides whatever the source advertises. */
    val epgUrlOverride: String?,
    val lastRefreshEpochMs: Long?,
    val status: SourceStatus,
)

sealed interface SourceConfig {
    /** Plain M3U/M3U8 playlist, from a URL or a local file (content:// or file://). */
    data class M3u(val url: String) : SourceConfig

    /** Xtream Codes API (`player_api.php`). Password is stored encrypted, never in plain DB columns. */
    data class Xtream(val serverUrl: String, val username: String, val password: String) : SourceConfig
}

sealed interface SourceStatus {
    data object Idle : SourceStatus
    data class Refreshing(val progress: Float?) : SourceStatus
    data class Error(val kind: SourceErrorKind) : SourceStatus
}

/** User-facing error categories. The UI maps these to plain-language messages. */
enum class SourceErrorKind {
    Unreachable,
    InvalidCredentials,
    AccountExpired,
    NotAPlaylist,
    Unknown,
}
