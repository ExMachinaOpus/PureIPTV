package app.pureiptv.ui.guide

import app.pureiptv.ui.components.PureDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import app.pureiptv.core.data.db.ChannelRow
import app.pureiptv.core.data.db.ProgrammeEntity
import app.pureiptv.core.data.db.ProgrammeHit
import app.pureiptv.core.data.library.LiveFilter
import app.pureiptv.core.data.library.LiveGroup
import app.pureiptv.ui.R
import app.pureiptv.ui.components.ChannelLogo
import app.pureiptv.ui.components.ListTile
import app.pureiptv.ui.components.RemoteTextField
import app.pureiptv.ui.components.Tile
import app.pureiptv.ui.components.TileColors
import app.pureiptv.ui.components.formatted
import app.pureiptv.ui.components.isTv
import app.pureiptv.ui.components.label
import app.pureiptv.ui.components.rememberFocus
import app.pureiptv.ui.components.rememberGridEntry
import app.pureiptv.ui.components.rememberListEntry
import app.pureiptv.ui.live.enterOn
import app.pureiptv.ui.player.PlayRequest
import java.util.Date
import kotlin.math.roundToInt

private val LiveRed = Color(0xFFE5484D)
private val RowHeight = 56.dp
private val PanelHeight = 84.dp
/** Search matches in the grid. */
private val MatchColour = Color(0xFF3B4A7A)

/**
 * TV guide. Top: search over the whole guide (all channels) and the channel group picker
 * (favourites by default). Below: the time grid. While the remote is in the grid, the selected
 * programme takes the place of the search row, so the grid keeps its room and never moves; Up from
 * the first row brings the search back.
 *
 * Remote: each grid row is one focusable item, so Up/Down move between channels natively and keep
 * the time (the programme at the same time on the next channel is selected); Left/Right move
 * between programmes; OK plays what's on now, or shows a later programme's details; Back returns to
 * now first. Fast-forward / rewind page by two hours.
 */
@Composable
fun GuideScreen(onPlay: (PlayRequest) -> Unit, modifier: Modifier = Modifier, viewModel: GuideViewModel = hiltViewModel()) {
    val tv = isTv
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val group by viewModel.group.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<ProgrammeHit?>(null) }

    val play: (Long) -> Unit = { channelId ->
        viewModel.rowFocus.markForRestore()
        // Up/Down in the player zaps through the group; search results are not a channel list.
        onPlay(PlayRequest.Channel(channelId, if (results == null) group?.let { LiveFilter(it) } else null))
    }

    details?.let { hit -> ProgrammeDialog(hit, onWatch = { play(hit.channelId) }, onDismiss = { details = null }) }
    if (picking) {
        GroupPicker(groups.orEmpty().map { it.group to it.count }, group, onPick = {
            viewModel.selectGroup(it)
            picking = false
        }, onDismiss = { picking = false })
    }

    val searchShown by animateFloatAsState(if (viewModel.gridFocused) 0f else 1f, label = "guide search row")

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth()) {
            // Faded, not removed: Up from the grid needs something to land on.
            Row(
                Modifier.alpha(searchShown),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    placeholder = stringResource(R.string.guide_search),
                    modifier = if (tv) Modifier.width(420.dp) else Modifier.weight(1f),
                )
                Tile(onClick = { picking = true }, shape = RoundedCornerShape(50)) { _ ->
                    Text(
                        (group?.label() ?: "") + "  ▾",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
            // Only the remote selects a programme; on touch a tap acts on it directly.
            if (tv) SelectedPanel(viewModel.selected, Modifier.alpha(1f - searchShown))
        }
        val r = results
        if (r != null && r.isEmpty()) {
            Text(
                stringResource(R.string.guide_nothing, query.trim(), group?.label().orEmpty()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 32.dp),
            )
        } else {
            val search = remember(r) { r?.let(::SearchGrid) }
            Grid(viewModel, entryKey = if (search != null) "q:" + query.trim() else group, tv, search, play = play, details = { channel, p -> details = p.toHit(channel) })
        }
    }
}

