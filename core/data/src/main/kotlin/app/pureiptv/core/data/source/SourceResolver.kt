package app.pureiptv.core.data.source

import app.pureiptv.core.data.credentials.SourceSecret
import app.pureiptv.core.data.db.SourceType
import app.pureiptv.core.model.SourceErrorKind
import app.pureiptv.core.parsing.xtream.XtreamCredentials
import app.pureiptv.core.parsing.xtream.XtreamDetector
import okhttp3.OkHttpClient
import javax.inject.Inject

/** What the user typed. */
sealed interface SourceInput {
    /** [guideUrl]: an XMLTV guide address given with the playlist, for playlists that name none. */
    data class Link(val url: String, val guideUrl: String? = null) : SourceInput
    data class Login(val server: String, val username: String, val password: String) : SourceInput
}

/** A checked, working source ready to be saved and imported. */
data class ResolvedSource(
    val type: SourceType,
    val name: String,
    val host: String,
    val secret: SourceSecret,
    /** Xtream: "m3u8" when the account allows HLS, else "ts". */
    val liveFormat: String? = null,
    /** A guide address the user gave; it takes the place of the one the source offers. */
    val guideUrl: String? = null,
)

/**
 * Turns user input into a [ResolvedSource], or throws [SourceException] with a user-facing reason.
 * Xtream-shaped playlist links are upgraded to the Xtream API when the login works.
 */
class SourceResolver @Inject constructor(
    private val http: OkHttpClient,
    private val xtream: XtreamClient,
) {

    suspend fun resolve(input: SourceInput): ResolvedSource = when (input) {
        is SourceInput.Login -> resolveXtream(
            XtreamCredentials(
                XtreamDetector.normalizeServerUrl(input.server),
                input.username.trim(),
                input.password,
            ),
        )
        is SourceInput.Link -> resolveLink(normalizeUrl(input.url))
            .copy(guideUrl = input.guideUrl?.takeIf { it.isNotBlank() }?.let { normalizeUrl(it) })
    }

    private suspend fun resolveLink(url: String): ResolvedSource {
        val candidate = XtreamDetector.fromPlaylistUrl(url)
        if (candidate != null) {
            try {
                return resolveXtream(candidate)
            } catch (e: SourceException) {
                // A clear answer from the panel is the real answer; anything else: try as plain M3U.
                if (e.kind == SourceErrorKind.InvalidCredentials || e.kind == SourceErrorKind.AccountExpired) throw e
            }
        }
        checkPlaylist(url)
        val host = displayHost(url)
        return ResolvedSource(SourceType.M3u, host, host, SourceSecret.M3u(url))
    }

    private suspend fun resolveXtream(credentials: XtreamCredentials): ResolvedSource {
        val auth = xtream.authenticate(credentials)
        val formats = auth.userInfo?.allowedOutputFormats.orEmpty()
        val liveFormat = if (formats.isEmpty() || "m3u8" in formats) "m3u8" else "ts"
        val host = displayHost(credentials.serverUrl)
        return ResolvedSource(
            type = SourceType.Xtream,
            name = host,
            host = host,
            secret = SourceSecret.Xtream(credentials.serverUrl, credentials.username, credentials.password),
            liveFormat = liveFormat,
        )
    }

    /** Reads only the start of the file: enough to tell a playlist from an HTML error page. */
    private suspend fun checkPlaylist(url: String) {
        http.get(url) { response ->
            if (!response.isSuccessful) throw SourceException(httpErrorKind(response.code))
            val head = response.body.source().let { src ->
                src.request(PEEK_BYTES)
                src.buffer.snapshot().utf8()
            }
            if (!looksLikePlaylist(head)) throw SourceException(SourceErrorKind.NotAPlaylist)
        }
    }

    companion object {
        private const val PEEK_BYTES = 64L * 1024

        internal fun looksLikePlaylist(head: String): Boolean =
            head.contains("#EXTM3U", ignoreCase = true) || head.contains("#EXTINF", ignoreCase = true)

        /** Adds http:// when the user typed a bare host; trims whitespace and stray quotes. */
        internal fun normalizeUrl(input: String): String {
            val s = input.trim().trim('"', '\'')
            return if (s.contains("://")) s else "http://$s"
        }
    }
}
