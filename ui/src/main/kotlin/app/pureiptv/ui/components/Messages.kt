package app.pureiptv.ui.components

import androidx.annotation.StringRes
import app.pureiptv.core.model.SourceErrorKind
import app.pureiptv.ui.R

/** Plain-language message for every error the user can see. Never show exception text. */
@StringRes
fun SourceErrorKind.messageRes(): Int = when (this) {
    SourceErrorKind.Unreachable -> R.string.error_unreachable
    SourceErrorKind.InvalidCredentials -> R.string.error_invalid_credentials
    SourceErrorKind.AccountExpired -> R.string.error_account_expired
    SourceErrorKind.NotAPlaylist -> R.string.error_not_a_playlist
    SourceErrorKind.Unknown -> R.string.error_unknown
}

private val numberFormat = java.text.NumberFormat.getIntegerInstance()

fun Int.formatted(): String = numberFormat.format(this)

/** "55,598 live channels · 1 movie · 0 series" (each count with its own singular/plural). */
@androidx.compose.runtime.Composable
fun importCounts(live: Int, movies: Int, series: Int): String = listOf(
    androidx.compose.ui.res.pluralStringResource(app.pureiptv.ui.R.plurals.count_live_channels, live, live.formatted()),
    androidx.compose.ui.res.pluralStringResource(app.pureiptv.ui.R.plurals.count_movies, movies, movies.formatted()),
    androidx.compose.ui.res.pluralStringResource(app.pureiptv.ui.R.plurals.count_series, series, series.formatted()),
).joinToString(" · ")
