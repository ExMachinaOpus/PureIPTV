package app.pureiptv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * TV vs touch is decided once per device, not by window size: a TV is a TV even if a layout
 * would technically fit a phone design. TV = remote-first layouts (androidx.tv.material3),
 * Touch = phone/tablet layouts (material3). ViewModels are shared.
 */
enum class FormFactor { Tv, Touch }

fun detectFormFactor(context: Context): FormFactor {
    val uiMode = context.getSystemService(UiModeManager::class.java)?.currentModeType
    val pm = context.packageManager
    val isTv = uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
        pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    return if (isTv) FormFactor.Tv else FormFactor.Touch
}

val LocalFormFactor = staticCompositionLocalOf { FormFactor.Touch }
