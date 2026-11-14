package app.pureiptv.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay

/**
 * A text field that behaves like a button on TV: read-only (no keyboard) until OK is pressed on it,
 * so moving through it with the D-pad never pops the keyboard. It is one permanent view: swapping a
 * button for a field would remove the focused view and let focus drift to the tabs (AGENTS.md).
 * Touch: a normal text field.
 */
@Composable
fun RemoteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /** Left while not typing; return true to consume (e.g. reveal a side list). */
    onLeftEdge: () -> Boolean = { false },
) {
    val tv = isTv
    var editing by remember { mutableStateOf(!tv) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Closing the keyboard any way (✓, Back) ends typing, so Left/Right move focus again. Asked of
    // the window directly: Compose's isImeVisible only works edge-to-edge, which TV doesn't use.
    if (tv && editing) {
        val view = LocalView.current
        LaunchedEffect(Unit) {
            var seen = false
            while (true) {
                val visible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                if (visible) seen = true else if (seen) break
                delay(250)
            }
            editing = false
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = !editing,
        singleLine = true,
        placeholder = { Text("⌕  $placeholder") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            if (tv) editing = false else focusManager.clearFocus()
        }),
        modifier = modifier
            .onFocusChanged { if (tv && !it.isFocused) editing = false }
            .onPreviewKeyEvent { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    e.key == Key.DirectionLeft && !editing -> onLeftEdge()
                    // Not typing: Right moves on (a text field would move its cursor instead).
                    e.key == Key.DirectionRight && !editing -> focusManager.moveFocus(FocusDirection.Right)
                    tv && !editing && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) -> {
                        editing = true
                        keyboard?.show()
                        true
                    }
                    else -> false
                }
            }
            .dpadLeavesTextField(focusManager),
    )
}
