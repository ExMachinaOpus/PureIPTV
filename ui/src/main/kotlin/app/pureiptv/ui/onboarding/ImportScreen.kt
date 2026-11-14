package app.pureiptv.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureiptv.core.data.source.ImportCounts
import app.pureiptv.core.data.source.ImportState
import app.pureiptv.ui.components.ImportProgress
import app.pureiptv.ui.components.label
import app.pureiptv.core.data.source.SourceManager
import app.pureiptv.ui.R
import app.pureiptv.ui.components.PrimaryButton
import app.pureiptv.ui.components.Screen
import app.pureiptv.ui.components.SecondaryButton
import app.pureiptv.ui.components.formatted
import app.pureiptv.ui.components.importCounts
import app.pureiptv.ui.components.isTv
import app.pureiptv.ui.components.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class ImportViewModel @Inject constructor(private val sources: SourceManager) : ViewModel() {
    fun state(sourceId: Long) = sources.imports.map { it[sourceId] }
    fun name(sourceId: Long) = sources.sources.map { list -> list.firstOrNull { it.id == sourceId }?.name.orEmpty() }
    fun retry(sourceId: Long) = sources.refresh(sourceId)
}

@Composable
fun ImportScreen(
    sourceId: Long,
    onDone: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by remember(sourceId) { viewModel.state(sourceId) }.collectAsStateWithLifecycle(null)
    val name by remember(sourceId) { viewModel.name(sourceId) }.collectAsStateWithLifecycle("")
    val actionFocus = remember { FocusRequester() }
    val tv = isTv

    Screen(contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.import_title, name), style = MaterialTheme.typography.headlineMedium)
            when (val s = state) {
                null, is ImportState.Running -> {
                    val running = s
                    ImportProgress(running?.phase.label(), running?.progress)
                    Counts(running?.counts ?: ImportCounts())
                }
                is ImportState.Done -> {
                    Text(stringResource(R.string.import_done), style = MaterialTheme.typography.titleLarge)
                    Counts(s.counts)
                    PrimaryButton(stringResource(R.string.import_continue), onDone, Modifier.focusRequester(actionFocus))
                    LaunchedEffect(Unit) { if (tv) actionFocus.requestFocus() }
                }
                is ImportState.Failed -> {
                    Text(stringResource(R.string.import_failed), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(s.kind.messageRes()), color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryButton(
                            stringResource(R.string.import_retry),
                            { viewModel.retry(sourceId) },
                            Modifier.focusRequester(actionFocus),
                        )
                        SecondaryButton(stringResource(R.string.import_back), onDone)
                    }
                    LaunchedEffect(Unit) { if (tv) actionFocus.requestFocus() }
                }
            }
        }
    }
}

@Composable
private fun Counts(c: ImportCounts) {
    Text(
        importCounts(c.live, c.movies, c.series),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
