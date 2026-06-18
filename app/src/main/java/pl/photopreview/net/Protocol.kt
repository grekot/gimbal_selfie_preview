package pl.photopreview.net

import java.io.DataInputStream
import java.io.DataOutputStream

/** Message types exchanged over the single TCP connection. */
enum class MsgType(val code: Int) {
    /** Camera -> Viewer. Payload = [1 byte rotation/90][JPEG bytes]. */
    FRAME(1),

    /** Viewer -> Camera. No payload. Requests a full-resolution capture. */
    SHUTTER(2),

    /** Camera -> Viewer. Payload = small JPEG thumbnail of the saved photo. */
    PHOTO_TAKEN(3),

    /** Viewer -> Camera. Payload = JSON StreamConfig (resolution / fps / quality / timer). */
    CONFIG(4),

    /** Viewer -> Camera. Payload = ASCII float zoom ratio (e.g. "2.0"). */
    ZOOM(5),

    /** Viewer -> Camera. Payload = ASCII float -1..1 (normalized exposure compensation). */
    EXPOSURE(6),

    /** Viewer -> Camera. Payload = "1"/"0" (torch on/off). */
    TORCH(7),

    /** Camera -> Viewer. Payload = ASCII int (self-timer seconds remaining; 0 = capturing now). */
    COUNTDOWN(8),

    /** Camera -> Viewer. Payload = full-resolution JPEG bytes, to be saved on the viewer phone. */
    PHOTO_FULL(9),

    /** Camera -> Viewer. H.264 codec config: [4B width][4B height][4B rotation][SPS/PPS bytes]. */
    VIDEO_CONFIG(10),

    /** Camera -> Viewer. H.264 access unit: [1B keyframe flag][Annex-B NAL bytes]. */
    VIDEO_FRAME(11),

    /** Camera -> Viewer. Supported zoom ratio range as "min;max" (e.g. "1.0;10.0"). */
    ZOOM_RANGE(12),

    /** Camera -> Viewer. Recording state: "1" (recording) / "0" (stopped). */
    REC_STATE(13);

    companion object {
        fun from(code: Int): MsgType? = entries.firstOrNull { it.code == code }
    }
}

class Message(val type: MsgType, val payload: ByteArray)

/**
 * Tiny length-prefixed binary framing: [1 byte type][4 byte big-endian length][payload].
 * The transport is plain TCP, so the same code works over shared Wi-Fi, a hotspot,
 * or a Wi-Fi Direct link.
 */
object Protocol {
    const val DEFAULT_PORT = 8765
    const val SERVICE_TYPE = "_photopreview._tcp."
    const val SERVICE_NAME = "PhotoPreview"

    private const val MAX_PAYLOAD = 50 * 1024 * 1024 // 50 MB safety bound

    fun write(out: DataOutputStream, type: MsgType, payload: ByteArray) {
        synchronized(out) {
            out.writeByte(type.code)
            out.writeInt(payload.size)
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        }
    }

    fun read(input: DataInputStream): Message {
        val code = input.readUnsignedByte()
        val len = input.readInt()
        check(len in 0..MAX_PAYLOAD) { "Bad frame length: $len" }
        val payload = ByteArray(len)
        input.readFully(payload)
        val type = MsgType.from(code) ?: throw IllegalStateException("Unknown message type: $code")
        return Message(type, payload)
    }
}
