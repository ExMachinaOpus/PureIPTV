package app.pureiptv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.pureiptv.core.data.db.GroupCount
import app.pureiptv.core.data.library.LiveGroup
import app.pureiptv.core.model.Genre
import app.pureiptv.ui.R
import java.text.Collator
import java.util.Locale

/** "United Kingdom", "Arabic", "Other channels"... in the interface language. */
@Composable
fun LiveGroup.label(): String = when (this) {
    LiveGroup.Favorites -> stringResource(R.string.live_favorites)
    LiveGroup.Recent -> stringResource(R.string.live_recent)
    LiveGroup.All -> stringResource(R.string.live_all)
    is LiveGroup.Region -> regionName(code) ?: stringResource(R.string.live_other)
}

fun regionName(code: String, locale: Locale = uiLocale): String? = when {
    code.isEmpty() -> null
    code.startsWith("@") -> languageName(code.drop(1), locale)
    else -> Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { null }
}

/** The sort tile shows only above more entries than this: a short list is easy to scan in any order. */
const val MIN_ENTRIES_TO_SORT = 8

/**
 * Countries and languages in list order: A–Z in the interface language when [byName], else the
 * device's country first and then the biggest. Channels without a region ("Other") always come last.
 */
fun sortRegions(regions: List<GroupCount>, byName: Boolean, locale: Locale, home: String = Locale.getDefault().country): List<GroupCount> =
    if (byName) {
        val collator = Collator.getInstance(locale)
        regions.sortedWith(compareBy<GroupCount> { it.key.isEmpty() }.thenBy(collator) { regionName(it.key, locale).orEmpty() })
    } else {
        regions.sortedWith(compareBy({ it.key != home }, { it.key.isEmpty() }, { -it.count }))
    }

/**
 * Film/series languages in list order: A–Z in the interface language when [byName], else the
 * device's language first and then the most titles. "Language not stated" ([noLanguage]) always last.
 */
fun sortLanguages(
    languages: List<GroupCount>,
    byName: Boolean,
    locale: Locale,
    noLanguage: String,
    home: String = Locale.getDefault().language,
): List<GroupCount> =
    if (byName) {
        val collator = Collator.getInstance(locale)
        languages.sortedWith(compareBy<GroupCount> { it.key == noLanguage }.thenBy(collator) { languageName(it.key, locale) ?: it.key })
    } else {
        languages.sortedWith(compareBy({ it.key == noLanguage }, { it.key != home }, { -it.count }))
    }

/** "German", "English"... in the interface language, or null. */
fun languageName(code: String?, locale: Locale = uiLocale): String? = code?.let {
    Locale.forLanguageTag(it).getDisplayLanguage(locale).replaceFirstChar { c -> c.titlecase(locale) }.ifBlank { null }
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
