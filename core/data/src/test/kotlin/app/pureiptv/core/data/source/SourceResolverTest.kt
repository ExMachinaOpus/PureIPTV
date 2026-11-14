package app.pureiptv.core.data.source

import app.pureiptv.core.data.credentials.SourceSecret
import app.pureiptv.core.data.db.SourceType
import app.pureiptv.core.model.SourceErrorKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class SourceResolverTest {

    private val server = MockWebServer()
    private val http = OkHttpClient()
    private val resolver = SourceResolver(http, XtreamClient(http))

    /** path (+query) -> response */
    private val routes = HashMap<String, MockResponse>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                val key = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
                return routes[key] ?: routes[url.encodedPath] ?: MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
    private fun url(path: String) = server.url(path).toString()

    private suspend fun errorOf(input: SourceInput): SourceErrorKind? =
        try {
            resolver.resolve(input)
            null
        } catch (e: SourceException) {
            e.kind
        }

    private val authOk = """{"user_info":{"auth":1,"status":"Active","exp_date":"4102444800",
        "allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"x"}}"""

    @Test
    fun `plain m3u link`() = runTest {
        routes["/list.m3u"] = ok("#EXTM3U\n#EXTINF:-1,One\nhttp://a/1.ts\n")
        val r = resolver.resolve(SourceInput.Link(url("/list.m3u")))
        assertThat(r.type).isEqualTo(SourceType.M3u)
        assertThat(r.secret).isEqualTo(SourceSecret.M3u(url("/list.m3u")))
        assertThat(r.name).isEqualTo(server.hostName)
    }

    @Test
    fun `html page is not a playlist`() = runTest {
        routes["/page"] = ok("<html><body>Login</body></html>")
        assertThat(errorOf(SourceInput.Link(url("/page")))).isEqualTo(SourceErrorKind.NotAPlaylist)
    }

    @Test
    fun `404 is not a playlist, 401 is a login problem`() = runTest {
        assertThat(errorOf(SourceInput.Link(url("/missing.m3u")))).isEqualTo(SourceErrorKind.NotAPlaylist)
        routes["/private.m3u"] = MockResponse.Builder().code(401).build()
        assertThat(errorOf(SourceInput.Link(url("/private.m3u")))).isEqualTo(SourceErrorKind.InvalidCredentials)
    }

    @Test
    fun `xtream link is upgraded to the api`() = runTest {
        routes["/player_api.php"] = ok(authOk)
        val r = resolver.resolve(SourceInput.Link(url("/get.php?username=john&password=pw&type=m3u_plus")))
        assertThat(r.type).isEqualTo(SourceType.Xtream)
        assertThat(r.liveFormat).isEqualTo("m3u8")
        val secret = r.secret as SourceSecret.Xtream
        assertThat(secret.username).isEqualTo("john")
        assertThat(secret.password).isEqualTo("pw")
    }

    @Test
    fun `xtream-looking link that is really a plain playlist falls back to m3u`() = runTest {
        routes["/player_api.php"] = ok("not json")
        routes["/get.php"] = ok("#EXTM3U\n")
        val r = resolver.resolve(SourceInput.Link(url("/get.php?username=a&password=b")))
        assertThat(r.type).isEqualTo(SourceType.M3u)
    }

    @Test
    fun `login errors`() = runTest {
        val login = SourceInput.Login(server.url("/").toString(), "john", "pw")
        routes["/player_api.php"] = ok("""{"user_info":{"auth":0}}""")
        assertThat(errorOf(login)).isEqualTo(SourceErrorKind.InvalidCredentials)

        routes["/player_api.php"] = ok("""{"user_info":{"auth":1,"status":"Expired"}}""")
        assertThat(errorOf(login)).isEqualTo(SourceErrorKind.AccountExpired)

        routes["/player_api.php"] = ok("""{"user_info":{"auth":1,"status":"Active","exp_date":"1000"}}""")
        assertThat(errorOf(login)).isEqualTo(SourceErrorKind.AccountExpired)

        routes["/player_api.php"] = ok("<html>nginx</html>")
        assertThat(errorOf(login)).isEqualTo(SourceErrorKind.NotAPlaylist)
    }

    @Test
    fun `login with bare host and ts-only account`() = runTest {
        routes["/player_api.php"] = ok("""{"user_info":{"auth":1,"allowed_output_formats":["ts"]}}""")
        val host = "${server.hostName}:${server.port}"
        val r = resolver.resolve(SourceInput.Login(host, " john ", "pw"))
        assertThat(r.liveFormat).isEqualTo("ts")
        assertThat((r.secret as SourceSecret.Xtream).username).isEqualTo("john")
    }

    @Test
    fun `unreachable server`() = runTest {
        val dead = "http://127.0.0.1:1/list.m3u"
        assertThat(errorOf(SourceInput.Link(dead))).isEqualTo(SourceErrorKind.Unreachable)
    }

    @Test
    fun `garbage input`() = runTest {
        assertThat(errorOf(SourceInput.Link("   "))).isNotNull()
        assertThat(errorOf(SourceInput.Link("ht!tp://%%%"))).isNotNull()
    }
}
