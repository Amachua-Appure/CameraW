package com.cameraw

import android.content.SharedPreferences
import android.util.Range
import android.util.Size
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.hardware.HardwareBuffer
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer

private val CINEMATIC_RESOLUTIONS = listOf(
    Size(16320, 12240),
    Size(8192, 6144),
    Size(8160, 6120),
    Size(8000, 6000),
    Size(8064, 6048),
    Size(5712, 4284),
    Size(4096, 3072),
    Size(4096, 2160),
    Size(4096, 1716),
    Size(4000,3000),
    Size(4000, 2104),
    Size(4000, 2000),
    Size(4000, 1818),
    Size(4000, 1673),
    Size(3996, 2160),
    Size(3840, 2176),
    Size(3840, 2160),
    Size(3840, 2074),
    Size(3840, 2020),
    Size(3840, 1920),
    Size(3840, 1634),
    Size(3840, 1608),
    Size(3840, 1480),
    Size(3840, 1392),
    Size(3482, 2176),
    Size(3110, 2176),
    Size(2560, 1792),
    Size(2560, 1600),
    Size(2560, 1440),
    Size(2560, 1384),
    Size(2560, 1280),
    Size(2560, 1088),
    Size(2400, 1080),
    Size(2048,858),
    Size(2176, 2176),
    Size(1998, 1080),
    Size(1920, 1344),
    Size(1920, 1200),
    Size(1920, 1080),
    Size(1920, 1038),
    Size(1920, 960),
    Size(1920, 816)
)
private val FRONT_CINEMATIC_RESOLUTIONS = listOf(
    Size(4096, 3072),
    Size(4096, 2160),
    Size(4096, 1716),
    Size(4000,3000),
    Size(4000, 2104),
    Size(4000, 2000),
    Size(4000, 1818),
    Size(4000, 1673),
    Size(3996, 2160),
    Size(3840, 2176),
    Size(3840, 2160),
    Size(3840, 2074),
    Size(3840, 2020),
    Size(3840, 1920),
    Size(3840, 1634),
    Size(3840, 1608),
    Size(3840, 1480),
    Size(3840, 1392),
    Size(3482, 2176),
    Size(3110, 2176),
    Size(2560, 1792),
    Size(2560, 1600),
    Size(2560, 1440),
    Size(2560, 1384),
    Size(2560, 1280),
    Size(2560, 1088),
    Size(2400, 1080),
    Size(2320, 1744),
    Size(2320, 1312),
    Size(2320, 1252),
    Size(2320, 1222),
    Size(2320, 1160),
    Size(2320, 986),
    Size(2320, 971),
    Size(2160,2160),
    Size(2060, 1440),
    Size(1920, 1344),
    Size(1920, 1200),
    Size(1920, 1080),
    Size(1920, 960),
    Size(1600, 900),
    Size(1440, 1080)
)

private val TRUE_HDR_FPS_OPTIONS = listOf(24, 25, 30, 48, 50, 60, 90).map { Range(it, it) }.distinct()

enum class FocusState { IDLE, SCANNING, FOCUSED, FAILED }

