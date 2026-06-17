package pl.photopreview.vm

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import pl.photopreview.MediaSaver
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
    val countdown = session.countdown
    val videoConfig = session.videoConfig
    val discovered = MutableStateFlow<Pair<String, Int>?>(null)
    val config = MutableStateFlow(StreamConfig())
    val photoThumb = MutableStateFlow<Bitmap?>(null)
    val photoSaved = MutableStateFlow(false)
    val saveMsg = MutableStateFlow<String?>(null)
    val qrError = MutableStateFlow<String?>(null)

    // Live shooting controls (sent to the camera phone).
    val zoom = MutableStateFlow(0f)
    val exposure = MutableStateFlow(0f)
    val torch = MutableStateFlow(false)
    val grid = MutableStateFlow(false) // local overlay only

    init {
        viewModelScope.launch {
            session.photoTaken.collect { bytes ->
                photoSaved.value = false
                photoThumb.value =
                    if (bytes.isNotEmpty()) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
            }
        }
        viewModelScope.launch {
            session.photoFull.collect { bytes ->
                val uri = MediaSaver.saveJpeg(getApplication<Application>(), bytes)
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                photoThumb.value = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                photoSaved.value = uri != null
                saveMsg.value = if (uri != null) "Zapisano w galerii ✓" else "Zapis nieudany (uprawnienie?)"
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

    fun setVideoSink(sink: ((ByteArray, Boolean) -> Unit)?) {
        session.onVideoFrame = sink
    }

    fun setZoom(v: Float) {
        zoom.value = v.coerceIn(0f, 1f)
        session.sendZoom(zoom.value)
    }

    fun setExposure(v: Float) {
        exposure.value = v.coerceIn(-1f, 1f)
        session.sendExposure(exposure.value)
    }

    fun toggleTorch() {
        torch.value = !torch.value
        session.sendTorch(torch.value)
    }

    fun setTimer(sec: Int) = updateConfig(config.value.copy(timerSeconds = sec))

    fun toggleGrid() {
        grid.value = !grid.value
    }

    fun updateConfig(cfg: StreamConfig) {
        config.value = cfg
        session.sendConfig(cfg)
    }

    fun clearPhotoThumb() {
        photoThumb.value = null
        saveMsg.value = null
    }

    override fun onCleared() {
        session.disconnect()
        nsd.stopDiscovery()
        wifiJoiner.leave()
    }
}
