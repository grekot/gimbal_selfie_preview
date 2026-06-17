package pl.photopreview.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Joins a specific Wi-Fi network (the camera phone's local-only hotspot) on demand and
 * exposes the resulting [Network] so the viewer can open sockets bound to it.
 * Requires Android 10 (API 29)+.
 */
class WifiJoiner(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    fun join(
        ssid: String,
        passphrase: String,
        onAvailable: (Network) -> Unit,
        onLost: () -> Unit,
        onUnavailable: () -> Unit,
    ) {
        leave()
        val cleanSsid = ssid.trim().removeSurrounding("\"")
        val builder = WifiNetworkSpecifier.Builder().setSsid(cleanSsid)
        if (passphrase.isNotEmpty()) builder.setWpa2Passphrase(passphrase)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(builder.build())
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailable(network)
            override fun onLost(network: Network) = onLost()
            override fun onUnavailable() = onUnavailable()
        }
        callback = cb
        cm.requestNetwork(request, cb)
    }

    /** Best-effort: the camera/AP address to connect to once joined. */
    fun serverHost(network: Network): String? {
        val lp = cm.getLinkProperties(network) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.dhcpServerAddress?.hostAddress?.let { return it }
        }
        lp.routes.forEach { route ->
            val gw = route.gateway
            if (gw != null && !gw.isAnyLocalAddress && !gw.isLoopbackAddress) {
                gw.hostAddress?.let { return it }
            }
        }
        return null
    }

    fun leave() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }
}
