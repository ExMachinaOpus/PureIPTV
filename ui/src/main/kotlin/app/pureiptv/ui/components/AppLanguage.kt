package app.pureiptv.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.LayoutDirection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** An interface language: [tag] selects the translation, [name] is written in that language. */
data class AppLanguage(val tag: String, val name: String) {
    val locale: Locale get() = Locale.forLanguageTag(tag)
}

/**
 * The translations the app ships (`res/values-xx`; English (UK) is `res/values` and the fallback), in
 * the order the picker shows them: by name, Latin script first. Where a language has several variants
 * the first one is what other regions get (Portuguese in Angola gets Portugal's, German in Luxembourg
 * gets Germany's).
 */
val APP_LANGUAGES = listOf(
    AppLanguage("id", "Bahasa Indonesia"),
    AppLanguage("cs", "Čeština"),
    AppLanguage("da", "Dansk"),
    AppLanguage("de-DE", "Deutsch (Deutschland)"),
    AppLanguage("de-AT", "Deutsch (Österreich)"),
    AppLanguage("de-CH", "Deutsch (Schweiz)"),
    AppLanguage("et", "Eesti"),
    AppLanguage("en-GB", "English (UK)"),
    AppLanguage("es", "Español"),
    AppLanguage("fr", "Français"),
    AppLanguage("hr", "Hrvatski"),
    AppLanguage("it", "Italiano"),
    AppLanguage("lv", "Latviešu"),
    AppLanguage("lt", "Lietuvių"),
    AppLanguage("hu", "Magyar"),
    AppLanguage("nl", "Nederlands"),
    AppLanguage("nb", "Norsk bokmål"),
    AppLanguage("nn", "Norsk nynorsk"),
    AppLanguage("pl", "Polski"),
    AppLanguage("pt-PT", "Português (Portugal)"),
    AppLanguage("pt-BR", "Português (Brasil)"),
    AppLanguage("ro", "Română"),
    AppLanguage("sk", "Slovenčina"),
    AppLanguage("sl", "Slovenščina"),
    AppLanguage("fi", "Suomi"),
    AppLanguage("sv", "Svenska"),
    AppLanguage("tr", "Türkçe"),
    AppLanguage("el", "Ελληνικά"),
    AppLanguage("bg", "Български"),
    AppLanguage("ru", "Русский"),
    AppLanguage("sr", "Српски"),
    AppLanguage("uk", "Українська"),
    AppLanguage("ar", "العربية"),
    AppLanguage("fa", "فارسی"),
    AppLanguage("hi", "हिन्दी"),
)

private val ENGLISH = APP_LANGUAGES.first { it.tag == "en-GB" }

/** The device's language if the app has that translation (the exact variant when there is one), else English (UK). */
fun deviceLanguage(): AppLanguage {
    val device = LocaleList.getDefault()
    for (i in 0 until device.size()) {
        val locale = device[i]
        val language = if (locale.language == "no") "nb" else locale.language // "no" is the generic code for Bokmål
        val sameLanguage = APP_LANGUAGES.filter { it.locale.language == language }
        (sameLanguage.firstOrNull { it.locale.country == locale.country } ?: sameLanguage.firstOrNull())?.let { return it }
    }
    return ENGLISH
}

/** What the interface uses: the language the user picked ([saved] tag), else the device's. */
fun resolveLanguage(saved: String?): AppLanguage = APP_LANGUAGES.firstOrNull { it.tag == saved } ?: deviceLanguage()

/**
 * The interface language's locale, for text that isn't a string resource (names of languages and
 * countries, weekdays). Device-based decisions (home country, preferred content language) keep using
 * the device locale.
 */
var uiLocale: Locale by mutableStateOf(ENGLISH.locale)

/** "Sun" in the interface language. */
fun weekdayShort(timeMs: Long): String = SimpleDateFormat("EEE", uiLocale).format(Date(timeMs))

/** Activity context (Hilt and others look for the activity) that reads strings in the chosen language. */
private class LanguageContext(base: Context, private val localised: Resources) : ContextWrapper(base) {
    override fun getResources(): Resources = localised
}

/**
 * Applies [language] to everything below it. Only values change, so switching language updates the
 * screen in place: no activity restart, and focus and scroll positions stay.
 */
@Composable
fun ProvideAppLanguage(language: AppLanguage, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val device = LocalConfiguration.current
    val configuration = remember(device, language) { Configuration(device).apply { setLocale(language.locale) } }
    val context = remember(base, configuration) { LanguageContext(base, base.createConfigurationContext(configuration).resources) }
    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides configuration,
        LocalResources provides context.resources,
        LocalLayoutDirection provides if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
        content = content,
    )
}

/**
 * For layouts where left and right mean time (player seek bar, guide timeline): Left is always back
 * on the remote, so these are not mirrored in right-to-left languages. Text inside still reads correctly.
 */
@Composable
fun KeepLeftToRight(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}
