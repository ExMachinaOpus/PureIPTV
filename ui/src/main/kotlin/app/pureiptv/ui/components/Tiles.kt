package app.pureiptv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pureiptv.core.model.Quality
import app.pureiptv.ui.R
import coil3.compose.SubcomposeAsyncImage

/** Shared colours for focus/selection, readable from 3 m. */
object TileColors {
    val normal = Color(0xFF1B1D24)
    /** The guide's channel cell while focus is on that row (where you are, not a selection). */
    val rowHighlight = Color(0xFF2A3350)
    val focused = Color(0xFFECEDEF)
    val onFocused = Color(0xFF0E0F13)
    val border = Color(0xFFFFFFFF)
}

/** A tile's resting background. Over artwork (details pages) it is see-through. */
val LocalTileBackground = staticCompositionLocalOf { TileColors.normal }

/**
 * The one focusable/clickable building block for lists and grids on TV and touch.
 * Focus = light background + scale + border (TV); touch gets a ripple instead. Selected = underline.
 * [onFocused] lets lists "select on focus" (browse without pressing OK).
 */
@Composable
fun Tile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    selected: Boolean = false,
    focusScale: Float = 1.04f,
    onFocused: (() -> Unit)? = null,
    transparent: Boolean = false,
    /** Hold OK (TV) / press and hold (touch). Never the only way to an action (AGENTS.md). */
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val tv = isTv
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && tv) focusScale else 1f, label = "tileScale")
    val background = when {
        focused && tv -> TileColors.focused
        // Selection never changes the background (the underline shows it).
        transparent -> Color.Transparent
        else -> LocalTileBackground.current
    }
    val accent = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    var longFired by remember { mutableStateOf(false) }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .clip(shape)
            .background(background)
            .then(if (focused && tv) Modifier.border(2.dp, TileColors.border, shape) else Modifier)
            // Selected (the tab, season, category or page that is showing): an underline, not a shade, so
            // it can't be mistaken for focus. Drawn, so it never changes the tile's size. Dark on the
            // light focused tile, accent colour otherwise.
            .then(if (selected) Modifier.drawWithContent {
                drawContent()
                val inset = 16.dp.toPx()
                val height = 3.dp.toPx()
                drawRoundRect(
                    color = if (focused && tv) TileColors.onFocused else accent,
                    topLeft = Offset(inset, size.height - 4.dp.toPx() - height),
                    size = Size((size.width - 2 * inset).coerceAtLeast(0f), height),
                    cornerRadius = CornerRadius(height / 2),
                )
            } else Modifier)
            .then(
                if (tv && onLongClick != null) {
                    // Holding OK repeats the key: the first repeat is the long press; the release that
                    // follows must not also count as a click.
                    Modifier.onPreviewKeyEvent { e ->
                        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                        when {
                            !ok -> false
                            e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> {
                                if (!longFired) {
                                    longFired = true
                                    onLongClick()
                                }
                                true
                            }
                            e.type == KeyEventType.KeyUp && longFired -> {
                                longFired = false
                                true
                            }
                            else -> false
                        }
                    }
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = if (tv) null else ripple(),
                onLongClick = if (tv) null else onLongClick,
                onClick = onClick,
            ),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (focused && tv) TileColors.onFocused else MaterialTheme.colorScheme.onSurface,
            androidx.tv.material3.LocalContentColor provides if (focused && tv) TileColors.onFocused else MaterialTheme.colorScheme.onSurface,
        ) {
            content(focused)
        }
    }
}

/** A one-line list entry: optional leading content, label, optional trailing text. */
@Composable
fun ListTile(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailing: String? = null,
    supporting: String? = null,
    supportingLines: Int = 1,
    onFocused: (() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    Tile(onClick, modifier.fillMaxWidth().heightIn(min = 48.dp), selected = selected, focusScale = 1.02f, onFocused = onFocused) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke(this)
            Column(Modifier.weight(1f)) {
                Text(
                    text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (supporting != null) {
                    Text(
                        supporting,
                        maxLines = supportingLines,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                }
            }
            if (trailing != null) {
                Text(trailing, style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current.copy(alpha = 0.7f))
            }
        }
    }
}

/** Channel logo, or the channel's initials when there is no (working) logo. */
@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier.size(width = 56.dp, height = 36.dp)) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B2E37)), contentAlignment = Alignment.Center) {
        val initials: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    initials(name),
                    color = Color(0xFFD5D7DD),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
        if (url.isNullOrBlank()) {
            initials()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(3.dp),
                loading = { initials() },
                error = { initials() },
            )
        }
    }
}

private fun initials(name: String): String =
    name.substringBefore(" (").split(' ', '-', '_', '[', ']', '|', ':')
        .filter { it.firstOrNull()?.isLetterOrDigit() == true }
        .take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "TV" }

/** "HD", "FHD", "4K" badge text, or null when unknown/SD. */
fun Quality.badge(): String? = when (this) {
    Quality.HD -> "HD"
    Quality.FHD -> "FHD"
    Quality.UHD -> "4K"
    else -> null
}

