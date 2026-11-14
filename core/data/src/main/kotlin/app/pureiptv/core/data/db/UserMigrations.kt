package app.pureiptv.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.pureiptv.core.model.ItemKeys

/** Migrations of user.db. Never destructive: this is the user's own data. */
internal object UserMigrations {

    /**
     * Takes provider logins out of user.db (it is backed up):
     * - `source.epg_url` is dropped. The guide URL is found again at the next refresh and kept in
     *   the encrypted credential store.
     * - keys that contained a playlist URL (M3U movies, per-feed zoom, remembered tracks) now carry
     *   a hash of that URL instead, so favourites, progress and history stay attached.
     * - `source.last_attempt_ms` is new.
     */
    val V1_TO_V2 = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE source DROP COLUMN epg_url")
            connection.execSQL("ALTER TABLE source ADD COLUMN last_attempt_ms INTEGER")
            rekey(connection, "favorite", "item_key")
            rekey(connection, "watch_progress", "item_key")
            rekey(connection, "history", "item_key")
            rekey(connection, "setting", "key")
        }
    }

    private fun rekey(connection: SQLiteConnection, table: String, column: String) {
        val old = ArrayList<String>()
        connection.prepare("SELECT DISTINCT `$column` FROM `$table` WHERE `$column` LIKE '%://%'").use { query ->
            while (query.step()) old += query.getText(0)
        }
        if (old.isEmpty()) return
        // OR REPLACE: two old keys can't hash to the same new one, but a key column must never
        // make a migration fail.
        connection.prepare("UPDATE OR REPLACE `$table` SET `$column` = ? WHERE `$column` = ?").use { update ->
            for (key in old) {
                val new = withoutUrl(key) ?: continue
                update.bindText(1, new)
                update.bindText(2, key)
                update.step()
                update.reset()
            }
        }
    }

    // movie:<source>:<url> · zoom:stream:<source>:<url> · zoom:stream:<source>:episode:<url> · tracks:movie:<source>:<url>
    private val urlKeyed = Regex("""^(?:tracks:|zoom:)?(?:movie|stream):\d+:(?:episode:)?""")

    /** [key] with its URL replaced by [ItemKeys.urlKey], or null when it holds no URL. */
    internal fun withoutUrl(key: String): String? {
        val prefix = urlKeyed.find(key)?.value ?: return null
        val rest = key.substring(prefix.length)
        if ("://" !in rest) return null
        return prefix + ItemKeys.urlKey(rest)
    }
}
