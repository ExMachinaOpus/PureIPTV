// Media3's track formats and SubtitleView are marked unstable; the app pins its Media3 version.
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package app.pureiptv.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.KeyEvent as AndroidKeyEvent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusDirection
import app.pureiptv.ui.components.languageName
import app.pureiptv.ui.components.PureDialog
import androidx.media3.common.MimeTypes
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.compose.ContentFrame
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.pureiptv.core.data.db.ProgrammeEntity
import app.pureiptv.core.data.library.Zoom
import androidx.compose.ui.layout.ContentScale
import app.pureiptv.ui.R
import app.pureiptv.ui.components.FocusMemory
import app.pureiptv.ui.components.ListTile
import app.pureiptv.ui.components.Tile
import app.pureiptv.ui.components.badge
import app.pureiptv.ui.components.isTv
import app.pureiptv.ui.components.rememberFocus
import app.pureiptv.ui.live.ChannelTile
import kotlinx.coroutines.delay

/**
 * Full-screen playback. Remote keys (AGENTS.md):
 * Live: Up/Down = channel, OK = info, Left/Right = channel list.
 * VOD: Left/Right = seek, OK = play/pause, Up/Down = show controls. Back puts the info panel away, then leaves.
 */
@Composable
fun PlayerScreen(request: PlayRequest, onExit: () -> Unit) {
    val viewModel = hiltViewModel<PlayerViewModel, PlayerViewModel.Factory>(
        key = request.toString(),
        creationCallback = { it.create(request) },
    )
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val player = viewModel.player
    val tv = isTv
    val isLive = ui.item?.isLive ?: (request is PlayRequest.Channel)

    var overlay by remember { mutableStateOf(true) }
    var overlayToken by remember { mutableIntStateOf(0) }
    var listOpen by remember { mutableStateOf(false) }
    // Flip list: fades in on Up/Down, fades out ~1.5 s after the last press.
    var flipVisible by remember { mutableStateOf(false) }
    var flipToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(flipToken) {
        if (flipToken > 0) {
            flipVisible = true
            delay(2_000)
            flipVisible = false
        }
    }
    val rootFocus = remember { FocusRequester() }
    val playing = rememberPlayerFlag(player) { it.isPlaying }
    val buffering = rememberPlayerFlag(player) { it.playbackState == Player.STATE_BUFFERING }

    // Live, OK: the info panel with its actions (favourite, picture) focused, so OK OK adds a favourite.
    val actionsFocus = remember { FocusRequester() }
    var actionsFocused by remember { mutableStateOf(false) }
    var focusActions by remember { mutableIntStateOf(0) }
    var choosingAudio by remember { mutableStateOf(false) }
    var choosingSubtitles by remember { mutableStateOf(false) }
    fun showOverlay() {
        overlay = true
        overlayToken++
    }
    fun hideOverlay() {
        // Never remove a focused view: hand focus back to the player first.
        if (actionsFocused) runCatching { rootFocus.requestFocus() }
        overlay = false
    }
    LaunchedEffect(overlayToken, playing, actionsFocused) {
        delay(if (actionsFocused) 8_000 else 4_500)
        if (playing) hideOverlay()
    }
    LaunchedEffect(focusActions) {
        if (focusActions > 0) {
            // The panel may still be appearing: try for a few frames until the buttons exist.
            repeat(20) {
                if (runCatching { actionsFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                delay(16)
            }
        }
    }
    LaunchedEffect(ui.item?.itemKey) { if (ui.item != null) showOverlay() }
    LaunchedEffect(ui.finished, ui.notFound) { if (ui.finished || ui.notFound) onExit() }
    LaunchedEffect(listOpen) { if (!listOpen) runCatching { rootFocus.requestFocus() } }

    // Phones: leaving the app while playing continues in a small window, with the video only.
    val pipActivity = if (tv) null else LocalView.current.context.findActivity() as? ComponentActivity
    val inPip = rememberPictureInPicture(pipActivity, player, playing)
    LaunchedEffect(inPip) {
        if (inPip) {
            listOpen = false
            hideOverlay()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onForeground() }

    MatchFrameRate(viewModel.frameRate.collectAsStateWithLifecycle().value)

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        // Phones: landscape, full screen (like every video app). TVs already are.
        val activity = view.context.findActivity()
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousOrientation = activity?.requestedOrientation
        if (!tv && activity != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            view.keepScreenOn = false
            if (!tv && activity != null) {
                activity.requestedOrientation = previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                insets?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler(enabled = listOpen) { listOpen = false }
    // Back first puts the info panel away (wherever focus is in it); with the panel gone it leaves.
    BackHandler(enabled = overlay && !listOpen) { hideOverlay() }
    BackHandler(enabled = ui.upNext != null) { viewModel.stopAfterEpisode() }

    // Last two minutes of an episode with a next one: a "Next episode" button, bottom right, that OK
    // presses. The end may still be story, not credits: Back puts it away and OK pauses again.
    val hasNext = ui.item?.nextEpisodeId != null && ui.item?.isLive == false
    var nearEnd by remember { mutableStateOf(false) }
    LaunchedEffect(hasNext, ui.item?.itemKey) {
        nearEnd = false
        while (hasNext) {
            val duration = player.duration
            val left = duration - player.currentPosition
            nearEnd = duration >= MIN_EPISODE_MS && left in 1..NEXT_PILL_WINDOW_MS
            delay(1_000)
        }
    }
    var nextDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(nearEnd) { if (!nearEnd) nextDismissed = false }
    val showNext = nearEnd && !nextDismissed && ui.upNext == null && !listOpen && !ui.failed && !inPip
    // The info panel's buttons take OK while one of them has focus.
    val nextSelected = showNext && !actionsFocused
    BackHandler(enabled = nextSelected) { nextDismissed = true }
    // Clear of the info panel when that is up.
    val nextBottom by animateDpAsState(if (overlay && !listOpen) NEXT_ABOVE_PANEL else NEXT_MARGIN, label = "nextBottom")

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            // With a panel button focused, Compose spends Back on clearing that focus and the panel
            // stays up. Back puts the panel away in one press, so take it before the buttons see it.
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_BACK || !overlay || !actionsFocused || listOpen) {
                    return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyUp) hideOverlay()
                true
            }
            .onKeyEvent { event ->
                // While the channel list is open its keys belong to the list (Up/Down move, OK picks).
                if (event.type != KeyEventType.KeyDown || listOpen) return@onKeyEvent false
                viewModel.userActivity()
                val native = event.nativeKeyEvent
                val ok = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                if (ui.upNext != null) {
                    // "Up next" / "Still watching?": OK plays, Back (BackHandler) stops, other keys wait.
                    if (ok && native.repeatCount == 0) viewModel.playNext()
                    return@onKeyEvent true
                }
                if (nextSelected && ok) {
                    if (native.repeatCount == 0) viewModel.playNext()
                    return@onKeyEvent true
                }
                handleKey(
                    native.keyCode, native.repeatCount, isLive, ui.failed, viewModel, ::showOverlay,
                    onZap = { flipToken++ },
                    onInfo = {
                        showOverlay()
                        if (tv) focusActions++
                    },
                ) { listOpen = true }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (overlay) overlay = false else showOverlay()
            },
    ) {
        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize(),
            contentScale = when (ui.zoom) {
                Zoom.Fit -> ContentScale.Fit
                Zoom.Fill -> ContentScale.Crop
                Zoom.Stretch -> ContentScale.FillBounds
            },
        )
        SubtitleLayer(player)

        if (buffering && !ui.failed) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Color.White)
        }

        if (ui.failed) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(if (isLive) R.string.player_channel_offline else R.string.player_title_unavailable),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(
                    stringResource(if (tv) (if (isLive) R.string.player_offline_hint_live else R.string.player_offline_hint) else R.string.player_offline_hint_touch),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFB0B3BA),
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (!tv) {
                    Tile(onClick = viewModel::retry, modifier = Modifier.padding(top = 16.dp)) { _ ->
                        Text(stringResource(R.string.player_retry), Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                    }
                }
            }
        }

        AnimatedVisibility(
            flipVisible && !listOpen && ui.channelCount != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            FlipList(viewModel, ui)
        }

        AnimatedVisibility(overlay && !listOpen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            InfoBar(
                ui, isLive, playing, player, tv, viewModel,
                // TV: while the buttons don't have focus, OK is play/pause; show that as a focused button.
                playPauseFocused = tv && !actionsFocused,
                onList = { listOpen = true },
                onAudio = { choosingAudio = true },
                onSubtitles = { choosingSubtitles = true },
                actions = if (tv) {
                    {
                        PlayerActions(
                            ui, isLive, viewModel, actionsFocus,
                            onFocused = { actionsFocused = it },
                            onUse = ::showOverlay,
                            // Up leaves the buttons: the panel stays, and OK pauses/plays again.
                            onLeave = {
                                runCatching { rootFocus.requestFocus() }
                                showOverlay()
                            },
                            onAudio = { choosingAudio = true },
                            onSubtitles = { choosingSubtitles = true },
                        )
                    }
                } else {
                    null
                },
            )
        }

        AnimatedVisibility(
            showNext,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = NEXT_MARGIN, bottom = nextBottom),
        ) {
            NextButton(tv, selected = nextSelected, onClick = { viewModel.playNext() })
        }

        if (choosingAudio) {
            AudioPicker(ui.audio, onPick = { viewModel.selectAudio(it); showOverlay() }, onDismiss = { choosingAudio = false })
        }
        if (choosingSubtitles) {
            SubtitlePicker(ui.subtitles, onPick = { viewModel.selectSubtitles(it); showOverlay() }, onDismiss = { choosingSubtitles = false })
        }

        ui.upNext?.let { next ->
            UpNextCard(next, tv, onPlay = { viewModel.playNext() }, onStop = viewModel::stopAfterEpisode, modifier = Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            listOpen,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            ChannelPanel(viewModel, ui, onPicked = { listOpen = false })
        }
    }
}

