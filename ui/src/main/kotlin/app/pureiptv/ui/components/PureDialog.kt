package app.pureiptv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog

/**
 * No navigation or click sounds: the system "bop" on every D-pad move is noise in an app you browse
 * for minutes at a time. Sound effects are a per-view flag, so every window's root view needs it
 * (the activity's, and each dialog's: use [PureDialog]).
 */
@Composable
fun NoSoundEffects() {
    val view = LocalView.current
    SideEffect {
        view.isSoundEffectsEnabled = false
        view.rootView.isSoundEffectsEnabled = false
    }
}

/**
 * [Dialog] without navigation sounds, and immune to the OK press that opened it: a menu opened by
 * holding OK would otherwise receive that key's repeats and release and pick its first option.
 * OK only counts after a fresh press inside the dialog. Use instead of Dialog.
 */
@Composable
fun PureDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    // A dialog is its own window and would go back to the device language: keep the app's.
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    val direction = LocalLayoutDirection.current
    Dialog(onDismissRequest = onDismissRequest) {
        NoSoundEffects()
        var armed by remember { mutableStateOf(false) }
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalResources provides resources,
            LocalLayoutDirection provides direction,
        ) {
            Box(
                Modifier.onPreviewKeyEvent { e ->
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    when {
                        !ok || armed -> false
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> {
                            armed = true
                            false
                        }
                        else -> true // repeat or release of the press that opened the dialog
                    }
                },
            ) { content() }
        }
    }
}