sealed class CameraUiEvent {
    object RecordButtonClicked : CameraUiEvent()
    object ResolutionClicked : CameraUiEvent()
    object FpsClicked : CameraUiEvent()
    object ToggleSaveGyroData : CameraUiEvent()
    object FlashToggled : CameraUiEvent()
    object IsoClicked : CameraUiEvent()
    data class SetVideoFormat(val format: Int) : CameraUiEvent()
    object SettingsClicked : CameraUiEvent()
    object ToggleSettings : CameraUiEvent()
    object PermissionsGranted : CameraUiEvent()
    object RecordingStarted : CameraUiEvent()
    data class RecordingStopped(val duration: String) : CameraUiEvent()
    object SessionRestart : CameraUiEvent()
    data class SetVideoCodec(val codec: String) : CameraUiEvent()
    data class SetAudioCodec(val codec: String) : CameraUiEvent()
    data class SetQuality(val quality: Int) : CameraUiEvent()
    data class SetNoiseReduction(val mode: Int) : CameraUiEvent()
    data class SetISO(val iso: Int) : CameraUiEvent()
    object ShowResolutionDialog : CameraUiEvent()
    object HideResolutionDialog : CameraUiEvent()
    data class SelectResolution(val size: Size) : CameraUiEvent()
    object ToggleProVideoDenoise : CameraUiEvent()
    data class SetCaptureMode(val mode: CameraMode) : CameraUiEvent()
    data class SetBurstFrames(val count: Int) : CameraUiEvent()
    data class SetPngCompression(val level: Int) : CameraUiEvent()
    data class SetResolution(val resolution: Size) : CameraUiEvent()
    data class SetFps(val fps: Int) : CameraUiEvent()
    data class SetPhotoFormat(val bitDepth: Int) : CameraUiEvent()
    data class SetZoom(val ratio: Float) : CameraUiEvent()
    data class UpdateMaxZoom(val maxZoom: Float) : CameraUiEvent()
    data class MediaSaved(val file: File) : CameraUiEvent()
    data class TapToFocus(val point: Offset) : CameraUiEvent()
    object LockAeAf : CameraUiEvent()
    object UnlockAeAf : CameraUiEvent()
    data class SetManualIso(val iso: Int?) : CameraUiEvent()
    data class SetShutterSpeed(val nano: Long?) : CameraUiEvent()
    data class SetFocusDistance(val distance: Float?) : CameraUiEvent()
    data class SetWhiteBalance(val temp: Int?) : CameraUiEvent()
    data class UpdateLiveMetadata(val iso: Int, val shutter: Long, val focusDist: Float, val wb: Int) : CameraUiEvent()
    data class UpdateHistogram(val data: FloatArray) : CameraUiEvent()
    object ToggleCamera : CameraUiEvent()
    data class SetHasFlash(val hasFlash: Boolean) : CameraUiEvent()
    data class SetCameraId(val id: String) : CameraUiEvent()
    data class SetDynamicMetadataMode(val mode: Int) : CameraUiEvent()

    data class UpdateFocusState(val state: FocusState) : CameraUiEvent()
    data class SetExposureCompensation(val step: Int) : CameraUiEvent()
    object ResumeContinuousFocus : CameraUiEvent()
}

data class CameraUiState(
    val saveGyroData: Boolean = true,
    val isRecording: Boolean = false,
    val recordingDuration: String = "00:00",
    val currentResolution: Size = Size(1920, 1080),
    val currentFps: Int = 30,
    val selectedFpsRange: Range<Int> = Range(30, 30),
    val flashEnabled: Boolean = false,
    val useManualISO: Boolean = false,
    val currentISO: Int = -1,
    val isoValue: String = "Auto",
    val showSettingsSheet: Boolean = false,
    val availableResolutions: List<Size> = emptyList(),
    val showResolutionDialog: Boolean = false,
    val videoCodec: String = "video/hevc",
    val audioCodec: String = "0",
    val quality: Int = 100,
    val noiseReductionMode: Int = 0,
    val burstFrames: Int = 5,
    val pngCompression: Int = 1,
    val photoBitDepth: Int = 16,
    val zoomRatio: Float = 1f,
    val maxZoomRatio: Float = 10f,
    val maxZoom: Float = 1f,
    val cameraMode: CameraMode = CameraMode.PRO_VIDEO,
    val videoFormat: Int = 1,
    val proVideoDenoiseEnabled: Boolean = false,
    val isProcessingPhoto: Boolean = false,
    val lastCapturedFile: File? = null,
    val focusPoint: Offset? = null,
    val isAeAfLocked: Boolean = false,
    val showFocusCircle: Boolean = false,
    val manualIso: Int? = null,
    val manualShutterNano: Long? = null,
    val manualFocusDist: Float? = null,
    val manualWbTemp: Int? = null,
    val activeIso: Int = 0,
    val activeShutter: Long = 0,
    val activeFocusDist: Float = 0f,
    val activeWb: Int = 5500,
    val histogramData: FloatArray? = null,
    val minIso: Int = 100,
    val maxIso: Int = 3200,
    val minShutter: Long = 1_000_000L,
    val maxShutter: Long = 1_000_000_000L,
    val minFocusDist: Float = 0f,
    val maxFocusDist: Float = 10f,
    val validShutterSpeeds: List<Long> = emptyList(),
    val cameraId: String = "0",
    val hasHardwareFlash: Boolean = true,
    val dynamicMetadataMode: Int = 1,

    val focusState: FocusState = FocusState.IDLE,
    val exposureCompensation: Int = 0,
)

