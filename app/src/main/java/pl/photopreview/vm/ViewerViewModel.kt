package pl.photopreview.vm

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import pl.photopreview.StreamConfig
import pl.photopreview.net.JoinPayload
import pl.photopreview.net.NsdHelper
import pl.photopreview.net.Protocol
import pl.photopreview.net.ViewerSessionManager
import pl.photopreview.wifi.WifiJoiner

class ViewerViewModel(app: Application) : AndroidViewModel(app) {

    val session = ViewerSessionManager(viewModelScope)
    private val nsd = NsdHelper(app)
    private val wifiJoiner = WifiJoiner(app)

    val status = session.status
    val frame = session.frame
    val discovered = MutableStateFlow<Pair<String, Int>?>(null)
    val config = MutableStateFlow(StreamConfig())
    val photoThumb = MutableStateFlow<Bitmap?>(null)
    val qrError = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            session.photoTaken.collect { bytes ->
                photoThumb.value =
                    if (bytes.isNotEmpty()) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
            }
        }
    }

    fun connectManual(host: String) {
        session.connect(host.trim(), Protocol.DEFAULT_PORT)
    }

    fun startDiscovery() {
        nsd.discover { host, port -> discovered.value = host to port }
    }

    fun connectDiscovered() {
        discovered.value?.let { (host, port) -> session.connect(host, port) }
    }

    fun connectViaQr(payload: String) {
        val parsed = JoinPayload.parse(payload)
        if (parsed == null) {
            qrError.value = "Nieprawidłowy kod QR"
            return
        }
        if (!wifiJoiner.supported || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            qrError.value =
                "Połącz się ręcznie z siecią \"${parsed.ssid}\", a potem użyj wyszukiwania w sieci."
            return
        }
        qrError.value = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiJoiner.join(
                ssid = parsed.ssid,
                passphrase = parsed.pass,
                onAvailable = { network ->
                    val host = wifiJoiner.serverHost(network) ?: "192.168.49.1"
                    session.connect(host, parsed.port, network.socketFactory)
                },
                onLost = { },
                onUnavailable = { qrError.value = "Nie udało się połączyć z hotspotem" },
            )
        }
    }

    fun sendShutter() = session.sendShutter()

    fun updateConfig(cfg: StreamConfig) {
        config.value = cfg
        session.sendConfig(cfg)
    }

    fun clearPhotoThumb() {
        photoThumb.value = null
    }

    override fun onCleared() {
        session.disconnect()
        nsd.stopDiscovery()
        wifiJoiner.leave()
    }
}
