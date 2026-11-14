package app.pureiptv.ui.guide

import androidx.compose.ui.res.stringResource

import app.pureiptv.ui.components.PureDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.pureiptv.core.data.db.ProgrammeHit
import app.pureiptv.ui.R
import app.pureiptv.ui.components.ChannelLogo
import app.pureiptv.ui.components.ListTile
import app.pureiptv.ui.components.Tile
import app.pureiptv.ui.components.weekdayShort
import java.util.Calendar
import java.util.Date

/** "Now · until 22:30", "Today 22:00", "Tomorrow 14:00", "Sun 14:00" (device 12/24-hour setting). */
fun programmeWhen(context: Context, startMs: Long, endMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val time = DateFormat.getTimeFormat(context)
    if (startMs <= nowMs) return context.getString(R.string.guide_now_until, time.format(Date(endMs)))
    val start = time.format(Date(startMs))
    return when (dayOffset(nowMs, startMs)) {
        0 -> context.getString(R.string.guide_today_at, start)
        1 -> context.getString(R.string.guide_tomorrow_at, start)
        else -> weekdayShort(startMs) + " " + start
    }
}

internal fun dayOffset(fromMs: Long, toMs: Long): Int {
    fun dayStart(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return Math.round((dayStart(toMs) - dayStart(fromMs)) / 86_400_000.0).toInt()
}

private val LiveAccent = Color(0xFFE5484D)

/** A guide search result: when, what, which channel. Airing now shows a progress bar. */
@Composable
fun ProgrammeCard(hit: ProgrammeHit, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 280.dp) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val live = hit.startMs <= now
    Tile(onClick, modifier.width(width)) { _ ->
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                programmeWhen(context, hit.startMs, hit.endMs, now),
                style = MaterialTheme.typography.labelLarge,
                color = if (live) LiveAccent else LocalContentColor.current.copy(alpha = 0.75f),
                maxLines = 1,
            )
            Text(hit.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(hit.subtitle, hit.episode).joinToString(" · ")
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                ChannelLogo(hit.channelLogo, hit.channelName, Modifier.size(width = 40.dp, height = 26.dp))
                Text(hit.channelName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (live) ProgressLine(((now - hit.startMs).toFloat() / (hit.endMs - hit.startMs).coerceAtLeast(1)).coerceIn(0f, 1f))
        }
    }
}

@Composable
private fun ProgressLine(fraction: Float) {
    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(LiveAccent))
    }
}

/** Details of a programme that hasn't started: description and "Watch <channel> now". Back closes. */
@Composable
fun ProgrammeDialog(hit: ProgrammeHit, onWatch: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    PureDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(520.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(programmeWhen(context, hit.startMs, hit.endMs), style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current.copy(alpha = 0.75f))
            Text(hit.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            listOfNotNull(hit.subtitle, hit.episode).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.titleSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChannelLogo(hit.channelLogo, hit.channelName, Modifier.size(width = 40.dp, height = 26.dp))
                Text(hit.channelName, style = MaterialTheme.typography.bodyMedium)
            }
            hit.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            ListTile(
                stringResource(R.string.guide_watch_channel, hit.channelName),
                onClick = {
                    onDismiss()
                    onWatch()
                },
                modifier = Modifier.padding(top = 12.dp).focusRequester(first),
            )
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
}