class CameraViewModel : ViewModel() {

    init {
        System.loadLibrary("cameraw_isp")
    }

    var mlvContextPtr: Long = 0L
    private external fun initMlvWriter(
        filePath: String,
        width: Int,
        height: Int,
        fpsNum: Int,
        fpsDen: Int,
        blackLevel: Int,
        whiteLevel: Int,
        cameraName: String,
        focalLength: Float,
        aperture: Float,
        colorMatrix: FloatArray,
        rGain: Float,
        gGain: Float,
        bGain: Float,
        cfa: Int,
        c2stFloats: FloatArray,
        c2stInts: IntArray,
        softwareStr: String,
        activeW: Int,
        activeH: Int,
        offsetX: Int,
        offsetY: Int
    ): Long

    private external fun nativeWriteVideoFrameWithMetadata(
        contextPtr: Long,
        buffer: HardwareBuffer,
        isLeftShifted: Boolean,
        timestampUs: Long,
        iso: Int,
        shutterNs: Long,
        noise: DoubleArray,
        lsc: FloatArray?,
        lscW: Int,
        lscH: Int,
        rowStrideBytes: Int,
        offsetX: Int,
        offsetY: Int
    )

    private external fun writeAudioFrame(
        contextPtr: Long,
        pcmBuffer: ByteBuffer,
        size: Int,
        timestampUs: Long
    )

    private external fun closeMlvWriter(contextPtr: Long)

    fun startRecording(
        filePath: String,
        blackLevel: Int,
        whiteLevel: Int,
        cameraName: String,
        focalLength: Float,
        aperture: Float,
        colorMatrix: FloatArray,
        rGain: Float,
        gGain: Float,
        bGain: Float,
        cfa: Int,
        c2stFloats: FloatArray,
        c2stInts: IntArray,
        softwareStr: String,
        activeW: Int,
        activeH: Int,
        offsetX: Int,
        offsetY: Int
    ) {
        val state = _uiState.value
        val width = state.currentResolution.width
        val height = state.currentResolution.height
        val fpsNum = state.currentFps * 1000
        val fpsDen = 1000

        mlvContextPtr = initMlvWriter(
            filePath, width, height, fpsNum, fpsDen, blackLevel, whiteLevel,
            cameraName, focalLength, aperture, colorMatrix,
            rGain, gGain, bGain, cfa,
            c2stFloats, c2stInts, softwareStr,
            activeW, activeH, offsetX, offsetY
        )
    }

    fun processRawFrameSync(
        buffer: HardwareBuffer,
        timestampUs: Long,
        iso: Int,
        shutterNs: Long,
        noise: DoubleArray,
        lsc: FloatArray?,
        lscW: Int,
        lscH: Int,
        rowStrideBytes: Int,
        offsetX: Int,
        offsetY: Int
    ) {
        if (mlvContextPtr != 0L) {
            nativeWriteVideoFrameWithMetadata(
                mlvContextPtr,
                buffer,
                false,
                timestampUs,
                iso,
                shutterNs,
                noise,
                lsc,
                lscW,
                lscH,
                rowStrideBytes,
                offsetX,
                offsetY
            )
        }
    }

