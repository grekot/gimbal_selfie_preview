package pl.photopreview.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps CameraX. Binds Preview + ImageAnalysis (-> JPEG frames for streaming) +
 * ImageCapture (full-resolution stills) on the back camera.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile var jpegQuality: Int = 60

    /** Invoked on a background thread for each analyzed frame: (jpegBytes, rotationDegrees). */
    @Volatile var onFrame: ((ByteArray, Int) -> Unit)? = null

    suspend fun bind(previewView: PreviewView, analysisHeight: Int) {
        val provider = awaitProvider()
        cameraProvider = provider
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val targetSize = Size(analysisHeight * 16 / 9, analysisHeight)
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::analyze) }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        imageCapture = capture

        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview, analysis, capture,
        )
    }

    private fun analyze(image: ImageProxy) {
        try {
            onFrame?.let { cb ->
                val jpeg = YuvToJpeg.convert(image, jpegQuality)
                cb(jpeg, image.imageInfo.rotationDegrees)
            }
        } catch (_: Throwable) {
            // drop the bad frame and continue
        } finally {
            image.close()
        }
    }

    fun takePhoto(onResult: (Uri?) -> Unit) {
        val capture = imageCapture ?: run { onResult(null); return }
        val name = "PhotoPreview_" + System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoPreview")
            }
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = onResult(output.savedUri)
                override fun onError(exc: ImageCaptureException) = onResult(null)
            },
        )
    }

    /** Small JPEG thumbnail of a saved photo, to confirm the shot on the viewer. */
    fun thumbnailFor(uri: Uri): ByteArray? = runCatching {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        context.contentResolver.openInputStream(uri)?.use { ins ->
            val bmp = BitmapFactory.decodeStream(ins, null, opts) ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
            out.toByteArray()
        }
    }.getOrNull()

    fun unbind() {
        runCatching { cameraProvider?.unbindAll() }
        onFrame = null
        analysisExecutor.shutdown()
    }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
}
