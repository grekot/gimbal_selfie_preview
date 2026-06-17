package pl.photopreview.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    private var serverSocket: ServerSocket? = null
    private var connection: Connection? = null
    private var job: Job? = null

    /** Called from the camera analyzer thread for every captured frame. */
    fun submitFrame(jpeg: ByteArray, rotationDegrees: Int) {
        rotationCode = (rotationDegrees / 90) and 0xFF
        latestFrame.set(jpeg)
    }

    fun start(port: Int = Protocol.DEFAULT_PORT) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { serverLoop(port) }
    }

    fun stop() {
        job?.cancel()
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
                else -> { /* ignore */ }
            }
        }
    }
}
