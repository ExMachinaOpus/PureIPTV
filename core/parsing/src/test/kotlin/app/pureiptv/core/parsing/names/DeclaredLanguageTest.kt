package app.pureiptv.core.parsing.names

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeclaredLanguageTest {

    @Test
    fun `reads two and three letter codes and English names`() {
        assertThat(DeclaredLanguage.parse("de")).isEqualTo("de")
        assertThat(DeclaredLanguage.parse("swe")).isEqualTo("sv")
        assertThat(DeclaredLanguage.parse("ger")).isEqualTo("de")
        assertThat(DeclaredLanguage.parse("Swedish")).isEqualTo("sv")
        assertThat(DeclaredLanguage.parse(" ENGLISH ")).isEqualTo("en")
        assertThat(DeclaredLanguage.parse("Farsi")).isEqualTo("fa")
    }

    @Test
    fun `a regional tag counts as its language`() {
        assertThat(DeclaredLanguage.parse("pt-BR")).isEqualTo("pt")
        assertThat(DeclaredLanguage.parse("en_GB")).isEqualTo("en")
    }

    @Test
    fun `first recognised of several wins`() {
        assertThat(DeclaredLanguage.parse("English;Spanish")).isEqualTo("en")
        assertThat(DeclaredLanguage.parse("Klingon, French")).isEqualTo("fr")
    }

    @Test
    fun `codes are language codes, not provider prefixes`() {
        assertThat(DeclaredLanguage.parse("uk")).isEqualTo("uk")
        assertThat(DeclaredLanguage.parse("se")).isEqualTo("se")
    }

    @Test
    fun `withdrawn codes give the current one`() {
        assertThat(DeclaredLanguage.parse("in")).isEqualTo("id")
        assertThat(DeclaredLanguage.parse("iw")).isEqualTo("he")
    }

    @Test
    fun `nothing usable gives null`() {
        assertThat(DeclaredLanguage.parse(null)).isNull()
        assertThat(DeclaredLanguage.parse("")).isNull()
        assertThat(DeclaredLanguage.parse("Undefined")).isNull()
        assertThat(DeclaredLanguage.parse("und")).isNull()
    }
}
