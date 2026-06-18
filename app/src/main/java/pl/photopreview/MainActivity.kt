package pl.photopreview

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import pl.photopreview.input.ShutterKeyBus
import pl.photopreview.ui.AppRoot
import pl.photopreview.ui.theme.PhotoPreviewTheme

class MainActivity : ComponentActivity() {

    private var lastShutterAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoPreviewTheme {
                AppRoot()
            }
        }
    }

    /**
     * The gimbal's Bluetooth remote pairs as an HID device. Shutter usually arrives as volume keys;
     * the zoom rocker may send zoom keys. We consume the ones we handle and report every key code
     * via [ShutterKeyBus.onKeyDebug] so an unknown remote button can be identified.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down) ShutterKeyBus.onKeyDebug?.invoke(keyCode)

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val handler = ShutterKeyBus.onShutter
            if (handler != null) {
                if (down) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastShutterAt > 350L) {
                        lastShutterAt = now
                        handler()
                    }
                }
                return true
            }
        }

        if (keyCode == KeyEvent.KEYCODE_ZOOM_IN && ShutterKeyBus.onZoomIn != null) {
            if (down) ShutterKeyBus.onZoomIn?.invoke()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_ZOOM_OUT && ShutterKeyBus.onZoomOut != null) {
            if (down) ShutterKeyBus.onZoomOut?.invoke()
            return true
        }

        return super.dispatchKeyEvent(event)
    }
}