/** Poster tile (2:3) with title underneath; shows the title on the image when there is no poster. */
@Composable
fun PosterTile(
    title: String,
    subtitle: String?,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    progress: Float? = null,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Column(modifier.width(width)) {
        Tile(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), focusScale = 1.06f, onFocused = onFocused, onLongClick = onLongClick) { _ ->
            val fallback: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize().background(Color(0xFF262A35)).padding(10.dp), contentAlignment = Alignment.Center) {
                    Text(title, textAlign = TextAlign.Center, color = Color(0xFFD5D7DD), style = MaterialTheme.typography.titleSmall, maxLines = 5)
                }
            }
            if (image.isNullOrBlank()) {
                fallback()
            } else {
                SubcomposeAsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = { fallback() },
                    error = { fallback() },
                )
            }
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).padding(6.dp).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (subtitle != null) {
            Text(subtitle, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Focus memory for TV lists: remembers which item had focus and gives it focus back when the
 * screen is shown again (e.g. after returning from the player). Lives in a ViewModel or remembered state.
 */
class FocusMemory {
    var key: Any? = null
        private set
    private var pendingRestore = false

    fun onFocused(key: Any) {
        // While a restore is pending, a passing focus (e.g. the screen's default focus landing on the
        // first item) must not replace the item to restore.
        if (pendingRestore && this.key != key) return
        this.key = key
    }

    /** Gives [key] focus as soon as its item is composed (e.g. the playing channel in a list). */
    fun request(key: Any) {
        this.key = key
        pendingRestore = true
    }

    /** An item is waiting to get its focus back (the screen's own initial focus must stay away). */
    val restorePending: Boolean get() = pendingRestore

    /** Call before navigating away; the remembered item takes focus when it is composed again. */
    fun markForRestore() {
        pendingRestore = key != null
    }

    internal fun consume(key: Any): Boolean {
        if (pendingRestore && this.key == key) {
            pendingRestore = false
            return true
        }
        return false
    }
}

/**
 * True while [rememberFocus] gives an item its focus back. Asking for focus inside a container that
 * doesn't have it yet runs the container's onEnter, and `enterOn` would then send it to the list's
 * usual entry point (the first visible row) instead of the item. UI thread only.
 */
internal var restoringFocus = false

/** Attach to every item that can be remembered by [memory]. */
@Composable
fun Modifier.rememberFocus(memory: FocusMemory, key: Any): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(key) {
        if (memory.consume(key)) {
            // Right after returning to a screen the item may not be attached yet: try a few frames.
            repeat(10) {
                restoringFocus = true
                val focused = try {
                    runCatching { requester.requestFocus() }.getOrDefault(false)
                } finally {
                    restoringFocus = false
                }
                if (focused) return@LaunchedEffect
                androidx.compose.runtime.withFrameNanos { }
            }
        }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) memory.onFocused(key) }
}

/**
 * Where focus enters a lazy list or grid: the item last focused in it while it is still on screen,
 * otherwise the first visible item. Use instead of focusRestorer on lists whose content is swapped
 * (position-keyed), where restoring "slot 3" of the previous content would land mid-list.
 * [key] identifies the content (e.g. the filter); a new key starts fresh.
 */
class ListEntry internal constructor(
    private val firstVisible: () -> Int,
    private val lastVisible: () -> Int,
) {
    val requester = FocusRequester()
    /** Index last focused in this list (-1 = none). Settable to restore it after leaving the screen. */
    var last by mutableIntStateOf(-1)
    val target: Int by derivedStateOf {
        val first = firstVisible()
        if (last in first..lastVisible()) last else first
    }

    /** Attach to item [index]. */
    fun Modifier.entryItem(index: Int): Modifier = this
        .then(if (index == target) Modifier.focusRequester(requester) else Modifier)
        .onFocusChanged { if (it.isFocused) last = index }
}

/** [initial]: index to enter on at first (e.g. restored after returning to the screen), -1 = none. */
@Composable
fun rememberListEntry(state: LazyListState, key: Any?, initial: Int = -1): ListEntry = remember(state, key) {
    ListEntry({ state.firstVisibleItemIndex }, { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }).also { it.last = initial }
}

@Composable
fun rememberGridEntry(state: LazyGridState, key: Any?, initial: Int = -1): ListEntry = remember(state, key) {
    ListEntry({ state.firstVisibleItemIndex }, { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }).also { it.last = initial }
}

/**
 * Switches a country/language list between A–Z and biggest first; shows the current order.
 * [countLabel]: the "biggest first" wording ("most channels", "most titles").
 */
@Composable
fun GroupOrderTile(byName: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, countLabel: Int = R.string.live_order_count) {
    ListTile(
        text = stringResource(if (byName) R.string.live_order_name else countLabel),
        onClick = onToggle,
        modifier = modifier,
        trailing = "⇅",
    )
}
