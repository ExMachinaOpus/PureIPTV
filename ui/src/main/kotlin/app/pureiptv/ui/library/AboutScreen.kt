package app.pureiptv.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pureiptv.ui.R
import app.pureiptv.ui.components.ListTile
import app.pureiptv.ui.components.Screen
import app.pureiptv.ui.components.Tile

/** Open-source software in the app and its licences (shown in Sources -> About). */
private val libraries = listOf(
    "AndroidX (Jetpack Compose, Compose for TV, Navigation 3, Lifecycle, Room, SQLite, Paging, WorkManager, DataStore)" to "Apache License 2.0",
    "AndroidX Media3 (ExoPlayer, session, UI)" to "Apache License 2.0",
    "NextLib (FFmpeg audio decoders for Media3)" to "GNU General Public License 3.0",
    "FFmpeg" to "GNU Lesser General Public License 3.0 or later",
    "dav1d" to "BSD 2-Clause License",
    "libass" to "ISC License",
    "Dagger / Hilt" to "Apache License 2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0",
    "OkHttp, Okio" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
    "Tink" to "Apache License 2.0",
    "ZXing (QR codes)" to "Apache License 2.0",
    "SQLite" to "Public domain",
)

@Composable
fun AboutScreen() {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // Both licences ship with the app (GPL 3 sections 4 and 6, Apache 2.0 section 4a), one paragraph per row.
    val resources = LocalResources.current
    val gpl = remember { paragraphs(resources.openRawResource(R.raw.gpl_3_0).bufferedReader().use { it.readText() }) }
    val apache = remember { paragraphs(resources.openRawResource(R.raw.apache_license_2_0).bufferedReader().use { it.readText() }) }
    Screen {
        LazyColumn(Modifier.widthIn(max = 820.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineMedium) }
            // The app's own notice first (GPL 3 section 5d: copyright, no warranty, the licence and where it is).
            // It takes the initial focus, so the page opens at the top.
            item {
                Tile(onClick = {}, modifier = Modifier.fillMaxWidth().focusRequester(first), focusScale = 1.01f) {
                    Text(
                        stringResource(R.string.about_copyright) + "\n" +
                            stringResource(R.string.about_app_license, stringResource(R.string.source_code_url)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LocalContentColor.current.copy(alpha = 0.9f),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.about_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(libraries.size) { i ->
                val (name, license) = libraries[i]
                // Focusable rows so the list scrolls with the remote.
                ListTile(name, onClick = {}, supporting = license)
            }
            item {
                Text(
                    stringResource(R.string.about_privacy, stringResource(R.string.privacy_policy_url)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            licenceText(R.string.about_gpl_text, gpl)
            licenceText(R.string.about_license_text, apache)
        }
    }
}

private fun paragraphs(text: String): List<String> = text
    .split(Regex("""\n\s*\n"""))
    .map { it.trim().replace(Regex("""\s+"""), " ") }
    .filter { it.isNotEmpty() }

/** A licence's heading and its full text, one focusable row per paragraph so it scrolls with the remote. */
private fun LazyListScope.licenceText(heading: Int, paragraphs: List<String>) {
    item {
        Text(
            stringResource(heading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    items(paragraphs.size) { i ->
        Tile(onClick = {}, modifier = Modifier.fillMaxWidth(), focusScale = 1.01f) {
            Text(
                paragraphs[i],
                style = MaterialTheme.typography.bodySmall,
                // The tile's own content colour: it turns dark when the focused tile turns light.
                color = LocalContentColor.current.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
