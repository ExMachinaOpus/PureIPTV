package app.pureiptv.ui.player

import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.media3.common.Player
import androidx.media3.common.VideoSize

/**
 * Phones and tablets: leaving the app (Home, a notification) while something plays keeps it playing
 * in a small window. Only while playing: a paused video or any other screen just goes to the
 * background as usual. Closing the window stops playback (the player pauses when the app stops).
 * TVs don't use it. Returns true while in the small window, so the player can hide its controls.
 */
@Composable
fun rememberPictureInPicture(activity: ComponentActivity?, player: Player, playing: Boolean): Boolean {
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    if (activity == null) return false

    var aspect by remember(player) { mutableStateOf(aspectOf(player.videoSize)) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                aspect = aspectOf(videoSize)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }

    DisposableEffect(activity, playing, aspect) {
        val params = PictureInPictureParams.Builder().setAspectRatio(aspect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: the system enters it smoothly when the user leaves.
            activity.setPictureInPictureParams(params.setAutoEnterEnabled(playing).build())
        } else {
            activity.setPictureInPictureParams(params.build())
        }
        // Android 8-11: enter it ourselves when the user leaves.
        val onLeave = Runnable {
            if (playing && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                runCatching { activity.enterPictureInPictureMode(params.build()) }
            }
        }
        activity.addOnUserLeaveHintListener(onLeave)
        onDispose {
            activity.removeOnUserLeaveHintListener(onLeave)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            }
        }
    }
    return inPip
}

/** The video's shape, within what Android accepts for the small window (2.39:1 to 1:2.39). */
private fun aspectOf(size: VideoSize): Rational {
    if (size.width <= 0 || size.height <= 0) return Rational(16, 9)
    val ratio = size.width * size.pixelWidthHeightRatio / size.height
    return when {
        ratio > 2.39f -> Rational(239, 100)
        ratio < 1 / 2.39f -> Rational(100, 239)
        else -> Rational((size.width * size.pixelWidthHeightRatio).toInt().coerceAtLeast(1), size.height)
    }
}
