// Media3's track formats and SubtitleView are marked unstable; the app pins its Media3 version.
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package app.pureiptv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.SubtitleView
import app.pureiptv.ui.R
import app.pureiptv.ui.components.ListTile
import app.pureiptv.ui.components.PureDialog
import app.pureiptv.ui.components.languageName

/** Subtitles over the video, styled by the system's caption settings (size, colours). */
@Composable
fun SubtitleLayer(player: Player, modifier: Modifier = Modifier) {
    val view = remember { mutableListOf<SubtitleView>() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                view.firstOrNull()?.setCues(cueGroup.cues)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    AndroidView(
        factory = { context ->
            SubtitleView(context).apply {
                setUserDefaultStyle()
                // One size for every subtitle. Styled formats (ASS/SSA, some TTML/WebVTT) carry their own font
                // size, often meant for a monitor and unreadable on a TV; their colours and positions stay.
                setApplyEmbeddedFontSizes(false)
                // The system caption size (Settings > Accessibility > Captions). The default fraction of the
                // screen height is already sized for a TV across the room, close to the streaming apps.
                val scale = context.getSystemService(android.view.accessibility.CaptioningManager::class.java)?.fontScale ?: 1f
                setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
                setBottomPaddingFraction(0.08f)
            }.also { view += it }
        },
        modifier = modifier.fillMaxSize(),
    )
}

/** Short name for buttons: "Persian · AAC stereo". */
@Composable
fun audioShortLabel(a: TrackInfo): String {
    val format = listOfNotNull(audioCodecName(a.codec, a.codecs, short = true), channelsLabel(a.channels)).joinToString(" ").ifEmpty { null }
    return listOfNotNull(trackTitle(a), format).joinToString(" · ")
}

/** One line for the info panel: "Persian · AAC stereo · 128 kbit/s". */
@Composable
fun audioLabel(a: TrackInfo): String = listOfNotNull(
    audioShortLabel(a),
    a.bitrate.takeIf { it > 0 }?.let { stringResource(R.string.audio_kbps, it / 1000) },
).joinToString("  ·  ")

/** "Persian", "English – Commentary", "Unknown language". */
@Composable
private fun trackTitle(t: TrackInfo): String {
    val language = languageName(t.language) ?: stringResource(R.string.audio_unknown_language)
    return listOfNotNull(language, t.label?.takeIf { !it.equals(language, ignoreCase = true) }).joinToString(" – ")
}

@Composable
private fun channelsLabel(channels: Int): String? = when (channels) {
    0 -> null
    1 -> stringResource(R.string.audio_mono)
    2 -> stringResource(R.string.audio_stereo)
    6 -> "5.1"
    8 -> "7.1"
    else -> stringResource(R.string.audio_channels, channels)
}

/** Everything known about an audio track: "AAC-LC · stereo · 48 kHz · bitrate not stated · default". */
@Composable
private fun audioDetails(a: TrackInfo): String = listOfNotNull(
    audioCodecName(a.codec, a.codecs, short = false),
    channelsLabel(a.channels),
    a.sampleRate.takeIf { it > 0 }?.let { stringResource(R.string.audio_khz, formatKhz(it)) },
    if (a.bitrate > 0) stringResource(R.string.audio_kbps, a.bitrate / 1000) else stringResource(R.string.audio_bitrate_unknown),
) .plus(roleLabels(a)).joinToString("  ·  ")

/** "Subtitles (SRT) · forced · for the hard of hearing". */
@Composable
private fun subtitleDetails(t: TrackInfo): String = listOfNotNull(subtitleFormatName(t.codec, t.codecs))
    .plus(roleLabels(t)).joinToString("  ·  ")

@Composable
private fun roleLabels(t: TrackInfo): List<String> = buildList {
    if (t.isDefault) add(stringResource(R.string.track_default))
    if (t.isForced) add(stringResource(R.string.track_forced))
    if (t.roles and C.ROLE_FLAG_COMMENTARY != 0) add(stringResource(R.string.track_commentary))
    if (t.roles and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) add(stringResource(R.string.track_audio_description))
    if (t.roles and (C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND or C.ROLE_FLAG_CAPTION) != 0) add(stringResource(R.string.track_hard_of_hearing))
    if (t.roles and C.ROLE_FLAG_DUB != 0) add(stringResource(R.string.track_dub))
}

private fun formatKhz(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(hz / 1000.0)

/** AAC profiles matter for quality: HE-AAC is a low-bitrate mode that sounds duller. */
private fun audioCodecName(mime: String?, codecs: String?, short: Boolean): String? = when (mime) {
    MimeTypes.AUDIO_AAC -> when {
        short -> "AAC"
        codecs?.endsWith(".40.2") == true -> "AAC-LC"
        codecs?.endsWith(".40.5") == true -> "HE-AAC"
        codecs?.endsWith(".40.29") == true -> "HE-AAC v2"
        codecs?.endsWith(".40.42") == true -> "xHE-AAC"
        else -> "AAC"
    }
    MimeTypes.AUDIO_AC3 -> "Dolby Digital"
    MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
    MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
    MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
    MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_MPEG_L2 -> "MP2"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    null -> null
    else -> mime.substringAfter('/').uppercase()
}

/** Subtitles parsed during extraction carry their original format in `codecs`. */
@Composable
private fun subtitleFormatName(mime: String?, codecs: String?): String? {
    val original = if (mime == MimeTypes.APPLICATION_MEDIA3_CUES) codecs else mime
    return when (original) {
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_SSA -> "SSA/ASS"
        MimeTypes.TEXT_VTT -> "WebVTT"
        MimeTypes.APPLICATION_TTML -> "TTML"
        MimeTypes.APPLICATION_PGS -> stringResource(R.string.subtitle_image, "PGS")
        MimeTypes.APPLICATION_DVBSUBS -> stringResource(R.string.subtitle_image, "DVB")
        MimeTypes.APPLICATION_VOBSUB -> stringResource(R.string.subtitle_image, "VobSub")
        MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> stringResource(R.string.subtitle_closed_captions)
        MimeTypes.APPLICATION_TX3G -> "Timed text"
        null -> null
        else -> original.substringAfter('/').uppercase()
    }
}

private data class PickerEntry(val title: String, val details: String?, val selected: Boolean, val pick: () -> Unit)

/** A list of tracks with all their details; the current one is ticked and focused. */
@Composable
private fun TrackPicker(title: String, entries: List<PickerEntry>, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    PureDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(640.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(entries) { _, e ->
                    ListTile(
                        e.title,
                        supporting = e.details,
                        supportingLines = 2,
                        onClick = {
                            onDismiss()
                            e.pick()
                        },
                        selected = e.selected,
                        leading = { Text(if (e.selected) "✓" else "  ") },
                        modifier = if (e.selected) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

@Composable
fun AudioPicker(tracks: List<TrackInfo>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val entries = tracks.map { t -> PickerEntry(trackTitle(t), audioDetails(t), t.selected) { onPick(t.id) } }
    TrackPicker(stringResource(R.string.player_audio_title), entries, onDismiss)
}

/** "Off" first (the default), then every subtitle track. */
@Composable
fun SubtitlePicker(tracks: List<TrackInfo>, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    val off = PickerEntry(stringResource(R.string.subtitles_off), null, tracks.none { it.selected }) { onPick(null) }
    val entries = listOf(off) + tracks.map { t -> PickerEntry(trackTitle(t), subtitleDetails(t), t.selected) { onPick(t.id) } }
    TrackPicker(stringResource(R.string.subtitles_title), entries, onDismiss)
}

/** Button text: "Subtitles: Off" / "Subtitles: Persian". */
@Composable
fun subtitlesButtonLabel(tracks: List<TrackInfo>): String {
    val on = tracks.firstOrNull { it.selected }
    return stringResource(R.string.player_subtitles_choose, on?.let { trackTitle(it) } ?: stringResource(R.string.subtitles_off))
}
