package app.pureiptv.core.parsing.m3u

import app.pureiptv.core.model.ContentKind
import app.pureiptv.core.parsing.names.EpisodeNameParser

/**
 * Decides whether an M3U entry is a live channel, a movie or a series episode.
 *
 * Deliberately conservative: anything that is not clearly a file defaults to [ContentKind.Live].
 * Group names alone are NOT trusted ("UK | Movies" is usually a group of live movie channels).
 */
object M3uClassifier {

    private val fileExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg")

    fun classify(entry: M3uEntry): ContentKind {
        val path = entry.url.substringBefore('?').substringBefore('#').lowercase()

        // Xtream-style URLs state the kind explicitly.
        if ("/series/" in path) return ContentKind.Series
        if ("/movie/" in path || "/movies/" in path) return ContentKind.Movie
        if ("/live/" in path) return ContentKind.Live

        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        val isFile = ext in fileExtensions
        if (isFile || entry.durationSeconds > 0) {
            return if (EpisodeNameParser.parse(entry.name) != null) ContentKind.Series else ContentKind.Movie
        }
        return ContentKind.Live
    }
}
