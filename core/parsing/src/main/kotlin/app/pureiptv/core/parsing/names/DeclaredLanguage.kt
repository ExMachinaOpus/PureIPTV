package app.pureiptv.core.parsing.names

import java.util.Locale

/**
 * Reads a language stated in a defined field (M3U `tvg-language`), as opposed to one deduced from a
 * name. Values are ISO 639 codes or English names, possibly several: `en`, `eng`, `pt-BR`,
 * `English;Spanish`. The first one recognised counts.
 *
 * Deliberately not [Regions.codes]: those are provider prefixes, where `UK` is Great Britain and `SE`
 * Sweden. Here they are the ISO language codes for Ukrainian and Northern Sami.
 */
object DeclaredLanguage {

    private val separators = Regex("""[;,|/]""")

    /** Android and older JDKs still report these three languages by their withdrawn codes. */
    private val withdrawn = mapOf("in" to "id", "iw" to "he", "ji" to "yi")

    /** ISO 639-2/B: the second, older set of three-letter codes, which Locale doesn't know. */
    private val bibliographic = mapOf(
        "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs", "dut" to "nl",
        "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi",
        "may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
    )

    /** Spellings in common use that differ from the name Locale gives. */
    private val otherNames = mapOf("farsi" to "fa", "modern greek" to "el", "greek, modern" to "el")

    private val lookup: Map<String, String> = buildMap {
        putAll(bibliographic)
        putAll(otherNames)
        for (iso in Locale.getISOLanguages()) {
            val code = withdrawn[iso] ?: iso
            val locale = Locale.forLanguageTag(iso)
            put(iso, code)
            runCatching { locale.isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { put(it, code) }
            locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT).takeIf { it != iso }?.let { put(it, code) }
        }
    }

    /** ISO 639-1 code of the first recognised language in [value], or null. */
    fun parse(value: String?): String? =
        value?.split(separators)?.firstNotNullOfOrNull { part ->
            val token = part.trim().lowercase(Locale.ROOT)
            lookup[token] ?: lookup[token.substringBefore('-').substringBefore('_')]
        }
}
