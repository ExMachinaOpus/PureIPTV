package app.pureiptv.core.parsing.m3u

import app.pureiptv.core.model.StreamHeaders
import app.pureiptv.core.parsing.names.DeclaredLanguage
import java.io.Reader

sealed interface M3uItem

/** `#EXTM3U` header. [epgUrls] comes from `url-tvg` / `x-tvg-url` / `tvg-url`. */
data class M3uHeader(
    val attributes: Map<String, String>,
    val epgUrls: List<String>,
) : M3uItem

data class M3uEntry(
    /** Text after the last unquoted comma of `#EXTINF`. May be blank in broken playlists. */
    val title: String,
    val url: String,
    /** -1 for live streams; > 0 usually means a VOD file. */
    val durationSeconds: Long,
    /** EXTINF attributes with lowercased keys (`tvg-id`, `tvg-logo`, `group-title`, ...). */
    val attributes: Map<String, String>,
    /** `group-title`, falling back to `#EXTGRP`. */
    val group: String?,
    val headers: StreamHeaders,
) : M3uItem {
    val tvgId: String? get() = attributes["tvg-id"]?.takeIf { it.isNotBlank() }
    val tvgName: String? get() = attributes["tvg-name"]?.takeIf { it.isNotBlank() }
    val tvgLogo: String? get() = (attributes["tvg-logo"] ?: attributes["logo"])?.takeIf { it.isNotBlank() }
    val tvgChannelNumber: Int? get() = attributes["tvg-chno"]?.trim()?.toIntOrNull()
    /** ISO 639-1 code from `tvg-language`, when the playlist states one. */
    val declaredLanguage: String? get() = DeclaredLanguage.parse(attributes["tvg-language"])

    /** Best available name: the EXTINF title, else tvg-name, else the last URL path segment. */
    val name: String
        get() = title.ifBlank { tvgName ?: url.substringBefore('?').substringAfterLast('/') }
}

/**
 * Streaming M3U/M3U8 parser. Reads line by line and yields items lazily, so playlists with
 * hundreds of thousands of entries never need to be held in memory.
 *
 * Tolerates what real providers produce: BOM, CRLF, missing header, quoted values containing
 * commas, unquoted values, single quotes, URLs without EXTINF, Kodi-style `url|User-Agent=..`.
 *
 * A line longer than [MAX_LINE_LENGTH] is not a playlist line (a broken or hostile file): it is
 * skipped, with the entry it belongs to, without ever being held in memory.
 */
object M3uParser {

