package pl.photopreview.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.Camera
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
import pl.photopreview.VideoConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * Wraps CameraX. Binds Preview + ImageAnalysis (-> JPEG frames for streaming) +
 * ImageCapture (full-resolution stills) on the back camera.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile var jpegQuality: Int = 60
    @Volatile var useH264: Boolean = true
    @Volatile var frameRate: Int = 15

    /** Invoked on a background thread for each analyzed frame: (jpegBytes, rotationDegrees). */
    @Volatile var onFrame: ((ByteArray, Int) -> Unit)? = null
    @Volatile var onVideoConfig: ((VideoConfig) -> Unit)? = null
    @Volatile var onVideoFrame: ((ByteArray, Boolean) -> Unit)? = null

    // Live camera controls; remembered so they survive a re-bind (e.g. resolution change).
    @Volatile private var linearZoom: Float = 0f
    @Volatile private var evFraction: Float = 0f
    @Volatile private var torchOn: Boolean = false
    private var encoder: H264Encoder? = null
    @Volatile private var lastRotation: Int = 0
    private var encoderWidth = 0
    private var encoderHeight = 0

    suspend fun bind(previewView: PreviewView, analysisHeight: Int) {
        val provider = awaitProvider()
        cameraProvider = provider
        provider.unbindAll()
        resetEncoder()

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

        camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview, analysis, capture,
        )
        applyControls()
    }

    private fun analyze(image: ImageProxy) {
        try {
            lastRotation = image.imageInfo.rotationDegrees
            if (useH264) {
                ensureEncoder(image.width, image.height)?.encode(image)
            } else {
                onFrame?.let { cb ->
                    val jpeg = YuvToJpeg.convert(image, jpegQuality)
                    cb(jpeg, image.imageInfo.rotationDegrees)
                }
            }
        } catch (_: Throwable) {
            // drop the bad frame and continue
        } finally {
            image.close()
        }
    }

    private fun ensureEncoder(w: Int, h: Int): H264Encoder? {
        val existing = encoder
        if (existing != null && encoderWidth == w && encoderHeight == h) return existing
        existing?.release()
        encoderWidth = w
        encoderHeight = h
        val bitRate = if (h <= 480) 1_500_000 else 3_000_000
        encoder = runCatching {
            H264Encoder(
                width = w,
                height = h,
                bitRate = bitRate,
                frameRate = frameRate.coerceIn(5, 30),
                onConfig = { csd -> onVideoConfig?.invoke(VideoConfig(w, h, lastRotation, csd)) },
                onEncoded = { nal, keyframe -> onVideoFrame?.invoke(nal, keyframe) },
            )
        }.getOrNull()
        return encoder
    }

    fun resetEncoder() {
        encoder?.release()
        encoder = null
        encoderWidth = 0
        encoderHeight = 0
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

    /** Linear zoom 0..1 (device-independent). */
    fun setLinearZoom(value: Float) {
        linearZoom = value.coerceIn(0f, 1f)
        runCatching { camera?.cameraControl?.setLinearZoom(linearZoom) }
    }

    /** Normalized exposure compensation -1..1, mapped to the device's supported range. */
    fun setExposureFraction(fraction: Float) {
        evFraction = fraction.coerceIn(-1f, 1f)
        applyExposure()
    }

    fun setTorch(on: Boolean) {
        torchOn = on
        val info = camera?.cameraInfo ?: return
        if (info.hasFlashUnit()) runCatching { camera?.cameraControl?.enableTorch(on) }
    }

    val hasFlash: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    private fun applyExposure() {
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val range = state.exposureCompensationRange
        val index = (if (evFraction >= 0f) evFraction * range.upper else -evFraction * range.lower)
            .roundToInt()
            .coerceIn(range.lower, range.upper)
        runCatching { cam.cameraControl.setExposureCompensationIndex(index) }
    }

    private fun applyControls() {
        runCatching { camera?.cameraControl?.setLinearZoom(linearZoom) }
        applyExposure()
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            runCatching { camera?.cameraControl?.enableTorch(torchOn) }
        }
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

    /** Full-resolution JPEG bytes of a saved photo (to send a copy to the viewer phone). */
    fun fullBytesFor(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    fun unbind() {
        runCatching { cameraProvider?.unbindAll() }
        resetEncoder()
        camera = null
        onFrame = null
        onVideoConfig = null
        onVideoFrame = null
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
