package pl.photopreview.input

/**
 * Bridges the hardware volume-key events from the gimbal's Bluetooth remote
 * (intercepted in MainActivity) to the active camera screen.
 */
object ShutterKeyBus {
    @Volatile
    var onShutter: (() -> Unit)? = null
}
