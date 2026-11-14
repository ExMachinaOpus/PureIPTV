package app.pureiptv.core.data.library

import app.pureiptv.core.model.ItemKeys
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContinueAtTest {
    private val episodes = listOf("E1", "E2", "E3")
    private val minutes45 = 2_700_000L

    private fun at(positionMs: Long) = Progress(positionMs, minutes45)

    @Test
    fun `an episode left midway resumes`() {
        assertThat(continueAt(episodes, 1, at(600_000))).isEqualTo(ContinueAt("E2", resume = true))
    }

    @Test
    fun `an episode left after a few seconds stays current and starts over`() {
        assertThat(continueAt(episodes, 1, at(6_000))).isEqualTo(ContinueAt("E2", resume = false))
    }

    @Test
    fun `an episode with no saved position stays current`() {
        assertThat(continueAt(episodes, 1, null)).isEqualTo(ContinueAt("E2", resume = false))
    }

    @Test
    fun `just before the finish line is not finished`() {
        assertThat(continueAt(episodes, 1, at((minutes45 * 0.94).toLong()))).isEqualTo(ContinueAt("E2", resume = true))
    }

    @Test
    fun `a finished episode moves on to the next, from the start`() {
        assertThat(continueAt(episodes, 1, at(minutes45))).isEqualTo(ContinueAt("E3", resume = false))
    }

    @Test
    fun `the last episode finished means nothing is left`() {
        assertThat(continueAt(episodes, 2, at(minutes45))).isNull()
    }

    @Test
    fun `episode keys are read back`() {
        val key = ItemKeys.episode("series:tmdb:5:en", 2, 7)
        assertThat(ItemKeys.seasonAndEpisode(key, "series:tmdb:5:en")).isEqualTo(2 to 7)
    }

    @Test
    fun `a series does not claim the episodes of its dubbed version`() {
        val dubbed = ItemKeys.episode("series:tmdb:5:en", 2, 7)
        assertThat(ItemKeys.seasonAndEpisode(dubbed, "series:tmdb:5")).isNull()
    }

    @Test
    fun `a series does not claim the episodes of another series`() {
        assertThat(ItemKeys.seasonAndEpisode(ItemKeys.episode("series:tmdb:51", 1, 1), "series:tmdb:5")).isNull()
    }
}
