package pl.photopreview.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import pl.photopreview.SessionStatus
import pl.photopreview.StreamConfig
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Camera-side (server) session. Listens on a TCP port, accepts one viewer at a time,
 * pushes the latest JPEG preview frame at the target FPS, and reacts to commands
 * (SHUTTER / CONFIG) coming back from the viewer.
 */
class CameraSessionManager(private val scope: CoroutineScope) {

    val status = MutableStateFlow<SessionStatus>(SessionStatus.Idle)

    @Volatile var targetFps: Int = 15

    private val latestFrame = AtomicReference<ByteArray?>(null)
    @Volatile private var rotationCode: Int = 0

    @Volatile var onShutter: (() -> Unit)? = null
    @Volatile var onConfig: ((StreamConfig) -> Unit)? = null
    @Volatile var onZoom: ((Float) -> Unit)? = null
    @Volatile var onExposure: ((Float) -> Unit)? = null
    @Volatile var onTorch: ((Boolean) -> Unit)? = null
    @Volatile var onFocus: ((Float, Float) -> Unit)? = null
    @Volatile var onFocusReset: (() -> Unit)? = null
    @Volatile var onGimbal: ((Int, Int, Int) -> Unit)? = null
    @Volatile var onGimbalConnect: (() -> Unit)? = null
    @Volatile var onGimbalRelease: (() -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    private var connection: Connection? = null
    private var job: Job? = null
    private var videoJob: Job? = null

    private class VideoMsg(val type: MsgType, val payload: ByteArray)
    private val videoChannel = Channel<VideoMsg>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var videoConfigPayload: ByteArray? = null
    @Volatile private var zoomRangePayload: ByteArray? = null
    @Volatile private var recStatePayload: ByteArray? = null
    @Volatile private var batteryPayload: ByteArray? = null

    /** Called from the camera analyzer thread for every captured frame. */
    fun submitFrame(jpeg: ByteArray, rotationDegrees: Int) {
        rotationCode = (rotationDegrees / 90) and 0xFF
        latestFrame.set(jpeg)
    }

    fun start(port: Int = Protocol.DEFAULT_PORT) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { serverLoop(port) }
        videoJob = scope.launch(Dispatchers.IO) { videoConsumer() }
    }

    fun stop() {
        job?.cancel()
        videoJob?.cancel()
        runCatching { serverSocket?.close() }
        runCatching { connection?.close() }
        serverSocket = null
        connection = null
        status.value = SessionStatus.Idle
    }

