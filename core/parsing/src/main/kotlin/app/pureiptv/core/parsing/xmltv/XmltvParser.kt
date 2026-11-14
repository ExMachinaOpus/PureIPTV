package app.pureiptv.core.parsing.xmltv

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

sealed interface XmltvItem

data class XmltvChannel(
    val id: String,
    val displayNames: List<String>,
    val iconUrl: String?,
) : XmltvItem

data class XmltvProgramme(
    val channelId: String,
    val startEpochMs: Long,
    val stopEpochMs: Long?,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val categories: List<String>,
    val iconUrl: String?,
    /** "S1 E5" style, from xmltv_ns or onscreen episode-num, when present. */
    val episode: String?,
) : XmltvItem

/**
 * Streaming XMLTV parser. EPG files are commonly 50–500 MB uncompressed, so this yields items one
 * at a time and never builds a DOM. Gzip must be unwrapped by the caller (GZIPInputStream).
 *
 * @param newParser on Android pass `{ android.util.Xml.newPullParser() }`; tests use kxml2.
 */
class XmltvParser(private val newParser: () -> XmlPullParser) {

    fun parse(input: InputStream, encoding: String? = null): Sequence<XmltvItem> = sequence {
        val xpp = newParser()
        xpp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        xpp.setInput(input, encoding)
        var event = xpp.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (xpp.name) {
                    "channel" -> readChannel(xpp)?.let { yield(it) }
                    "programme" -> readProgramme(xpp)?.let { yield(it) }
                }
            }
            event = runCatching { xpp.next() }.getOrElse {
                // Truncated or broken file: keep what we have instead of failing the whole import.
                XmlPullParser.END_DOCUMENT
            }
        }
    }

    private fun readChannel(xpp: XmlPullParser): XmltvChannel? {
        val id = xpp.getAttributeValue(null, "id")?.trim()
        val names = ArrayList<String>(2)
        var icon: String? = null
        forEachChild(xpp) { tag ->
            when (tag) {
                "display-name" -> xpp.readText()?.let(names::add)
                "icon" -> icon = icon ?: xpp.getAttributeValue(null, "src")
            }
        }
        if (id.isNullOrEmpty()) return null
        return XmltvChannel(id, names, icon)
    }

    private fun readProgramme(xpp: XmlPullParser): XmltvProgramme? {
        val channel = xpp.getAttributeValue(null, "channel")?.trim()
        val start = xpp.getAttributeValue(null, "start")?.let(::parseTime)
        val stop = xpp.getAttributeValue(null, "stop")?.let(::parseTime)
        var title: String? = null
        var subtitle: String? = null
        var desc: String? = null
        var icon: String? = null
        var episode: String? = null
        val categories = ArrayList<String>(2)
        forEachChild(xpp) { tag ->
            when (tag) {
                "title" -> { val t = xpp.readText(); if (title == null) title = t }
                "sub-title" -> { val t = xpp.readText(); if (subtitle == null) subtitle = t }
                "desc" -> { val t = xpp.readText(); if (desc == null) desc = t }
                "category" -> xpp.readText()?.let(categories::add)
                "icon" -> icon = icon ?: xpp.getAttributeValue(null, "src")
                "episode-num" -> {
                    val system = xpp.getAttributeValue(null, "system")
                    val text = xpp.readText()
                    if (episode == null || system == "xmltv_ns") episode = formatEpisode(system, text) ?: episode
                }
            }
        }
        if (channel.isNullOrEmpty() || start == null || title.isNullOrBlank()) return null
        return XmltvProgramme(channel, start, stop, title!!, subtitle, desc, categories, icon, episode)
    }

    /** Calls [onChild] for each direct child start tag; consumes up to the parent's end tag. */
    private inline fun forEachChild(xpp: XmlPullParser, onChild: (String) -> Unit) {
        if (xpp.isEmptyElementTag) {
            xpp.next()
            return
        }
        val depth = xpp.depth
        while (true) {
            val e = xpp.next()
            if (e == XmlPullParser.END_DOCUMENT) return
            if (e == XmlPullParser.END_TAG && xpp.depth == depth) return
            if (e == XmlPullParser.START_TAG && xpp.depth == depth + 1) {
                val name = xpp.name
                onChild(name)
                // Skip anything the child handler didn't consume (nested or unknown elements).
                if (xpp.eventType == XmlPullParser.START_TAG && xpp.name == name && xpp.depth == depth + 1) skip(xpp)
            }
        }
    }

    /** Reads text content of the current element and leaves the parser on its END_TAG. */
    private fun XmlPullParser.readText(): String? {
        val sb = StringBuilder()
        val d = depth
        while (true) {
            val e = next()
            when {
                e == XmlPullParser.TEXT -> sb.append(text)
                e == XmlPullParser.END_TAG && depth == d -> break
                e == XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    private fun skip(xpp: XmlPullParser) {
        var level = 1
        while (level > 0) {
            when (xpp.next()) {
                XmlPullParser.START_TAG -> level++
                XmlPullParser.END_TAG -> level--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    companion object {
        private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        /** `20250101120000 +0100`, `20250101120000+0100`, `202501011200`, `20250101120000` (UTC assumed). */
        fun parseTime(raw: String): Long? {
            val s = raw.trim()
            if (s.length < 12) return null
            val digits = s.takeWhile { it.isDigit() }
            val padded = when (digits.length) {
                12 -> digits + "00"
                14 -> digits
                else -> if (digits.length > 14) digits.substring(0, 14) else return null
            }
            val local = runCatching { LocalDateTime.parse(padded, formatter) }.getOrNull() ?: return null
            val tz = s.substring(digits.length).trim()
            val offset = parseOffset(tz) ?: ZoneOffset.UTC
            return local.toInstant(offset).toEpochMilli()
        }

        private fun parseOffset(tz: String): ZoneOffset? {
            if (tz.isEmpty()) return null
            val m = Regex("""^([+-])(\d{2}):?(\d{2})$""").find(tz) ?: return null
            val sign = if (m.groupValues[1] == "-") -1 else 1
            return runCatching {
                ZoneOffset.ofHoursMinutes(sign * m.groupValues[2].toInt(), sign * m.groupValues[3].toInt())
            }.getOrNull()
        }

        /** xmltv_ns is zero-based `season.episode.part` → "S2 E5". Other systems are passed through. */
        internal fun formatEpisode(system: String?, text: String?): String? {
            if (text.isNullOrBlank()) return null
            if (system != "xmltv_ns") return text
            val parts = text.split('.')
            val season = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
            val episode = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
            return when {
                season != null && episode != null -> "S$season E$episode"
                episode != null -> "E$episode"
                season != null -> "S$season"
                else -> null
            }
        }
    }
}
