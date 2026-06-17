package pl.photopreview.net

import android.net.Uri

/** Encodes/decodes the QR pairing payload: Wi-Fi SSID + passphrase + TCP port. */
object JoinPayload {
    data class Parsed(val ssid: String, val pass: String, val port: Int)

    fun build(ssid: String, pass: String, port: Int): String {
        val s = Uri.encode(ssid)
        val p = Uri.encode(pass)
        return "photopreview://join?ssid=$s&pass=$p&port=$port"
    }

    fun parse(text: String): Parsed? {
        val u = runCatching { Uri.parse(text) }.getOrNull() ?: return null
        if (u.scheme != "photopreview") return null
        val ssid = u.getQueryParameter("ssid") ?: return null
        val pass = u.getQueryParameter("pass") ?: ""
        val port = u.getQueryParameter("port")?.toIntOrNull() ?: Protocol.DEFAULT_PORT
        return Parsed(ssid, pass, port)
    }
}
