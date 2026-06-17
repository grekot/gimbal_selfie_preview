package pl.photopreview.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import pl.photopreview.StreamConfig
import pl.photopreview.net.CameraSessionManager
import pl.photopreview.net.NetUtils
import pl.photopreview.net.NsdHelper
import pl.photopreview.net.Protocol
import pl.photopreview.wifi.HotspotInfo
import pl.photopreview.wifi.HotspotManager

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    val session = CameraSessionManager(viewModelScope)
    private val nsd = NsdHelper(app)
    private val hotspot = HotspotManager(app)

    val status = session.status
    val config = MutableStateFlow(StreamConfig())
    val hotspotInfo = MutableStateFlow<HotspotInfo?>(null)
    val hotspotError = MutableStateFlow<String?>(null)

    val localAddresses: List<String> get() = NetUtils.localIpv4Addresses()
    val port: Int get() = Protocol.DEFAULT_PORT

    init {
        session.onConfig = { applyRemoteConfig(it) }
        session.targetFps = config.value.fps
        session.start(Protocol.DEFAULT_PORT)
        nsd.register(Protocol.DEFAULT_PORT)
    }

    private fun applyRemoteConfig(cfg: StreamConfig) {
        config.value = cfg
        session.targetFps = cfg.fps
    }

    fun startHotspot() {
        hotspotError.value = null
        hotspot.start(
            onReady = { hotspotInfo.value = it },
            onFailed = { hotspotError.value = it },
        )
    }

    fun stopHotspot() {
        hotspot.stop()
        hotspotInfo.value = null
    }

    override fun onCleared() {
        session.stop()
        nsd.unregister()
        hotspot.stop()
    }
}
