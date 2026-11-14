package app.pureiptv.core.model

/**
 * Stable keys for user state (favorites, progress, history) in user.db. They never use content.db
 * row ids, which change on every refresh. See dev/architecture.md, "Data model".
 */
object ItemKeys {
    fun channel(identityKey: String) = "channel:$identityKey"

    /**
     * Same movie from two providers shares favourites/progress when both know its TMDB id and it is
     * in the same language. Dubbed versions are different items (a German favourite stays German).
     */
    fun movie(sourceId: Long, providerKey: String, tmdbId: String?, language: String? = null) =
        tmdbId?.takeIf { it.isNotBlank() && it != "0" }?.let { "movie:tmdb:$it${lang(language)}" } ?: "movie:$sourceId:$providerKey"

    fun series(sourceId: Long, providerKey: String, tmdbId: String?, language: String? = null) =
        tmdbId?.takeIf { it.isNotBlank() && it != "0" }?.let { "series:tmdb:$it${lang(language)}" } ?: "series:$sourceId:$providerKey"

    private fun lang(language: String?) = language?.let { ":$it" } ?: ""

    fun episode(seriesKey: String, season: Int, episode: Int) = "${episodePrefix(seriesKey)}$season:$episode"

    /** Prefix shared by all episode keys of one series. */
    fun episodePrefix(seriesKey: String) = "episode:${seriesKey.removePrefix("series:")}:"

    /**
     * Season and episode of [key] if it is an episode of exactly this series. A prefix alone is not
     * enough: "series:tmdb:1" would also claim the episodes of its dubbed "series:tmdb:1:en".
     */
    fun seasonAndEpisode(key: String, seriesKey: String): Pair<Int, Int>? {
        val prefix = episodePrefix(seriesKey)
        if (!key.startsWith(prefix)) return null
        val parts = key.substring(prefix.length).split(':')
        if (parts.size != 2) return null
        return (parts[0].toIntOrNull() ?: return null) to (parts[1].toIntOrNull() ?: return null)
    }

    /**
     * Stands in for a stream URL wherever a key is stored or shown. Playlist URLs usually carry the
     * provider login, and keys end up in user.db (which is backed up), so the URL itself must never
     * be part of one. Same URL, same key: user state survives refreshes exactly as before.
     */
    fun urlKey(url: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return buildString(1 + URL_KEY_BYTES * 2) {
            append('u')
            for (i in 0 until URL_KEY_BYTES) {
                val b = digest[i].toInt() and 0xff
                append(HEX[b ushr 4]).append(HEX[b and 0x0f])
            }
        }
    }

    private const val URL_KEY_BYTES = 16
    private const val HEX = "0123456789abcdef"

    const val CHANNEL_PREFIX = "channel:"
    const val MOVIE_PREFIX = "movie:"
    const val SERIES_PREFIX = "series:"
    const val EPISODE_PREFIX = "episode:"
}
