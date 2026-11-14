package app.pureiptv.core.data.source

import app.pureiptv.core.model.SourceErrorKind
import app.pureiptv.core.parsing.xtream.XtreamAuthResponse
import app.pureiptv.core.parsing.xtream.XtreamCredentials
import app.pureiptv.core.parsing.xtream.XtreamJson
import app.pureiptv.core.parsing.xtream.XtreamUrls
import app.pureiptv.core.parsing.xtream.XtreamSeriesInfo
import app.pureiptv.core.parsing.xtream.XtreamVodInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.decodeToSequence
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XtreamClient @Inject constructor(private val http: OkHttpClient) {

    /**
     * Logs in. Throws [SourceException] with InvalidCredentials, AccountExpired, Unreachable,
     * or NotAPlaylist (the server answered, but not like an Xtream panel).
     */
    suspend fun authenticate(
        credentials: XtreamCredentials,
        nowSec: Long = System.currentTimeMillis() / 1000,
    ): XtreamAuthResponse {
        val auth = http.get(XtreamUrls(credentials).api()) { response ->
            if (!response.isSuccessful) throw SourceException(httpErrorKind(response.code))
            val body = response.boundedString()
            runCatching { XtreamJson.decodeFromString(XtreamAuthResponse.serializer(), body) }.getOrNull()
                ?: throw SourceException(SourceErrorKind.NotAPlaylist)
        }
        val user = auth.userInfo ?: throw SourceException(SourceErrorKind.NotAPlaylist)
        if (user.auth != true) throw SourceException(SourceErrorKind.InvalidCredentials)
        val status = user.status?.lowercase()
        val exp = user.expDate
        if (status == "expired" || (exp != null && exp in 1 until nowSec)) {
            throw SourceException(SourceErrorKind.AccountExpired)
        }
        if (status == "banned" || status == "disabled") throw SourceException(SourceErrorKind.InvalidCredentials)
        return auth
    }

    /** Streams a JSON array from `player_api.php?action=...` in chunks (see [streamChunks]). */
    @OptIn(ExperimentalSerializationApi::class)
    fun <T> list(
        credentials: XtreamCredentials,
        action: String,
        deserializer: DeserializationStrategy<T>,
    ): Flow<List<T>> = http.streamChunks(XtreamUrls(credentials).api(action)) { response ->
        val stream = response.body.byteStream().buffered(64 * 1024)
        // AUTO_DETECT: a normal array, but also tolerates panels answering `{}` for "empty".
        XtreamJson.decodeToSequence(stream, deserializer, DecodeSequenceMode.AUTO_DETECT)
    }

    /** Episodes of one series (`get_series_info`). Small response, read at once. */
    suspend fun seriesInfo(credentials: XtreamCredentials, seriesId: String): XtreamSeriesInfo =
        http.get(XtreamUrls(credentials).api("get_series_info", "series_id" to seriesId)) { response ->
            if (!response.isSuccessful) throw SourceException(httpErrorKind(response.code))
            runCatching { XtreamJson.decodeFromString(XtreamSeriesInfo.serializer(), response.boundedString()) }
                .getOrElse { throw SourceException(SourceErrorKind.NotAPlaylist, it) }
        }

    /** Details of one movie (`get_vod_info`): plot, cast, duration. */
    suspend fun vodInfo(credentials: XtreamCredentials, streamId: String): XtreamVodInfo =
        http.get(XtreamUrls(credentials).api("get_vod_info", "vod_id" to streamId)) { response ->
            if (!response.isSuccessful) throw SourceException(httpErrorKind(response.code))
            runCatching { XtreamJson.decodeFromString(XtreamVodInfo.serializer(), response.boundedString()) }
                .getOrElse { throw SourceException(SourceErrorKind.NotAPlaylist, it) }
        }
}
