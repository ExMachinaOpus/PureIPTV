package app.pureiptv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pureiptv.ui.FormFactor
import app.pureiptv.ui.LocalFormFactor
import androidx.compose.material3.Button as TouchButton
import androidx.compose.material3.OutlinedButton as TouchOutlinedButton
import androidx.compose.material3.Text as TouchText
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.OutlinedButton as TvOutlinedButton
import androidx.tv.material3.Text as TvText

/**
 * Small building blocks that render the right component set per form factor (tv-material on TV
 * for proper focus visuals, material3 on touch). Use these instead of picking a Button directly.
 */

val isTv: Boolean
    @Composable get() = LocalFormFactor.current == FormFactor.Tv

/**
 * Full-screen container: overscan-safe margins on TV, system-bar-safe padding on touch.
 * [backdrop] is drawn edge to edge behind the content (outside the margins).
 */
@Composable
fun Screen(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    backdrop: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val padding = if (isTv) {
        Modifier.padding(horizontal = 58.dp, vertical = 28.dp) // ~5% overscan safe area
    } else {
        Modifier.safeDrawingPadding().padding(24.dp)
    }
    if (backdrop == null) {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(padding), contentAlignment = contentAlignment, content = content)
        return
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        backdrop()
        Box(Modifier.fillMaxSize().then(padding), contentAlignment = contentAlignment, content = content)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (isTv) {
        TvButton(onClick = onClick, modifier = modifier, enabled = enabled) { TvText(text) }
    } else {
        TouchButton(onClick = onClick, modifier = modifier, enabled = enabled) { TouchText(text) }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (isTv) {
        TvOutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) { TvText(text) }
    } else {
        TouchOutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) { TouchText(text) }
    }
}

/** A choice among a few ("Playlist link" / "Server and login"): the selected one is filled and ticked. */
@Composable
fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tile(onClick, modifier, shape = RoundedCornerShape(50), selected = selected) { _ ->
        androidx.compose.material3.Text(
            if (selected) "✓  $text" else text,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

/**
 * Remote navigation for text fields: Up/Down leave the field (Compose would otherwise keep D-pad
 * focus inside it), so a form is navigable without touching the on-screen keyboard.
 */
fun Modifier.dpadLeavesTextField(focusManager: FocusManager): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
        else -> false
    }
}
