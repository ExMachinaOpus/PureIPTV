package app.pureiptv.ui.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pureiptv.core.data.library.UserRepository
import app.pureiptv.ui.R
import app.pureiptv.ui.components.APP_LANGUAGES
import app.pureiptv.ui.components.Tile
import app.pureiptv.ui.components.deviceLanguage
import app.pureiptv.ui.components.isTv
import app.pureiptv.ui.home.LocalTabsFocus
import app.pureiptv.ui.library.LibraryScreen
import app.pureiptv.ui.live.enterOn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val user: UserRepository) : ViewModel() {
    val language: Flow<String?> = user.uiLanguage

    fun setLanguage(tag: String?) {
        viewModelScope.launch { user.setUiLanguage(tag) }
    }
}

private enum class SettingsPage { General, Sources }

/** Behind the gear: a sidebar that is always there, and the chosen page next to it. */
@Composable
fun SettingsScreen(
    startOnSources: Boolean,
    onAddSource: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableStateOf(if (startOnSources) SettingsPage.Sources else SettingsPage.General) }
    val sidebarEntry = remember { FocusRequester() }
    // Up from the top of a column goes to the gear; it must not stop on the sidebar, which would change the page.
    val tabs = LocalTabsFocus.current
    val upToTabs = Modifier
        .focusProperties { onExit = { if (tabs != null && requestedFocusDirection == FocusDirection.Up) tabs.requestFocus() } }
        .focusGroup()
    Row(
        // Down from the gear lands on the current page in the sidebar, not on whatever is below the gear.
        modifier.enterOn(sidebarEntry).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 32.dp else 12.dp),
    ) {
        Column(Modifier.width(if (isTv) 220.dp else 104.dp).fillMaxHeight().then(upToTabs), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsPage.entries.forEach { p ->
                Tile(
                    onClick = { page = p },
                    onFocused = { page = p },
                    selected = p == page,
                    transparent = true,
                    modifier = Modifier.fillMaxWidth().then(if (p == page) Modifier.focusRequester(sidebarEntry) else Modifier),
                ) { _ ->
                    Text(
                        stringResource(if (p == SettingsPage.General) R.string.settings_general else R.string.settings_sources),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
        Box(Modifier.weight(1f).then(upToTabs)) {
            when (page) {
                SettingsPage.General -> GeneralPage()
                SettingsPage.Sources -> LibraryScreen(onAddSource = onAddSource, onAbout = onAbout, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Interface language. Not something the app can always get right by itself: the device may be in a
 * language other than the viewer's, and a shared TV has several viewers. It defaults to the device's.
 */
@Composable
private fun GeneralPage(viewModel: SettingsViewModel = hiltViewModel()) {
    val saved by viewModel.language.collectAsStateWithLifecycle(null)
    val current = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().widthIn(max = 620.dp).enterOn(current).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.settings_language),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LanguageRow(
            name = stringResource(R.string.settings_language_device, deviceLanguage().name),
            chosen = saved == null,
            focus = current,
            onClick = { viewModel.setLanguage(null) },
        )
        APP_LANGUAGES.forEach { language ->
            LanguageRow(language.name, chosen = saved == language.tag, focus = current, onClick = { viewModel.setLanguage(language.tag) })
        }
    }
}

@Composable
private fun LanguageRow(name: String, chosen: Boolean, focus: FocusRequester, onClick: () -> Unit) {
    Tile(onClick = onClick, modifier = Modifier.fillMaxWidth().then(if (chosen) Modifier.focusRequester(focus) else Modifier)) { _ ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (chosen) Text("✓", style = MaterialTheme.typography.titleMedium)
        }
    }
}