/**
 * Guide search shown as the grid: one row per channel with a match (soonest match first), matching
 * programmes highlighted. Rows are built from the hits, so no channel query is needed.
 */
private class SearchGrid(hits: List<ProgrammeHit>) {
    val channels: List<ChannelRow>
    /** Start times of matching programmes per channel, earliest first. */
    val matches: Map<Long, List<Long>>

    init {
        val byChannel = LinkedHashMap<Long, MutableList<ProgrammeHit>>()
        for (h in hits) byChannel.getOrPut(h.channelId) { mutableListOf() } += h
        channels = byChannel.values.map { list ->
            val h = list.first()
            ChannelRow(h.channelId, "", h.channelName, h.channelLogo, null, 0, 0, "", null)
        }
        matches = byChannel.mapValues { (_, list) -> list.map { it.startMs }.sorted() }
    }
}

@Composable
private fun Grid(
    viewModel: GuideViewModel,
    entryKey: Any?,
    tv: Boolean,
    search: SearchGrid?,
    play: (Long) -> Unit,
    details: (ChannelRow, ProgrammeEntity) -> Unit,
) {
    val paged = viewModel.channels.collectAsLazyPagingItems()
    val count = search?.channels?.size ?: paged.itemCount
    val channelAt: (Int) -> ChannelRow? = { i -> if (search != null) search.channels.getOrNull(i) else paged[i] }
    BackHandler(enabled = viewModel.gridFocused && !viewModel.isAtNow) { viewModel.backToNow() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val channelWidth = if (tv) 230.dp else 112.dp
        val timelineWidth = maxWidth - channelWidth - 8.dp
        val density = LocalDensity.current
        val pxPerMs = with(density) { timelineWidth.toPx() } / GuideViewModel.SPAN_MS
        Column(Modifier.fillMaxSize()) {
            TimeHeader(viewModel, channelWidth, pxPerMs)
            // A new group or search starts at the top.
            val listState = key(entryKey) { rememberLazyListState() }
            val entry = rememberListEntry(listState, entryKey)
            // Touch: drag the grid sideways to move in time.
            var dragged by remember { mutableFloatStateOf(0f) }
            val stepPx = pxPerMs * GuideViewModel.STEP_MS
            val drag = rememberDraggableState { delta ->
                dragged += delta
                while (dragged <= -stepPx) { dragged += stepPx; viewModel.shift(1) }
                while (dragged >= stepPx) { dragged -= stepPx; viewModel.shift(-1) }
            }
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { viewModel.gridFocused = it.hasFocus }
                    .enterOn(entry.requester)
                    .then(if (tv) Modifier else Modifier.draggable(drag, Orientation.Horizontal)),
            ) {
                // Position keys: the list is replaced when the group or the search changes (AGENTS.md).
                items(count, key = { "row$it" }) { i ->
                    val channel = channelAt(i) ?: return@items
                    GuideRow(
                        channel,
                        viewModel,
                        tv,
                        channelWidth,
                        pxPerMs,
                        matches = search?.matches?.get(channel.id),
                        onOpen = { p ->
                            if (p == null || p.startMs <= System.currentTimeMillis()) play(channel.id) else details(channel, p)
                        },
                        modifier = with(entry) { Modifier.rememberFocus(viewModel.rowFocus, channel.id).entryItem(i) },
                    )
                }
            }
        }
    }
}

