package pl.photopreview.vm

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
        viewModelScope.launch {
            while (true) {
                session.sendBattery(readBatteryPercent())
                delay(30_000)
            }
        }
    }

    private fun readBatteryPercent(): Int {
        val bm = getApplication<Application>().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (p in 0..100) p else 0
    }

    private fun applyRemoteConfig(cfg: StreamConfig) {
        config.value = cfg
        session.targetFps = cfg.fps
    }

    fun setTimer(sec: Int) {
        config.value = config.value.copy(timerSeconds = sec)
    }

    fun setVideoMode(on: Boolean) {
        config.value = config.value.copy(videoMode = on)
    }

    fun setFrontCamera(front: Boolean) {
        config.value = config.value.copy(frontCamera = front)
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
