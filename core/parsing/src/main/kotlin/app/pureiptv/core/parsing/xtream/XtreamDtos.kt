package app.pureiptv.core.parsing.xtream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

// Wire format of the Xtream Codes `player_api.php`. Every field is optional and lenient on purpose.
// Map these to domain types in core:data; never expose DTOs to the UI.

@Serializable
data class XtreamAuthResponse(
    @SerialName("user_info") val userInfo: XtreamUserInfo? = null,
    @SerialName("server_info") val serverInfo: XtreamServerInfo? = null,
)

@Serializable
data class XtreamUserInfo(
    @Serializable(LenientString::class) val username: String? = null,
    @Serializable(LenientString::class) val message: String? = null,
    @Serializable(LenientBool::class) val auth: Boolean? = null,
    @Serializable(LenientString::class) val status: String? = null,
    @SerialName("exp_date") @Serializable(LenientLong::class) val expDate: Long? = null,
    @SerialName("is_trial") @Serializable(LenientBool::class) val isTrial: Boolean? = null,
    @SerialName("active_cons") @Serializable(LenientInt::class) val activeConnections: Int? = null,
    @SerialName("max_connections") @Serializable(LenientInt::class) val maxConnections: Int? = null,
    @SerialName("allowed_output_formats") @Serializable(LenientStringList::class)
    val allowedOutputFormats: List<String> = emptyList(),
)

@Serializable
data class XtreamServerInfo(
    @Serializable(LenientString::class) val url: String? = null,
    @Serializable(LenientString::class) val port: String? = null,
    @SerialName("https_port") @Serializable(LenientString::class) val httpsPort: String? = null,
    @SerialName("server_protocol") @Serializable(LenientString::class) val protocol: String? = null,
    @Serializable(LenientString::class) val timezone: String? = null,
    @SerialName("timestamp_now") @Serializable(LenientLong::class) val timestampNow: Long? = null,
)

@Serializable
data class XtreamCategory(
    @SerialName("category_id") @Serializable(LenientString::class) val id: String? = null,
    @SerialName("category_name") @Serializable(LenientString::class) val name: String? = null,
    @SerialName("parent_id") @Serializable(LenientString::class) val parentId: String? = null,
)

@Serializable
data class XtreamLiveStream(
    @Serializable(LenientInt::class) val num: Int? = null,
    @Serializable(LenientString::class) val name: String? = null,
    @SerialName("stream_id") @Serializable(LenientLong::class) val streamId: Long? = null,
    @SerialName("stream_icon") @Serializable(LenientString::class) val icon: String? = null,
    @SerialName("epg_channel_id") @Serializable(LenientString::class) val epgChannelId: String? = null,
    @Serializable(LenientLong::class) val added: Long? = null,
    @SerialName("is_adult") @Serializable(LenientBool::class) val isAdult: Boolean? = null,
    @SerialName("category_id") @Serializable(LenientString::class) val categoryId: String? = null,
    @SerialName("tv_archive") @Serializable(LenientBool::class) val tvArchive: Boolean? = null,
    @SerialName("tv_archive_duration") @Serializable(LenientInt::class) val tvArchiveDays: Int? = null,
    @SerialName("direct_source") @Serializable(LenientString::class) val directSource: String? = null,
)

@Serializable
data class XtreamVodStream(
    @Serializable(LenientInt::class) val num: Int? = null,
    @Serializable(LenientString::class) val name: String? = null,
    @SerialName("stream_id") @Serializable(LenientLong::class) val streamId: Long? = null,
    @SerialName("stream_icon") @Serializable(LenientString::class) val icon: String? = null,
    @Serializable(LenientDouble::class) val rating: Double? = null,
    @Serializable(LenientLong::class) val added: Long? = null,
    @SerialName("is_adult") @Serializable(LenientBool::class) val isAdult: Boolean? = null,
    @SerialName("category_id") @Serializable(LenientString::class) val categoryId: String? = null,
    @SerialName("container_extension") @Serializable(LenientString::class) val containerExtension: String? = null,
    @Serializable(LenientString::class) val tmdb: String? = null,
    @SerialName("tmdb_id") @Serializable(LenientString::class) val tmdbId: String? = null,
    @Serializable(LenientString::class) val year: String? = null,
    @SerialName("direct_source") @Serializable(LenientString::class) val directSource: String? = null,
) {
    val tmdbAny: String? get() = tmdb ?: tmdbId
}

@Serializable
data class XtreamSeries(
    @Serializable(LenientInt::class) val num: Int? = null,
    @Serializable(LenientString::class) val name: String? = null,
    @SerialName("series_id") @Serializable(LenientLong::class) val seriesId: Long? = null,
    @Serializable(LenientString::class) val cover: String? = null,
    @Serializable(LenientString::class) val plot: String? = null,
    @Serializable(LenientString::class) val cast: String? = null,
    @Serializable(LenientString::class) val director: String? = null,
    @Serializable(LenientString::class) val genre: String? = null,
    @SerialName("releaseDate") @Serializable(LenientString::class) val releaseDate: String? = null,
    @SerialName("release_date") @Serializable(LenientString::class) val releaseDate2: String? = null,
    @SerialName("last_modified") @Serializable(LenientLong::class) val lastModified: Long? = null,
    @Serializable(LenientDouble::class) val rating: Double? = null,
    @SerialName("backdrop_path") @Serializable(LenientStringList::class) val backdrops: List<String> = emptyList(),
    @SerialName("youtube_trailer") @Serializable(LenientString::class) val trailer: String? = null,
    @SerialName("category_id") @Serializable(LenientString::class) val categoryId: String? = null,
    @Serializable(LenientString::class) val tmdb: String? = null,
    @SerialName("tmdb_id") @Serializable(LenientString::class) val tmdbId: String? = null,
) {
    val tmdbAny: String? get() = tmdb ?: tmdbId
    val release: String? get() = releaseDate ?: releaseDate2
}

