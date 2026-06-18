package pl.photopreview.input

/**
 * Bridges hardware key events from the gimbal's Bluetooth remote (intercepted in MainActivity)
 * to the active camera screen: shutter (volume keys), zoom (zoom keys), and a debug hook that
 * reports any key code so an unknown remote button can be identified and mapped.
 */
object ShutterKeyBus {
    @Volatile
    var onShutter: (() -> Unit)? = null

    @Volatile
    var onZoomIn: (() -> Unit)? = null

    @Volatile
    var onZoomOut: (() -> Unit)? = null

    /** Reports every key-down code (for discovering what an unknown remote button sends). */
    @Volatile
    var onKeyDebug: ((Int) -> Unit)? = null
}