    fun sendPhotoTaken(thumbnailJpeg: ByteArray) {
        val c = connection ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { Protocol.write(c.output, MsgType.PHOTO_TAKEN, thumbnailJpeg) }
        }
    }

    fun sendPhotoFull(jpeg: ByteArray) {
        val c = connection ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { Protocol.write(c.output, MsgType.PHOTO_FULL, jpeg) }
        }
    }

    fun sendCountdown(secondsRemaining: Int) {
        val c = connection ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                Protocol.write(c.output, MsgType.COUNTDOWN, secondsRemaining.toString().toByteArray())
            }
        }
    }

    /** H.264: video config (SPS/PPS) — cached and (re)sent to each viewer that connects. */
    fun setVideoConfig(payload: ByteArray) {
        videoConfigPayload = payload
        videoChannel.trySend(VideoMsg(MsgType.VIDEO_CONFIG, payload))
    }

    /** H.264: one encoded access unit, sent in order. */
    fun submitVideo(nal: ByteArray, keyframe: Boolean) {
        val payload = ByteArray(nal.size + 1)
        payload[0] = if (keyframe) 1 else 0
        System.arraycopy(nal, 0, payload, 1, nal.size)
        videoChannel.trySend(VideoMsg(MsgType.VIDEO_FRAME, payload))
    }

    private suspend fun videoConsumer() {
        for (msg in videoChannel) {
            val c = connection ?: continue
            runCatching { Protocol.write(c.output, msg.type, msg.payload) }
        }
    }

    /** Camera's supported zoom ratio range — cached and (re)sent to each viewer. */
    fun sendZoomRange(min: Float, max: Float) {
        val payload = "$min;$max".toByteArray()
        zoomRangePayload = payload
        val c = connection ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { Protocol.write(c.output, MsgType.ZOOM_RANGE, payload) }
        }
    }

    /** Recording on/off — cached and (re)sent so the viewer can show a REC indicator. */
    fun sendRecState(recording: Boolean) {
        val payload = (if (recording) "1" else "0").toByteArray()
        recStatePayload = payload
        val c = connection ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { Protocol.write(c.output, MsgType.REC_STATE, payload) }
        }
    }

    /** Camera battery percent — cached and (re)sent so the viewer can show it. */
    fun sendBattery(percent: Int) {
        val payload = percent.toString().toByteArray()
        batteryPayload = payload
        val c = connection ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { Protocol.write(c.output, MsgType.BATTERY, payload) }
        }
    }

    private suspend fun serverLoop(port: Int) {
        try {
            val ss = ServerSocket(port)
            serverSocket = ss
            status.value = SessionStatus.Listening(port)
            while (coroutineContext.isActive) {
                val socket = runCatching { ss.accept() }.getOrNull() ?: break
                val c = Connection(socket)
                connection = c
                status.value = SessionStatus.Connected(c.peer)
                videoConfigPayload?.let { videoChannel.trySend(VideoMsg(MsgType.VIDEO_CONFIG, it)) }
                zoomRangePayload?.let { p ->
                    scope.launch(Dispatchers.IO) { runCatching { Protocol.write(c.output, MsgType.ZOOM_RANGE, p) } }
                }
                recStatePayload?.let { p ->
                    scope.launch(Dispatchers.IO) { runCatching { Protocol.write(c.output, MsgType.REC_STATE, p) } }
                }
                batteryPayload?.let { p ->
                    scope.launch(Dispatchers.IO) { runCatching { Protocol.write(c.output, MsgType.BATTERY, p) } }
                }
                try {
                    coroutineScope {
                        launch { senderLoop(c) }
                        readerLoop(c) // blocks until the peer disconnects / errors
                    }
                } catch (_: Exception) {
                    // peer disconnected; fall through to listen again
                } finally {
                    runCatching { c.close() }
                    connection = null
                    if (coroutineContext.isActive) status.value = SessionStatus.Listening(port)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status.value = SessionStatus.Error(e.message ?: "Błąd serwera")
        }
    }

    private suspend fun senderLoop(c: Connection) {
        while (coroutineContext.isActive) {
            val frame = latestFrame.getAndSet(null)
            if (frame != null) {
                val payload = ByteArray(frame.size + 1)
                payload[0] = rotationCode.toByte()
                System.arraycopy(frame, 0, payload, 1, frame.size)
                Protocol.write(c.output, MsgType.FRAME, payload)
            }
            val fps = targetFps.coerceIn(1, 60)
            delay(1000L / fps)
        }
    }

    private fun readerLoop(c: Connection) {
        while (true) {
            val msg = Protocol.read(c.input) // blocking
            when (msg.type) {
                MsgType.SHUTTER -> onShutter?.invoke()
                MsgType.CONFIG -> runCatching { StreamConfig.fromJson(msg.payload) }
                    .getOrNull()?.let { onConfig?.invoke(it) }
                MsgType.ZOOM -> String(msg.payload).trim().toFloatOrNull()?.let { onZoom?.invoke(it) }
                MsgType.EXPOSURE -> String(msg.payload).trim().toFloatOrNull()?.let { onExposure?.invoke(it) }
                MsgType.TORCH -> onTorch?.invoke(String(msg.payload).trim() == "1")
                MsgType.FOCUS -> {
                    val parts = String(msg.payload).split(";")
                    if (parts.size == 2) {
                        val x = parts[0].toFloatOrNull()
                        val y = parts[1].toFloatOrNull()
                        if (x != null && y != null) onFocus?.invoke(x, y)
                    }
                }
                MsgType.FOCUS_RESET -> onFocusReset?.invoke()
                MsgType.GIMBAL -> {
                    when (val s = String(msg.payload)) {
                        "C" -> onGimbalConnect?.invoke()
                        "R" -> onGimbalRelease?.invoke()
                        else -> {
                            val parts = s.split(";")
                            if (parts.size >= 2) {
                                val pan = parts[0].toIntOrNull()
                                val tilt = parts[1].toIntOrNull()
                                val roll = parts.getOrNull(2)?.toIntOrNull() ?: 0
                                if (pan != null && tilt != null) onGimbal?.invoke(pan, tilt, roll)
                            }
                        }
                    }
                }
                else -> { /* ignore */ }
            }
        }
    }
}
