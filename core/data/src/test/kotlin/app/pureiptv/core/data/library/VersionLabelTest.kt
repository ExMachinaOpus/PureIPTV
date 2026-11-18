package app.pureiptv.core.data.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VersionLabelTest {

    @Test
    fun `distinct labels stay as they are`() {
        assertThat(PlaybackRepository.uniqueLabels(listOf("NORDIC SERIES", "NORDIC SERIES 4K"))).containsExactly("NORDIC SERIES", "NORDIC SERIES 4K").inOrder()
    }

    @Test
    fun `labels that read the same are numbered`() {
        assertThat(PlaybackRepository.uniqueLabels(listOf("SVT1 HD", "SVT1 FHD", "SVT1 HD")))
            .containsExactly("SVT1 HD (1)", "SVT1 FHD", "SVT1 HD (2)").inOrder()
    }
}
