package pl.photopreview.net

import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import pl.photopreview.DisplayFrame
import pl.photopreview.SessionStatus
import pl.photopreview.StreamConfig
import java.net.InetSocketAddress
import javax.net.SocketFactory

/**
 * Viewer-side (client) session. Connects to the camera phone, decodes incoming
 * JPEG frames into bitmaps, and sends SHUTTER / CONFIG commands back.
 * Reconnects automatically while [connect] is active.
 */
class ViewerSessionManager(private val scope: CoroutineScope) {

    val status = MutableStateFlow<SessionStatus>(SessionStatus.Idle)
    val frame = MutableStateFlow<DisplayFrame?>(null)
    val photoTaken = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    val photoFull = MutableSharedFlow<ByteArray>(extraBufferCapacity = 2)
    val countdown = MutableStateFlow<Int?>(null)

    private var connection: Connection? = null
    private var job: Job? = null
    @Volatile private var autoReconnect = false

    fun connect(host: String, port: Int = Protocol.DEFAULT_PORT, socketFactory: SocketFactory? = null) {
        disconnect()
        autoReconnect = true
        job = scope.launch(Dispatchers.IO) {
            while (isActive && autoReconnect) {
                runConnection(host, port, socketFactory)
                if (!autoReconnect || !isActive) break
                status.value = SessionStatus.Connecting("$host (ponawiam…)")
                delay(2000)
            }
        }
    }

    fun disconnect() {
        autoReconnect = false
        job?.cancel()
        runCatching { connection?.close() }
        connection = null
        status.value = SessionStatus.Idle
    }

    fun sendShutter() = send(MsgType.SHUTTER, ByteArray(0))
    fun sendConfig(cfg: StreamConfig) = send(MsgType.CONFIG, cfg.toJson())
    fun sendZoom(linear: Float) = send(MsgType.ZOOM, linear.coerceIn(0f, 1f).toString().toByteArray())
    fun sendExposure(fraction: Float) = send(MsgType.EXPOSURE, fraction.coerceIn(-1f, 1f).toString().toByteArray())
    fun sendTorch(on: Boolean) = send(MsgType.TORCH, (if (on) "1" else "0").toByteArray())

    private fun send(type: MsgType, payload: ByteArray) {
        val c = connection ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { Protocol.write(c.output, type, payload) }
        }
    }

    private fun runConnection(host: String, port: Int, socketFactory: SocketFactory?) {
        try {
            status.value = SessionStatus.Connecting(host)
            val socket = (socketFactory ?: SocketFactory.getDefault()).createSocket()
            socket.connect(InetSocketAddress(host, port), 8000)
            val c = Connection(socket)
            connection = c
            status.value = SessionStatus.Connected(host)
            while (true) {
                val msg = Protocol.read(c.input) // blocking
                when (msg.type) {
                    MsgType.FRAME -> decodeFrame(msg.payload)
                    MsgType.PHOTO_TAKEN -> { countdown.value = null; photoTaken.tryEmit(msg.payload) }
                    MsgType.PHOTO_FULL -> { countdown.value = null; photoFull.tryEmit(msg.payload) }
                    MsgType.COUNTDOWN -> {
                        val n = String(msg.payload).trim().toIntOrNull() ?: 0
                        countdown.value = if (n > 0) n else null
                    }
                    else -> { /* ignore */ }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status.value = SessionStatus.Error(e.message ?: "Rozłączono")
        } finally {
            runCatching { connection?.close() }
            connection = null
        }
    }

    private fun decodeFrame(payload: ByteArray) {
        if (payload.isEmpty()) return
        val rotation = (payload[0].toInt() and 0xFF) * 90
        val bmp = BitmapFactory.decodeByteArray(payload, 1, payload.size - 1) ?: return
        frame.value = DisplayFrame(bmp, rotation, System.nanoTime())
    }
}
