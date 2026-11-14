package app.pureiptv.core.parsing.names

import app.pureiptv.core.model.NameFlag
import app.pureiptv.core.model.ParsedName
import app.pureiptv.core.model.Quality
import java.text.Normalizer

/**
 * Turns a noisy provider name (`|UK| BBC One FHD (Backup)`) into a [ParsedName].
 *
 * Rules (see dev/architecture.md, "Duplicate detection"):
 * - Strip things that describe the *stream*: country prefix, quality, codec, fps, backup/VIP noise.
 * - Never strip things that describe a *different channel*: `+1`, regional words, numbers in the name.
 * - When unsure, keep the text. A missed duplicate is harmless; a wrong merge plays the wrong channel.
 */
object ChannelNameParser {

    /**
     * @param tvgId provider guide id. Used only as evidence for the country when neither the name nor
     *   the category has one: `BBCOne.uk`, `CCTV1.cn@HD` (iptv-org / epg.best convention).
     */
    fun parse(raw: String, category: String? = null, tvgId: String? = null): ParsedName {
        // Status markers describe availability, not the channel ("[Not 24/7]" is not a 24/7 channel).
        val normalized = normalizeUnicode(raw)
        val text = if (normalized.indexOf('[') >= 0 || normalized.indexOf('(') >= 0) statusMarkers.replace(normalized, " ") else normalized
        val flags = mutableSetOf<NameFlag>()
        detectFlags(text, flags)
        val categoryInfo = category?.let(::categoryInfo)
        categoryInfo?.flags?.let(flags::addAll)

        var rest = text.trim()
        var region: Region? = null
        var quality = Quality.Unknown

        // 1. Leading prefixes: "UK:", "|UK|", "[DE]", "VIP UK:", "4K | UK |". Up to a few in a row.
        for (attempt in 0 until 4) {
            // A leading tag followed by just a space ("VIP UK: ...", "4K BBC One") is also stripped.
            leadingWord(rest)?.let { (word, end) ->
                classifyTag(word.uppercase())?.let { tag ->
                    quality = maxOf(quality, tag.quality)
                    tag.flag?.let(flags::add)
                    rest = rest.substring(end)
                }
            }
            if (!mayHavePrefix(rest)) break
            val m = prefixRegex.find(rest) ?: break
            val token = m.groupValues[1].uppercase()
            val tag = classifyTag(token)
            when {
                region == null && Regions.codes.containsKey(token) -> region = Regions.codes.getValue(token)
                tag != null -> {
                    quality = maxOf(quality, tag.quality)
                    tag.flag?.let(flags::add)
                }
                else -> break
            }
            rest = rest.substring(m.range.last + 1)
        }

        // 2. Bracketed groups made only of tags/numbers are noise: "(Backup)", "[FHD]", "(2)".
        if (rest.any { it == '(' || it == '[' || it == '{' }) rest = bracketRegex.replace(rest) { m ->
            val inner = m.groupValues[1].trim()
            val tokens = inner.split(tagSplit).filter { it.isNotEmpty() }
            val tags = tokens.map { classifyTag(it.uppercase()) }
            when {
                // Copy counters "(2)"; longer numbers like "(1999)" are years and stay.
                inner.length in 1..2 && inner.all { it.isDigit() } -> " "
                tokens.isNotEmpty() && tags.all { it != null } -> {
                    tags.forEach { t ->
                        quality = maxOf(quality, t!!.quality)
                        t.flag?.let(flags::add)
                    }
                    " "
                }
                else -> m.value
            }
        }

        // 3. Loose tag tokens anywhere: "BBC One HD", "Sky Sports F1 UHD HEVC", "Channel 5 BACKUP".
        val upperRest = rest.uppercase()
        rest = multiWordTags.filter { (regex, _) -> multiWordHints.getValue(regex).any { it in upperRest } }.fold(rest) { acc, (regex, tag) ->
            regex.replace(acc) {
                quality = maxOf(quality, tag.quality)
                tag.flag?.let(flags::add)
                " "
            }
        }
        val kept = ArrayList<String>()
        for (word in rest.split(' ', '\t')) {
            if (word.isEmpty()) continue
            val core = word.trim(*delimiterChars).uppercase()
            val tag = if (core.isNotEmpty()) classifyTag(core) else null
            if (tag != null && word.length - core.length <= 2) {
                quality = maxOf(quality, tag.quality)
                tag.flag?.let(flags::add)
            } else {
                kept += word
            }
        }
        rest = kept.joinToString(" ")

        // 4. Trailing copy counters: "BBC One #2".
        if (rest.indexOf('#') >= 0) rest = rest.replace(trailingCounter, "")

        // 5. Timeshift marker ("Channel 4 +1") is part of the identity. The match key drops spaces, so
        //    "+ 1" and "+1" already match; here we only flag it. A "+" glued to a word is a brand (Canal+).
        if (rest.indexOf('+') >= 0 && timeshiftRegex.containsMatchIn(rest)) flags += NameFlag.Timeshift

        val display = collapseSpaces(rest).trim(*delimiterChars).trim()
            .ifEmpty { text.trim() }

        if (region == null && categoryInfo != null) region = categoryInfo.region
        if (region == null && tvgId != null) region = countryFromTvgId(tvgId)?.let { Region(it, null) }

        return ParsedName(
            displayName = display,
            matchKey = matchKey(display),
            country = region?.country,
            language = region?.language,
            quality = quality,
            flags = flags,
        )
    }

