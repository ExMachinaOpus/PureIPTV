package app.pureiptv.core.data.library

import app.pureiptv.core.data.db.FavoriteEntity
import app.pureiptv.core.data.db.HistoryEntity
import app.pureiptv.core.data.db.SettingEntity
import app.pureiptv.core.data.db.UserDao
import app.pureiptv.core.data.db.WatchProgressEntity
import app.pureiptv.core.model.ItemKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Resume point of a movie/episode. */
data class Progress(val positionMs: Long, val durationMs: Long) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Watched to the end (episodes keep this mark; movies are forgotten instead). */
    val finished: Boolean get() = fraction >= FINISHED

    /** Worth offering "Resume": started, and not basically finished. */
    val resumable: Boolean get() = positionMs > 30_000 && fraction < FINISHED

    companion object {
        const val FINISHED = 0.95f
    }
}

/** Where a series continues: [episode], from its resume point when [resume] is true. */
data class ContinueAt<E>(val episode: E, val resume: Boolean)

/**
 * The one rule for where a series continues, shared by Home and the series page. Only an episode
 * that was really finished moves on; one that was left early, however early, is still the current one.
 * Null when the last episode there is was finished.
 */
fun <E> continueAt(episodes: List<E>, lastIndex: Int, last: Progress?): ContinueAt<E>? = when {
    last?.finished == true -> episodes.getOrNull(lastIndex + 1)?.let { ContinueAt(it, resume = false) }
    else -> episodes.getOrNull(lastIndex)?.let { ContinueAt(it, resume = last?.resumable == true) }
}

/** How video fills the screen. Fit (whole picture, black bars) is the default; the rest are per item. */
enum class Zoom { Fit, Fill, Stretch }

/** The user's own state: favorites, progress, history, the few settings. Backed by user.db. */
@Singleton
class UserRepository @Inject constructor(private val dao: UserDao) {

    val favoriteKeys: Flow<List<String>> = dao.favoriteKeys()

    fun isFavorite(itemKey: String): Flow<Boolean> = favoriteKeys.map { itemKey in it }.distinctUntilChanged()

    suspend fun setFavorite(itemKey: String, favorite: Boolean) {
        if (favorite) {
            dao.addFavorite(FavoriteEntity(itemKey, System.currentTimeMillis(), dao.nextFavoritePosition()))
        } else {
            dao.removeFavorite(itemKey)
        }
    }

    suspend fun progress(itemKey: String): Progress? =
        dao.progress(itemKey)?.let { Progress(it.positionMs, it.durationMs) }

    fun progressWithPrefix(prefix: String): Flow<Map<String, Progress>> =
        dao.progressWithPrefix(prefix).map { rows -> rows.associate { it.itemKey to Progress(it.positionMs, it.durationMs) } }

    /** Saves a resume point; a finished item forgets it (next time starts from the beginning). */
    suspend fun saveProgress(itemKey: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        if (positionMs.toFloat() / durationMs >= Progress.FINISHED) {
            // A finished episode is remembered as finished (so the series moves on to the next one);
            // a finished movie is simply forgotten.
            if (itemKey.startsWith(ItemKeys.EPISODE_PREFIX)) {
                dao.putProgress(WatchProgressEntity(itemKey, durationMs, durationMs, System.currentTimeMillis()))
            } else {
                dao.clearProgress(itemKey)
            }
        } else {
            dao.putProgress(WatchProgressEntity(itemKey, positionMs, durationMs, System.currentTimeMillis()))
        }
    }

    suspend fun recordWatched(vararg itemKeys: String) {
        val now = System.currentTimeMillis()
        itemKeys.forEach { dao.addHistory(HistoryEntity(itemKey = it, watchedMs = now)) }
        dao.trimHistory()
    }

    fun recentKeys(prefix: String, limit: Int = 30): Flow<List<String>> = dao.recentKeys(prefix, limit)

    /** Movies and series watched, newest first. */
    fun recentTitleKeys(limit: Int = 40): Flow<List<String>> = dao.recentTitleKeys(limit)

