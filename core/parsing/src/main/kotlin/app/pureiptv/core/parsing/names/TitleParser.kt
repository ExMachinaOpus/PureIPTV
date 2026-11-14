package app.pureiptv.core.parsing.names

import app.pureiptv.core.model.Quality

/** Movie or series title with provider noise removed. */
data class ParsedTitle(
    val title: String,
    val year: Int?,
    val country: String?,
    val language: String?,
    val quality: Quality,
)

/**
 * Cleans VOD titles: `EN - The Matrix (1999) 4K` -> "The Matrix", 1999.
 * Reuses the channel-name rules for prefixes and quality tags, then extracts a release year.
 */
object TitleParser {

    private val bracketYear = Regex("""[\(\[]((?:19|20)\d{2})[\)\]]""")
    private val trailingYear = Regex("""(?:\s[-–|]\s?|\s)((?:19|20)\d{2})\s*$""")

    /**
     * Provider labels before a title: "NF - ", "D+ - ", "TOP - ", "4K-EN - ". Short, upper case,
     * followed by a spaced delimiter. At least two characters, so "M - Eine Stadt ..." keeps its M.
     */
    private val labelPrefix = Regex("""^([A-Z0-9][A-Z0-9+]{1,4}(?:-[A-Z0-9]{1,3}){0,2})\s*[-–|:]\s+""")

    /** A release year can't be far in the future ("Börü 2039" is a title, not a year). */
    private fun plausibleYear(text: String): Boolean =
        text.toInt() <= java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1

    private val fieldYear = Regex("""^(?:19|20)\d{2}""")

    /** Year from a typed field: "2019" or "2019-03-01". Providers also send "0" and free text, which give null. */
    fun yearFromField(text: String?): Int? =
        text?.trim()?.let(fieldYear::find)?.value?.takeIf(::plausibleYear)?.toInt()

    /** @param declaredLanguage language stated in a defined field; beats anything deduced from the text. */
    fun parse(raw: String, category: String? = null, declaredLanguage: String? = null): ParsedTitle {
        val parsed = ChannelNameParser.parse(raw, category)
        var title = parsed.displayName
        var year: Int? = null
        var labelLanguage: String? = null

        repeat(3) {
            val m = labelPrefix.find(title) ?: return@repeat
            val rest = title.substring(m.range.last + 1)
            if (rest.isBlank()) return@repeat
            m.groupValues[1].split('-').forEach { part -> Regions.codes[part]?.language?.let { labelLanguage = it } }
            title = rest
        }

        bracketYear.find(title)?.takeIf { plausibleYear(it.groupValues[1]) }?.let { m ->
            year = m.groupValues[1].toInt()
            title = title.removeRange(m.range)
        } ?: trailingYear.find(title)?.takeIf { plausibleYear(it.groupValues[1]) }?.let { m ->
            // Only treat a trailing number as a year if something remains ("2012" alone is a title).
            val rest = title.removeRange(m.range).trim()
            if (rest.isNotEmpty()) {
                year = m.groupValues[1].toInt()
                title = rest
            }
        }

        title = title.replace(Regex("""\s+"""), " ").trim().trim('-', '–', '|', ':').trim()
        return ParsedTitle(
            title = title.ifEmpty { parsed.displayName },
            year = year,
            country = parsed.country,
            // A language in the title's own label beats the category's ("4K-EN - X" in a German list).
            language = declaredLanguage ?: labelLanguage ?: parsed.language,
            quality = parsed.quality,
        )
    }
}
