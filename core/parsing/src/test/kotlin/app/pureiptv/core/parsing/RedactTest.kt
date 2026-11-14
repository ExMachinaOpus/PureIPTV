package app.pureiptv.core.parsing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RedactTest {
    @Test
    fun `credentials are removed from all url shapes`() {
        assertThat(redact("http://h:8080/live/john/s3cret/1.ts")).isEqualTo("http://h:8080/live/***/***/1.ts")
        assertThat(redact("http://h/get.php?username=john&password=x&type=m3u"))
            .isEqualTo("http://h/get.php?username=***&password=***&type=m3u")
        assertThat(redact("http://john:pw@h/list.m3u")).isEqualTo("http://***@h/list.m3u")
        assertThat(redact("http://cdn/stream.m3u8")).isEqualTo("http://cdn/stream.m3u8")
    }
}