    fun onAudioDataAvailable(pcmBuffer: ByteBuffer, size: Int, timestampUs: Long) {
        if (mlvContextPtr != 0L) {
            writeAudioFrame(mlvContextPtr, pcmBuffer, size, timestampUs)
        }
    }

    @Synchronized
    fun stopRecording(onComplete: () -> Unit) {
        if (mlvContextPtr != 0L) {
            val ptrToClose = mlvContextPtr
            mlvContextPtr = 0

            viewModelScope.launch(Dispatchers.IO) {
                closeMlvWriter(ptrToClose)
                launch(Dispatchers.Main) {
                    onComplete()
                }
            }
        } else {
            onComplete()
        }
    }

    companion object {
        var savedPreferences: Map<String, String> = emptyMap()
    }

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var resetFocusJob: kotlinx.coroutines.Job? = null

    fun setZoom(zoom: Float) {
        val clampedZoom = zoom.coerceIn(1f, _uiState.value.maxZoomRatio)
        _uiState.update { it.copy(zoomRatio = clampedZoom) }
    }
    private var photoResolutions: List<Size> = emptyList()
    private var jpegResolutions: List<Size> = emptyList()
    private var trueHdrFpsRanges: List<Range<Int>> = emptyList()
    private var rawResolutions: List<Size> = emptyList()

    private var lastTrueHdrRes: Size? = null
    private var lastTrueHdrRange: Range<Int>? = null
    private var lastPhotoRes: Size? = null
    private var lastRawRes: Size? = null

    private fun filterTo4K(sizes: List<Size>): List<Size> =
        sizes.filter { it.width <= 3840 && it.height <= 2160 }

    fun initializeCameraModes(
        rawRes: List<Size>,
        jpegRes: List<Size>,
        maxRawFps: Int,
        prefs: SharedPreferences?
    ) {
        photoResolutions = rawRes
        jpegResolutions = jpegRes
        rawResolutions = rawRes

        trueHdrFpsRanges = TRUE_HDR_FPS_OPTIONS.filter { it.upper <= maxRawFps }
        if (trueHdrFpsRanges.isEmpty()) trueHdrFpsRanges = listOf(Range(24, 24))

        val id = _uiState.value.cameraId

        val savedModeIdx = prefs?.getInt("last_camera_mode_idx_$id", 0) ?: 0
        val savedMode = CameraMode.values().getOrElse(savedModeIdx) { CameraMode.PRO_VIDEO }

        val photoW = prefs?.getInt("photo_width_$id", -1) ?: -1
        val photoH = prefs?.getInt("photo_height_$id", -1) ?: -1
        if (photoW != -1 && photoH != -1) {
            lastPhotoRes = Size(photoW, photoH)
        } else {
            lastPhotoRes = rawRes.maxByOrNull { it.width * it.height }
                ?: jpegRes.maxByOrNull { it.width * it.height }
        }

        val trueHdrW = prefs?.getInt("true_hdr_width_$id", -1) ?: -1
        val trueHdrH = prefs?.getInt("true_hdr_height_$id", -1) ?: -1
        if (trueHdrW != -1 && trueHdrH != -1) {
            lastTrueHdrRes = Size(trueHdrW, trueHdrH)
            lastTrueHdrRange = prefs?.getInt("true_hdr_fps_max_$id", -1)?.let { Range(it, it) }
        }

        val rawW = prefs?.getInt("raw_width_$id", -1) ?: -1
        val rawH = prefs?.getInt("raw_height_$id", -1) ?: -1
        if (rawW != -1 && rawH != -1) {
            lastRawRes = Size(rawW, rawH)
        }

        val videoCodec = savedPreferences["videoCodec"] ?: prefs?.getString("video_codec", "video/hevc") ?: "video/hevc"
        val audioCodec = savedPreferences["audioCodec"] ?: prefs?.getString("audio_codec", "0") ?: "0"
        val quality = (savedPreferences["quality"]?.toIntOrNull() ?: prefs?.getInt("quality", 100) ?: 100).coerceIn(40, 600)
        val noiseReductionMode = savedPreferences["noiseReductionMode"]?.toIntOrNull() ?: prefs?.getInt("noise_reduction_mode", 0) ?: 0
        val burstFrames = prefs?.getInt("burst_frames", 5)?.coerceIn(1, 12) ?: 5
        val pngCompression = prefs?.getInt("png_compression", 1)?.coerceIn(0, 9) ?: 1
        val photoBitDepth = prefs?.getInt("photo_bit_depth", 16) ?: 16
        val dynamicMetadataMode = prefs?.getInt("dynamic_metadata_mode", 1) ?: 1
        val videoFormat = prefs?.getInt("video_format", 1) ?: 1
        val saveGyroData = prefs?.getBoolean("save_gyro_data", true) ?: true

        _uiState.update {
            it.copy(
                cameraMode = savedMode,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                quality = quality,
                noiseReductionMode = noiseReductionMode,
                burstFrames = burstFrames,
                pngCompression = pngCompression,
                photoBitDepth = photoBitDepth,
                dynamicMetadataMode = dynamicMetadataMode,
                videoFormat = videoFormat,
                saveGyroData = saveGyroData
            )
        }

        if (lastTrueHdrRes == null) lastTrueHdrRes = CINEMATIC_RESOLUTIONS.firstOrNull()
        if (lastRawRes == null) lastRawRes = rawResolutions.firstOrNull()

        refreshModeSettings()
    }