/** Response of `get_series_info`. `episodes` is normally `{"1":[..]}` but some panels send `[[..]]` or `[]`. */
@Serializable
data class XtreamSeriesInfo(
    val info: JsonElement? = null,
    val episodes: JsonElement? = null,
) {
    fun episodesBySeason(): Map<Int, List<XtreamEpisode>> {
        val decoded: List<XtreamEpisode> = when (val e = episodes) {
            is JsonObject -> e.values.flatMap { decodeList(it) }
            is JsonArray -> e.flatMap { decodeList(it) }
            else -> emptyList()
        }
        return decoded.groupBy { it.season ?: 1 }.toSortedMap()
    }

    private fun decodeList(element: JsonElement): List<XtreamEpisode> = when (element) {
        is JsonArray -> element.mapNotNull { runCatching { XtreamJson.decodeFromJsonElement<XtreamEpisode>(it) }.getOrNull() }
        is JsonObject -> listOfNotNull(runCatching { XtreamJson.decodeFromJsonElement<XtreamEpisode>(element) }.getOrNull())
        else -> emptyList()
    }
}

@Serializable
data class XtreamEpisode(
    @Serializable(LenientString::class) val id: String? = null,
    @SerialName("episode_num") @Serializable(LenientInt::class) val episodeNum: Int? = null,
    @Serializable(LenientString::class) val title: String? = null,
    @SerialName("container_extension") @Serializable(LenientString::class) val containerExtension: String? = null,
    @Serializable(LenientInt::class) val season: Int? = null,
    @Serializable(LenientLong::class) val added: Long? = null,
    val info: JsonElement? = null,
) {
    val details: XtreamEpisodeDetails
        get() = runCatching { XtreamJson.decodeFromJsonElement<XtreamEpisodeDetails>(info.asObjectOrEmpty()) }
            .getOrDefault(XtreamEpisodeDetails())
}

@Serializable
data class XtreamEpisodeDetails(
    @Serializable(LenientString::class) val plot: String? = null,
    @SerialName("movie_image") @Serializable(LenientString::class) val image: String? = null,
    @SerialName("duration_secs") @Serializable(LenientLong::class) val durationSecs: Long? = null,
    @Serializable(LenientDouble::class) val rating: Double? = null,
    @SerialName("releasedate") @Serializable(LenientString::class) val releaseDate: String? = null,
)

/** Response of `get_vod_info`. `info` may be `[]` on some panels. */
@Serializable
data class XtreamVodInfo(
    val info: JsonElement? = null,
) {
    val details: XtreamVodDetails
        get() = runCatching { XtreamJson.decodeFromJsonElement<XtreamVodDetails>(info.asObjectOrEmpty()) }
            .getOrDefault(XtreamVodDetails())
}

@Serializable
data class XtreamVodDetails(
    @SerialName("tmdb_id") @Serializable(LenientString::class) val tmdbId: String? = null,
    @Serializable(LenientString::class) val name: String? = null,
    @Serializable(LenientString::class) val plot: String? = null,
    @Serializable(LenientString::class) val description: String? = null,
    @Serializable(LenientString::class) val cast: String? = null,
    @Serializable(LenientString::class) val director: String? = null,
    @Serializable(LenientString::class) val genre: String? = null,
    @SerialName("releasedate") @Serializable(LenientString::class) val releaseDate: String? = null,
    @SerialName("duration_secs") @Serializable(LenientLong::class) val durationSecs: Long? = null,
    @SerialName("movie_image") @Serializable(LenientString::class) val image: String? = null,
    @SerialName("backdrop_path") @Serializable(LenientStringList::class) val backdrops: List<String> = emptyList(),
    @Serializable(LenientDouble::class) val rating: Double? = null,
    @SerialName("youtube_trailer") @Serializable(LenientString::class) val trailer: String? = null,
)

@Serializable
data class XtreamShortEpg(
    @SerialName("epg_listings") val listings: List<XtreamEpgListing> = emptyList(),
)

/** Titles and descriptions are base64 encoded by the panel. */
@Serializable
data class XtreamEpgListing(
    @SerialName("epg_id") @Serializable(LenientString::class) val epgId: String? = null,
    @Serializable(LenientString::class) val title: String? = null,
    @Serializable(LenientString::class) val description: String? = null,
    @SerialName("start_timestamp") @Serializable(LenientLong::class) val startEpochSec: Long? = null,
    @SerialName("stop_timestamp") @Serializable(LenientLong::class) val stopEpochSec: Long? = null,
) {
    val decodedTitle: String? get() = title?.let(::decodeBase64OrSelf)
    val decodedDescription: String? get() = description?.let(::decodeBase64OrSelf)
}

internal fun decodeBase64OrSelf(s: String): String =
    runCatching { String(java.util.Base64.getDecoder().decode(s.trim()), Charsets.UTF_8) }.getOrDefault(s)
