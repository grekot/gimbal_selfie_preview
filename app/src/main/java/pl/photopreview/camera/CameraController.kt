package pl.photopreview.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.OrientationEventListener
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
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
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile var jpegQuality: Int = 60
    @Volatile var useH264: Boolean = true
    @Volatile var frameRate: Int = 15
    @Volatile var videoMode: Boolean = false
    @Volatile var frontCamera: Boolean = false
    @Volatile var onRecordingState: ((Boolean) -> Unit)? = null
    @Volatile var onVideoSaved: ((Uri?) -> Unit)? = null

    /** Invoked on a background thread for each analyzed frame: (jpegBytes, rotationDegrees). */
    @Volatile var onFrame: ((ByteArray, Int) -> Unit)? = null
    @Volatile var onVideoConfig: ((VideoConfig) -> Unit)? = null
    @Volatile var onVideoFrame: ((ByteArray, Boolean) -> Unit)? = null

    /** Face-follow: when true the analyzer runs face detection (throttled) and reports the largest face. */
    @Volatile var faceFollow: Boolean = false
    @Volatile var onFace: ((FaceBox?) -> Unit)? = null
    /** Upright (display) aspect ratio w/h of the analyzed frame, for drawing the face box. */
    @Volatile var faceFrameAspect: Float = 0f
    /** Physical device rotation 0/90/180/270 (the app is portrait-locked, so we read it from the sensor). */
    @Volatile var deviceRotation: Int = 0
    private var faceTracker: FaceTracker? = null
    @Volatile private var lastFaceAt: Long = 0L
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            deviceRotation = when {
                orientation >= 315 || orientation < 45 -> 0
                orientation < 135 -> 90
                orientation < 225 -> 180
                else -> 270
            }
        }
    }

    // Live camera controls; remembered so they survive a re-bind (e.g. resolution change).
    @Volatile private var zoomRatio: Float = 1f
    @Volatile private var evFraction: Float = 0f
    @Volatile private var torchOn: Boolean = false
    @Volatile var captureFlashMode: Int = ImageCapture.FLASH_MODE_OFF
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

        val useCases = mutableListOf<UseCase>(preview, analysis)
        if (videoMode) {
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
                )
                .build()
            val vc = VideoCapture.withOutput(recorder)
            videoCapture = vc
            imageCapture = null
            useCases.add(vc)
        } else {
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture
            videoCapture = null
            useCases.add(capture)
        }

        val selector = if (frontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            selector,
            *useCases.toTypedArray(),
        )
        applyControls()
        runCatching { if (orientationListener.canDetectOrientation()) orientationListener.enable() }
    }

    fun isRecording(): Boolean = activeRecording != null

    fun startRecording(withAudio: Boolean) {
        val vc = videoCapture ?: return
        if (activeRecording != null) return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "PhotoPreview_" + System.currentTimeMillis() + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PhotoPreview")
            }
        }
        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(values).build()
        var pending = vc.output.prepareRecording(context, options)
        if (withAudio) runCatching { pending = pending.withAudioEnabled() }
        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> onRecordingState?.invoke(true)
                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    onRecordingState?.invoke(false)
                    onVideoSaved?.invoke(if (event.hasError()) null else event.outputResults.outputUri)
                }
            }
        }
    }

    fun stopRecording() {
        runCatching { activeRecording?.stop() }
        activeRecording = null
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(image: ImageProxy) {
        var asyncClose = false
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
            if (faceFollow) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFaceAt >= 150) {
                    lastFaceAt = now
                    val rot = image.imageInfo.rotationDegrees
                    val mlRot = (rot + deviceRotation) % 360 // gravity-upright for ML Kit (portrait-locked app)
                    val uw = if (rot % 180 == 0) image.width else image.height
                    val uh = if (rot % 180 == 0) image.height else image.width
                    faceFrameAspect = if (uh != 0) uw.toFloat() / uh else 0f
                    val mediaImg = image.image
                    if (mediaImg != null) {
                        val tracker = faceTracker ?: FaceTracker().also { ft ->
                            ft.onResult = { box -> onFace?.invoke(box) }
                            faceTracker = ft
                        }
                        tracker.submit(
                            InputImage.fromMediaImage(mediaImg, mlRot),
                            image.width, image.height, mlRot,
                        ) { runCatching { image.close() } }
                        asyncClose = true
                    }
                }
            }
        } catch (_: Throwable) {
            // drop the bad frame and continue
        } finally {
            if (!asyncClose) image.close()
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
        capture.flashMode = captureFlashMode
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

    /** Zoom by ratio; on devices whose logical rear camera switches lenses this can engage optical telephoto. */
    fun setZoomRatio(ratio: Float) {
        val cam = camera
        if (cam == null) {
            zoomRatio = ratio
            return
        }
        val state = cam.cameraInfo.zoomState.value
        val min = state?.minZoomRatio ?: 1f
        val max = state?.maxZoomRatio ?: 1f
        zoomRatio = ratio.coerceIn(min, max)
        runCatching { cam.cameraControl.setZoomRatio(zoomRatio) }
    }

    fun zoomRange(): Pair<Float, Float> {
        val s = camera?.cameraInfo?.zoomState?.value ?: return 1f to 1f
        return s.minZoomRatio to s.maxZoomRatio
    }

    fun focus(point: MeteringPoint) {
        runCatching {
            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
        }
    }

    /**
     * Focus at a point given in the viewer's upright preview space (0..1). We invert the current
     * frame rotation to get the sensor-oriented point, then meter there.
     */
    fun resetFocus() {
        runCatching { camera?.cameraControl?.cancelFocusAndMetering() }
    }

    fun focusNormalized(ux: Float, uy: Float) {
        val rot = ((lastRotation % 360) + 360) % 360
        val sx: Float
        val sy: Float
        when (rot) {
            90 -> { sx = uy; sy = 1f - ux }
            180 -> { sx = 1f - ux; sy = 1f - uy }
            270 -> { sx = 1f - uy; sy = ux }
            else -> { sx = ux; sy = uy }
        }
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(sx.coerceIn(0f, 1f), sy.coerceIn(0f, 1f))
        runCatching { camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build()) }
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
        runCatching { camera?.cameraControl?.setZoomRatio(zoomRatio) }
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
        runCatching { orientationListener.disable() }
        runCatching { activeRecording?.stop() }
        activeRecording = null
        runCatching { cameraProvider?.unbindAll() }
        resetEncoder()
        camera = null
        videoCapture = null
        onFrame = null
        onVideoConfig = null
        onVideoFrame = null
        onFace = null
        faceTracker?.close()
        faceTracker = null
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
