package pl.photopreview.wifi

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresPermission

class HotspotInfo(val ssid: String, val passphrase: String)

/**
 * Starts a Local-Only Hotspot on the camera phone so the viewer can join directly,
 * with no router. The randomly generated SSID/passphrase are read back for the QR code.
 */
class HotspotManager(context: Context) {

    private val wifi =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @RequiresPermission(
        anyOf = [Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION]
    )
    fun start(onReady: (HotspotInfo) -> Unit, onFailed: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onFailed("Hotspot wymaga Androida 8.0+ (ten telefon użyj jako Podgląd)")
            return
        }
        stop()
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val info = extract(res)
                    if (info != null) onReady(info) else onFailed("Nie udało się odczytać danych hotspotu")
                }

                override fun onFailed(reason: Int) {
                    onFailed("Hotspot nie wystartował (kod $reason)")
                }
            }, null)
        } catch (e: Exception) {
            onFailed(e.message ?: "Błąd hotspotu")
        }
    }

    @Suppress("DEPRECATION")
    private fun extract(res: WifiManager.LocalOnlyHotspotReservation): HotspotInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cfg = res.softApConfiguration
            val ssid = cfg.ssid ?: return null
            HotspotInfo(ssid.removeSurrounding("\""), cfg.passphrase ?: "")
        } else {
            val cfg = res.wifiConfiguration ?: return null
            HotspotInfo((cfg.SSID ?: "").removeSurrounding("\""), cfg.preSharedKey ?: "")
        }
    }

    fun stop() {
        reservation?.let { runCatching { it.close() } }
        reservation = null
    }
}
