package app.pureiptv.core.parsing.xtream

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class XtreamCredentials(
    /** Scheme + host + port (+ optional path prefix), no trailing slash. e.g. `http://example.com:8080`. */
    val serverUrl: String,
    val username: String,
    val password: String,
)

/** Builds every Xtream URL in one place. Nothing else in the app should concatenate Xtream URLs. */
class XtreamUrls(private val c: XtreamCredentials) {

    private val u = enc(c.username)
    private val p = enc(c.password)

    fun api(action: String? = null, vararg params: Pair<String, Any>): String = buildString {
        append(c.serverUrl).append("/player_api.php?username=").append(u).append("&password=").append(p)
        if (action != null) append("&action=").append(action)
        params.forEach { (k, v) -> append('&').append(k).append('=').append(enc(v.toString())) }
    }

    fun xmltv(): String = "${c.serverUrl}/xmltv.php?username=$u&password=$p"

    /** HLS is preferred when the account allows it: better adaptive behaviour and seeking in Media3. */
    fun live(streamId: Long, ext: String = "m3u8"): String = "${c.serverUrl}/live/$u/$p/$streamId.$ext"

    fun movie(streamId: Long, containerExtension: String?): String =
        "${c.serverUrl}/movie/$u/$p/$streamId.${containerExtension ?: "mp4"}"

    fun episode(episodeId: String, containerExtension: String?): String =
        "${c.serverUrl}/series/$u/$p/$episodeId.${containerExtension ?: "mp4"}"

    /** Catch-up. [startUtc] in the server's timezone formatted as `yyyy-MM-dd:HH-mm`. */
    fun timeshift(streamId: Long, durationMinutes: Int, startFormatted: String): String =
        "${c.serverUrl}/timeshift/$u/$p/$durationMinutes/$startFormatted/$streamId.ts"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/**
 * Recognizes Xtream accounts hiding behind M3U links, so the app can use the much richer API
 * (proper movies/series/EPG) without the user knowing the difference.
 */
object XtreamDetector {

    /** `http://host:port/get.php?username=u&password=p&type=m3u_plus` or a `player_api.php` URL. */
    fun fromPlaylistUrl(url: String): XtreamCredentials? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val path = uri.rawPath ?: return null
        val file = path.substringAfterLast('/')
        if (!file.equals("get.php", true) && !file.equals("player_api.php", true)) return null
        val query = parseQuery(uri.rawQuery ?: return null)
        val user = query["username"]?.takeIf { it.isNotEmpty() } ?: return null
        val pass = query["password"]?.takeIf { it.isNotEmpty() } ?: return null
        return XtreamCredentials(baseUrl(uri, path.substringBeforeLast('/')), user, pass)
    }

    /**
     * Stream URL in Xtream layout: `/live/u/p/123.ts`, `/movie/u/p/1.mkv`, `/series/u/p/1.mp4`
     * or the short live form `/u/p/123`. Used when a plain M3U turns out to come from an Xtream panel.
     */
    fun fromStreamUrl(url: String): XtreamCredentials? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val segments = (uri.rawPath ?: return null).trim('/').split('/')
        val kinds = setOf("live", "movie", "series", "timeshift")
        val (user, pass, prefix) = when {
            segments.size >= 4 && segments[segments.size - 4] in kinds ->
                Triple(segments[segments.size - 3], segments[segments.size - 2], segments.dropLast(4))
            segments.size == 3 && segments[2].substringBefore('.').all { it.isDigit() } ->
                Triple(segments[0], segments[1], emptyList())
            else -> return null
        }
        if (user.isEmpty() || pass.isEmpty()) return null
        val prefixPath = if (prefix.isEmpty()) "" else "/" + prefix.joinToString("/")
        return XtreamCredentials(baseUrl(uri, prefixPath), dec(user), dec(pass))
    }

    /** Normalizes what a user types as a server: adds http://, removes trailing slashes and known files. */
    fun normalizeServerUrl(input: String): String {
        var s = input.trim().trimEnd('/')
        if (!s.contains("://")) s = "http://$s"
        listOf("/player_api.php", "/get.php", "/xmltv.php").forEach { s = s.removeSuffix(it) }
        return s.trimEnd('/')
    }

    private fun baseUrl(uri: URI, path: String): String {
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port${path.trimEnd('/')}"
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').mapNotNull { pair ->
            val k = pair.substringBefore('=')
            if (k.isEmpty()) null else dec(k).lowercase() to dec(pair.substringAfter('=', ""))
        }.toMap()

    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
