package pl.photopreview.camera

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Largest detected face, centre + size normalized to the upright (display) image, range 0..1. */
data class FaceBox(val cx: Float, val cy: Float, val w: Float, val h: Float)

/**
 * Wraps ML Kit on-device face detection. Feed NV21 frames via [submit]; the largest face (or null)
 * is reported through [onResult]. Frames arriving while a detection is in flight are dropped.
 */
class FaceTracker {

    @Volatile var onResult: ((FaceBox?) -> Unit)? = null

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.06f)
            .build(),
    )
    private val busy = AtomicBoolean(false)

    fun submit(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int) {
        if (!busy.compareAndSet(false, true)) return
        val input = try {
            // ML Kit is more reliable with a direct buffer than ByteBuffer.wrap (heap) here.
            val buf = ByteBuffer.allocateDirect(nv21.size)
            buf.put(nv21)
            buf.rewind()
            InputImage.fromByteBuffer(buf, width, height, rotationDegrees, InputImage.IMAGE_FORMAT_NV21)
        } catch (e: Exception) {
            busy.set(false)
            return
        }
        // ML Kit returns boxes in the upright (rotation-applied) image space.
        val uprightW = if (rotationDegrees % 180 == 0) width else height
        val uprightH = if (rotationDegrees % 180 == 0) height else width
        detector.process(input)
            .addOnSuccessListener { faces ->
                val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (f == null || uprightW == 0 || uprightH == 0) {
                    onResult?.invoke(null)
                } else {
                    val b = f.boundingBox
                    onResult?.invoke(
                        FaceBox(
                            cx = (b.exactCenterX() / uprightW).coerceIn(0f, 1f),
                            cy = (b.exactCenterY() / uprightH).coerceIn(0f, 1f),
                            w = (b.width().toFloat() / uprightW).coerceIn(0f, 1f),
                            h = (b.height().toFloat() / uprightH).coerceIn(0f, 1f),
                        ),
                    )
                }
            }
            .addOnFailureListener { onResult?.invoke(null) }
            .addOnCompleteListener { busy.set(false) }
    }

    fun close() {
        runCatching { detector.close() }
    }
}