    /**
     * Removes a movie or series from "Continue watching": forgets its progress and history (for a
     * series, of all its episodes). Favourites are not touched.
     */
    suspend fun forget(itemKey: String) {
        val prefix = if (itemKey.startsWith(ItemKeys.SERIES_PREFIX)) ItemKeys.episodePrefix(itemKey) else ""
        dao.forgetHistory(itemKey, prefix)
        dao.forgetProgress(itemKey, prefix)
    }

    /** Removes one entry (e.g. a channel) from the watch history. */
    suspend fun forgetHistory(itemKey: String) = dao.deleteHistory(itemKey)

    /** Adult content is hidden by default; the only content setting there is. */
    val showAdult: Flow<Boolean> = dao.setting(KEY_SHOW_ADULT).map { it == "true" }.distinctUntilChanged()

    suspend fun setShowAdult(show: Boolean) = dao.putSetting(SettingEntity(KEY_SHOW_ADULT, show.toString()))

    /** Countries and languages in Live TV and the guide: A–Z when true, else most channels first. */
    val groupsByName: Flow<Boolean> = dao.setting(KEY_GROUPS_BY_NAME).map { it == "true" }.distinctUntilChanged()

    /** Flips the saved order. Reads the stored value, so two quick presses always cancel out. */
    suspend fun toggleGroupsByName() {
        if (dao.setting(KEY_GROUPS_BY_NAME).first() == "true") dao.deleteSetting(KEY_GROUPS_BY_NAME)
        else dao.putSetting(SettingEntity(KEY_GROUPS_BY_NAME, "true"))
    }

    /** The interface language the user picked (BCP 47 tag), or null to follow the device. */
    val uiLanguage: Flow<String?> = dao.setting(KEY_UI_LANGUAGE).distinctUntilChanged()

    suspend fun setUiLanguage(tag: String?) {
        if (tag == null) dao.deleteSetting(KEY_UI_LANGUAGE) else dao.putSetting(SettingEntity(KEY_UI_LANGUAGE, tag))
    }

    /** Remembered zoom for one feed (stream key: source + provider id, survives refreshes). */
    suspend fun zoom(streamKey: String): Zoom =
        dao.setting(KEY_ZOOM_PREFIX + streamKey).first()?.let { v -> Zoom.entries.firstOrNull { it.name == v } } ?: Zoom.Fit

    suspend fun setZoom(streamKey: String, zoom: Zoom) {
        if (zoom == Zoom.Fit) dao.deleteSetting(KEY_ZOOM_PREFIX + streamKey) else dao.putSetting(SettingEntity(KEY_ZOOM_PREFIX + streamKey, zoom.name))
    }

    /**
     * Audio/subtitle choice remembered for a channel, as languages (track numbers differ between a
     * channel's streams and backups). Null = nothing remembered.
     */
    suspend fun trackChoice(itemKey: String): TrackChoice? =
        dao.setting(KEY_TRACKS_PREFIX + itemKey).first()?.let(TrackChoice::decode)

    suspend fun setTrackChoice(itemKey: String, choice: TrackChoice) {
        if (choice == TrackChoice()) dao.deleteSetting(KEY_TRACKS_PREFIX + itemKey)
        else dao.putSetting(SettingEntity(KEY_TRACKS_PREFIX + itemKey, choice.encode()))
    }

    private companion object {
        const val KEY_TRACKS_PREFIX = "tracks:"
        const val KEY_ZOOM_PREFIX = "zoom:"
        const val KEY_SHOW_ADULT = "show_adult"
        const val KEY_UI_LANGUAGE = "ui_language"
        const val KEY_GROUPS_BY_NAME = "groups_by_name"
    }
}

/**
 * Remembered tracks for a channel. [audioLanguage] null = the player's default. [subtitleLanguage]
 * null = subtitles off (the default).
 */
data class TrackChoice(val audioLanguage: String? = null, val subtitleLanguage: String? = null) {
    fun encode(): String = "a=${audioLanguage.orEmpty()};s=${subtitleLanguage.orEmpty()}"

    companion object {
        fun decode(value: String): TrackChoice {
            val parts = value.split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            return TrackChoice(parts["a"]?.ifEmpty { null }, parts["s"]?.ifEmpty { null })
        }
    }
}
