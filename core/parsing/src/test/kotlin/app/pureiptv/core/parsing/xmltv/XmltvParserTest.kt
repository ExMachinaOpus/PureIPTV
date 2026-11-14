package app.pureiptv.core.parsing.xmltv

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.time.Instant

class XmltvParserTest {

    private val parser = XmltvParser { KXmlParser() }

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="BBCOne.uk">
            <display-name lang="en">BBC One</display-name>
            <display-name>BBC 1</display-name>
            <icon src="http://logo/bbc1.png"/>
            <url>http://bbc.co.uk</url>
          </channel>
          <channel id="">
            <display-name>No id, skipped</display-name>
          </channel>
          <programme start="20250101120000 +0100" stop="20250101130000 +0100" channel="BBCOne.uk">
            <title lang="en">News at Ten &amp; More</title>
            <sub-title>Special</sub-title>
            <desc lang="en">The latest news.</desc>
            <credits><director>Someone</director></credits>
            <category>News</category>
            <category>Current affairs</category>
            <episode-num system="onscreen">Ep 5</episode-num>
            <episode-num system="xmltv_ns">1.4.0/1</episode-num>
            <icon src="http://img/p.jpg"/>
          </programme>
          <programme start="20250101130000" channel="BBCOne.uk">
            <title>No stop, UTC assumed</title>
          </programme>
          <programme start="garbage" channel="BBCOne.uk"><title>Bad time, skipped</title></programme>
          <programme start="20250101140000 +0000" channel="BBCOne.uk"></programme>
        </tv>
    """.trimIndent()

    @Test
    fun `parses channels and programmes`() {
        val items = parser.parse(xml.byteInputStream()).toList()
        val channels = items.filterIsInstance<XmltvChannel>()
        val programmes = items.filterIsInstance<XmltvProgramme>()

        assertThat(channels).containsExactly(XmltvChannel("BBCOne.uk", listOf("BBC One", "BBC 1"), "http://logo/bbc1.png"))
        assertThat(programmes).hasSize(2)

        val p = programmes[0]
        assertThat(p.title).isEqualTo("News at Ten & More")
        assertThat(p.subtitle).isEqualTo("Special")
        assertThat(p.description).isEqualTo("The latest news.")
        assertThat(p.categories).containsExactly("News", "Current affairs").inOrder()
        assertThat(p.episode).isEqualTo("S2 E5")
        assertThat(p.iconUrl).isEqualTo("http://img/p.jpg")
        assertThat(p.startEpochMs).isEqualTo(Instant.parse("2025-01-01T11:00:00Z").toEpochMilli())
        assertThat(p.stopEpochMs).isEqualTo(Instant.parse("2025-01-01T12:00:00Z").toEpochMilli())

        assertThat(programmes[1].startEpochMs).isEqualTo(Instant.parse("2025-01-01T13:00:00Z").toEpochMilli())
        assertThat(programmes[1].stopEpochMs).isNull()
    }

    @Test
    fun `truncated file keeps what was parsed`() {
        val truncated = xml.substring(0, xml.indexOf("<programme start=\"20250101130000\""))
        val items = parser.parse(truncated.byteInputStream()).toList()
        assertThat(items.filterIsInstance<XmltvProgramme>()).hasSize(1)
    }

    @Test
    fun `time formats`() {
        assertThat(XmltvParser.parseTime("20250101120000 +0100"))
            .isEqualTo(Instant.parse("2025-01-01T11:00:00Z").toEpochMilli())
        assertThat(XmltvParser.parseTime("20250101120000-0500"))
            .isEqualTo(Instant.parse("2025-01-01T17:00:00Z").toEpochMilli())
        assertThat(XmltvParser.parseTime("202501011200"))
            .isEqualTo(Instant.parse("2025-01-01T12:00:00Z").toEpochMilli())
        assertThat(XmltvParser.parseTime("2025")).isNull()
    }
}
