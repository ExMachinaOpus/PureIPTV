package app.pureiptv.ui.library

import app.pureiptv.ui.components.dpadLeavesTextField
import app.pureiptv.ui.components.PureDialog
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.pureiptv.ui.live.enterOn
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pureiptv.core.data.library.LibraryRepository
import app.pureiptv.core.data.library.LibrarySummary
import app.pureiptv.core.data.library.SourceSummary
import app.pureiptv.core.data.library.UserRepository
import app.pureiptv.core.data.source.ImportState
import app.pureiptv.ui.components.ImportProgress
import app.pureiptv.ui.components.guideProgress
import app.pureiptv.ui.components.label
import app.pureiptv.core.data.source.SourceManager
import app.pureiptv.core.data.db.SourceType
import app.pureiptv.core.model.SourceErrorKind
import app.pureiptv.ui.R
import app.pureiptv.ui.components.PrimaryButton
import app.pureiptv.ui.components.SecondaryButton
import app.pureiptv.ui.components.formatted
import app.pureiptv.ui.components.importCounts
import app.pureiptv.ui.components.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings, Sources page: what each source contains, refresh, enable/disable, order, delete; plus the one content setting. */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val manager: SourceManager,
    private val user: UserRepository,
    library: LibraryRepository,
) : ViewModel() {
    val state: Flow<LibrarySummary> = library.summary()
    val showAdult: Flow<Boolean> = user.showAdult

    fun refresh(sourceId: Long) = manager.refresh(sourceId)
    fun setEnabled(sourceId: Long, enabled: Boolean) = viewModelScope.launch { manager.setEnabled(sourceId, enabled) }
    fun moveUp(sourceId: Long) = viewModelScope.launch { manager.moveUp(sourceId) }
    fun delete(sourceId: Long) = viewModelScope.launch { manager.delete(sourceId) }
    fun setShowAdult(show: Boolean) = viewModelScope.launch { user.setShowAdult(show) }
    suspend fun guideUrl(sourceId: Long): String = manager.guideUrl(sourceId).orEmpty()
    fun setGuideUrl(sourceId: Long, url: String) = viewModelScope.launch { manager.setGuideUrl(sourceId, url) }
}

@Composable
fun LibraryScreen(onAddSource: () -> Unit, onAbout: () -> Unit, modifier: Modifier = Modifier, viewModel: LibraryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle(LibrarySummary())
    val showAdult by viewModel.showAdult.collectAsStateWithLifecycle(false)
    val entry = remember { FocusRequester() }

    Column(
        // Entering from the tabs always lands on the first source's Refresh, never on a nearby
        // "Prefer"/"Remove" button.
        modifier = modifier.widthIn(max = 820.dp).fillMaxWidth().enterOn(entry).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            androidx.compose.ui.res.pluralStringResource(R.plurals.library_merged, state.mergedChannels, state.mergedChannels.formatted()),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.sources.forEachIndexed { i, row ->
            SourceCard(row = row, first = i == 0, viewModel = viewModel, refreshModifier = if (i == 0) Modifier.focusRequester(entry) else Modifier)
        }
        SecondaryButton(
            stringResource(R.string.library_add_source),
            onAddSource,
            if (state.sources.isEmpty()) Modifier.focusRequester(entry) else Modifier,
        )
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        SecondaryButton(
            stringResource(if (showAdult) R.string.settings_adult_on else R.string.settings_adult_off),
            { viewModel.setShowAdult(!showAdult) },
        )
        SecondaryButton(stringResource(R.string.about_title), onAbout)
    }
}

@Composable
private fun SourceCard(row: SourceSummary, first: Boolean, viewModel: LibraryViewModel, refreshModifier: Modifier) {
    val source = row.source
    var confirmDelete by remember(source.id) { mutableStateOf(false) }
    var editGuide by remember(source.id) { mutableStateOf(false) }
    if (editGuide) GuideLinkDialog(source.id, viewModel, onDismiss = { editGuide = false })
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                source.name + if (!source.enabled) "  ·  " + stringResource(R.string.sources_disabled) else "",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                importCounts(row.live, row.movies, row.series),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val imp = row.import) {
                is ImportState.Running -> ImportProgress(imp.phase.label(), imp.progress)
                is ImportState.Done if imp.guide != null -> ImportProgress(stringResource(R.string.import_guide), guideProgress(imp.guide))
                is ImportState.Failed -> Text(stringResource(imp.kind.messageRes()), color = MaterialTheme.colorScheme.error)
                else -> {
                    val error = source.lastError?.let { runCatching { SourceErrorKind.valueOf(it) }.getOrNull() }
                    Text(
                        when {
                            error != null -> stringResource(error.messageRes())
                            source.lastRefreshMs != null -> stringResource(R.string.library_source_updated)
                            else -> stringResource(R.string.library_source_never)
                        },
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Never disabled: a disabled button drops TV focus. Pressing Refresh during an import restarts it.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(stringResource(R.string.library_refresh), { viewModel.refresh(source.id) }, refreshModifier)
                SecondaryButton(
                    stringResource(if (source.enabled) R.string.sources_disable else R.string.sources_enable),
                    { viewModel.setEnabled(source.id, !source.enabled) },
                )
                if (!first) SecondaryButton(stringResource(R.string.sources_move_up), { viewModel.moveUp(source.id) })
                // Playlists that name no guide (or the wrong one) need a way to add it; Xtream servers bring their own.
                if (source.type == SourceType.M3u.name) SecondaryButton(stringResource(R.string.sources_guide), { editGuide = true })
                SecondaryButton(
                    stringResource(if (confirmDelete) R.string.sources_delete_confirm else R.string.sources_delete),
                    {
                        if (confirmDelete) viewModel.delete(source.id) else confirmDelete = true
                    },
                )
            }
        }
    }
}

/** Sets or clears a playlist's guide address. Empty means: use the one the playlist names, if any. */
@Composable
private fun GuideLinkDialog(sourceId: Long, viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sourceId) { url = viewModel.guideUrl(sourceId) }
    val field = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    PureDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 640.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.sources_guide), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.sources_guide_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val save = { viewModel.setGuideUrl(sourceId, url.orEmpty()); onDismiss() }
            OutlinedTextField(
                value = url.orEmpty(),
                onValueChange = { url = it },
                placeholder = { Text(stringResource(R.string.add_field_guide_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(field).dpadLeavesTextField(focus),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(stringResource(R.string.sources_guide_save), save)
                SecondaryButton(stringResource(R.string.add_cancel), onDismiss)
            }
        }
        // Focus the field once the stored address is in it.
        LaunchedEffect(url != null) { if (url != null) runCatching { field.requestFocus() } }
    }
}
