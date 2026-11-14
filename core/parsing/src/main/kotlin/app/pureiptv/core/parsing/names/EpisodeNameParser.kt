package app.pureiptv.core.parsing.names

/** Series/season/episode extracted from a flat M3U entry name like `Show Name S01 E02 - Pilot`. */
data class EpisodeName(
    val seriesTitle: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
)

object EpisodeNameParser {

    private val sxe = Regex(
        """^(.*?)[\s._\-:|]*\bS(\d{1,3})[\s._\-]*E(\d{1,4})\b(?:[\s._\-:|]+(.*))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val nxn = Regex("""^(.*?)[\s._\-:|]*\b(\d{1,2})x(\d{2,3})\b(?:[\s._\-:|]+(.*))?$""", RegexOption.IGNORE_CASE)

    fun parse(name: String): EpisodeName? {
        val m = sxe.find(name.trim()) ?: nxn.find(name.trim()) ?: return null
        val series = m.groupValues[1].trim().trimEnd('-', ':', '|', '.').trim()
        if (series.isEmpty()) return null
        return EpisodeName(
            seriesTitle = series,
            season = m.groupValues[2].toInt(),
            episode = m.groupValues[3].toInt(),
            episodeTitle = m.groupValues[4].trim().ifEmpty { null },
        )
    }
}
