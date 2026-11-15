package app.pureiptv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.pureiptv.core.data.source.SourceManager
import app.pureiptv.core.data.work.ImportKeepAliveWorker
import app.pureiptv.ui.FormFactor
import app.pureiptv.ui.PureApp
import app.pureiptv.ui.detectFormFactor
import app.pureiptv.ui.theme.PureTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val STRAY_KEYS_BEFORE_RESET = 5
private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var sources: SourceManager

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The import that triggered the request is already running: show its notification now.
        if (granted) lifecycleScope.launch { if (sources.busy.first()) ImportKeepAliveWorker.showNotification(this@MainActivity) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val formFactor = detectFormFactor(this)
        // The app is always dark, so the system bars get light icons whatever the system theme is
        // (the default follows the system and draws dark icons on a light-mode phone).
        if (formFactor == FormFactor.Touch) {
            enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        }
        setContent {
            PureTheme(formFactor) {
                PureApp()
            }
        }
        if (formFactor == FormFactor.Touch && Build.VERSION.SDK_INT >= 33) askForImportNotification()
    }

    /**
     * Phones and tablets (Android 13+) show an import's progress notification only with the
     * notification permission. Asked once, when the first import starts (adding or refreshing a
     * source), so the request comes with the thing it is for. TVs have no notification shade: not asked.
     */
    private fun askForImportNotification() {
        val prefs = getPreferences(MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sources.busy.first { it }
                if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return@repeatOnLifecycle
                prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    /**
     * Keyboards (and the emulator) send Escape, not Back. Compose would use Escape to drop focus,
     * which strands remote navigation, so it is turned into Back before anything else sees it.
     */
    // Activity.dispatchKeyEvent is public API; lint flags it because androidx.core's own override is restricted.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (BuildConfig.DEBUG) {
            // Remote/keyboard debugging: a held key is one "down" with repeat > 0, then one "up".
            val action = if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"
            android.util.Log.d(
                "PureKeys",
                "${KeyEvent.keyCodeToString(event.keyCode)} $action repeat=${event.repeatCount} device=${event.deviceId} flags=0x${Integer.toHexString(event.flags)}",
            )
        }
        if (isStrayKeyboardKey(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Keys sent by the on-screen keyboard (deviceId VIRTUAL_KEYBOARD or FLAG_SOFT_KEYBOARD) while no
     * keyboard is showing can't be typing. Google TV's keyboard sends a stray Backspace when an app
     * starts, and once got stuck repeating it ~20 times a second, which made every screen lag. Such
     * keys are dropped here, before any screen sees them; if they keep coming, the keyboard
     * connection is restarted, which ends a stuck key repeat.
     */
    private fun isStrayKeyboardKey(event: KeyEvent): Boolean {
        val fromSoftKeyboard = event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD ||
            (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD) != 0
        if (!fromSoftKeyboard || event.keyCode != KeyEvent.KEYCODE_DEL) return false
        val decor = window.decorView
        if (ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val now = SystemClock.uptimeMillis()
            if (now - strayWindowStart > 1_000) {
                strayWindowStart = now
                strayCount = 0
            }
            if (++strayCount >= STRAY_KEYS_BEFORE_RESET && now - lastKeyboardReset > 5_000) {
                lastKeyboardReset = now
                if (BuildConfig.DEBUG) android.util.Log.w("PureKeys", "stray keyboard keys: restarting the keyboard connection")
                getSystemService(InputMethodManager::class.java)?.let { imm ->
                    imm.hideSoftInputFromWindow(decor.windowToken, 0)
                    (currentFocus ?: decor).let(imm::restartInput)
                }
            }
        }
        return true
    }

    private var strayWindowStart = 0L
    private var strayCount = 0
    private var lastKeyboardReset = 0L

    /** Never leave the keyboard mid-press when the app goes away (that is how it once got stuck). */
    override fun onStop() {
        currentFocus?.let { focused ->
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focused.windowToken, 0)
        }
        super.onStop()
    }

    /**
     * Emulator only: Backspace = Back when nothing else used it (text fields consume it first), because
     * the emulator window doesn't forward Escape. Never on real devices: the Google TV keyboard service
     * sends a stray Backspace when an app starts, which closed the app straight away.
     */
    private val backspaceIsBack = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" ||
        Build.FINGERPRINT.contains("generic")
    private var backspaceDown = false

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (backspaceIsBack && keyCode == KeyEvent.KEYCODE_DEL) {
            // Only a press that started here: not a key still held (or injected) when the app opened.
            if (backspaceDown && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
            backspaceDown = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (backspaceIsBack && keyCode == KeyEvent.KEYCODE_DEL) {
            if (event.repeatCount == 0) backspaceDown = true
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
