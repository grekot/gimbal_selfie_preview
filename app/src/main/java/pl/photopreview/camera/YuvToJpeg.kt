package pl.photopreview.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Converts a CameraX YUV_420_888 [ImageProxy] to JPEG bytes.
 *
 * The frame is NOT rotated here (rotation is cheap to apply on the viewer when drawing),
 * which avoids an extra decode/encode round-trip on the camera phone.
 */
object YuvToJpeg {

    fun convert(image: ImageProxy, quality: Int): ByteArray {
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream(image.width * image.height / 4)
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    /** Handles arbitrary row/pixel strides; output is NV21 (Y plane followed by interleaved V/U). */
    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val nv21 = ByteArray(ySize + 2 * chromaWidth * chromaHeight)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // --- Y plane ---
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var pos = 0
        if (yRowStride == width && yPixelStride == 1) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                val rowStart = row * yRowStride
                var col = 0
                while (col < width) {
                    nv21[pos++] = yBuffer.get(rowStart + col * yPixelStride)
                    col++
                }
            }
        }

        // --- Chroma (write as V, U interleaved => NV21) ---
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until chromaWidth) {
                nv21[pos++] = vBuffer.get(vRowStart + col * vPixelStride)
                nv21[pos++] = uBuffer.get(uRowStart + col * uPixelStride)
            }
        }
        return nv21
    }
}
