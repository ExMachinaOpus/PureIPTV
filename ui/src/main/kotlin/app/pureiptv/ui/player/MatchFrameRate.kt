package app.pureiptv.ui.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import app.pureiptv.core.player.refreshRateFor
import app.pureiptv.ui.BuildConfig

/**
 * Films and episodes: switches the display to a refresh rate that shows the video evenly. 24 fps film
 * on a 60 Hz output judders (frames alternate between 2 and 3 refreshes); at 24 Hz it doesn't. A switch
 * blanks the screen for a second or two on most TVs, so the system setting "Match content frame rate"
 * (Android 12+) decides, as it does for every streaming app: we switch only when it is "Always".
 * ("Seamless only" Media3 already does itself; older Android keeps the display's rate.)
 * [frameRate] is null for live TV; leaving the player returns the display to its own mode.
 */
@Composable
internal fun MatchFrameRate(frameRate: Float?) {
    val view = LocalView.current
    val home = remember(view) { homeMode(view.display?.mode) }
    LaunchedEffect(view, frameRate) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val mode = frameRate?.let { modeFor(view.context, view.display, home, it) } ?: 0
        if (BuildConfig.DEBUG) android.util.Log.d("PureFrameRate", "video $frameRate fps, display mode $mode (0 = own)")
        window.preferDisplayMode(mode)
    }
    DisposableEffect(view) {
        onDispose { view.context.findActivity()?.window?.preferDisplayMode(0) }
    }
}

/** The last mode we asked for and the display's own mode at that time. */
private var requestedMode = 0
private var lastHome: Display.Mode? = null

/**
 * The display's own mode: the resolution to keep and the rate that needs no switch. That is the
 * [current] one, unless it is still our switch from the previous film (going back takes a second or
 * two, and the next film can start before that).
 */
private fun homeMode(current: Display.Mode?): Display.Mode? {
    if (current == null || current.modeId != requestedMode || lastHome == null) lastHome = current
    return lastHome
}

/** Asks for display mode [id]; 0 is the display's own. */
private fun Window.preferDisplayMode(id: Int) {
    if (id != 0) requestedMode = id
    if (attributes.preferredDisplayModeId != id) attributes = attributes.apply { preferredDisplayModeId = id }
}

/** The display mode for [fps] video, or 0 (no switch) when the viewer doesn't want switches or none fits better. */
private fun modeFor(context: Context, display: Display?, home: Display.Mode?, fps: Float): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || display == null || home == null) return 0
    val preference = context.getSystemService(DisplayManager::class.java)?.matchContentFrameRateUserPreference
    if (preference != DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS) return 0
    val sameSize = display.supportedModes.filter {
        it.physicalWidth == home.physicalWidth && it.physicalHeight == home.physicalHeight
    }
    val refresh = refreshRateFor(fps, sameSize.map { it.refreshRate }, home.refreshRate) ?: return 0
    if (refresh == home.refreshRate) return 0
    return sameSize.firstOrNull { it.refreshRate == refresh }?.modeId ?: 0
}