private const val MIN_EPISODE_MS = 10 * 60 * 1000L
private const val NEXT_PILL_WINDOW_MS = 2 * 60 * 1000L
private val NEXT_MARGIN = 40.dp
private val NEXT_ABOVE_PANEL = 200.dp

/**
 * Bottom right near the end of an episode. On TV it never takes real focus (the player keeps the keys,
 * and it can't vanish with focus on it): [selected] draws it as focused, and OK on the player presses it.
 */
@Composable
private fun NextButton(tv: Boolean, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color(0xB3000000))
            .then(if (tv) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 28.dp, vertical = 14.dp),
    ) {
        Text(
            stringResource(R.string.player_next_episode),
            color = if (selected) Color.Black else Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** After an episode: the countdown to the next one, or "Still watching?". */
@Composable
private fun UpNextCard(next: UpNext, tv: Boolean, onPlay: () -> Unit, onStop: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE6101216))
            .padding(horizontal = 36.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val seconds = next.secondsLeft
        Text(
            if (seconds == null) stringResource(R.string.player_still_watching) else stringResource(R.string.player_up_next, next.label),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        if (seconds != null) Text(stringResource(R.string.player_up_next_in, seconds), style = MaterialTheme.typography.titleMedium, color = Color(0xFFB0B3BA))
        if (tv) {
            Text(
                if (seconds == null) stringResource(R.string.player_still_watching_hint, next.label) else stringResource(R.string.player_up_next_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFB0B3BA),
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                Tile(onPlay) { _ -> Text(stringResource(R.string.player_up_next, next.label), Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
                Tile(onStop) { _ -> Text(stringResource(R.string.player_stop), Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
            }
        }
    }
}

private fun handleKey(
    keyCode: Int,
    repeat: Int,
    isLive: Boolean,
    failed: Boolean,
    vm: PlayerViewModel,
    showOverlay: () -> Unit,
    onZap: () -> Unit,
    onInfo: () -> Unit,
    openList: () -> Unit,
): Boolean {
    when (keyCode) {
        AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
            vm.togglePlayPause()
            showOverlay()
            return true
        }
        AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { vm.zap(-1); onZap(); return true }
        AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.zap(1); onZap(); return true }
    }
    val center = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
    if (failed && center) {
        vm.retry()
        return true
    }
    return if (isLive) {
        when {
            keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP -> { vm.zap(-1); onZap(); true }
            keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { vm.zap(1); onZap(); true }
            keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT || keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { openList(); true }
            center -> { onInfo(); true }
            else -> false
        }
    } else {
        val step = if (repeat >= 3) 30_000L else 10_000L
        when {
            keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT || keyCode == AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { vm.seekBy(-step); showOverlay(); true }
            keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT || keyCode == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { vm.seekBy(step); showOverlay(); true }
            center -> { vm.togglePlayPause(); showOverlay(); true }
            keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP || keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { onInfo(); true }
            else -> false
        }
    }
}

@Composable
private fun InfoBar(
    ui: PlayerUi,
    isLive: Boolean,
    playing: Boolean,
    player: Player,
    tv: Boolean,
    vm: PlayerViewModel,
    playPauseFocused: Boolean,
    onList: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
    onAudio: () -> Unit = {},
    onSubtitles: () -> Unit = {},
) {
    val item = ui.item ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color(0xB3000000), 1f to Color(0xF2000000)))
            .padding(start = 48.dp, end = 48.dp, top = 72.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (isLive && ui.channelNumber != null) {
                Text("${ui.channelNumber}", style = MaterialTheme.typography.headlineMedium, color = Color(0xFFB0B3BA))
            }
            Text(item.title, style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
            if (ui.favorite) Text("★", style = MaterialTheme.typography.headlineSmall, color = Color(0xFFFFD54F))
        }
        val stream = item.streams.getOrNull(ui.streamIndex)
        val details = listOfNotNull(
            item.subtitle,
            stream?.quality?.badge(),
            stream?.sourceName?.takeIf { item.streams.map { it.sourceName }.distinct().size > 1 },
            // Only worth mentioning when the first choice failed and a fallback is playing.
            if (ui.streamIndex > 0) stringResource(R.string.player_backup_stream) else null,
        )
        if (details.isNotEmpty()) Text(details.joinToString("  ·  "), color = Color(0xFFB0B3BA), style = MaterialTheme.typography.titleMedium)
        ui.audio.firstOrNull { it.selected }?.let {
            Text(stringResource(R.string.player_audio, audioLabel(it)), color = Color(0xFF8A8E98), style = MaterialTheme.typography.bodyMedium)
        }
        if (isLive) Guide(ui.guide)
        if (!isLive) VodProgress(player, playing, playPauseFocused)
        actions?.invoke()
        if (tv) {
            Text(
                stringResource(if (isLive) R.string.player_hint_live else R.string.player_hint_vod),
                color = Color(0xFF8A8E98),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            TouchControls(isLive, playing, vm, onList, onAudio, onSubtitles)
        }
    }
}

/**
 * TV: the player's buttons, reached with OK (live) or Up/Down (films, episodes): favourite (live),
 * picture, audio when there is more than one track, subtitles when there are any. No scrolling through the channel list.
 */
@Composable
private fun PlayerActions(
    ui: PlayerUi,
    isLive: Boolean,
    vm: PlayerViewModel,
    first: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onUse: () -> Unit,
    onLeave: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    Row(
        Modifier
            .padding(top = 8.dp)
            .onFocusChanged { onFocused(it.hasFocus) }
            .focusGroup() // so the listener above sees focus on the buttons inside
            // Left/Right stay in the row (at the ends they would open the channel list or seek).
            // Up goes back to the picture, where OK is play/pause (nothing above the row takes focus).
            .onKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                when {
                    e.type != KeyEventType.KeyDown -> false
                    code == AndroidKeyEvent.KEYCODE_DPAD_LEFT || code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        focusManager.moveFocus(if (code == AndroidKeyEvent.KEYCODE_DPAD_LEFT) FocusDirection.Left else FocusDirection.Right)
                        onUse()
                        true
                    }
                    code == AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        onLeave()
                        true
                    }
                    else -> false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        @Composable
        fun button(text: String, modifier: Modifier = Modifier, action: () -> Unit) = Tile(onClick = { action(); onUse() }, shape = RoundedCornerShape(50), modifier = modifier) { _ ->
            Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        }
        if (isLive) {
            button(stringResource(if (ui.favorite) R.string.player_remove_favourite_short else R.string.player_add_favourite_short), Modifier.focusRequester(first)) { vm.toggleFavorite() }
            button(stringResource(R.string.player_zoom, zoomLabel(ui.zoom))) { vm.cycleZoom() }
        } else {
            button(stringResource(R.string.player_zoom, zoomLabel(ui.zoom)), Modifier.focusRequester(first)) { vm.cycleZoom() }
        }
        if (ui.audio.size > 1) {
            val current = ui.audio.firstOrNull { it.selected }
            button(stringResource(R.string.player_audio_choose, current?.let { audioShortLabel(it) } ?: "")) { onAudio() }
        }
        if (ui.subtitles.isNotEmpty()) button(subtitlesButtonLabel(ui.subtitles)) { onSubtitles() }
    }
}

@Composable
private fun Guide(guide: List<ProgrammeEntity>) {
    val now = guide.firstOrNull() ?: return
    // Follows the device's 12/24-hour setting (Settings > System > Date & time).
    val context = androidx.compose.ui.platform.LocalContext.current
    val time = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "${time.format(java.util.Date(now.startMs))}–${time.format(java.util.Date(now.endMs))}",
            color = Color(0xFFB0B3BA),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(now.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1)
    }
    val span = (now.endMs - now.startMs).coerceAtLeast(1)
    val fraction = ((System.currentTimeMillis() - now.startMs).toFloat() / span).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth(0.5f).height(3.dp).background(Color(0x55FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(MaterialTheme.colorScheme.primary))
    }
    now.description?.takeIf { it.isNotBlank() }?.let {
        Text(it, color = Color(0xFFB0B3BA), style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.fillMaxWidth(0.7f))
    }
    guide.getOrNull(1)?.let { next ->
        Text(
            stringResource(R.string.player_next, time.format(java.util.Date(next.startMs)), next.title),
            color = Color(0xFFB0B3BA),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun VodProgress(player: Player, playing: Boolean, playPauseFocused: Boolean) {
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var duration by remember { mutableLongStateOf(player.duration) }
    LaunchedEffect(player, playing) {
        while (true) {
            position = player.currentPosition
            duration = player.duration
            delay(500)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Looks like a focused button when OK presses it (Up from the buttons below lands here).
        Box(
            Modifier
                .background(if (playPauseFocused) Color.White else Color.Transparent, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            // What OK does, like any player's button: pause while playing, play while paused.
            Text(if (playing) "❚❚" else "▶", color = if (playPauseFocused) Color.Black else Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Text(formatTime(position), color = Color.White, style = MaterialTheme.typography.labelLarge)
        Box(Modifier.weight(1f).height(4.dp).background(Color(0x55FFFFFF))) {
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(MaterialTheme.colorScheme.primary))
        }
        Text(if (duration > 0) formatTime(duration) else "--:--", color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun TouchControls(isLive: Boolean, playing: Boolean, vm: PlayerViewModel, onList: () -> Unit, onAudio: () -> Unit, onSubtitles: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
        @Composable
        fun button(text: String, action: () -> Unit) = Tile(action) { _ ->
            Text(text, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium)
        }
        val ui by vm.ui.collectAsStateWithLifecycle()
        button("⤢ " + zoomLabel(ui.zoom), vm::cycleZoom)
        if (ui.audio.size > 1) button("♪", onAudio)
        if (ui.subtitles.isNotEmpty()) button("CC", onSubtitles)
        if (isLive) {
            button("▲") { vm.zap(-1) }
            button("▼") { vm.zap(1) }
            button("☰", onList)
            button("★", vm::toggleFavorite)
        } else {
            button("−10s") { vm.seekBy(-10_000) }
            button(if (playing) "❚❚" else "▶", vm::togglePlayPause)
            button("+10s") { vm.seekBy(10_000) }
        }
    }
}

/**
 * Shown while flipping with Up/Down: a few channels around the highlighted one. Not focusable;
 * the player keeps the keys.
 */
@Composable
private fun FlipList(vm: PlayerViewModel, ui: PlayerUi) {
    val channels = vm.channelList.collectAsLazyPagingItems()
    val highlighted = ui.zapIndex ?: ((ui.channelNumber ?: 1) - 1)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (highlighted - 3).coerceAtLeast(0))
    LaunchedEffect(highlighted, channels.itemCount) {
        if (channels.itemCount > 0) listState.scrollToItem((highlighted - 3).coerceIn(0, channels.itemCount - 1))
    }
    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .width(460.dp)
            .fillMaxHeight()
            .background(Color(0xE6101116))
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        items(channels.itemCount, key = channels.itemKey { it.id }) { i ->
            val c = channels[i] ?: return@items
            val selected = i == highlighted
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(if (selected) Color(0xFFECEDEF) else Color(0xFF1B1D24))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${i + 1}", color = if (selected) Color(0xFF0E0F13) else Color(0xFF8A8E98), style = MaterialTheme.typography.titleMedium)
                app.pureiptv.ui.components.ChannelLogo(c.logo, c.name)
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = if (selected) Color(0xFF0E0F13) else Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    c.nowTitle?.let {
                        Text(it, color = if (selected) Color(0xFF3A3D45) else Color(0xFFB0B3BA), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Live side panel: favorite toggle for the current channel + the list it was started from. */
@Composable
private fun ChannelPanel(vm: PlayerViewModel, ui: PlayerUi, onPicked: () -> Unit) {
    val channels = vm.channelList.collectAsLazyPagingItems()
    val focus = remember { FocusMemory() }
    val start = ((ui.channelNumber ?: 1) - 1).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    val favoriteFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        val current = vm.currentChannelId
        if (current != null && ui.channelNumber != null) focus.request(current) else runCatching { favoriteFocus.requestFocus() }
    }
    Column(
        Modifier
            .width(460.dp)
            .fillMaxHeight()
            .background(Color(0xF0101116))
            .onKeyEvent { event ->
                // Left/Right closes the panel (Up/Down/OK are handled by the list).
                val code = event.nativeKeyEvent.keyCode
                if (event.type == KeyEventType.KeyDown &&
                    (code == AndroidKeyEvent.KEYCODE_DPAD_LEFT || code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
                ) {
                    onPicked()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val name = ui.item?.title.orEmpty()
        ListTile(
            text = stringResource(if (ui.favorite) R.string.player_unfavorite else R.string.player_favorite, name),
            onClick = vm::toggleFavorite,
            modifier = Modifier.focusRequester(favoriteFocus),
            leading = { Text(if (ui.favorite) "★" else "☆") },
        )
        ListTile(
            text = stringResource(R.string.player_zoom, zoomLabel(ui.zoom)),
            onClick = vm::cycleZoom,
            leading = { Text("⤢") },
        )
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(channels.itemCount, key = channels.itemKey { it.id }) { i ->
                val c = channels[i] ?: return@items
                ChannelTile(
                    channel = c,
                    favorite = false,
                    supporting = if (c.id == vm.currentChannelId) stringResource(R.string.player_now_playing) else null,
                    onClick = {
                        vm.playChannel(c.id)
                        onPicked()
                    },
                    modifier = Modifier.rememberFocus(focus, c.id),
                )
            }
        }
    }
}

@Composable
private fun zoomLabel(zoom: Zoom): String = stringResource(
    when (zoom) {
        Zoom.Fit -> R.string.zoom_fit
        Zoom.Fill -> R.string.zoom_fill
        Zoom.Stretch -> R.string.zoom_stretch
    },
)

internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Observes one player property as Compose state. */
@Composable
private fun rememberPlayerFlag(player: Player, read: (Player) -> Boolean): Boolean {
    var value by remember(player) { mutableStateOf(read(player)) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                value = read(player)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return value
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
