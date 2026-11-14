package app.pureiptv.core.data.library

import app.pureiptv.core.data.library.PlaybackRepository.Companion.cleanEpisodeTitle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpisodeTitleTest {

    @Test
    fun `provider prefixes and codes are removed`() {
        assertThat(cleanEpisodeTitle("S1E1 - Bunny", "Open Shorts")).isEqualTo("Bunny")
        assertThat(cleanEpisodeTitle("Open Shorts - S01E02 - Dream", "Open Shorts")).isEqualTo("Dream")
        assertThat(cleanEpisodeTitle("The Office 2x05 Halloween", "The Office")).isEqualTo("Halloween")
    }

    @Test
    fun `titles that are only a code become null`() {
        assertThat(cleanEpisodeTitle("S01E03", "X")).isNull()
        assertThat(cleanEpisodeTitle("Open Shorts - S01 E03", "Open Shorts")).isNull()
        assertThat(cleanEpisodeTitle("  ", null)).isNull()
    }

    @Test
    fun `real titles are kept`() {
        assertThat(cleanEpisodeTitle("Pilot", "Lost")).isEqualTo("Pilot")
        assertThat(cleanEpisodeTitle("Ozymandias", "Breaking Bad")).isEqualTo("Ozymandias")
    }
}