    fun onEvent(event: CameraUiEvent) {
        when (event) {
            is CameraUiEvent.SetVideoFormat -> {
                _uiState.update {
                    val newCodec = if (event.format == 1 && it.videoCodec == "video/avc") "video/hevc" else it.videoCodec
                    it.copy(videoFormat = event.format, videoCodec = newCodec)
                }
            }
            is CameraUiEvent.SetCaptureMode -> {
                if (_uiState.value.cameraMode != event.mode) {
                    saveLastForCurrentMode()
                    _uiState.update { it.copy(cameraMode = event.mode) }
                    refreshModeSettings()
                }
            }
            is CameraUiEvent.SetVideoCodec -> _uiState.update { it.copy(videoCodec = event.codec) }
            is CameraUiEvent.SetAudioCodec -> _uiState.update { it.copy(audioCodec = event.codec) }
            is CameraUiEvent.SetQuality -> _uiState.update { it.copy(quality = event.quality) }
            is CameraUiEvent.ToggleProVideoDenoise -> {
                _uiState.update { it.copy(proVideoDenoiseEnabled = !it.proVideoDenoiseEnabled) }
            }
            is CameraUiEvent.SetNoiseReduction -> _uiState.update { it.copy(noiseReductionMode = event.mode) }
            is CameraUiEvent.MediaSaved -> _uiState.update { it.copy(lastCapturedFile = event.file) }
            is CameraUiEvent.SetResolution -> _uiState.update { it.copy(currentResolution = event.resolution) }
            is CameraUiEvent.SetFps -> _uiState.update { it.copy(currentFps = event.fps) }
            is CameraUiEvent.SetBurstFrames -> _uiState.update { it.copy(burstFrames = event.count) }
            is CameraUiEvent.SetPngCompression -> _uiState.update { it.copy(pngCompression = event.level) }
            is CameraUiEvent.SetPhotoFormat -> {
                _uiState.update { it.copy(photoBitDepth = event.bitDepth) }
                refreshModeSettings()
            }
            is CameraUiEvent.SetZoom -> {
                val clampedZoom = event.ratio.coerceIn(1f, _uiState.value.maxZoom)
                _uiState.update { it.copy(zoomRatio = clampedZoom) }
            }
            is CameraUiEvent.UpdateMaxZoom -> _uiState.update { it.copy(maxZoom = event.maxZoom) }
            is CameraUiEvent.SetISO -> {
                val useManual = event.iso != -1
                _uiState.update {
                    it.copy(
                        useManualISO = useManual,
                        currentISO = event.iso,
                        isoValue = if (useManual) "ISO ${event.iso}" else "Auto"
                    )
                }
            }
            is CameraUiEvent.ToggleSaveGyroData -> {
                _uiState.update { it.copy(saveGyroData = !it.saveGyroData) }
            }
            is CameraUiEvent.ToggleSettings -> _uiState.update { it.copy(showSettingsSheet = !it.showSettingsSheet) }
            is CameraUiEvent.RecordingStarted -> _uiState.update { it.copy(isRecording = true) }
            is CameraUiEvent.RecordingStopped -> _uiState.update {
                it.copy(
                    isRecording = false,
                    recordingDuration = "00:00"
                )
            }
            is CameraUiEvent.ShowResolutionDialog -> _uiState.update { it.copy(showResolutionDialog = true) }
            is CameraUiEvent.HideResolutionDialog -> _uiState.update { it.copy(showResolutionDialog = false) }
            is CameraUiEvent.SelectResolution -> selectResolution(event.size)
            is CameraUiEvent.TapToFocus -> {
                _uiState.update {
                    it.copy(
                        focusPoint = event.point,
                        showFocusCircle = true,
                        isAeAfLocked = false,
                        focusState = FocusState.SCANNING
                    )
                }
                resetFocusJob?.cancel()
                resetFocusJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(4000)
                    if (!_uiState.value.isAeAfLocked) {
                        onEvent(CameraUiEvent.ResumeContinuousFocus)
                    }
                }
            }
            is CameraUiEvent.LockAeAf -> _uiState.update {
                it.copy(
                    isAeAfLocked = true,
                    showFocusCircle = true
                )
            }
            is CameraUiEvent.UnlockAeAf -> _uiState.update {
                it.copy(
                    isAeAfLocked = false,
                    focusPoint = null
                )
            }
            is CameraUiEvent.SetManualIso -> _uiState.update { it.copy(manualIso = event.iso) }
            is CameraUiEvent.SetShutterSpeed -> _uiState.update { it.copy(manualShutterNano = event.nano) }
            is CameraUiEvent.SetFocusDistance -> _uiState.update { it.copy(manualFocusDist = event.distance) }
            is CameraUiEvent.SetWhiteBalance -> _uiState.update { it.copy(manualWbTemp = event.temp) }
            is CameraUiEvent.UpdateLiveMetadata -> _uiState.update {
                it.copy(
                    activeIso = event.iso,
                    activeShutter = event.shutter,
                    activeFocusDist = event.focusDist,
                    activeWb = event.wb
                )
            }
            is CameraUiEvent.UpdateHistogram -> _uiState.update { it.copy(histogramData = event.data) }
            is CameraUiEvent.ToggleCamera -> {
                val nextCamera = if (_uiState.value.cameraId == "0") "1" else "0"
                _uiState.update { it.copy(cameraId = nextCamera, flashEnabled = false) }
            }
            is CameraUiEvent.SetHasFlash -> _uiState.update { it.copy(hasHardwareFlash = event.hasFlash) }
            is CameraUiEvent.SetCameraId -> _uiState.update {
                it.copy(
                    cameraId = event.id,
                    flashEnabled = false
                )
            }
            is CameraUiEvent.SetDynamicMetadataMode -> {
                _uiState.update { it.copy(dynamicMetadataMode = event.mode) }
            }

            is CameraUiEvent.UpdateFocusState -> _uiState.update { it.copy(focusState = event.state) }
            is CameraUiEvent.SetExposureCompensation -> {
                _uiState.update { it.copy(exposureCompensation = event.step) }
                if (!_uiState.value.isAeAfLocked) {
                    resetFocusJob?.cancel()
                    resetFocusJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(4000)
                        if (!_uiState.value.isAeAfLocked) {
                            onEvent(CameraUiEvent.ResumeContinuousFocus)
                        }
                    }
                }
            }
            is CameraUiEvent.ResumeContinuousFocus -> _uiState.update {
                it.copy(focusPoint = null, showFocusCircle = false, focusState = FocusState.IDLE)
            }

