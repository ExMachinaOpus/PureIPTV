package app.pureiptv.core.data.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackChoiceTest {
    @Test
    fun `round trips languages and off`() {
        listOf(
            TrackChoice("sv", null),
            TrackChoice(null, "fa"),
            TrackChoice("en", "sv"),
            TrackChoice(),
        ).forEach { assertThat(TrackChoice.decode(it.encode())).isEqualTo(it) }
    }

    @Test
    fun `tolerates unknown or broken values`() {
        assertThat(TrackChoice.decode("")).isEqualTo(TrackChoice())
        assertThat(TrackChoice.decode("a=de;x=1")).isEqualTo(TrackChoice("de", null))
    }
}
