package app.pureiptv.core.parsing.names

import app.pureiptv.core.model.Genre
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GenreAndEpisodeTest {

    @Test
    fun `genre from category first`() {
        assertThat(GenreClassifier.classify("UK | Sports", "Sky Mix")).isEqualTo(Genre.Sports)
        assertThat(GenreClassifier.classify("DE | Nachrichten", "n-tv")).isEqualTo(Genre.News)
        assertThat(GenreClassifier.classify("ES | Deportes", "Gol")).isEqualTo(Genre.Sports)
        assertThat(GenreClassifier.classify("PT | Documentários", "X")).isEqualTo(Genre.Documentary)
        assertThat(GenreClassifier.classify("FR | Jeunesse", "Gulli")).isEqualTo(Genre.Kids)
    }

    @Test
    fun `genre from name when category is unhelpful`() {
        assertThat(GenreClassifier.classify("UK", "Sky Sports F1")).isEqualTo(Genre.Sports)
        assertThat(GenreClassifier.classify("US", "CNN")).isEqualTo(Genre.News)
        assertThat(GenreClassifier.classify(null, "Disney Junior")).isEqualTo(Genre.Kids)
        assertThat(GenreClassifier.classify("UK", "BBC One")).isEqualTo(Genre.Other)
    }

    @Test
    fun `episode names`() {
        val e = EpisodeNameParser.parse("Breaking Bad S01 E02 - Cat's in the Bag")!!
        assertThat(e.seriesTitle).isEqualTo("Breaking Bad")
        assertThat(e.season).isEqualTo(1)
        assertThat(e.episode).isEqualTo(2)
        assertThat(e.episodeTitle).isEqualTo("Cat's in the Bag")

        assertThat(EpisodeNameParser.parse("The.Office.S05E14")!!.seriesTitle).isEqualTo("The.Office")
        assertThat(EpisodeNameParser.parse("Friends 3x07")!!.episode).isEqualTo(7)
        assertThat(EpisodeNameParser.parse("The Matrix (1999)")).isNull()
        assertThat(EpisodeNameParser.parse("S01E01")).isNull()
    }
}