    /** Lowercase, accent-free, letters/digits/'+' only. Works for any script (Arabic, Cyrillic, ...). */
    fun matchKey(name: String): String {
        val lower = name.lowercase().replace("&", "and").replace("ß", "ss")
        val decomposed = if (lower.isAscii()) lower else Normalizer.normalize(lower, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.isLetterOrDigit(ch) || ch == '+') sb.append(ch)
        }
        return sb.toString()
    }

    private val tvgCountry = Regex("""^[^.@\s]+\.([a-z]{2})(?:@.*)?$""", RegexOption.IGNORE_CASE)
    private val isoCountries: Set<String> = java.util.Locale.getISOCountries().toSet()
    /** Two-letter suffixes that are usually domains/brands, not countries. */
    private val notCountrySuffixes = setOf("TV", "FM", "IO", "ME", "AI", "CC", "TO", "LY")

    /** `BBCOne.uk` -> GB, `CCTV1.cn@HD` -> CN, `Foo.tv` -> null. */
    fun countryFromTvgId(tvgId: String): String? {
        val code = tvgCountry.find(tvgId.trim())?.groupValues?.get(1)?.uppercase() ?: return null
        val iso = if (code == "UK") "GB" else code
        return iso.takeIf { it in isoCountries && code !in notCountrySuffixes }
    }

    /**
     * Guide id without a pure quality feed, so `CCTV1.cn@SD` and `CCTV1.cn@HD` are the same channel,
     * while regional feeds (`NatureTime.ca@AU`) stay distinct.
     */
    fun normalizeTvgId(tvgId: String): String =
        tvgId.trim().lowercase().replace(Regex("""@(sd|hd|fhd|uhd|4k)$"""), "")

    /** Country (ISO alpha-2) and language (ISO 639-1) of a category name, both null when unknown. */
    data class CategoryRegion(val country: String?, val language: String?)

    fun categoryRegion(category: String): CategoryRegion =
        regionFromCategory(category).let { CategoryRegion(it?.country, it?.language) }

    /** Country/language from a category name such as `UK | Sports`, `|DE| Sport`, `Germany HD`. */
    internal fun regionFromCategory(category: String): Region? {
        val text = normalizeUnicode(category).trim()
        prefixRegex.find(text)?.let { m ->
            Regions.codes[m.groupValues[1].uppercase()]?.let { return it }
        }
        // Categories often use a plain space: "UK SPORTS". Accept unless the code is also an English word.
        categorySpacePrefix.find(text)?.let { m ->
            val code = m.groupValues[1].uppercase()
            if (code !in Regions.wordLikeCodes) Regions.codes[code]?.let { return it }
        }
        val upper = stripAccents(text).uppercase()
        return categoryNameRegexes.firstOrNull { (regex, _) -> regex.containsMatchIn(upper) }?.second
    }

    private val categoryNameRegexes: List<Pair<Regex, Region>> =
        Regions.names.map { (name, region) -> Regex("""\b$name\b""") to region }

    // ---- tags --------------------------------------------------------------------------------

    private data class Tag(val quality: Quality = Quality.Unknown, val flag: NameFlag? = null)

    private val qualityTags: Map<String, Quality> = mapOf(
        "SD" to Quality.SD, "480P" to Quality.SD, "576P" to Quality.SD, "LQ" to Quality.SD,
        "HD" to Quality.HD, "720P" to Quality.HD, "HQ" to Quality.HD,
        "FHD" to Quality.FHD, "FULLHD" to Quality.FHD, "1080P" to Quality.FHD, "1080I" to Quality.FHD,
        "UHD" to Quality.UHD, "4K" to Quality.UHD, "2160P" to Quality.UHD, "8K" to Quality.UHD,
    )
    private val hevcTags = setOf("HEVC", "H265", "H.265", "X265")
    private val backupTags = setOf("BACKUP", "BK", "ALT", "ALTERNATIVE", "ALTERNATE", "RESERVE", "BAK")
    private val noiseTags = setOf("VIP", "RAW", "H264", "H.264", "X264", "HDR", "HDR10", "SDR")

    private fun classifyTag(token: String): Tag? {
        qualityTags[token]?.let { return Tag(quality = it) }
        if (token.isEmpty()) return null
        val last = token.last()
        // Regexes only for tokens of the right shape (each regex call is costly on Android).
        if ((last == 'P' || last == 'I') && token.first().isDigit()) resolutionTag.matchEntire(token)?.let { m ->
            val lines = m.groupValues[1].toInt()
            return Tag(
                quality = when {
                    lines >= 2000 -> Quality.UHD
                    lines >= 1000 -> Quality.FHD
                    lines >= 700 -> Quality.HD
                    else -> Quality.SD
                },
            )
        }
        if (token in hevcTags) return Tag(flag = NameFlag.Hevc)
        if (token in backupTags || (last.isDigit() && backupNumbered.matches(token))) return Tag(flag = NameFlag.Backup)
        if (token in noiseTags || (token.endsWith("FPS") && fpsRegex.matches(token))) return Tag()
        return null
    }

    private val resolutionTag = Regex("""(\d{3,4})[PI]""")
    private val backupNumbered = Regex("""(BACKUP|ALT|BK)\d{1,2}""")
    private val fpsRegex = Regex("""\d{2,3}FPS""")
    private val multiWordTags: List<Pair<Regex, Tag>> = listOf(
        Regex("""\bFULL\s+HD\b""", RegexOption.IGNORE_CASE) to Tag(quality = Quality.FHD),
        Regex("""\bULTRA\s+HD\b""", RegexOption.IGNORE_CASE) to Tag(quality = Quality.UHD),
        Regex("""\bBACK\s*UP\s*\d{0,2}\b""", RegexOption.IGNORE_CASE) to Tag(flag = NameFlag.Backup),
        Regex("""\b(ALT|BK)\s+\d{1,2}\b""", RegexOption.IGNORE_CASE) to Tag(flag = NameFlag.Backup),
        Regex("""\b\d{2,3}\s+FPS\b""", RegexOption.IGNORE_CASE) to Tag(),
        Regex("""\bH\.26[45]\b""", RegexOption.IGNORE_CASE) to Tag(),
    )

    /** Cheap substring checks so the regexes above only run on names that can match (Android regex is slow). */
    private val multiWordHints: Map<Regex, List<String>> = multiWordTags.map { it.first }.zip(
        listOf(listOf("FULL"), listOf("ULTRA"), listOf("BACK"), listOf("ALT", "BK"), listOf("FPS"), listOf("H.26")),
    ).toMap()
    private val tagSplit = Regex("""[\s/,|]+""")

    // ---- category facts (cached: a 50k playlist has only a few hundred categories) -------------

    private class CategoryInfo(val region: Region?, val flags: Set<NameFlag>)

    private val categoryCache = java.util.concurrent.ConcurrentHashMap<String, CategoryInfo>()

    private fun categoryInfo(category: String): CategoryInfo {
        categoryCache[category]?.let { return it }
        val flags = buildSet {
            if (eventCategory.containsMatchIn(category)) add(NameFlag.Event)
            if (loopRegex.containsMatchIn(category)) add(NameFlag.Loop247)
            if (adultRegex.containsMatchIn(category)) add(NameFlag.Adult)
        }
        val info = CategoryInfo(regionFromCategory(category), flags)
        if (categoryCache.size > 20_000) categoryCache.clear()
        categoryCache[category] = info
        return info
    }

    // ---- flags -------------------------------------------------------------------------------

    private val eventCategory = Regex("""\b(PPV|EVENTS?|LIVE\s+EVENTS?|MATCH\s*DAY|FIXTURES)\b""", RegexOption.IGNORE_CASE)
    private val eventName = Regex("""^\s*PPV\b|(\bvs?\.?|\s@)\s.+\b\d{1,2}[:.h]\d{2}\b""", RegexOption.IGNORE_CASE)
    private val loopRegex = Regex("""\b24\s*[/x\-]\s*7\b""", RegexOption.IGNORE_CASE)
    private val adultRegex = Regex(
        """(?<!\w)(XXX|ADULTS?(?!\s+SWIM)|FOR\s+ADULTS|PORN\w*|18\s*\+|\+\s*18)(?!\w)""",
        RegexOption.IGNORE_CASE,
    )

    /** True for adult category/title text ("XXX", "Adults", "18+"; not "Adult Swim"). */
    fun isAdult(text: String): Boolean = adultRegex.containsMatchIn(text)

    /** Name-based flags. Each regex runs only when a cheap check says it could match. */
    private fun detectFlags(name: String, flags: MutableSet<NameFlag>) {
        if (name.none { it.isLetterOrDigit() } || hasSeparatorRun(name)) flags += NameFlag.Separator
        val upper = name.uppercase()
        if ((upper.trimStart().startsWith("PPV") || hasTimeOfDay(name)) && eventName.containsMatchIn(name)) flags += NameFlag.Event
        if ("24" in upper && loopRegex.containsMatchIn(name)) flags += NameFlag.Loop247
        if (("XXX" in upper || "ADULT" in upper || "PORN" in upper || "18" in upper) && adultRegex.containsMatchIn(name)) {
            flags += NameFlag.Adult
        }
    }

    /** "20:00", "8.30", "21h00": event channels carry a kick-off time. */
    private fun hasTimeOfDay(s: String): Boolean {
        for (i in 1 until s.length - 1) {
            val c = s[i]
            if ((c == ':' || c == '.' || c == 'h') && s[i - 1].isDigit() && s[i + 1].isDigit()) return true
        }
        return false
    }

    /** Manual `^\s*([A-Za-z0-9]{2,6})\s+(?=\S)`: the first word and where the text after its spaces starts. */
    private fun leadingWord(s: String): Pair<String, Int>? {
        var i = 0
        while (i < s.length && s[i].isWhitespace()) i++
        val start = i
        while (i < s.length && s[i].code < 0x80 && s[i].isLetterOrDigit()) i++
        val len = i - start
        if (len !in 2..6 || i >= s.length || !s[i].isWhitespace()) return null
        while (i < s.length && s[i].isWhitespace()) i++
        return if (i < s.length) s.substring(start, start + len) to i else null
    }

    /** prefixRegex needs a delimiter within its first few characters. */
    private fun mayHavePrefix(s: String): Boolean {
        val end = minOf(s.length, 24)
        for (i in 0 until end) if (s[i] in prefixDelimiters) return true
        return false
    }

    private const val prefixDelimiters = ":|])}>•▎»-–—"

    private fun collapseSpaces(s: String): String {
        val sb = StringBuilder(s.length)
        var space = false
        for (c in s) {
            if (c.isWhitespace()) {
                space = true
            } else {
                if (space && sb.isNotEmpty()) sb.append(' ')
                space = false
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Same as `separatorRun`: three or more of the same decoration character in a row. */
    private fun hasSeparatorRun(name: String): Boolean {
        var run = 1
        for (i in 1 until name.length) {
            val c = name[i]
            run = if (c == name[i - 1] && c in separatorChars) run + 1 else 1
            if (run >= 3) return true
        }
        return false
    }

    private const val separatorChars = "#=*~_-★☆▬•━─▀▄█|+<>«»✦◆●"

    // ---- text helpers --------------------------------------------------------------------------

    private val delimiterChars = charArrayOf('-', '–', '—', ':', '|', '•', '▎', '»', '>', '/', ',', '.', '*', '_', '~')

    /** `UK:`, `|UK|`, `[UK]`, `(UK)`, `UK -`, `UK ▎` at the start. A delimiter (not just a space) is required. */
    private val prefixRegex = Regex(
        """^[\s|\[\({<#*•▎]*([A-Za-z0-9]{2,6}(?:-[A-Za-z]{2})?)\s*[:|\]\)}>•▎»\-–—]+\s*""",
    )
    private val categorySpacePrefix = Regex("""^([A-Za-z]{2,3})\s+\S""")
    private val statusMarkers = Regex(
        """[\[(]\s*(not\s*24\s*/\s*7|geo[-\s]?(blocked|restricted)|offline|unstable)\s*[\])]""",
        RegexOption.IGNORE_CASE,
    )
    private val bracketRegex = Regex("""[\(\[{]([^\)\]}]*)[\)\]}]""")
    private val trailingCounter = Regex("""\s#\d{1,2}\s*$""")
    private val timeshiftRegex = Regex("""(?<=\s)\+\s?(\d{1,2})(?!\d)""")

    /**
     * Superscript/modifier letters (`BBC Oneᴴᴰ`) become normal letters separated by a space, then NFKC
     * folds full-width and compatibility characters.
     */
    internal fun normalizeUnicode(raw: String): String {
        if (raw.isAscii()) return raw // most names: skip ICU (slow through JNI on Android)
        val sb = StringBuilder(raw.length + 4)
        var inSuper = false
        for (ch in raw) {
            val isSuper = Character.getType(ch) == Character.MODIFIER_LETTER.toInt() ||
                ch in '⁰'..'₟' || ch == '²' || ch == '³' || ch == '¹'
            if (isSuper && !inSuper && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            if (!isSuper && inSuper && ch != ' ') sb.append(' ')
            sb.append(ch)
            inSuper = isSuper
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFKC)
    }

    internal fun stripAccents(s: String): String = if (s.isAscii()) s else
        Normalizer.normalize(s, Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

    private fun CharSequence.isAscii(): Boolean {
        for (c in this) if (c.code >= 0x80) return false
        return true
    }
}