    fun parse(reader: Reader): Sequence<M3uItem> = sequence {
        val lines = LineReader(reader, MAX_LINE_LENGTH)
        var pending: PendingEntry? = null
        var first = true
        // The #EXTINF of the next URL was too long to read: that URL has no entry to belong to.
        var skipNextUrl = false

        while (true) {
            var line = lines.readLine() ?: break
            if (first) {
                line = line.removePrefix("﻿")
                first = false
            }
            line = line.trim()
            if (line.isEmpty()) continue
            if (lines.truncated) {
                when {
                    line.startsWith("#EXTINF:", ignoreCase = true) -> {
                        pending = null
                        skipNextUrl = true
                    }
                    line.startsWith("#") -> Unit
                    else -> {
                        pending = null
                        skipNextUrl = false
                    }
                }
                continue
            }

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = parseAttributes(line, "#EXTM3U".length).attributes
                    yield(M3uHeader(attrs, epgUrlsFrom(attrs)))
                }

                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    // Directives (#EXTVLCOPT/#KODIPROP/#EXTGRP) may appear before #EXTINF: keep them.
                    pending = parseExtinf(line).also { it.inheritDirectives(pending) }
                    skipNextUrl = false
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    val p = pending ?: PendingEntry().also { pending = it }
                    p.extGroup = line.substringAfter(':').trim().ifEmpty { null }
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val p = pending ?: PendingEntry().also { pending = it }
                    applyVlcOpt(p, line.substringAfter(':'))
                }

                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    val p = pending ?: PendingEntry().also { pending = it }
                    applyKodiProp(p, line.substringAfter(':'))
                }

                line.startsWith("#") -> Unit // Unknown directive or comment.

                else -> {
                    if (skipNextUrl) {
                        skipNextUrl = false
                    } else {
                        yield((pending ?: PendingEntry()).toEntry(line))
                    }
                    pending = null
                }
            }
        }
    }

    /** Longest line read whole. Real lines (even with long logo URLs) stay far below this. */
    const val MAX_LINE_LENGTH = 256 * 1024

    /**
     * Reads lines (ending in LF, CR or CRLF) keeping at most [maxLength] characters of each. The
     * rest of a longer line is read past and dropped, and [truncated] says so.
     */
    private class LineReader(private val reader: Reader, private val maxLength: Int) {
        private val buffer = CharArray(64 * 1024)
        private var position = 0
        private var length = 0
        private var skipLineFeed = false

        /** True when the line just returned was longer than [maxLength]. */
        var truncated = false
            private set

        fun readLine(): String? {
            truncated = false
            val line = StringBuilder()
            var started = false
            while (true) {
                if (position >= length) {
                    length = reader.read(buffer, 0, buffer.size)
                    position = 0
                    if (length < 0) {
                        length = 0
                        return if (started) line.toString() else null
                    }
                    if (length == 0) continue
                }
                if (skipLineFeed) {
                    skipLineFeed = false
                    if (buffer[position] == '\n') {
                        position++
                        continue
                    }
                }
                started = true
                val from = position
                while (position < length && buffer[position] != '\n' && buffer[position] != '\r') position++
                val room = maxLength - line.length
                val count = position - from
                if (count <= room) {
                    line.append(buffer, from, count)
                } else {
                    if (room > 0) line.append(buffer, from, room)
                    truncated = true
                }
                if (position < length) {
                    if (buffer[position] == '\r') skipLineFeed = true
                    position++
                    return line.toString()
                }
            }
        }
    }

    private class PendingEntry(
        var title: String = "",
        var duration: Long = -1,
        var attributes: Map<String, String> = emptyMap(),
        var extGroup: String? = null,
        var userAgent: String? = null,
        var referrer: String? = null,
        val extraHeaders: MutableMap<String, String> = mutableMapOf(),
    ) {
        fun toEntry(rawUrl: String): M3uEntry {
            // Kodi-style headers appended to the URL: http://host/stream.m3u8|User-Agent=x&Referer=y
            var url = rawUrl
            val pipe = rawUrl.indexOf('|')
            if (pipe > 0) {
                url = rawUrl.substring(0, pipe)
                parseHeaderPairs(rawUrl.substring(pipe + 1)) { k, v -> addHeader(k, v) }
            }
            attributes["user-agent"]?.let { userAgent = userAgent ?: it }
            attributes["http-user-agent"]?.let { userAgent = userAgent ?: it }
            attributes["http-referrer"]?.let { referrer = referrer ?: it }
            attributes["referrer"]?.let { referrer = referrer ?: it }

            return M3uEntry(
                title = title,
                url = url.trim(),
                durationSeconds = duration,
                attributes = attributes,
                group = attributes["group-title"]?.trim()?.ifEmpty { null } ?: extGroup,
                headers = StreamHeaders(userAgent, referrer, extraHeaders.toMap()).let {
                    if (it.isEmpty) StreamHeaders.None else it
                },
            )
        }

        fun inheritDirectives(previous: PendingEntry?) {
            if (previous == null) return
            extGroup = extGroup ?: previous.extGroup
            userAgent = userAgent ?: previous.userAgent
            referrer = referrer ?: previous.referrer
            previous.extraHeaders.forEach { (k, v) -> extraHeaders.putIfAbsent(k, v) }
        }

        fun addHeader(key: String, value: String) {
            when (key.lowercase()) {
                "user-agent" -> userAgent = value
                "referer", "referrer" -> referrer = value
                else -> extraHeaders[key] = value
            }
        }
    }

    private fun parseExtinf(line: String): PendingEntry {
        // #EXTINF:<duration> key="value" key2=value2,Title
        var i = "#EXTINF:".length
        val durStart = i
        while (i < line.length && line[i] != ' ' && line[i] != ',' && line[i] != '\t') i++
        val duration = line.substring(durStart, i).trim().toDoubleOrNull()?.toLong() ?: -1
        val parsed = parseAttributes(line, i)
        return PendingEntry(title = parsed.title.trim(), duration = duration, attributes = parsed.attributes)
    }

    private class AttributeParse(val attributes: Map<String, String>, val title: String)

    /** Parses `key="value"` pairs from [start] until the first unquoted comma; the rest is the title. */
    private fun parseAttributes(line: String, start: Int): AttributeParse {
        val attrs = LinkedHashMap<String, String>()
        var i = start
        val n = line.length
        while (i < n) {
            val c = line[i]
            when {
                c == ' ' || c == '\t' -> i++
                c == ',' -> return AttributeParse(attrs, line.substring(i + 1))
                else -> {
                    val keyStart = i
                    while (i < n && line[i] != '=' && line[i] != ' ' && line[i] != ',' && line[i] != '\t') i++
                    val key = line.substring(keyStart, i).lowercase()
                    if (i < n && line[i] == '=') {
                        i++
                        val value: String
                        if (i < n && (line[i] == '"' || line[i] == '\'')) {
                            val quote = line[i]
                            val end = line.indexOf(quote, i + 1).let { if (it < 0) n else it }
                            value = line.substring(i + 1, end)
                            i = (end + 1).coerceAtMost(n)
                        } else {
                            val valueStart = i
                            while (i < n && line[i] != ' ' && line[i] != ',' && line[i] != '\t') i++
                            value = line.substring(valueStart, i)
                        }
                        if (key.isNotEmpty()) attrs[key] = value.trim()
                    }
                }
            }
        }
        return AttributeParse(attrs, "")
    }

    private fun epgUrlsFrom(attrs: Map<String, String>): List<String> =
        listOf("url-tvg", "x-tvg-url", "tvg-url")
            .mapNotNull { attrs[it] }
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()

    private fun applyVlcOpt(p: PendingEntry, opt: String) {
        val key = opt.substringBefore('=').trim().lowercase()
        val value = opt.substringAfter('=', "").trim()
        if (value.isEmpty()) return
        when (key) {
            "http-user-agent" -> p.userAgent = value
            "http-referrer", "http-referer" -> p.referrer = value
            "http-origin" -> p.extraHeaders["Origin"] = value
        }
    }

    private fun applyKodiProp(p: PendingEntry, prop: String) {
        val key = prop.substringBefore('=').trim().lowercase()
        val value = prop.substringAfter('=', "").trim()
        if (key == "inputstream.adaptive.stream_headers" || key == "inputstream.adaptive.common_headers") {
            parseHeaderPairs(value) { k, v -> p.addHeader(k, v) }
        }
    }

    private inline fun parseHeaderPairs(raw: String, add: (String, String) -> Unit) {
        for (pair in raw.split('&')) {
            val k = pair.substringBefore('=').trim()
            val v = pair.substringAfter('=', "").trim()
            // Values are usually percent-encoded, but not always. Never let a stray '%' fail the import.
            // (String charset overload: the Charset one needs Android API 33.)
            val decoded = if ('%' in v) runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v) else v
            if (k.isNotEmpty() && decoded.isNotEmpty()) add(k, decoded)
        }
    }
}
