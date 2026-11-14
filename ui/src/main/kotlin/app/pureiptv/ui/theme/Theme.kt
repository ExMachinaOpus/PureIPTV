package app.pureiptv.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import app.pureiptv.ui.FormFactor
import app.pureiptv.ui.LocalFormFactor
import androidx.compose.material3.MaterialTheme as TouchMaterialTheme
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

/** One palette, dark only: video apps live in dark rooms, and TV UIs are expected to be dark. */
private object Palette {
    val background = Color(0xFF0E0F13)
    val surface = Color(0xFF181A20)
    val surfaceVariant = Color(0xFF23262E)
    val primary = Color(0xFF8AB4FF)
    val onPrimary = Color(0xFF0B1B3A)
    val onSurface = Color(0xFFECEDEF)
    val onSurfaceVariant = Color(0xFFA9ADB7)
    val border = Color(0xFFFFFFFF)
    val error = Color(0xFFFF8A80)
}

@Composable
fun PureTheme(formFactor: FormFactor, content: @Composable () -> Unit) {
    app.pureiptv.ui.components.NoSoundEffects()
    CompositionLocalProvider(LocalFormFactor provides formFactor) {
        // Both themes are provided so shared components can use either set of tokens.
        TouchMaterialTheme(
            colorScheme = darkColorScheme(
                background = Palette.background,
                surface = Palette.surface,
                surfaceVariant = Palette.surfaceVariant,
                primary = Palette.primary,
                onPrimary = Palette.onPrimary,
                onBackground = Palette.onSurface,
                onSurface = Palette.onSurface,
                onSurfaceVariant = Palette.onSurfaceVariant,
                error = Palette.error,
            ),
        ) {
            TvMaterialTheme(
                colorScheme = tvDarkColorScheme(
                    background = Palette.background,
                    surface = Palette.surface,
                    surfaceVariant = Palette.surfaceVariant,
                    primary = Palette.primary,
                    onPrimary = Palette.onPrimary,
                    onBackground = Palette.onSurface,
                    onSurface = Palette.onSurface,
                    onSurfaceVariant = Palette.onSurfaceVariant,
                    border = Palette.border,
                    error = Palette.error,
                ),
            ) {
                // Default text/icon colour for both component sets, so text is never black-on-black
                // when a screen draws its own background instead of using a Surface.
                CompositionLocalProvider(
                    androidx.tv.material3.LocalContentColor provides Palette.onSurface,
                    androidx.compose.material3.LocalContentColor provides Palette.onSurface,
                    content = content,
                )
            }
        }
    }
}
