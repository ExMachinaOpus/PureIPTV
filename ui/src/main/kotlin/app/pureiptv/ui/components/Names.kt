package app.pureiptv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.pureiptv.core.data.library.LiveGroup
import app.pureiptv.core.model.Genre
import app.pureiptv.ui.R
import java.util.Locale

/** "United Kingdom", "Arabic", "Other channels"... in the interface language. */
@Composable
fun LiveGroup.label(): String = when (this) {
    LiveGroup.Favorites -> stringResource(R.string.live_favorites)
    LiveGroup.Recent -> stringResource(R.string.live_recent)
    LiveGroup.All -> stringResource(R.string.live_all)
    is LiveGroup.Region -> regionName(code) ?: stringResource(R.string.live_other)
}

fun regionName(code: String): String? = when {
    code.isEmpty() -> null
    code.startsWith("@") -> languageName(code.drop(1))
    else -> Locale.Builder().setRegion(code).build().getDisplayCountry(uiLocale).ifBlank { null }
}

/** "German", "English"... in the interface language, or null. */
fun languageName(code: String?): String? = code?.let {
    Locale.forLanguageTag(it).getDisplayLanguage(uiLocale).replaceFirstChar { c -> c.titlecase(uiLocale) }.ifBlank { null }
}

@Composable
fun genreLabel(name: String?): String = when (runCatching { name?.let(Genre::valueOf) }.getOrNull()) {
    null -> stringResource(R.string.genre_all)
    Genre.News -> stringResource(R.string.genre_news)
    Genre.Sports -> stringResource(R.string.genre_sports)
    Genre.Kids -> stringResource(R.string.genre_kids)
    Genre.Movies -> stringResource(R.string.genre_movies)
    Genre.Entertainment -> stringResource(R.string.genre_entertainment)
    Genre.Documentary -> stringResource(R.string.genre_documentary)
    Genre.Music -> stringResource(R.string.genre_music)
    Genre.Religion -> stringResource(R.string.genre_religion)
    Genre.Other -> stringResource(R.string.genre_other)
}
