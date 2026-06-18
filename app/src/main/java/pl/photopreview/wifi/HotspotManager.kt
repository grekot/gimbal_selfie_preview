package pl.photopreview.wifi

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresPermission

class HotspotInfo(val ssid: String, val passphrase: String)

/**
 * Local-Only Hotspot on the camera phone. The OS always assigns a random SSID/passphrase
 * (a custom config is a system-app-only API), so we can't shorten them — but [start] is
 * idempotent: while a hotspot is running it reuses the same network instead of creating a
 * new one, and it stays up until [stop] is called.
 */
class HotspotManager(context: Context) {

    private val wifi =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var cachedInfo: HotspotInfo? = null

    @RequiresPermission(
        anyOf = [Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION],
    )
    fun start(onReady: (HotspotInfo) -> Unit, onFailed: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onFailed("Hotspot wymaga Androida 8.0+ (ten telefon użyj jako Podgląd)")
            return
        }
        // Idempotent: reuse the running hotspot rather than creating a new (differently named) one.
        val existing = cachedInfo
        if (reservation != null && existing != null) {
            onReady(existing)
            return
        }
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val info = extract(res)
                    if (info != null) {
                        cachedInfo = info
                        onReady(info)
                    } else {
                        onFailed("Nie udało się odczytać danych hotspotu")
                    }
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
        cachedInfo = null
    }
}
