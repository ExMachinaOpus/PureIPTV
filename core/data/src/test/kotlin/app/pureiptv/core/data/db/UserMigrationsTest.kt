package app.pureiptv.core.data.db

import app.pureiptv.core.model.ItemKeys
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserMigrationsTest {
    private val url = "http://example.com:8080/movie/john/secret/12.mkv"
    private val hash = ItemKeys.urlKey(url)

    @Test
    fun `keys holding a playlist url get its hash instead`() {
        assertThat(UserMigrations.withoutUrl("movie:3:$url")).isEqualTo("movie:3:$hash")
        assertThat(UserMigrations.withoutUrl("zoom:stream:3:$url")).isEqualTo("zoom:stream:3:$hash")
        assertThat(UserMigrations.withoutUrl("zoom:stream:3:episode:$url")).isEqualTo("zoom:stream:3:episode:$hash")
        assertThat(UserMigrations.withoutUrl("tracks:movie:3:$url")).isEqualTo("tracks:movie:3:$hash")
    }

    @Test
    fun `the new key is what a fresh import produces`() {
        assertThat(UserMigrations.withoutUrl("movie:3:$url")).isEqualTo(ItemKeys.movie(3, ItemKeys.urlKey(url), null))
        assertThat(hash).doesNotContain("john")
        assertThat(hash).hasLength(33)
    }

    @Test
    fun `keys without a url are left alone`() {
        for (key in listOf(
            "channel:GB|bbcone", "movie:tmdb:603", "movie:tmdb:603:de", "movie:3:1245", "series:3:breakingbad",
            "episode:3:breakingbad:1:2", "zoom:stream:3:887", "tracks:channel:GB|bbcone", "show_adult", "ui_language",
            "channel:http://odd.example/id",
        )) {
            assertThat(UserMigrations.withoutUrl(key)).isNull()
        }
    }
}
