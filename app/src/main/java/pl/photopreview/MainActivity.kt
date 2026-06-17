package pl.photopreview

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import pl.photopreview.input.ShutterKeyBus
import pl.photopreview.ui.AppRoot
import pl.photopreview.ui.theme.PhotoPreviewTheme

class MainActivity : ComponentActivity() {

    private var lastShutterAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotoPreviewTheme {
                AppRoot()
            }
        }
    }

    /**
     * The gimbal's Bluetooth remote pairs as an HID keyboard and emits volume keys.
     * When the camera screen is active we consume those keys and fire the shutter,
     * with a short debounce, and suppress the on-screen volume UI.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val handler = ShutterKeyBus.onShutter
            if (handler != null) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastShutterAt > 350L) {
                        lastShutterAt = now
                        handler()
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
