package app.pureiptv.core.parsing.xtream

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.decodeFromString
import org.junit.Test

class XtreamTest {

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/xtream/$name")).reader().readText()

    @Test
    fun `messy live streams decode without throwing`() {
        val streams = XtreamJson.decodeFromString<List<XtreamLiveStream>>(fixture("live_streams_messy.json"))
        assertThat(streams).hasSize(4)

        val bbc = streams[0]
        assertThat(bbc.streamId).isEqualTo(1001)
        assertThat(bbc.tvArchive).isTrue()
        assertThat(bbc.tvArchiveDays).isEqualTo(7)
        assertThat(bbc.added).isEqualTo(1600000000)

        val itv = streams[1]
        assertThat(itv.streamId).isEqualTo(1002) // string id
        assertThat(itv.icon).isNull()            // empty string -> null
        assertThat(itv.categoryId).isEqualTo("12") // number -> string
        assertThat(itv.tvArchive).isFalse()

        assertThat(streams[2].isAdult).isTrue()

        val broken = streams[3]
        assertThat(broken.streamId).isNull()
        assertThat(broken.added).isNull()
        assertThat(broken.categoryId).isNull()
    }

    @Test
    fun `auth response, success and failure`() {
        val ok = XtreamJson.decodeFromString<XtreamAuthResponse>(
            """{"user_info":{"username":"u","auth":1,"status":"Active","exp_date":"1767225600","max_connections":"2",
               "allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"example.com","port":"8080","timezone":"Europe/London"}}""",
        )
        assertThat(ok.userInfo!!.auth).isTrue()
        assertThat(ok.userInfo!!.maxConnections).isEqualTo(2)
        assertThat(ok.userInfo!!.allowedOutputFormats).containsExactly("m3u8", "ts")

        val fail = XtreamJson.decodeFromString<XtreamAuthResponse>("""{"user_info":{"auth":0}}""")
        assertThat(fail.userInfo!!.auth).isFalse()

        val nullExp = XtreamJson.decodeFromString<XtreamAuthResponse>("""{"user_info":{"auth":"1","exp_date":null}}""")
        assertThat(nullExp.userInfo!!.expDate).isNull()
    }

    @Test
    fun `series info with object episodes`() {
        val info = XtreamJson.decodeFromString<XtreamSeriesInfo>(fixture("series_info_object.json"))
        val seasons = info.episodesBySeason()
        assertThat(seasons.keys).containsExactly(1, 2).inOrder()
        assertThat(seasons.getValue(1).map { it.id }).containsExactly("5001", "5002").inOrder()
        assertThat(seasons.getValue(1)[0].details.durationSecs).isEqualTo(3480)
        assertThat(seasons.getValue(1)[1].details.plot).isNull() // info: []
        assertThat(seasons.getValue(2)[0].id).isEqualTo("5101")   // numeric id
    }

    @Test
    fun `series info with array or empty episodes`() {
        val arrayForm = XtreamJson.decodeFromString<XtreamSeriesInfo>(
            """{"episodes":[[{"id":"1","season":1,"episode_num":1}],[{"id":"2","season":2,"episode_num":1}]]}""",
        )
        assertThat(arrayForm.episodesBySeason().keys).containsExactly(1, 2)

        val empty = XtreamJson.decodeFromString<XtreamSeriesInfo>("""{"info":[],"episodes":[]}""")
        assertThat(empty.episodesBySeason()).isEmpty()
    }

    @Test
    fun `vod info tolerates array info`() {
        val vod = XtreamJson.decodeFromString<XtreamVodInfo>("""{"info":[],"movie_data":{"stream_id":1}}""")
        assertThat(vod.details.plot).isNull()
        val vod2 = XtreamJson.decodeFromString<XtreamVodInfo>(
            """{"info":{"tmdb_id":603,"plot":"Neo","backdrop_path":"http://b/1.jpg","duration_secs":"8160"}}""",
        )
        assertThat(vod2.details.tmdbId).isEqualTo("603")
        assertThat(vod2.details.backdrops).containsExactly("http://b/1.jpg")
        assertThat(vod2.details.durationSecs).isEqualTo(8160)
    }

    @Test
    fun `short epg titles are base64 decoded`() {
        val epg = XtreamJson.decodeFromString<XtreamShortEpg>(
            """{"epg_listings":[{"title":"TmV3cyBhdCBUZW4=","description":"","start_timestamp":"1700000000","stop_timestamp":"1700001800"}]}""",
        )
        assertThat(epg.listings.single().decodedTitle).isEqualTo("News at Ten")
        assertThat(epg.listings.single().startEpochSec).isEqualTo(1700000000)
    }

    @Test
    fun `detects xtream behind playlist urls`() {
        val c = XtreamDetector.fromPlaylistUrl("http://example.com:8080/get.php?username=john&password=s3cr%40t&type=m3u_plus&output=ts")!!
        assertThat(c.serverUrl).isEqualTo("http://example.com:8080")
        assertThat(c.username).isEqualTo("john")
        assertThat(c.password).isEqualTo("s3cr@t")

        val prefixed = XtreamDetector.fromPlaylistUrl("https://example.com/panel/player_api.php?username=a&password=b")!!
        assertThat(prefixed.serverUrl).isEqualTo("https://example.com/panel")

        assertThat(XtreamDetector.fromPlaylistUrl("http://example.com/playlist.m3u")).isNull()
        assertThat(XtreamDetector.fromPlaylistUrl("http://example.com/get.php?username=a")).isNull()
    }

    @Test
    fun `detects xtream from stream urls`() {
        val live = XtreamDetector.fromStreamUrl("http://example.com:8080/live/john/pw/1001.ts")!!
        assertThat(live).isEqualTo(XtreamCredentials("http://example.com:8080", "john", "pw"))
        val short = XtreamDetector.fromStreamUrl("http://example.com:8080/john/pw/1001")!!
        assertThat(short.username).isEqualTo("john")
        assertThat(XtreamDetector.fromStreamUrl("http://cdn.example.com/hls/stream/index.m3u8")).isNull()
    }

    @Test
    fun `url builder encodes credentials`() {
        val urls = XtreamUrls(XtreamCredentials("http://h:80", "a b", "p&w"))
        assertThat(urls.api("get_live_streams", "category_id" to 5))
            .isEqualTo("http://h:80/player_api.php?username=a+b&password=p%26w&action=get_live_streams&category_id=5")
        assertThat(urls.movie(7, "mkv")).isEqualTo("http://h:80/movie/a+b/p%26w/7.mkv")
    }

    @Test
    fun `server url normalization`() {
        assertThat(XtreamDetector.normalizeServerUrl("example.com:8080/")).isEqualTo("http://example.com:8080")
        assertThat(XtreamDetector.normalizeServerUrl("https://x.com/player_api.php")).isEqualTo("https://x.com")
    }
}
