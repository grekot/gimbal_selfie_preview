package pl.photopreview

import android.graphics.Bitmap
import org.json.JSONObject

/** Connection lifecycle state, shared by camera (server) and viewer (client). */
sealed interface SessionStatus {
    data object Idle : SessionStatus
    data class Listening(val port: Int) : SessionStatus
    data class Connecting(val target: String) : SessionStatus
    data class Connected(val peer: String) : SessionStatus
    data class Error(val message: String) : SessionStatus
}

/** Live-preview stream parameters, controllable from the viewer. */
data class StreamConfig(
    val analysisHeight: Int = 720, // 480 or 720
    val fps: Int = 15,
    val jpegQuality: Int = 60,
    val timerSeconds: Int = 0, // self-timer: 0 / 3 / 10
    val saveToViewer: Boolean = true, // also save a full-res copy on the viewer phone
) {
    fun toJson(): ByteArray =
        JSONObject()
            .put("h", analysisHeight)
            .put("fps", fps)
            .put("q", jpegQuality)
            .put("t", timerSeconds)
            .put("sv", saveToViewer)
            .toString()
            .toByteArray()

    companion object {
        fun fromJson(bytes: ByteArray): StreamConfig {
            val o = JSONObject(String(bytes))
            return StreamConfig(
                analysisHeight = o.optInt("h", 720),
                fps = o.optInt("fps", 15),
                jpegQuality = o.optInt("q", 60),
                timerSeconds = o.optInt("t", 0),
                saveToViewer = o.optBoolean("sv", true),
            )
        }
    }
}

/** A decoded preview frame ready to draw on the viewer. */
data class DisplayFrame(
    val bitmap: Bitmap,
    val rotationDegrees: Int,
    val arrivalNanos: Long,
)
