package app.pureiptv.core.model

/** What a playable entry is. Determined at import time, never in the UI. */
enum class ContentKind { Live, Movie, Series }

/** Ordered: higher ordinal = better picture. Used to rank stream variants. */
enum class Quality { Unknown, SD, HD, FHD, UHD }

/**
 * Coarse genre buckets used for browsing ("country -> genre"). Kept deliberately small;
 * provider categories map onto these, anything unclear stays [Other].
 */
enum class Genre { News, Sports, Kids, Movies, Entertainment, Documentary, Music, Religion, Other }

/**
 * Extra request details some streams need (from #EXTVLCOPT / #KODIPROP or provider quirks).
 * Must be passed to the player, otherwise these streams fail with 403s.
 */
data class StreamHeaders(
    val userAgent: String? = null,
    val referrer: String? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = userAgent == null && referrer == null && extra.isEmpty()

    companion object {
        val None = StreamHeaders()
    }
}

/** Catch-up / timeshift capability advertised by the source for a live channel. */
data class CatchupInfo(
    /** e.g. "default", "append", "shift", "flussonic", "xc". */
    val mode: String,
    val days: Int,
    /** Template for "default"/"append" modes, with {utc}, {start}, {duration} etc. placeholders. */
    val sourceTemplate: String?,
)
