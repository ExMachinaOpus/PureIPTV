package app.pureiptv.core.parsing.names

import app.pureiptv.core.model.Quality
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TitleParserTest {

    @Test
    fun `strips prefix, quality and extracts year`() {
        val t = TitleParser.parse("EN - The Matrix (1999) 4K")
        assertThat(t.title).isEqualTo("The Matrix")
        assertThat(t.year).isEqualTo(1999)
        assertThat(t.language).isEqualTo("en")
        assertThat(t.quality).isEqualTo(Quality.UHD)
    }

    @Test
    fun `trailing year forms`() {
        assertThat(TitleParser.parse("Dune - 2021").year).isEqualTo(2021)
        assertThat(TitleParser.parse("Dune - 2021").title).isEqualTo("Dune")
        assertThat(TitleParser.parse("Oppenheimer 2023").year).isEqualTo(2023)
        assertThat(TitleParser.parse("Blade Runner [1982]").title).isEqualTo("Blade Runner")
    }

    @Test
    fun `a year-like title is kept`() {
        val t = TitleParser.parse("2012")
        assertThat(t.title).isEqualTo("2012")
        assertThat(t.year).isNull()
    }

    @Test
    fun `category region`() {
        assertThat(ChannelNameParser.categoryRegion("UK | Movies").country).isEqualTo("GB")
        assertThat(ChannelNameParser.categoryRegion("Action").country).isNull()
    }

    @Test
    fun `provider labels are removed`() {
        assertThat(TitleParser.parse("NF - Mulan").title).isEqualTo("Mulan")
        assertThat(TitleParser.parse("D+ - Mulan II").title).isEqualTo("Mulan II")
        assertThat(TitleParser.parse("TOP - Simulant").title).isEqualTo("Simulant")
        assertThat(TitleParser.parse("AS - Fighting Mulan").title).isEqualTo("Fighting Mulan")
        assertThat(TitleParser.parse("ANM-S - Reincarnated as a Sword").title).isEqualTo("Reincarnated as a Sword")
        assertThat(TitleParser.parse("AR-TR-S - Börü 2039").title).isEqualTo("Börü 2039")
        assertThat(TitleParser.parse("AR-TR-S - Börü 2039").year).isNull()
        assertThat(TitleParser.parse("M - Eine Stadt sucht einen Mörder").title).isEqualTo("M - Eine Stadt sucht einen Mörder")
    }

    @Test
    fun `language comes from the label, else the category`() {
        assertThat(TitleParser.parse("4K-EN - John Mulaney: Baby J").language).isEqualTo("en")
        assertThat(TitleParser.parse("4K-EN - John Mulaney: Baby J").title).isEqualTo("John Mulaney: Baby J")
        assertThat(TitleParser.parse("Mulan", "DE - KINDER FILME").language).isEqualTo("de")
        assertThat(TitleParser.parse("DE - Mulan").language).isEqualTo("de")
        assertThat(TitleParser.parse("Mulan", "DISNEY+ MOVIES").language).isNull()
    }

    @Test
    fun `a declared language beats the label and the category`() {
        assertThat(TitleParser.parse("EN - The Matrix", "|DE| Filme", declaredLanguage = "fr").language).isEqualTo("fr")
        assertThat(TitleParser.parse("EN - The Matrix", "|DE| Filme").language).isEqualTo("en")
    }

    @Test
    fun `year from a typed field`() {
        assertThat(TitleParser.yearFromField("1999")).isEqualTo(1999)
        assertThat(TitleParser.yearFromField(" 2019-03-01")).isEqualTo(2019)
        assertThat(TitleParser.yearFromField("0")).isNull()
        assertThat(TitleParser.yearFromField("01/03/2019")).isNull()
        assertThat(TitleParser.yearFromField("2039")).isNull()
        assertThat(TitleParser.yearFromField(null)).isNull()
    }
}