            else -> {}
        }
    }

    private fun refreshModeSettings() {
        val mode = _uiState.value.cameraMode
        when (mode) {
            CameraMode.PRO_VIDEO -> {
                if (photoResolutions.isEmpty()) {
                    _uiState.update { it.copy(cameraMode = CameraMode.PHOTO) }
                    refreshModeSettings()
                    return
                }

                val maxSensorWidth = photoResolutions.maxOfOrNull { it.width } ?: 4000
                val maxSensorHeight = photoResolutions.maxOfOrNull { it.height } ?: 3000

                val availableSizes = if (_uiState.value.cameraId == "1") {
                    FRONT_CINEMATIC_RESOLUTIONS
                } else {
                    CINEMATIC_RESOLUTIONS
                }.filter { size ->
                    size.width <= maxSensorWidth && size.height <= maxSensorHeight
                }.ifEmpty {
                    listOf(Size(1920, 1080))
                }

                val res = lastTrueHdrRes?.takeIf { availableSizes.contains(it) } ?: availableSizes.firstOrNull() ?: Size(3840, 2160)
                val range = lastTrueHdrRange?.takeIf { trueHdrFpsRanges.contains(it) } ?: trueHdrFpsRanges.maxByOrNull { it.upper } ?: Range(30, 30)

                _uiState.update {
                    it.copy(
                        availableResolutions = availableSizes,
                        currentResolution = res,
                        selectedFpsRange = range,
                        currentFps = range.upper
                    )
                }
            }
            CameraMode.RAW_VIDEO -> {
                if (rawResolutions.isEmpty()) {
                    _uiState.update { it.copy(cameraMode = CameraMode.PRO_VIDEO) }
                    refreshModeSettings()
                    return
                }

                val maxSensorWidth = rawResolutions.maxOfOrNull { it.width } ?: 4000
                val maxSensorHeight = rawResolutions.maxOfOrNull { it.height } ?: 3000

                val availableSizes = if (_uiState.value.cameraId == "1") {
                    FRONT_CINEMATIC_RESOLUTIONS
                } else {
                    CINEMATIC_RESOLUTIONS
                }.filter { size ->
                    size.width <= maxSensorWidth && size.height <= maxSensorHeight
                }.ifEmpty {
                    listOf(Size(1920, 1080))
                }

                val res = lastRawRes?.takeIf { availableSizes.contains(it) } ?: availableSizes.firstOrNull() ?: Size(1920, 1080)
                val range = lastTrueHdrRange?.takeIf { trueHdrFpsRanges.contains(it) } ?: trueHdrFpsRanges.maxByOrNull { it.upper } ?: Range(24, 24)
                _uiState.update {
                    it.copy(
                        availableResolutions = availableSizes,
                        currentResolution = res,
                        selectedFpsRange = range,
                        currentFps = range.upper
                    )
                }
            }
            CameraMode.PHOTO -> {
                var bitDepth = _uiState.value.photoBitDepth
                var resList = if (bitDepth == 8) jpegResolutions else photoResolutions

                if (bitDepth == 16 && resList.isEmpty()) {
                    bitDepth = 8
                    resList = jpegResolutions
                    _uiState.update { it.copy(photoBitDepth = 8) }
                }

                val res = lastPhotoRes?.takeIf { resList.contains(it) } ?: resList.firstOrNull() ?: Size(1920, 1080)
                _uiState.update {
                    it.copy(
                        availableResolutions = resList,
                        currentResolution = res,
                        selectedFpsRange = Range(0, 0),
                        currentFps = 0
                    )
                }
            }
        }
        refreshShutterList()
    }

    fun cycleFps() {
        val state = _uiState.value
        when (state.cameraMode) {
            CameraMode.PRO_VIDEO, CameraMode.RAW_VIDEO -> {
                val sorted = trueHdrFpsRanges.sortedBy { it.upper }
                val next = sorted[((sorted.indexOf(state.selectedFpsRange).takeIf { it != -1 } ?: 0) + 1) % sorted.size]
                _uiState.update { it.copy(selectedFpsRange = next, currentFps = next.upper) }
            }
            else -> return
        }
        refreshShutterList()
    }

    private fun selectResolution(size: Size) {
        when (_uiState.value.cameraMode) {
            CameraMode.PRO_VIDEO -> lastTrueHdrRes = size
            CameraMode.PHOTO -> lastPhotoRes = size
            CameraMode.RAW_VIDEO -> lastRawRes = size
        }
        _uiState.update { it.copy(currentResolution = size, showResolutionDialog = false) }
        refreshModeSettings()
    }

    fun saveCurrentState(prefs: SharedPreferences) {
        val state = _uiState.value
        val id = state.cameraId
        with(prefs.edit()) {
            putInt("last_camera_mode_idx_$id", state.cameraMode.ordinal)

            when (state.cameraMode) {
                CameraMode.PRO_VIDEO -> {
                    putInt("true_hdr_width_$id", state.currentResolution.width)
                    putInt("true_hdr_height_$id", state.currentResolution.height)
                    putInt("true_hdr_fps_max_$id", state.selectedFpsRange.upper)
                }
                CameraMode.PHOTO -> {
                    putInt("photo_width_$id", state.currentResolution.width)
                    putInt("photo_height_$id", state.currentResolution.height)
                }
                CameraMode.RAW_VIDEO -> {
                    putInt("raw_width_$id", state.currentResolution.width)
                    putInt("raw_height_$id", state.currentResolution.height)
                }
            }
            putString("video_codec", state.videoCodec)
            putString("audio_codec", state.audioCodec)
            putInt("quality", state.quality)
            putInt("noise_reduction_mode", state.noiseReductionMode)
            putInt("burst_frames", state.burstFrames)
            putInt("png_compression", state.pngCompression)
            putInt("photo_bit_depth", state.photoBitDepth)
            putString("camera_id", state.cameraId)
            putInt("dynamic_metadata_mode", state.dynamicMetadataMode)
            putInt("video_format", state.videoFormat)
            putBoolean("save_gyro_data", state.saveGyroData)
            apply()
        }
    }

    private fun saveLastForCurrentMode() {
        val state = _uiState.value
        when (state.cameraMode) {
            CameraMode.PRO_VIDEO -> {
                lastTrueHdrRes = state.currentResolution; lastTrueHdrRange = state.selectedFpsRange
            }
            CameraMode.PHOTO -> {
                lastPhotoRes = state.currentResolution
            }
            CameraMode.RAW_VIDEO -> {
                lastRawRes = state.currentResolution
            }
        }
    }

    fun toggleFlash() = _uiState.update { it.copy(flashEnabled = !it.flashEnabled) }
    fun updateRecordingTime(time: String) = _uiState.update { it.copy(recordingDuration = time) }
    fun showResolutionDialog() = _uiState.update { it.copy(showResolutionDialog = true) }
    fun setProcessing(isProcessing: Boolean) = _uiState.update { it.copy(isProcessingPhoto = isProcessing) }

    fun updateCapabilities(minI: Int, maxI: Int, minS: Long, maxS: Long, minF: Float, maxF: Float) {
        _uiState.update {
            it.copy(
                minIso = minI,
                maxIso = maxI,
                minShutter = minS,
                maxShutter = maxS,
                minFocusDist = minF,
                maxFocusDist = maxF
            )
        }
        refreshShutterList()
    }

    private fun refreshShutterList() {
        val state = _uiState.value
        val safeFps = if (state.currentFps > 0) state.currentFps else 30
        _uiState.update {
            it.copy(
                validShutterSpeeds = CameraProUtils.getDynamicShutterList(
                    safeFps,
                    state.minShutter,
                    state.maxShutter
                )
            )
        }
    }
}