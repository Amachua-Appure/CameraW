package com.cameraw

import android.Manifest
import android.R.attr.height
import android.R.attr.width
import android.media.Image
import android.hardware.camera2.params.RggbChannelVector
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.util.Rational
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.media.ImageWriter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.abs
import com.cameraw.FocusState

class MainActivity : ComponentActivity() {
    private lateinit var cameraManager: CameraManager
    private var previewSurface: Surface? = null
    private var textureViewSurfaceTexture: SurfaceTexture? = null
    private var textureViewSurface: Surface? = null
    private var activeRequestBuilder: CaptureRequest.Builder? = null
    private var activeRequestSession: CameraCaptureSession? = null

    private var rawReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var yuvReader: ImageReader? = null

    private val capturedImages = mutableListOf<Image>()
    private val capturedResults = mutableListOf<TotalCaptureResult>()

    private var gyroflowLogger: GyroflowLogger? = null
    private var currentGcsvFile: File? = null

    @Volatile
    private var lastPreviewResult: TotalCaptureResult? = null

    @Volatile
    private var captureSessionLockedResult: TotalCaptureResult? = null
    private var hasUltraHighRes = false
    private var remosaicKey: CaptureRequest.Key<Int>? = null

    private val isCapturing = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)

    private val activeRawImages = AtomicInteger(0)

    private var histogramReader: ImageReader? = null
    private var histogramHandler: Handler? = null
    private var histogramThread: HandlerThread? = null
    private var previousHistogramBins: FloatArray = FloatArray(256)

    private var burstFramesReceived = 0

    private val vulkanBridge = VulkanHdrBridge()
    private var vulkanHandle = 0L

    private var rawAudioThread: Thread? = null
    private var rawAudioRecord: AudioRecord? = null
    private var rawOutputFile: File? = null

    private companion object {
        const val TAG = "CameraW"
        val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
        const val PREFS_NAME = "camera_prefs"
    }

    private val optimalPreviewSizeState = mutableStateOf(Size(1920, 1080))
    private val currentSensorOrientationState = mutableStateOf(90)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var previewSize: Size

    private val cameraId: String get() = viewModel.uiState.value.cameraId
    private val isFrontCamera: Boolean get() = cameraId == "1"

    private val cameraOpenCloseLock = Semaphore(1)

    private var recorder: Recorder? = null
    @Volatile private var isRecording = false
    private var recordingStartTime = 0L

    private var hasFlash = false
    private var hasOis = false
    private var supportsModernZoom = false
    private var sensorOrientation = 90
    private var activeArraySize: Rect? = null
    private var minFocusDist = 0f
    private var isoRange: Range<Int>? = null
    private var shutterRange: Range<Long>? = null
    private var whiteLevel = 1023
    private var blackLevel = 64
    private var colorFilterArrangement = 0

    private var forwardMatrix1: ColorSpaceTransform? = null
    private var forwardMatrix2: ColorSpaceTransform? = null
    private var referenceIlluminant1: Int? = null
    private var referenceIlluminant2: Int? = null

    private var lastMetadataUpdate = AtomicLong(0)

    private var rawResolutions: List<Size> = emptyList()
    private var jpegResolutions: List<Size> = emptyList()

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val timerHandler = Handler()
    private var imageReaderThread: HandlerThread? = null
    private var imageReaderHandler: Handler? = null

    private var orientationEventListener: OrientationEventListener? = null
    private var currentOrientation = 0

    private lateinit var viewModel: CameraViewModel

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.onEvent(CameraUiEvent.PermissionsGranted)
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLogger.install(this.applicationContext)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        window.setSustainedPerformanceMode(true)

        loadSavedPreferences()
        if (!hasPermissions()) permissionLauncher.launch(REQUIRED_PERMISSIONS)

        gyroflowLogger = GyroflowLogger(this)

        viewModel = ViewModelProvider(this)[CameraViewModel::class.java]

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedCameraId = prefs.getString("camera_id", "0") ?: "0"
        viewModel.onEvent(CameraUiEvent.SetCameraId(savedCameraId))

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            CameraWTheme {
                CameraWScreen(
                    viewModel = viewModel,
                    onEvent = { event -> handleUiEvent(event) },
                    onSurfaceTextureAvailable = { texture, _, _ -> openCamera(texture) },
                    onSurfaceTextureDestroyed = {
                        textureViewSurfaceTexture = null
                        textureViewSurface = null
                        closeCamera()
                        true
                    },
                    deviceRotation = rememberDeviceOrientation(),
                    previewSize = optimalPreviewSizeState.value,
                    sensorOrientation = currentSensorOrientationState.value,
                    isRecording = uiState.isRecording
                )
            }
        }

        setupOrientationListener()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        orientationEventListener?.enable()
        if (textureViewSurfaceTexture != null && cameraDevice == null) {
            openCamera(textureViewSurfaceTexture)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onPause() {
        super.onPause()
        if (isRecording) stopRecordingInternal()
        closeCamera()
        stopBackgroundThread()
        imageReaderThread?.quitSafely()
        try { imageReaderThread?.join() } catch (_: InterruptedException) {}
        imageReaderThread = null
        orientationEventListener?.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP,
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event?.repeatCount == 0) {
                    handleUiEvent(CameraUiEvent.RecordButtonClicked)
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun handleUiEvent(event: CameraUiEvent) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        when (event) {
            is CameraUiEvent.RecordButtonClicked -> {
                val mode = viewModel.uiState.value.cameraMode
                if (mode == CameraMode.PHOTO) {
                    takePhoto()
                } else {
                    if (isRecording) stopRecordingInternal() else startRecordingInternal()
                }
                viewModel.saveCurrentState(prefs)
            }
            is CameraUiEvent.SetCaptureMode -> {
                val oldCameraId = cameraId
                viewModel.onEvent(event)
                viewModel.saveCurrentState(prefs)

                val newCameraId = cameraId
                if (oldCameraId != newCameraId) {
                    closeCamera()
                    stopBackgroundThread()
                    startBackgroundThread()
                    textureViewSurfaceTexture?.let { openCamera(it) }
                } else {
                    restartCameraSession()
                }
            }
            is CameraUiEvent.ResolutionClicked -> {
                viewModel.showResolutionDialog()
            }
            is CameraUiEvent.FpsClicked -> {
                viewModel.cycleFps()
                viewModel.saveCurrentState(prefs)
                restartCameraSession()
            }
            is CameraUiEvent.FlashToggled -> toggleFlash()
            is CameraUiEvent.IsoClicked -> viewModel.onEvent(event)
            is CameraUiEvent.SettingsClicked -> viewModel.onEvent(CameraUiEvent.ToggleSettings)

            is CameraUiEvent.SetVideoCodec -> {
                prefs.edit { putString("video_codec", event.codec) }
                viewModel.onEvent(event)
            }
            is CameraUiEvent.SetAudioCodec -> {
                prefs.edit { putString("audio_codec", event.codec) }
                viewModel.onEvent(event)
            }
            is CameraUiEvent.SetPhotoFormat -> {
                viewModel.onEvent(event)
                viewModel.saveCurrentState(prefs)
                restartCameraSession()
            }
            is CameraUiEvent.SetQuality -> {
                prefs.edit { putInt("quality", event.quality) }
                viewModel.onEvent(event)
            }
            is CameraUiEvent.SetNoiseReduction -> {
                prefs.edit { putInt("noise_reduction_mode", event.mode) }
                viewModel.onEvent(event)
                if (isRecording) updateFlashDuringRecording() else updatePreview()
            }

            is CameraUiEvent.SelectResolution -> {
                viewModel.onEvent(event)
                viewModel.saveCurrentState(prefs)
                restartCameraSession()
            }

            is CameraUiEvent.TapToFocus -> {
                viewModel.onEvent(event)
                triggerAutofocus(event.point)
            }
            is CameraUiEvent.ResumeContinuousFocus,
            is CameraUiEvent.SetExposureCompensation -> {
                viewModel.onEvent(event)
                updatePreview()
            }
            is CameraUiEvent.LockAeAf -> {
                viewModel.onEvent(event)
                updatePreview()
            }
            is CameraUiEvent.UnlockAeAf -> {
                viewModel.onEvent(event)
                updatePreview()
            }
            is CameraUiEvent.SetManualIso,
            is CameraUiEvent.SetShutterSpeed,
            is CameraUiEvent.SetFocusDistance,
            is CameraUiEvent.SetWhiteBalance -> {
                viewModel.onEvent(event)
                updatePreview()
            }
            is CameraUiEvent.SetZoom -> {
                viewModel.onEvent(event)
                if (viewModel.uiState.value.cameraMode != CameraMode.PHOTO) {
                    val builder = activeRequestBuilder ?: return
                    val session = captureSession ?: return
                    backgroundHandler?.post {
                        try {
                            applyManualControls(builder)
                            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                        } catch (e: Exception) {
                            Log.e(TAG, "Fast zoom update failed", e)
                        }
                    }
                }
            }
            is CameraUiEvent.ToggleCamera -> {
                viewModel.saveCurrentState(getSharedPreferences(PREFS_NAME, MODE_PRIVATE))
                viewModel.onEvent(event)
                val nextCamera = viewModel.uiState.value.cameraId
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("camera_id", nextCamera).apply()
                closeCamera()
                stopBackgroundThread()
                startBackgroundThread()
                textureViewSurfaceTexture?.let { openCamera(it) }
            }
            is CameraUiEvent.SetVideoFormat,
            is CameraUiEvent.SetBurstFrames,
            is CameraUiEvent.SetPngCompression,
            is CameraUiEvent.SetDynamicMetadataMode,
            is CameraUiEvent.ToggleSaveGyroData -> {
                viewModel.onEvent(event)
                viewModel.saveCurrentState(prefs)
            }

            is CameraUiEvent.SetHasFlash -> viewModel.onEvent(event)

            else -> viewModel.onEvent(event)
        }
    }

    private fun loadSavedPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val videoCodec = prefs.getString("video_codec", "video/hevc") ?: "video/hevc"
        val audioCodec = prefs.getString("audio_codec", "0") ?: "0"
        val quality = prefs.getInt("quality", 100)
        val noiseReductionMode = prefs.getInt("noise_reduction_mode", 0)

        CameraViewModel.savedPreferences = mapOf(
            "videoCodec" to videoCodec,
            "audioCodec" to audioCodec,
            "quality" to quality.toString(),
            "noiseReductionMode" to noiseReductionMode.toString()
        )
    }

    private fun getOptimalPreviewSize(targetRes: Size): Size {
        val state = viewModel.uiState.value

        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return targetRes

        val supportedSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: return targetRes

        val maxWidth = if (state.cameraMode == CameraMode.PRO_VIDEO || state.cameraMode == CameraMode.RAW_VIDEO) {
            1920
        } else {
            if (targetRes.width > 1920) targetRes.width else 1920
        }

        val safeSizes = supportedSizes.filter { it.width <= maxWidth }
        val searchPool = if (safeSizes.isNotEmpty()) safeSizes else supportedSizes
        val targetRatio = targetRes.width.toFloat() / targetRes.height.toFloat()

        val matchingSizes = searchPool.filter { size ->
            Math.abs((size.width.toFloat() / size.height.toFloat()) - targetRatio) < 0.05f
        }

        return matchingSizes.maxByOrNull { it.width * it.height }
            ?: searchPool.firstOrNull { Math.abs((it.width.toFloat() / it.height.toFloat()) - (16f/9f)) < 0.05f }
            ?: searchPool.maxByOrNull { it.width * it.height }
            ?: targetRes
    }

    @Composable
    private fun rememberDeviceOrientation(): State<Float> {
        val rotation = remember { mutableFloatStateOf(0f) }
        DisposableEffect(Unit) {
            val listener = object : OrientationEventListener(this@MainActivity) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return

                    val targetAngle = when {
                        orientation >= 315 || orientation < 45 -> 0f
                        orientation in 45..134 -> -90f
                        orientation in 135..224 -> 180f
                        orientation in 225..314 -> 90f
                        else -> 0f
                    }

                    var delta = targetAngle - (rotation.floatValue % 360f)
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f

                    rotation.floatValue += delta
                }
            }
            if (listener.canDetectOrientation()) listener.enable()
            onDispose { listener.disable() }
        }
        return rotation
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setupStandardVideoPipeline(state: CameraUiState) {
        val recordSurface = recorder!!.encoderSurface!!
        val previewSurface = textureViewSurface ?: run { stopRecordingInternal(); return }
        createCameraSession(
            outputs = listOf(previewSurface, recordSurface),
            stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    builder.addTarget(previewSurface)
                    builder.addTarget(recordSurface)
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, state.selectedFpsRange)
                    applyManualControls(builder)
                    activeRequestBuilder = builder
                    activeRequestSession = session
                    session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                    isRecording = true
                    recordingStartTime = System.currentTimeMillis()
                    timerHandler.post(timerRunnable)
                    viewModel.onEvent(CameraUiEvent.RecordingStarted)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    stopRecordingInternal()
                    startPreview()
                }
            },
            template = CameraDevice.TEMPLATE_RECORD,
            cameraMode = state.cameraMode,
            isRecordingSession = true
        )
    }

    private fun setupOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when {
                    orientation >= 315 || orientation < 45 -> 0
                    orientation in 45..134 -> -90
                    orientation in 135..224 -> 180
                    orientation in 225..314 -> 90
                    else -> 0
                }
                if (rotation != currentOrientation) currentOrientation = rotation
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(surfaceTexture: SurfaceTexture? = null) {
        if (!hasPermissions()) return

        textureViewSurfaceTexture = surfaceTexture ?: textureViewSurfaceTexture
        textureViewSurface = textureViewSurfaceTexture?.let { Surface(it) }

        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS))
                throw RuntimeException("Camera open timeout")

            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

            hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            runOnUiThread { viewModel.onEvent(CameraUiEvent.SetHasFlash(hasFlash)) }

            hasOis = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) ?: false
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            minFocusDist = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
            val blPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            blackLevel = blPattern?.getOffsetForIndex(0, 0) ?: 64

            colorFilterArrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?:
                    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB

            forwardMatrix1 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)
            forwardMatrix2 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)
            referenceIlluminant1 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt()
            referenceIlluminant2 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()

            var maxZ = 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRange != null) {
                    supportsModernZoom = true
                    maxZ = zoomRange.upper
                } else {
                    maxZ = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                }
            } else {
                maxZ = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            }

            runOnUiThread { viewModel.onEvent(CameraUiEvent.UpdateMaxZoom(maxZ)) }

            getCameraCapabilities(characteristics)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                hasUltraHighRes = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) == true
            }

            try {
                remosaicKey = characteristics.availableCaptureRequestKeys.find {
                    it.name.contains("remosaicenable", ignoreCase = true)
                } as? CaptureRequest.Key<Int>
            } catch (e: Exception) {}

            buildCameraInventories(map)

            val bestRawSize = rawResolutions.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
            val rawMinDur = if (rawResolutions.isNotEmpty()) map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, bestRawSize) else 0L
            val maxRawFps = if (rawMinDur > 0) (1_000_000_000.0 / rawMinDur).toInt() else 30

            viewModel.initializeCameraModes(
                rawResolutions,
                jpegResolutions,
                maxRawFps,
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            )

            val optimalSize = getOptimalPreviewSize(viewModel.uiState.value.currentResolution)
            optimalPreviewSizeState.value = optimalSize
            currentSensorOrientationState.value = sensorOrientation
            textureViewSurfaceTexture?.setDefaultBufferSize(optimalSize.width, optimalSize.height)
            previewSurface = Surface(textureViewSurfaceTexture)
            manager.openCamera(cameraId, deviceStateCallback, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun getCameraCapabilities(characteristics: CameraCharacteristics) {
        isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        shutterRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        runOnUiThread {
            viewModel.updateCapabilities(
                isoRange?.lower ?: 100, isoRange?.upper ?: 6400,
                shutterRange?.lower ?: 100_000L, shutterRange?.upper ?: 100_000_000L,
                0f, minFocusDist
            )
        }
    }

    private fun buildCameraInventories(map: StreamConfigurationMap) {
        rawResolutions = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() ?: emptyList()

        var maxJpegs: List<Size> = emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(cameraId)

            val maxResMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            if (maxResMap != null) {
                maxJpegs = maxResMap.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
            }

            if (maxJpegs.isEmpty()) {
                val hiddenKeys = listOf(
                    "android.scaler.availableStreamConfigurationsMaximumResolution"
                )
                for (keyName in hiddenKeys) {
                    try {
                        val customKey = CameraCharacteristics.Key(keyName, IntArray::class.java)
                        val intArray = chars.get(customKey)
                        if (intArray != null) {
                            val vendorSizes = mutableListOf<Size>()
                            for (i in 0 until intArray.size step 4) {
                                if (intArray[i] == 33 && intArray[i + 3] == 0) {
                                    vendorSizes.add(Size(intArray[i + 1], intArray[i + 2]))
                                }
                            }
                            if (vendorSizes.isNotEmpty()) {
                                maxJpegs = vendorSizes
                                break
                            }
                        }
                    } catch (e: Exception) { }
                }
            }
        }
        val standardJpegs = map.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        jpegResolutions = (maxJpegs + standardJpegs).distinct().sortedByDescending { it.width * it.height }
    }

    private val deviceStateCallback = object : CameraDevice.StateCallback() {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPreview()
        }
        override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
        override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null }
    }

    private fun gainsToColorTemperature(gains: RggbChannelVector): Int {
        val r = gains.red
        val g = (gains.greenEven + gains.greenOdd) / 2f
        val b = gains.blue
        if (g == 0f || b == 0f) return 5500
        val rNorm = r / g
        val bNorm = b / g
        val ratio = rNorm / bNorm
        val temp = (3363f * ratio) + 1456f
        return temp.coerceIn(2000f, 10000f).toInt()
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = System.currentTimeMillis()
            if (now - lastMetadataUpdate.get() > 150) {
                lastMetadataUpdate.set(now)
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                val shutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
                val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                val wb = if (gains != null) gainsToColorTemperature(gains) else 5500

                viewModel.onEvent(CameraUiEvent.UpdateLiveMetadata(iso, shutter, focus, wb))
            }
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (afState != null) {
                val focusStatus = when (afState) {
                    CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN,
                    CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> FocusState.SCANNING
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                    CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> FocusState.FOCUSED
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
                    CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> FocusState.FAILED
                    else -> FocusState.IDLE
                }
                if (viewModel.uiState.value.focusState != focusStatus) {
                    viewModel.onEvent(CameraUiEvent.UpdateFocusState(focusStatus))
                }
            }
            lastPreviewResult = result
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun createCameraSession(
        outputs: List<Surface>,
        stateCallback: CameraCaptureSession.StateCallback,
        template: Int,
        cameraMode: CameraMode,
        isRecordingSession: Boolean = false
    ) {
        val device = cameraDevice ?: return
        try {
            val outputConfigs = outputs.map { OutputConfiguration(it) }
            val executor = backgroundHandler?.asExecutor() ?: Executor { it.run() }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                executor,
                stateCallback
            )

            val sessionBuilder = device.createCaptureRequest(template)

            val state = viewModel.uiState.value
            if (state.cameraMode == CameraMode.PHOTO && state.currentResolution.width > 4000) {
                if (hasUltraHighRes) {
                    sessionBuilder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                }
            }
            sessionConfig.sessionParameters = sessionBuilder.build()

            try {
                device.createCaptureSession(sessionConfig)
            } catch (e: Exception) {
                Log.e(TAG, "SessionConfiguration failed, attempting legacy fallback", e)
                try {
                    device.createCaptureSession(outputs, stateCallback, backgroundHandler)
                } catch (e2: Exception) {
                    Log.e(TAG, "Legacy session creation failed too", e2)
                    if (isRecording) {
                        backgroundHandler?.post { stopRecordingInternal() }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SessionConfiguration failed, falling back", e)
            device.createCaptureSession(outputs, stateCallback, backgroundHandler)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun startPreview() {
        if (cameraDevice == null) return
        closeSession()
        val surface = textureViewSurface ?: return
        val mode = viewModel.uiState.value.cameraMode

        if (histogramReader == null) {
            histogramReader = ImageReader.newInstance(256, 144, ImageFormat.YUV_420_888, 2)
            histogramThread = HandlerThread("Histogram").also { it.start() }
            histogramHandler = Handler(histogramThread!!.looper)

            histogramReader!!.setOnImageAvailableListener({ reader ->
                val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
                image?.use { img ->
                    val planes = img.planes
                    if (planes.isNotEmpty()) {
                        val buffer = planes[0].buffer
                        val limit = buffer.limit()
                        val bytes = ByteArray(limit)
                        buffer.get(bytes)

                        val bins = FloatArray(256)
                        val step = 4
                        var totalSamples = 0

                        for (i in 0 until limit step step) {
                            val luma = bytes[i].toInt() and 0xFF
                            bins[luma]++
                            totalSamples++
                        }

                        val smoothingFactor = 0.3f
                        val visualGain = 50.0f
                        val normFactor = if (totalSamples > 0) visualGain / totalSamples else 0f

                        val finalData = FloatArray(256)
                        for (i in bins.indices) {
                            val rawVal = bins[i] * normFactor
                            finalData[i] = (previousHistogramBins[i] * (1 - smoothingFactor)) + (rawVal * smoothingFactor)
                        }

                        previousHistogramBins = finalData
                        runOnUiThread { viewModel.onEvent(CameraUiEvent.UpdateHistogram(finalData)) }
                    }
                }
            }, histogramHandler)
        }

        val bitDepth = viewModel.uiState.value.photoBitDepth
        val currentRes = viewModel.uiState.value.currentResolution
        val is50Mp = (currentRes.width == 8192 && currentRes.height == 6144) ||
                (currentRes.width == 4640 && currentRes.height == 3488)

        if (bitDepth == 8) {
            rawReader?.close()
            rawReader = null
            activeRawImages.set(0)

            if (is50Mp) {
                jpegReader?.close()
                jpegReader = null

                if (yuvReader == null || yuvReader?.width != currentRes.width || yuvReader?.height != currentRes.height) {
                    yuvReader?.close()
                    yuvReader = ImageReader.newInstance(currentRes.width, currentRes.height, ImageFormat.YUV_420_888, viewModel.uiState.value.burstFrames)
                }

                yuvReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    if (!isCapturing.get()) {
                        image.close()
                        return@setOnImageAvailableListener
                    }

                    if (burstFramesReceived == 0) {
                        CameraWISP.initYuvAccumulator(currentRes.width, currentRes.height)
                    }

                    val yPlane = image.planes[0]
                    val uPlane = image.planes[1]
                    val vPlane = image.planes[2]

                    CameraWISP.addYuvFrame(
                        yPlane.buffer, uPlane.buffer, vPlane.buffer,
                        currentRes.width, currentRes.height,
                        yPlane.rowStride, uPlane.rowStride, uPlane.pixelStride
                    )

                    burstFramesReceived++
                    image.close()

                    if (burstFramesReceived >= viewModel.uiState.value.burstFrames) {
                        isCapturing.set(false)
                        burstFramesReceived = 0
                        isProcessing.set(true)
                        processAccumulatedYuv(currentRes.width, currentRes.height)
                    }
                }, backgroundHandler)
            } else {
                yuvReader?.close()
                yuvReader = null

                if (jpegReader == null || jpegReader?.width != currentRes.width || jpegReader?.height != currentRes.height) {
                    jpegReader?.close()
                    jpegReader = ImageReader.newInstance(currentRes.width, currentRes.height, ImageFormat.JPEG, 2)
                }

                jpegReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    if (!isCapturing.get()) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    isCapturing.set(false)
                    isProcessing.set(true)
                    saveJpegDirectly(image)
                }, backgroundHandler)
            }
        } else {
            jpegReader?.close()
            jpegReader = null
            yuvReader?.close()
            yuvReader = null

            if (rawReader == null || rawReader?.width != currentRes.width || rawReader?.height != currentRes.height) {
                rawReader?.close()
                rawReader = ImageReader.newInstance(currentRes.width, currentRes.height, ImageFormat.RAW_SENSOR, 12)
                activeRawImages.set(0)
            }

            rawReader?.setOnImageAvailableListener({ reader ->
                if (!isCapturing.get()) {
                    reader.acquireNextImage()?.close()
                    return@setOnImageAvailableListener
                }

                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                activeRawImages.incrementAndGet()

                synchronized(capturedImages) {
                    capturedImages.add(image)

                    val framesNeeded = viewModel.uiState.value.burstFrames
                    if (capturedImages.size >= framesNeeded) {
                        isCapturing.set(false)

                        val finalStack = ArrayList(capturedImages)
                        val finalResults = ArrayList(capturedResults)

                        capturedImages.clear()
                        capturedResults.clear()

                        val locked = captureSessionLockedResult ?: lastPreviewResult
                        isProcessing.set(true)
                        processCapturedStack(finalStack, finalResults, locked)
                    }
                }
            }, backgroundHandler)
        }

        val surfaces = mutableListOf(surface)
        histogramReader?.surface?.let { surfaces.add(it) }

        if (mode == CameraMode.PHOTO) {
            if (bitDepth == 8) {
                if (is50Mp) yuvReader?.surface?.let { surfaces.add(it) }
                else jpegReader?.surface?.let { surfaces.add(it) }
            } else {
                rawReader?.surface?.let { surfaces.add(it) }
            }
        }

        createCameraSession(
            outputs = surfaces,
            stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updatePreview()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Preview session config failed")
                }
            },
            template = CameraDevice.TEMPLATE_PREVIEW,
            cameraMode = mode,
            isRecordingSession = false
        )
    }

    private fun updatePreview() {
        try {
            val device = cameraDevice ?: return
            val session = captureSession ?: return
            val state = viewModel.uiState.value

            val template = if (isRecording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = device.createCaptureRequest(template)

            activeRequestBuilder = builder
            activeRequestSession = session

            textureViewSurface?.let { builder.addTarget(it) }

            if (isRecording) {
                when (state.cameraMode) {
                    CameraMode.PRO_VIDEO, CameraMode.RAW_VIDEO -> {
                        rawReader?.surface?.let {
                            builder.addTarget(it)
                        }
                    }
                    else -> recorder?.encoderSurface?.let { builder.addTarget(it) }
                }
            } else {
                histogramReader?.surface?.let { builder.addTarget(it) }
            }

            applyManualControls(builder)

            if (state.cameraMode == CameraMode.RAW_VIDEO) {
                builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
                builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)

                if (state.manualShutterNano == null) {
                    val maxShutterNs = 1_000_000_000L / state.currentFps
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, maxShutterNs)
                }
            }

            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Update preview failed", e)
        }
    }
    private fun applyManualControls(builder: CaptureRequest.Builder, isStillCapture: Boolean = false) {
        val state = viewModel.uiState.value
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val nrMode = prefs.getInt("noise_reduction_mode", 0)

        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionFromInt(nrMode))
        builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)
        builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_FAST)
        builder.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_FAST)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, state.exposureCompensation)
        if (state.cameraMode == CameraMode.PHOTO) {
            builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        } else {
            builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_OFF)
        }

        val activeArrayW = activeArraySize?.width() ?: 4000
        if (state.cameraMode == CameraMode.PHOTO && state.currentResolution.width > activeArrayW && isStillCapture) {
            if (hasUltraHighRes) {
                builder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            } else {
                remosaicKey?.let { try { builder.set(it, 1) } catch (_: Exception) {} }
            }
        } else {
            if (hasUltraHighRes) {
                builder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
            } else {
                remosaicKey?.let { try { builder.set(it, 0) } catch (_: Exception) {} }
            }
        }

        val meteringRect = if (state.focusPoint != null && activeArraySize != null) {
            val rect = calculateMeteringRect(state.focusPoint!!, activeArraySize!!)
            arrayOf(MeteringRectangle(rect, 1000))
        } else null
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, meteringRect)
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, meteringRect)

        val afMode = when {
            state.manualFocusDist != null -> CameraMetadata.CONTROL_AF_MODE_OFF
            state.focusPoint != null -> CameraMetadata.CONTROL_AF_MODE_AUTO
            else -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        if (state.manualFocusDist != null) {
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, state.manualFocusDist)
        }

        if (state.manualIso != null || state.manualShutterNano != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, state.manualIso ?: isoRange?.lower ?: 100)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, state.manualShutterNano ?: 16_666_666L)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, state.isAeAfLocked)
        }

        if (state.manualWbTemp != null) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, computeTemperatureGains(state.manualWbTemp))
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, state.isAeAfLocked)
        }

        val safeFlashMode = if (state.flashEnabled && hasFlash) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF
        builder.set(CaptureRequest.FLASH_MODE, safeFlashMode)

        applyZoom(builder, state.zoomRatio)
    }

    private fun applyZoom(builder: CaptureRequest.Builder, zoomRatio: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && supportsModernZoom) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        } else {
            activeArraySize?.let { sensorRect ->
                val centerX = sensorRect.centerX()
                val centerY = sensorRect.centerY()
                val deltaX = (sensorRect.width() / (2 * zoomRatio)).toInt()
                val deltaY = (sensorRect.height() / (2 * zoomRatio)).toInt()
                builder.set(
                    CaptureRequest.SCALER_CROP_REGION,
                    Rect(centerX - deltaX, centerY - deltaY, centerX + deltaX, centerY + deltaY)
                )
            }
        }
    }

    private fun computeTemperatureGains(temp: Int): RggbChannelVector {
        val t = temp.coerceIn(2000, 10000).toFloat()
        val norm = (t - 2000) / 8000f
        val redGain = 1.0f + (1.5f * norm)
        val blueGain = 2.5f - (1.5f * norm)
        return RggbChannelVector(redGain, 1.0f, 1.0f, blueGain)
    }

    private fun calculateMeteringRect(touchPoint: Offset, activeArray: Rect): Rect {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()
        val isPortrait = currentOrientation == 0 || currentOrientation == 180

        val sensorW = optimalPreviewSizeState.value.width.toFloat()
        val sensorH = optimalPreviewSizeState.value.height.toFloat()
        val targetRatio = if (isPortrait) sensorH / sensorW else sensorW / sensorH
        val screenRatio = screenW / screenH

        var vfWidth = screenW
        var vfHeight = screenH
        var offsetX = 0f
        var offsetY = 0f

        if (screenRatio > targetRatio) {
            vfWidth = screenH * targetRatio
            offsetX = (screenW - vfWidth) / 2f
        } else {
            vfHeight = screenW / targetRatio
            offsetY = (screenH - vfHeight) / 2f
        }

        val normX = ((touchPoint.x - offsetX) / vfWidth).coerceIn(0f, 1f)
        val normY = ((touchPoint.y - offsetY) / vfHeight).coerceIn(0f, 1f)

        var sensorNormX = 0f
        var sensorNormY = 0f

        when (sensorOrientation) {
            90 -> {
                sensorNormX = normY
                sensorNormY = 1f - normX
            }
            270 -> {
                sensorNormX = 1f - normY
                sensorNormY = 1f - normX
            }
            180 -> {
                sensorNormX = 1f - normX
                sensorNormY = 1f - normY
            }
            else -> {
                sensorNormX = normX
                sensorNormY = normY
            }
        }

        val arrayW = activeArray.width().toFloat()
        val arrayH = activeArray.height().toFloat()

        val centerX = (sensorNormX * arrayW).toInt() + activeArray.left
        val centerY = (sensorNormY * arrayH).toInt() + activeArray.top

        val regionSize = (arrayW * 0.1f).toInt()
        val halfSize = regionSize / 2

        val left = (centerX - halfSize).coerceIn(activeArray.left, activeArray.right - regionSize)
        val top = (centerY - halfSize).coerceIn(activeArray.top, activeArray.bottom - regionSize)

        return Rect(left, top, left + regionSize, top + regionSize)
    }

    private fun triggerAutofocus(point: Offset) {
        val session = captureSession ?: return
        val builder = activeRequestBuilder ?: return
        val state = viewModel.uiState.value

        if (session != activeRequestSession) return

        try {
            applyManualControls(builder)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), null, backgroundHandler)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), captureCallback, backgroundHandler)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "AF Trigger failed", e)
        }
    }

    private fun noiseReductionFromInt(value: Int): Int = when (value) {
        0 -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
        1 -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
        2 -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        3 -> CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
        4 -> CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG
        else -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
    }
    private fun getJpegOrientation(deviceOrientation: Int): Int {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + deviceOrientation) % 360
        } else {
            (sensorOrientation - deviceOrientation + 360) % 360
        }
    }
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun takePhoto() {
        if (isCapturing.get() || isProcessing.get()) {
            Toast.makeText(this, "Busy processing...", Toast.LENGTH_SHORT).show()
            return
        }

        val lockedResult = lastPreviewResult ?: run {
            Toast.makeText(this, "Waiting for camera...", Toast.LENGTH_SHORT).show()
            return
        }

        captureSessionLockedResult = lockedResult
        isCapturing.set(true)

        synchronized(capturedImages) {
            capturedImages.clear()
            capturedResults.clear()
        }

        try {
            val device = cameraDevice ?: return
            val bitDepth = viewModel.uiState.value.photoBitDepth
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(textureViewSurface!!)
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(currentOrientation))

            if (bitDepth == 8) {
                val currentRes = viewModel.uiState.value.currentResolution
                val activeArrayW = activeArraySize?.width() ?: 4000
                val isUltraHighRes = currentRes.width > activeArrayW

                if (isUltraHighRes) {
                    val burstCount = viewModel.uiState.value.burstFrames
                    captureBuilder.addTarget(yuvReader!!.surface)

                    captureBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                    captureBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, true)

                    applyManualControls(captureBuilder, isStillCapture = true)

                    val burstRequests = List(burstCount) { captureBuilder.build() }
                    captureSession?.captureBurst(burstRequests, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                            Log.e(TAG, "YUV Burst frame failed")
                            isCapturing.set(false)
                        }
                    }, backgroundHandler)
                } else {
                    captureBuilder.addTarget(jpegReader!!.surface)
                    captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())

                    applyManualControls(captureBuilder, isStillCapture = true)
                    captureSession?.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                            Log.e(TAG, "JPEG Capture failed")
                            isCapturing.set(false)
                        }
                    }, backgroundHandler)
                }
            } else {
                val burstCount = viewModel.uiState.value.burstFrames.coerceAtLeast(8)
                captureBuilder.addTarget(rawReader!!.surface)

                val iso = lockedResult.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                val exp = lockedResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)

                val dist = lockedResult.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, dist)

                val gains = lockedResult.get(CaptureResult.COLOR_CORRECTION_GAINS)
                val ccm = lockedResult.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)

                if (gains != null && ccm != null) {
                    captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                    captureBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
                    captureBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    captureBuilder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ccm)
                }

                captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                captureBuilder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
                captureBuilder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)

                val burstRequests = List(burstCount) { captureBuilder.build() }

                captureSession?.captureBurst(burstRequests, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        synchronized(capturedResults) { capturedResults.add(result) }
                    }
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        Log.e(TAG, "Frame failed: ${failure.reason}")
                    }
                }, backgroundHandler)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Capture Error", e)
            isCapturing.set(false)
            Toast.makeText(this, "Camera Error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processAccumulatedYuv(width: Int, height: Int) {
        thread(start = true, name = "YuvFinalizer") {
            try {
                runOnUiThread { Toast.makeText(this@MainActivity, "Finalizing...", Toast.LENGTH_SHORT).show() }

                val denoisedNv21 = CameraWISP.finishYuvAccumulator(width, height)

                val yuvImage = YuvImage(denoisedNv21, ImageFormat.NV21, width, height, null)
                val outStream = ByteArrayOutputStream()
                val safeJpegQuality = viewModel.uiState.value.quality.coerceIn(0, 100)
                yuvImage.compressToJpeg(Rect(0, 0, width, height), safeJpegQuality, outStream)

                val tempFile = File(cacheDir, "CameraW__${System.currentTimeMillis()}.jpg")
                FileOutputStream(tempFile).use { it.write(outStream.toByteArray()) }

                saveToPublicStorage(tempFile, "jpg", "image/jpeg")
                runOnUiThread { Toast.makeText(this@MainActivity, "Saved JPEG", Toast.LENGTH_SHORT).show() }

            } catch (e: Exception) {
                Log.e(TAG, "Merge Failed", e)
                runOnUiThread { Toast.makeText(this@MainActivity, "Merge Failed", Toast.LENGTH_SHORT).show() }
            } finally {
                isProcessing.set(false)
                backgroundHandler?.post { updatePreview() }
            }
        }
    }

    private fun processCapturedStack(
        images: List<Image>,
        results: List<TotalCaptureResult>,
        lockedResult: TotalCaptureResult?
    ) {
        thread(start = true, name = "OpenCV_Processor") {
            try {
                if (images.isEmpty() || lockedResult == null) return@thread

                System.gc()
                Thread.sleep(50)

                runOnUiThread { Toast.makeText(this@MainActivity, "Developing RAW...", Toast.LENGTH_SHORT).show() }

                val blackLevels = lockedResult.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                val safeBlackLevel = blackLevels?.getOrNull(0)?.toInt() ?: blackLevel

                val shadingMap = lockedResult.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)

                val iso = lockedResult.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                val shutter = lockedResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L

                val noiseProfile = lockedResult.get(CaptureResult.SENSOR_NOISE_PROFILE)
                val noiseScale = noiseProfile?.get(0)?.first?.toFloat() ?: 0.0001f
                val noiseOffset = noiseProfile?.get(0)?.second?.toFloat() ?: 0.0001f

                val gains = lockedResult.get(CaptureResult.COLOR_CORRECTION_GAINS)
                val wbTemp = if (gains != null) gainsToColorTemperature(gains) else 5500

                var hwRGain = 1.0f
                var hwBGain = 1.0f
                if (gains != null) {
                    val avgGreen = (gains.greenEven + gains.greenOdd) / 2f
                    if (avgGreen > 0f) {
                        hwRGain = gains.red / avgGreen
                        hwBGain = gains.blue / avgGreen
                    }
                }

                val compression = viewModel.uiState.value.pngCompression
                val bitDepth = viewModel.uiState.value.photoBitDepth
                val ext = when (bitDepth) {
                    16 -> "png"
                    14 -> "dng"
                    9 -> "heic"
                    else -> "avif"
                }
                val mimeType = when (bitDepth) {
                    16 -> "image/png"
                    14 -> "image/x-adobe-dng"
                    9 -> "image/heic"
                    else -> "image/avif"
                }

                val tempFile = File(cacheDir, "CameraWPro_${System.currentTimeMillis()}.$ext")
                val manager = getSystemService(CAMERA_SERVICE) as CameraManager
                val characteristics = manager.getCameraCharacteristics(cameraId)
                ImageUtils.processBurst(
                    images = images,
                    file = tempFile,
                    whiteLevel = whiteLevel,
                    blackLevel = safeBlackLevel,
                    shadingMap = shadingMap,
                    sensorOrientation = sensorOrientation,
                    deviceOrientation = currentOrientation,
                    iso = iso,
                    shutter = shutter,
                    wbTemp = wbTemp,
                    compressionLevel = compression,
                    bitDepth = bitDepth,
                    rGain = hwRGain,
                    bGain = hwBGain,
                    isFrontCamera = isFrontCamera,
                    noiseScale = noiseScale,
                    noiseOffset = noiseOffset,
                    characteristics = characteristics,
                    captureResult = lockedResult
                )

                saveToPublicStorage(tempFile, ext, mimeType)
                viewModel.onEvent(CameraUiEvent.MediaSaved(tempFile))
                runOnUiThread { Toast.makeText(this@MainActivity, "Saved to Gallery", Toast.LENGTH_SHORT).show() }

            } catch (t: Throwable) {
                Log.e(TAG, "Fatal", t)
                runOnUiThread { Toast.makeText(this@MainActivity, "Error: ${t.message}", Toast.LENGTH_LONG).show() }
            } finally {
                images.forEach { try { it.close() } catch (_: Exception) {} }
                activeRawImages.set(0)
                isProcessing.set(false)
                captureSessionLockedResult = null
                backgroundHandler?.post { updatePreview() }
            }
        }
    }

    private fun saveJpegDirectly(image: Image) {
        thread(start = true, name = "JpegSaver") {
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)

                val tempFile = File(cacheDir, "CameraW_${System.currentTimeMillis()}.jpg")
                FileOutputStream(tempFile).use { it.write(bytes) }

                saveToPublicStorage(tempFile, "jpg", "image/jpeg")
                viewModel.onEvent(CameraUiEvent.MediaSaved(tempFile))
                runOnUiThread { Toast.makeText(this, "Saved JPEG", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Log.e(TAG, "Save failed", e)
            } finally {
                image.close()
                isProcessing.set(false)
                backgroundHandler?.post { updatePreview() }
            }
        }
    }

    private fun saveToPublicStorage(tempFile: File, ext: String, mimeType: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_PRO_${System.currentTimeMillis()}.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/CameraW")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let { target ->
            contentResolver.openOutputStream(target)?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun startRecordingInternal() {
        if (cameraDevice == null || isRecording) return

        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
        } catch (_: Exception) {}
        closeSession()

        val mode = viewModel.uiState.value.cameraMode
        val state = viewModel.uiState.value

        if (mode == CameraMode.PRO_VIDEO || mode == CameraMode.RAW_VIDEO) {
            if (state.saveGyroData) {
                val timestamp = System.currentTimeMillis()
                currentGcsvFile = File(cacheDir, "CameraW_${timestamp}.gcsv")
                gyroflowLogger?.start(currentGcsvFile!!)
            }
        }

        val intendedW = state.currentResolution.width
        val intendedH = state.currentResolution.height

        var encW = intendedW
        var encH = intendedH
        var sarN = 1
        var sarD = 1
        if (mode == CameraMode.PRO_VIDEO) {
            val maxRes = getMaxHevcResolution()

            if (encW > maxRes.width) {
                encW = maxRes.width
            }
            if (encH > maxRes.height) {
                encH = maxRes.height
            }

            if (encW != intendedW || encH != intendedH) {
                val num = intendedW * encH
                val den = encW * intendedH
                val gcdVal = java.math.BigInteger.valueOf(num.toLong()).gcd(java.math.BigInteger.valueOf(den.toLong())).toInt()
                sarN = num / gcdVal
                sarD = den / gcdVal
                Log.i(TAG, "Intended: ${intendedW}x${intendedH} | Encoded: ${encW}x${encH} | Applied SAR $sarN:$sarD")
            } else {
                Log.i(TAG, "Resolution ${intendedW}x${intendedH} fits HW limits. Passing 1:1.")
            }
        }

        val previewSize = optimalPreviewSizeState.value
        textureViewSurfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

        imageReaderThread = HandlerThread("ImageReaderBridge").also { it.start() }
        imageReaderHandler = Handler(imageReaderThread!!.looper)

        val config = Recorder.RecordConfig(
            width = encW,
            height = encH,
            fps = state.currentFps,
            videoBitrate = state.quality * 1_000_000,
            videoCodecMime = state.videoCodec,
            isHdr = false,
            isSdrPassthrough = false,
            isTrueHdr = (mode == CameraMode.PRO_VIDEO && state.videoFormat == 1),
            sarNum = sarN,
            sarDen = sarD,
            dynamicMetadataMode = if (state.videoFormat == 1) state.dynamicMetadataMode else 0
        )

        if (mode != CameraMode.RAW_VIDEO) {
            recorder = Recorder(this, getSharedPreferences(PREFS_NAME, MODE_PRIVATE))
            if (!recorder!!.start(config, "Crw-${System.currentTimeMillis()}")) {
                recorder = null
                return
            }
        }

        when (mode) {
            CameraMode.PRO_VIDEO -> setupProVideoPipeline(state, encW, encH, intendedW, intendedH)
            CameraMode.RAW_VIDEO -> setupRawVideoPipeline(state)
            else -> setupStandardVideoPipeline(state)
        }
    }

    private fun extractColorMatrix(chars: CameraCharacteristics): FloatArray {
        val transform: ColorSpaceTransform? = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        val matrix = FloatArray(9)
        if (transform != null) {
            val rationals = arrayOfNulls<Rational>(9)
            transform.copyElements(rationals, 0)
            for (i in 0..8) {
                matrix[i] = rationals[i]?.toFloat() ?: 0f
            }
        } else {
            matrix[0] = 1f; matrix[4] = 1f; matrix[8] = 1f
        }
        return matrix
    }
    private fun setupAudioPipeline() {
        val audioBufferSize = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            rawAudioRecord = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, 48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, audioBufferSize)
            rawAudioThread = thread(start = true) {

                rawAudioRecord?.startRecording()
                val buffer = ByteBuffer.allocateDirect(audioBufferSize)

                while (!isRecording && rawAudioThread?.isInterrupted == false) {
                    Thread.sleep(10)
                }

                while (isRecording) {
                    buffer.clear()
                    val read = rawAudioRecord?.read(buffer, audioBufferSize) ?: 0
                    if (read > 0) {
                        viewModel.onAudioDataAvailable(buffer, read, System.nanoTime() / 1000L)
                    }
                }
            }
        }
    }
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setupRawVideoPipeline(state: CameraUiState) {
        val intendedRes = state.currentResolution
        val targetFps = state.currentFps
        val previewSurface = textureViewSurface ?: run { stopRecordingInternal(); return }

        val activeW = activeArraySize?.width() ?: 4000
        val activeH = activeArraySize?.height() ?: 3000
        val bestRawSize = rawResolutions.maxByOrNull { it.width * it.height } ?: Size(activeW, activeH)

        var offsetX = Math.max(0, (bestRawSize.width - intendedRes.width) / 2)
        var offsetY = Math.max(0, (bestRawSize.height - intendedRes.height) / 2)
        offsetX = offsetX and 0xFFFFFFFE.toInt()
        offsetY = offsetY and 0xFFFFFFFE.toInt()

        rawReader = ImageReader.newInstance(
            bestRawSize.width,
            bestRawSize.height,
            ImageFormat.RAW_SENSOR,
            5,
            android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN
        )

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraDevice!!.id)

        val blackLevelArray: android.hardware.camera2.params.BlackLevelPattern? =
            characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)

        val avgBlackLevel = if (blackLevelArray != null) {
            (blackLevelArray.getOffsetForIndex(0, 0) +
                    blackLevelArray.getOffsetForIndex(1, 0) +
                    blackLevelArray.getOffsetForIndex(0, 1) +
                    blackLevelArray.getOffsetForIndex(1, 1)) / 4
        } else 64

        val whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val focalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val aperture = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f
        val cameraName = "${Build.MANUFACTURER} ${Build.MODEL}"

        val colorMatrix = extractColorMatrix(characteristics)

        val cfaInt = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0

        val result = lastPreviewResult

        val tempFile = File(getExternalFilesDir(null), "CameraWRaw_${System.currentTimeMillis()}.mlv")
        rawOutputFile = tempFile

        val hwGains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val rGain = (hwGains?.red ?: 1.0f).coerceAtLeast(0.1f)
        val bGain = (hwGains?.blue ?: 1.0f).coerceAtLeast(0.1f)
        val gGain = if (hwGains != null) ((hwGains.greenEven + hwGains.greenOdd) / 2f).coerceAtLeast(0.1f) else 1.0f
        val chars = characteristics

        fun getMatrix(key: CameraCharacteristics.Key<ColorSpaceTransform>): FloatArray {
            val transform = chars.get(key) ?: return floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
            val arr = FloatArray(9)
            for (i in 0..8) {
                val r = transform.getElement(i % 3, i / 3)
                arr[i] = r.numerator.toFloat() / r.denominator.toFloat()
            }
            return arr
        }

        val c2stFloats = getMatrix(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2) +
                getMatrix(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) +
                getMatrix(CameraCharacteristics.SENSOR_FORWARD_MATRIX2) +
                getMatrix(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1) +
                getMatrix(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)

        val blPattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val activeRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, width, height)

        val c2stInts = intArrayOf(
            chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 21,
            (chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) ?: 0).toInt(),
            blPattern?.getOffsetForIndex(0, 0) ?: 64,
            blPattern?.getOffsetForIndex(1, 0) ?: 64,
            blPattern?.getOffsetForIndex(0, 1) ?: 64,
            blPattern?.getOffsetForIndex(1, 1) ?: 64,
            activeRect.top, activeRect.left, activeRect.bottom, activeRect.right
        )

        val softwareStr = "${Build.MANUFACTURER}/${Build.MODEL}/${Build.DISPLAY}"

        viewModel.startRecording(
            filePath = tempFile.absolutePath,
            blackLevel = avgBlackLevel,
            whiteLevel = whiteLevel,
            cameraName = cameraName,
            focalLength = focalLength,
            aperture = aperture,
            colorMatrix = colorMatrix,
            rGain = rGain,
            gGain = gGain,
            bGain = bGain,
            cfa = cfaInt,
            c2stFloats = c2stFloats,
            c2stInts = c2stInts,
            softwareStr = softwareStr,
            activeW = activeW,
            activeH = activeH,
            offsetX = offsetX,
            offsetY = offsetY
        )
        rawReader!!.setOnImageAvailableListener({ reader ->
            val img = try { reader.acquireNextImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            if (!isRecording) {
                img.close()
                return@setOnImageAvailableListener
            }

            val result = lastPreviewResult ?: return@setOnImageAvailableListener
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
            val shutterNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L

            val noise = result.get(CaptureResult.SENSOR_NOISE_PROFILE)
            val noiseArray = doubleArrayOf(
                noise?.get(0)?.first ?: 0.0, noise?.get(0)?.second ?: 0.0,
                noise?.get(1)?.first ?: 0.0, noise?.get(1)?.second ?: 0.0,
                noise?.get(2)?.first ?: 0.0, noise?.get(2)?.second ?: 0.0
            )

            val lscMap = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            var lscArray: FloatArray? = null
            var lscW = 0
            var lscH = 0
            if (lscMap != null) {
                lscW = lscMap.columnCount
                lscH = lscMap.rowCount
                lscArray = FloatArray(lscW * lscH * 4)
                lscMap.copyGainFactors(lscArray, 0)
            }

            val hardwareBuffer = img.hardwareBuffer
            if (hardwareBuffer != null) {
                viewModel.processRawFrameSync(
                    hardwareBuffer,
                    img.timestamp,
                    iso,
                    shutterNs,
                    noiseArray,
                    lscArray,
                    lscW,
                    lscH,
                    img.planes[0].rowStride,
                    offsetX,
                    offsetY
                )
            } else {
                Log.e(TAG, "HardwareBuffer was null! Ensure USAGE_CPU_READ_OFTEN is set.")
            }

            img.close()
        }, imageReaderHandler)

        val rawSurface = rawReader!!.surface

        setupAudioPipeline()
        createCameraSession(
            outputs = listOf(previewSurface, rawSurface),
            stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    builder.addTarget(previewSurface)
                    builder.addTarget(rawSurface)

                    builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
                    builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / targetFps)

                    val maxShutterNs = 1_000_000_000L / targetFps
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, maxShutterNs)

                    applyManualControls(builder)

                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                    builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                    builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
                    builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)
                    builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)

                    activeRequestBuilder = builder
                    activeRequestSession = session

                    session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)

                    isRecording = true
                    recordingStartTime = System.currentTimeMillis()
                    timerHandler.post(timerRunnable)
                    viewModel.onEvent(CameraUiEvent.RecordingStarted)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    stopRecordingInternal()
                }
            },
            template = CameraDevice.TEMPLATE_RECORD,
            cameraMode = CameraMode.RAW_VIDEO,
            isRecordingSession = true
        )
    }

    private fun getMaxHevcResolution(): Size {
        var maxWidth = 0
        var maxHeight = 0
        var maxArea = 0

        try {
            val codecs = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            for (codec in codecs) {
                if (!codec.isEncoder) continue
                if (!codec.supportedTypes.contains("video/hevc")) continue

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (codec.isSoftwareOnly) continue
                } else {
                    val name = codec.name.lowercase()
                    if (name.contains("google") || name.contains("android")) continue
                }

                val caps = codec.getCapabilitiesForType("video/hevc").videoCapabilities
                val w = caps.supportedWidths.upper
                val h = caps.supportedHeights.upper

                if (w > 0 && h > 0) {
                    val area = w * h
                    if (area > maxArea) {
                        maxArea = area
                        maxWidth = w
                        maxHeight = h
                        Log.i(TAG, "Found HW HEVC Encoder: ${codec.name} with max res: ${w}x${h}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get HW HEVC caps", e)
        }

        if (maxWidth == 0 || maxHeight == 0) {
            Log.w(TAG, "Failed to detect HW HEVC, falling back to strict 4K")
            return Size(3840, 2160)
        }

        Log.i(TAG, "Final Max HEVC Hardware Resolution: ${maxWidth}x${maxHeight}")
        return Size(maxWidth, maxHeight)
    }
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setupProVideoPipeline(state: CameraUiState, encW: Int, encH: Int, intendedW: Int, intendedH: Int) {
        val rec = recorder ?: return

        val activeW = activeArraySize?.width() ?: 4000
        val activeH = activeArraySize?.height() ?: 3000
        val bestRawSize = rawResolutions.maxByOrNull { it.width * it.height } ?: Size(activeW, activeH)

        val optimalPreview = optimalPreviewSizeState.value
        textureViewSurfaceTexture?.setDefaultBufferSize(optimalPreview.width, optimalPreview.height)

        val actualHdrMode = if (state.videoFormat == 1) state.dynamicMetadataMode else 0
        vulkanHandle = vulkanBridge.nativeCreate(
            encW, encH, intendedW, intendedH,
            bestRawSize.width, bestRawSize.height, blackLevel, whiteLevel,
            actualHdrMode,
            colorFilterArrangement
        )

        vulkanBridge.nativeBindEncoderSurface(vulkanHandle, rec.encoderSurface!!)

        rawReader = ImageReader.newInstance(bestRawSize.width, bestRawSize.height, ImageFormat.RAW_SENSOR, 4)

        val XYZ_D50_TO_BT2020 = floatArrayOf(
            1.64734f, -0.39357f, -0.23599f,
            -0.68259f,  1.64758f,  0.01281f,
            0.02963f, -0.06288f,  1.25313f
        )

        fun extractTransform(transform: ColorSpaceTransform?): FloatArray {
            val matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            transform?.let {
                val rationals = arrayOfNulls<Rational>(9)
                it.copyElements(rationals, 0)
                for (i in 0..8) matrix[i] = rationals[i]?.toFloat() ?: 0f
            }
            return matrix
        }

        fun getKelvin(illuminant: Int): Float = when (illuminant) {
            1 -> 6500f; 2 -> 5000f; 3 -> 3400f; 9 -> 11000f; 10 -> 6500f
            11 -> 4800f; 12 -> 6500f; 13 -> 5000f; 14 -> 4000f; 15 -> 3000f
            17 -> 2850f; 18 -> 4800f; 19 -> 6774f; 20 -> 5500f; 21 -> 6500f
            22 -> 7500f; 23 -> 5000f; 24 -> 3200f; else -> 5500f
        }

        val fm1Array = extractTransform(forwardMatrix1)
        val fm2Array = extractTransform(forwardMatrix2)
        val ill1 = referenceIlluminant1 ?: 21
        val ill2 = referenceIlluminant2 ?: 17

        val cameraToXyz = FloatArray(9)
        var cachedMatrix = FloatArray(9)
        val wbGainsArray = FloatArray(4)
        val emptyLscArray = FloatArray(0)
        var lscBuffer = FloatArray(0)

        var lastWbTemp = -1

        fun updateInterpolatedMatrix(wbTemp: Int, outMatrix: FloatArray) {
            val t1 = getKelvin(ill1)
            val t2 = getKelvin(ill2)

            val invT = 1.0f / wbTemp.toFloat().coerceAtLeast(1000f)
            val invT1 = 1.0f / t1
            val invT2 = 1.0f / t2

            val t = if (invT1 == invT2) 0.0f else ((invT - invT1) / (invT2 - invT1)).coerceIn(0f, 1f)
            for (i in 0..8) outMatrix[i] = fm1Array[i] * (1f - t) + fm2Array[i] * t
        }

        fun updateMultipliedMatrix(a: FloatArray, b: FloatArray, outMatrix: FloatArray) {
            for (i in 0..2) {
                for (j in 0..2) {
                    var sum = 0f
                    for (k in 0..2) sum += a[i * 3 + k] * b[k * 3 + j]
                    outMatrix[i * 3 + j] = sum
                }
            }
        }

        rawReader!!.setOnImageAvailableListener({ reader ->
            val img = try { reader.acquireNextImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            if (!isRecording) {
                img.close()
                return@setOnImageAvailableListener
            }
            val hb = img.hardwareBuffer
            if (hb == null) {
                img.close()
                return@setOnImageAvailableListener
            }

            val lockedResult = lastPreviewResult
            val gains = lockedResult?.get(CaptureResult.COLOR_CORRECTION_GAINS)

            wbGainsArray[0] = gains?.red ?: 1f
            wbGainsArray[1] = gains?.greenEven ?: 1f
            wbGainsArray[2] = gains?.greenOdd ?: 1f
            wbGainsArray[3] = gains?.blue ?: 1f

            val expNs = lockedResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 16_000_000L
            val iso = lockedResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100

            val wbTemp = if (gains != null) gainsToColorTemperature(gains) else 5500
            if (abs(wbTemp - lastWbTemp) > 50) {
                updateInterpolatedMatrix(wbTemp, cameraToXyz)
                updateMultipliedMatrix(XYZ_D50_TO_BT2020, cameraToXyz, cachedMatrix)
                lastWbTemp = wbTemp
            }

            val lscMap = lockedResult?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            val lscW = lscMap?.columnCount ?: 0
            val lscH = lscMap?.rowCount ?: 0

            val lscArray = if (lscMap != null) {
                val needed = lscW * lscH * 4
                if (lscBuffer.size != needed) lscBuffer = FloatArray(needed)
                lscMap.copyGainFactors(lscBuffer, 0)
                lscBuffer
            } else emptyLscArray

            vulkanBridge.nativeProcessFrameBuffer(
                vulkanHandle, hb, img.timestamp, wbGainsArray, cachedMatrix, expNs, iso, -1, lscArray, lscW, lscH
            )

            hb.close()
            img.close()
        }, imageReaderHandler)

        val rawSurface = rawReader!!.surface

        vulkanBridge.metadataListener = object : VulkanHdrBridge.HdrMetadataListener {
            override fun onDynamicMetadataReady(metadata: ByteArray, dvMin: Int, dvMax: Int, dvAvg: Int, timestampNs: Long) {
                if (isRecording) {
                    recorder?.updateDynamicMetadata(metadata, dvMin, dvMax, dvAvg, timestampNs)
                }
            }
        }

        createCameraSession(
            outputs = listOf(textureViewSurface!!, rawSurface),
            stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    builder.addTarget(textureViewSurface!!)
                    builder.addTarget(rawSurface)

                    builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)

                    val targetFps = state.currentFps
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
                    builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / targetFps)
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                    builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                    builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)
                    builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)

                    applyManualControls(builder)

                    if (hasUltraHighRes) builder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)

                    activeRequestBuilder = builder
                    activeRequestSession = session

                    session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)

                    isRecording = true
                    recordingStartTime = System.currentTimeMillis()
                    timerHandler.post(timerRunnable)
                    viewModel.onEvent(CameraUiEvent.RecordingStarted)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    stopRecordingInternal()
                }
            },
            template = CameraDevice.TEMPLATE_RECORD,
            cameraMode = CameraMode.PRO_VIDEO,
            isRecordingSession = true
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun stopRecordingInternal() {
        if (!isRecording) return
        isRecording = false
        viewModel.onEvent(CameraUiEvent.RecordingStopped("00:00"))
        timerHandler.removeCallbacks(timerRunnable)

        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
        } catch (e: Exception) {}

        try {
            rawReader?.setOnImageAvailableListener(null, null)
            yuvReader?.setOnImageAvailableListener(null, null)
            yuvReader?.close()
        } catch (_: Exception) {}

        val readerToClose = rawReader
        rawReader = null
        yuvReader = null

        imageReaderThread?.quitSafely()
        try { imageReaderThread?.join(500) } catch (_: Exception) {}
        imageReaderThread = null
        imageReaderHandler = null

        val videoFile = recorder?.lastVideoFile
        try { recorder?.stop() } catch (_: Exception) {}
        recorder = null

        if (vulkanHandle != 0L) {
            vulkanBridge.nativeDestroy(vulkanHandle)
            vulkanBridge.metadataListener = null
            vulkanHandle = 0L
        }

        val fileToSave = rawOutputFile
        rawOutputFile = null

        try {
            rawAudioRecord?.stop()
            rawAudioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio", e)
        }

        rawAudioThread?.interrupt()
        rawAudioThread = null
        rawAudioRecord = null

        gyroflowLogger?.stop()
        currentGcsvFile?.let { gcsvFile ->
            if (gcsvFile.exists()) {
                saveGcsvToMediaStore(gcsvFile)
            }
            currentGcsvFile = null
        }

        viewModel.stopRecording {
            thread(start = true, name = "MLVSaver") {
                fileToSave?.let { file ->
                    if (file.exists()) {
                        try {
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/CameraW")
                                }
                            }
                            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                            uri?.let {
                                contentResolver.openOutputStream(it)?.use { out ->
                                    file.inputStream().use { input -> input.copyTo(out) }
                                }
                                runOnUiThread { viewModel.onEvent(CameraUiEvent.MediaSaved(file)) }
                            }
                            file.delete()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save RAW file", e)
                        }
                    }
                }
                videoFile?.let { file -> runOnUiThread { viewModel.onEvent(CameraUiEvent.MediaSaved(file)) } }
            }
        }

        try { readerToClose?.close() } catch (_: Exception) {}
        backgroundHandler?.postDelayed({
            if (!isFinishing && cameraDevice != null && !isRecording) startPreview()
        }, 500)

        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null

        timerHandler.removeCallbacks(timerRunnable)
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
                val timeString = String.format("%d:%02d", elapsed / 60, elapsed % 60)
                viewModel.updateRecordingTime(timeString)
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun cycleFps() {
        if (isRecording) return
        viewModel.cycleFps()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        viewModel.saveCurrentState(prefs)
        restartCameraSession()
    }

    private fun toggleFlash() {
        if (!hasFlash) return
        viewModel.toggleFlash()
        if (!isRecording) updatePreview() else updateFlashDuringRecording()
    }

    private fun updateFlashDuringRecording() {
        val session = captureSession ?: return
        val cam = cameraDevice ?: return
        val state = viewModel.uiState.value
        try {
            val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(textureViewSurface!!)
                when (state.cameraMode) {
                    CameraMode.PRO_VIDEO, CameraMode.RAW_VIDEO -> addTarget(rawReader!!.surface)
                    else -> {}
                }
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, state.selectedFpsRange)
                applyManualControls(this)
                if (!hasOis) set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
            }
            activeRequestBuilder = builder
            activeRequestSession = session

            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "updateFlashDuringRecording failed", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun restartCameraSession() {
        if (cameraDevice == null || textureViewSurfaceTexture == null) return
        val state = viewModel.uiState.value
        var targetRes = state.currentResolution

        val optimalSize = getOptimalPreviewSize(targetRes)
        closeSession()
        optimalPreviewSizeState.value = optimalSize
        textureViewSurfaceTexture?.setDefaultBufferSize(optimalSize.width, optimalSize.height)

        textureViewSurface?.release()
        textureViewSurface = Surface(textureViewSurfaceTexture)
        previewSurface = textureViewSurface

        startPreview()
    }

    private fun closeSession() {
        try { captureSession?.close() } catch (e: Exception) {} finally { captureSession = null }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)
            closeSession()
            histogramReader?.close(); histogramReader = null
            histogramThread?.quitSafely()
            try { histogramThread?.join() } catch (_: InterruptedException) {}
            histogramThread = null
            histogramHandler = null

            rawReader?.close(); rawReader = null
            jpegReader?.close(); jpegReader = null
            yuvReader?.close(); yuvReader = null

            activeRawImages.set(0)

            synchronized(capturedImages) {
                capturedImages.forEach { it.close() }
                capturedImages.clear()
                capturedResults.clear()
            }

            cameraDevice?.close(); cameraDevice = null
            cameraOpenCloseLock.release()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while closing camera", e)
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    private fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun Handler.asExecutor(): Executor = Executor { command -> post(command) }

    private fun saveGcsvToMediaStore(gcsvFile: File) {
        if (!gcsvFile.exists()) return
        try {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, gcsvFile.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CameraW")
                }
            }

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } else {
                resolver.insert(MediaStore.Files.getContentUri("external"), values)
            }

            uri?.let { targetUri ->
                resolver.openOutputStream(targetUri)?.use { out ->
                    gcsvFile.inputStream().use { input -> input.copyTo(out) }
                }
                gcsvFile.delete()
                Log.i(TAG, "Saved Gyroflow data to Download/CameraW/${gcsvFile.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save gcsv", e)
        }
    }
}