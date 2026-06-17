package pl.photopreview.net

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** Non-loopback IPv4 addresses of this device (for manual "type the IP" pairing). */
    fun localIpv4Addresses(): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                ni.inetAddresses.toList().forEach { addr ->
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { out.add(it) }
                    }
                }
            }
        }
        return out.distinct()
    }
}
