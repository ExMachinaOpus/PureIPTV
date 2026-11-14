package app.pureiptv.core.parsing.m3u

import app.pureiptv.core.model.ContentKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.StringReader

class M3uParserTest {

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/m3u/$name")).reader()

    private val items = M3uParser.parse(fixture("mixed.m3u")).toList()
    private val entries = items.filterIsInstance<M3uEntry>()

    @Test
    fun `header with BOM yields deduplicated epg urls`() {
        val header = items.first() as M3uHeader
        assertThat(header.epgUrls).containsExactly(
            "http://epg.example.com/guide.xml.gz",
            "http://epg2.example.com/a.xml",
        ).inOrder()
    }

    @Test
    fun `parses all entries including bare url`() {
        assertThat(entries).hasSize(10)
        assertThat(entries.last().url).isEqualTo("http://cdn.example.com/bare-url-without-extinf.m3u8")
        assertThat(entries.last().name).isEqualTo("bare-url-without-extinf.m3u8")
    }

    @Test
    fun `standard attributes`() {
        val bbc = entries[0]
        assertThat(bbc.tvgId).isEqualTo("BBCOne.uk")
        assertThat(bbc.tvgLogo).isEqualTo("http://logo/bbc1.png")
        assertThat(bbc.group).isEqualTo("UK | Entertainment")
        assertThat(bbc.title).isEqualTo("UK: BBC One FHD")
        assertThat(bbc.durationSeconds).isEqualTo(-1)
    }

    @Test
    fun `commas inside quoted values do not end the attribute list`() {
        val sky = entries[1]
        assertThat(sky.tvgName).isEqualTo("Sky, Sports Main Event")
        assertThat(sky.group).isEqualTo("UK | Sports, Premium")
        assertThat(sky.title).isEqualTo("|UK| Sky Sports Main Event ᴴᴰ")
        assertThat(sky.tvgId).isNull() // empty tvg-id is treated as missing
    }

    @Test
    fun `single quotes and unquoted values`() {
        val cnn = entries[2]
        assertThat(cnn.tvgId).isEqualTo("cnn.us")
        assertThat(cnn.group).isEqualTo("US")
    }

    @Test
    fun `EXTVLCOPT headers are attached`() {
        val cnn = entries[2]
        assertThat(cnn.headers.userAgent).isEqualTo("Mozilla/5.0 (SMART-TV)")
        assertThat(cnn.headers.referrer).isEqualTo("http://referer.example.com/")
    }

    @Test
    fun `kodi pipe headers are split off the url`() {
        val sport1 = entries[3]
        assertThat(sport1.url).isEqualTo("http://cdn.example.com/sport1.m3u8")
        assertThat(sport1.headers.userAgent).isEqualTo("VLC/3.0")
        assertThat(sport1.headers.referrer).isEqualTo("http://x.example.com/")
    }

    @Test
    fun `KODIPROP before EXTINF applies to the next entry`() {
        val tf1 = entries[8]
        assertThat(tf1.title).isEqualTo("FR: TF1")
        assertThat(tf1.headers.userAgent).isEqualTo("Kodi")
        assertThat(tf1.headers.referrer).isEqualTo("http://k.example.com/")
    }

    @Test
    fun `EXTGRP is used when group-title is missing`() {
        assertThat(entries[7].group).isEqualTo("Misc")
    }

    @Test
    fun `classification`() {
        val kinds = entries.map { M3uClassifier.classify(it) }
        assertThat(kinds[0]).isEqualTo(ContentKind.Live)
        assertThat(kinds[2]).isEqualTo(ContentKind.Live)
        assertThat(kinds[5]).isEqualTo(ContentKind.Movie)
        assertThat(kinds[6]).isEqualTo(ContentKind.Series)
        assertThat(kinds[7]).isEqualTo(ContentKind.Live)
    }

    @Test
    fun `no header, CRLF line endings, missing title`() {
        val text = "#EXTINF:-1 tvg-name=\"Fallback Name\"\r\nhttp://a/b.ts\r\n"
        val e = M3uParser.parse(StringReader(text)).filterIsInstance<M3uEntry>().single()
        assertThat(e.url).isEqualTo("http://a/b.ts")
        assertThat(e.name).isEqualTo("Fallback Name")
    }

    @Test
    fun `garbage input does not throw`() {
        val text = "#EXTINF:abc tvg-id=\"unterminated ,Name\nhttp://x\n#EXTINF:\n\n#RANDOM\nnot a url but a line\n"
        val result = M3uParser.parse(StringReader(text)).toList()
        assertThat(result).isNotEmpty()
    }

    @Test
    fun `large playlist streams without holding everything`() {
        val count = 200_000
        val reader = object : java.io.Reader() {
            var i = 0
            var buf = ""
            var pos = 0
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (pos >= buf.length) {
                    if (i >= count) return -1
                    buf = "#EXTINF:-1 tvg-id=\"c$i\" group-title=\"G${i % 50}\",UK: Channel $i HD\nhttp://h/live/u/p/$i.ts\n"
                    pos = 0
                    i++
                }
                val n = minOf(len, buf.length - pos)
                buf.toCharArray(cbuf, off, pos, pos + n)
                pos += n
                return n
            }
            override fun close() = Unit
        }
        val start = System.nanoTime()
        val n = M3uParser.parse(reader).count { it is M3uEntry }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertThat(n).isEqualTo(count)
        // Generous bound for CI machines; the Google TV Streamer budget is tracked separately.
        assertThat(ms).isLessThan(10_000)
    }

    @Test
    fun `a line too long to be a playlist line is skipped with its entry`() {
        val huge = "x".repeat(M3uParser.MAX_LINE_LENGTH + 10)
        val playlist = buildString {
            append("#EXTM3U\r\n")
            append("#EXTINF:-1,One\r\nhttp://example.com/1\r\n")
            append("#EXTINF:-1 tvg-logo=\"").append(huge).append("\",Broken\r\nhttp://example.com/broken\r\n")
            append("#EXTINF:-1,Two\nhttp://example.com/2\n")
            append("#EXTINF:-1,Lost\nhttp://example.com/").append(huge).append("\n")
            append("# ").append(huge).append("\n")
            append("#EXTINF:-1,Three\rhttp://example.com/3")
        }
        val entries = M3uParser.parse(java.io.StringReader(playlist)).filterIsInstance<M3uEntry>().toList()
        assertThat(entries.map { it.title }).containsExactly("One", "Two", "Three").inOrder()
        assertThat(entries.map { it.url }).containsExactly("http://example.com/1", "http://example.com/2", "http://example.com/3").inOrder()
    }

    @Test
    fun `an endless line never ends up in memory`() {
        // 200 MB without a line break, then one real entry.
        val endless = object : java.io.Reader() {
            private var left = 200L * 1024 * 1024
            private val tail = java.io.StringReader("\n#EXTINF:-1,After\nhttp://example.com/after\n")
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (left <= 0) return tail.read(cbuf, off, len)
                val n = minOf(len.toLong(), left).toInt()
                java.util.Arrays.fill(cbuf, off, off + n, 'a')
                left -= n
                return n
            }
            override fun close() = Unit
        }
        val entries = M3uParser.parse(endless).filterIsInstance<M3uEntry>().toList()
        assertThat(entries.map { it.title }).containsExactly("After")
    }
}