/** Fixed height, so moving between channels doesn't shift the grid. The channel is the highlighted row. */
@Composable
private fun SelectedPanel(selected: Pair<ChannelRow, ProgrammeEntity?>?, modifier: Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().height(PanelHeight).padding(horizontal = 4.dp)) {
        val (channel, p) = selected ?: return@Column
        if (p == null) {
            Text(channel.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(stringResource(R.string.guide_no_info), style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.7f))
            return@Column
        }
        val time = android.text.format.DateFormat.getTimeFormat(context)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(p.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(
                listOfNotNull(programmeDay(context, p.startMs) + "${time.format(Date(p.startMs))}–${time.format(Date(p.endMs))}", p.episode).joinToString("  ·  "),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = LocalContentColor.current.copy(alpha = 0.8f),
                // Long episode labels exist; the title must keep its room.
                modifier = Modifier.widthIn(max = 360.dp),
            )
        }
        val text = listOfNotNull(p.subtitle, p.description).joinToString("  ·  ")
        if (text.isNotEmpty()) {
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun TimeHeader(viewModel: GuideViewModel, channelWidth: Dp, pxPerMs: Float) {
    val context = LocalContext.current
    val time = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    Row(Modifier.fillMaxWidth().height(28.dp)) {
        Text(
            programmeDay(context, viewModel.viewStart).trim().ifEmpty { stringResource(R.string.guide_today) },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(channelWidth + 8.dp).padding(start = 4.dp),
        )
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(0.dp))) {
            var t = viewModel.viewStart
            while (t < viewModel.viewStart + GuideViewModel.SPAN_MS) {
                val x = ((t - viewModel.viewStart) * pxPerMs).roundToInt()
                Text(
                    time.format(Date(t)),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                    modifier = Modifier.offset { IntOffset(x, 0) }.padding(start = 6.dp),
                )
                t += GuideViewModel.STEP_MS
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: ChannelRow,
    viewModel: GuideViewModel,
    tv: Boolean,
    channelWidth: Dp,
    pxPerMs: Float,
    /** Search: start times of matching programmes (highlighted; focusing the row jumps to the first). */
    matches: List<Long>?,
    onOpen: (ProgrammeEntity?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val programmes by produceState<List<ProgrammeEntity>?>(null, channel.id, viewModel.now) {
        value = viewModel.schedule(channel.id)
    }
    var focused by remember { mutableStateOf(false) }
    val list = programmes.orEmpty()
    val selected = list.firstOrNull { it.startMs <= viewModel.focusTime && it.endMs > viewModel.focusTime }
    if (focused) {
        LaunchedEffect(channel, selected) { viewModel.selected = channel to selected }
        // Search: arriving on a channel shows its match, not whatever runs at the current time.
        LaunchedEffect(channel.id, matches) {
            val first = matches?.firstOrNull() ?: return@LaunchedEffect
            if (selected == null || selected.startMs !in matches) viewModel.moveTo(maxOf(first, viewModel.now), visible = first)
        }
    }

    val rowModifier = if (tv) {
        modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> {
                        val next = if (selected != null) list.getOrNull(list.indexOf(selected) + 1) else list.firstOrNull { it.startMs > viewModel.focusTime }
                        if (next != null) viewModel.moveTo(next.startMs) else viewModel.moveTo(viewModel.focusTime + GuideViewModel.STEP_MS)
                        true
                    }
                    Key.DirectionLeft -> {
                        val prev = if (selected != null) list.getOrNull(list.indexOf(selected) - 1) else list.lastOrNull { it.endMs <= viewModel.focusTime }
                        if (prev != null && prev.endMs > viewModel.earliest) {
                            viewModel.moveTo(maxOf(prev.startMs, viewModel.earliest))
                        } else {
                            viewModel.moveTo(viewModel.focusTime - GuideViewModel.STEP_MS)
                        }
                        true
                    }
                    Key.MediaFastForward -> { viewModel.page(forward = true); true }
                    Key.MediaRewind -> { viewModel.page(forward = false); true }
                    else -> false
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen(selected) }
    } else {
        modifier
    }

    Row(rowModifier.fillMaxWidth().height(RowHeight), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Channel: touch plays it.
        Row(
            Modifier
                .width(channelWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(if (focused) TileColors.rowHighlight else TileColors.normal)
                .then(if (tv) Modifier else Modifier.clickable { onOpen(null) })
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChannelLogo(channel.logo, channel.name)
            if (tv) {
                Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Timeline(
            list,
            loaded = programmes != null,
            viewModel,
            pxPerMs,
            highlight = if (focused) selected else null,
            matches = matches,
            rowFocused = focused,
            onTap = onOpen,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            tv = tv,
        )
    }
}

@Composable
private fun Timeline(
    list: List<ProgrammeEntity>,
    loaded: Boolean,
    viewModel: GuideViewModel,
    pxPerMs: Float,
    highlight: ProgrammeEntity?,
    matches: List<Long>?,
    rowFocused: Boolean,
    onTap: (ProgrammeEntity?) -> Unit,
    modifier: Modifier,
    tv: Boolean,
) {
    val context = LocalContext.current
    val time = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val density = LocalDensity.current
    val viewStart = viewModel.viewStart
    val viewEnd = viewStart + GuideViewModel.SPAN_MS
    val now = viewModel.now
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (tv) Modifier else Modifier.pointerInput(list, viewStart, pxPerMs) {
                    detectTapGestures { offset ->
                        val t = viewStart + (offset.x / pxPerMs).toLong()
                        onTap(list.firstOrNull { it.startMs <= t && it.endMs > t })
                    }
                },
            ),
    ) {
        if (loaded && list.none { it.endMs > viewStart && it.startMs < viewEnd }) {
            Cell(stringResource(R.string.guide_no_info), null, focused = rowFocused, past = false, match = false, Modifier.fillMaxSize())
        }
        for (p in list) {
            if (p.endMs <= viewStart || p.startMs >= viewEnd) continue
            val from = maxOf(p.startMs, viewStart)
            val to = minOf(p.endMs, viewEnd)
            val x = ((from - viewStart) * pxPerMs).roundToInt()
            val w = with(density) { ((to - from) * pxPerMs).toDp() } - 3.dp
            if (w <= 0.dp) continue
            key(p.id) {
                Cell(
                    p.title,
                    subtitle = if (w > 110.dp) "${time.format(Date(p.startMs))}–${time.format(Date(p.endMs))}" else null,
                    focused = p == highlight,
                    past = p.endMs <= now,
                    match = matches != null && p.startMs in matches,
                    modifier = Modifier.offset { IntOffset(x, 0) }.width(w).fillMaxHeight(),
                )
            }
        }
        if (now in viewStart until viewEnd) {
            val x = ((now - viewStart) * pxPerMs).roundToInt()
            Box(Modifier.offset { IntOffset(x, 0) }.width(2.dp).fillMaxHeight().background(LiveRed))
        }
    }
}

@Composable
private fun Cell(title: String, subtitle: String?, focused: Boolean, past: Boolean, match: Boolean, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    focused -> TileColors.focused
                    match -> MatchColour
                    else -> TileColors.normal
                },
            )
            .then(if (focused) Modifier.border(2.dp, TileColors.border, RoundedCornerShape(6.dp)) else Modifier)
            .alpha(if (past && !focused) 0.55f else 1f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val color = if (focused) TileColors.onFocused else LocalContentColor.current
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}

@Composable
private fun GroupPicker(groups: List<Pair<LiveGroup, Int>>, current: LiveGroup?, onPick: (LiveGroup) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    PureDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 460.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.guide_channels), style = MaterialTheme.typography.titleLarge)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(420.dp)) {
                items(groups, key = { it.first.toString() }) { (g, count) ->
                    ListTile(
                        g.label(),
                        trailing = count.formatted(),
                        selected = g == current,
                        onClick = { onPick(g) },
                        modifier = if (g == current) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

/** "" for today, else "Tomorrow " / "Sun " (with a trailing space). */
private fun programmeDay(context: android.content.Context, t: Long): String = when (dayOffset(System.currentTimeMillis(), t)) {
    0 -> ""
    1 -> context.getString(R.string.guide_tomorrow) + " "
    else -> app.pureiptv.ui.components.weekdayShort(t) + " "
}

private fun ProgrammeEntity.toHit(channel: ChannelRow) = ProgrammeHit(
    channelId = channel.id,
    channelName = channel.name,
    channelLogo = channel.logo,
    title = title,
    subtitle = subtitle,
    description = description,
    episode = episode,
    startMs = startMs,
    endMs = endMs,
)
