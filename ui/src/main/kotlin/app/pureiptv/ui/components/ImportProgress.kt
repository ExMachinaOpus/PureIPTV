package app.pureiptv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pureiptv.core.data.source.ImportState
import app.pureiptv.ui.R

/**
 * What an import is doing and how far it is: a real progress bar with a percentage when it can be
 * estimated (refreshes, guides with a known size), otherwise a moving bar.
 */
@Composable
fun ImportProgress(label: String, progress: Float?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (progress != null) {
                Text("${(progress * 100).toInt()} %", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (progress != null) {
            val shown by animateFloatAsState(progress, label = "importProgress")
            LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ImportState.Phase?.label(): String = stringResource(
    when (this) {
        ImportState.Phase.Guide -> R.string.import_guide
        ImportState.Phase.LiveTv -> R.string.import_live
        ImportState.Phase.Movies -> R.string.import_movies
        ImportState.Phase.Series -> R.string.import_series
        ImportState.Phase.Finishing -> R.string.import_finishing
        else -> R.string.import_connecting
    },
)

/** Progress of a guide download after the content import ([ImportState.Done.guide]). */
fun guideProgress(guide: Float?): Float? = guide?.takeIf { it != ImportState.GUIDE_UNKNOWN }
