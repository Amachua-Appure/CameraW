package com.cameraw

import android.content.Context
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private fun formatResolutionWithAspectRatio(size: Size): String {
    fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    val gcd = gcd(size.width, size.height)
    val num = size.width / gcd
    val den = size.height / gcd
    val ratio = size.width.toDouble() / size.height.toDouble()
    val commonRatios = mapOf(
        16.0 / 9.0 to "16:9 (Widescreen)",
        4.0 / 3.0 to "4:3 (Standard)",
        16.0 / 10.0 to "16:10 (WUXGA)",
        3.0 / 2.0 to "3:2 (VistaVision)",
        2.0 to "2:1 (Univisium)",
        2.6 to "2.60:1 (Cinerama)",
        2.76 to "2.76:1 (Ultra Panavision 70)",
        1.85 to "1.85:1 (FLAT)",
        2.35 to "2.35:1 (Cinemascope)",
        2.20 to "2.20:1 (70mm Film)",
        2.39 to "2.39:1 (Modern Anamorphic/Panavision)",
        1.9 to "1.90:1 (Digital IMAX)",
        1.43 to "1.43:1 (IMAX 70mm)",
        1.0 to "1:1 (Square)"
    )
    val name = commonRatios.entries.find { kotlin.math.abs(it.key - ratio) < 0.01 }?.value
    return if (name != null) {
        "${size.width}x${size.height} ($name)"
    } else {
        "${size.width}x${size.height} ($num:$den)"
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Composable
fun CameraWScreen(
    previewSize: Size,
    sensorOrientation: Int,
    isRecording: Boolean,
    onEvent: (CameraUiEvent) -> Unit,
    onSurfaceTextureAvailable: (SurfaceTexture, Int, Int) -> Unit,
    onSurfaceTextureDestroyed: () -> Boolean,
    deviceRotation: State<Float> = remember { mutableFloatStateOf(0f) },
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val localContext = LocalContext.current
    val cameraManager = remember { localContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val cameraCharacteristics = remember(uiState.cameraId) {
        try {
            if (uiState.cameraId.isNotEmpty() && uiState.cameraId != "-1") {
                cameraManager.getCameraCharacteristics(uiState.cameraId)
            } else null
        } catch (e: Exception) { null }
    }
    var activeManualControl by remember { mutableStateOf<ManualControlType?>(null) }
    var showHistogram by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }

    val rotation by deviceRotation
    val animatedRotation by animateFloatAsState(
        targetValue = rotation,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessVeryLow
        ),
        label = "DeviceRotation"
    )

    if (showGallery) {
        GalleryScreen(
            initialUri = null,
            onBack = { showGallery = false })
    } else {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = Color.Black,
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (activeManualControl != null) {
                                    activeManualControl = null
                                } else {
                                    onEvent(CameraUiEvent.TapToFocus(offset))
                                }
                            },
                            onLongPress = { offset ->
                                if (activeManualControl == null) {
                                    onEvent(CameraUiEvent.TapToFocus(offset))
                                    onEvent(CameraUiEvent.LockAeAf)
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoomFactor, _ ->
                            val currentZoom = uiState.zoomRatio
                            val newZoom = (currentZoom * zoomFactor).coerceIn(1f, uiState.maxZoom)
                            if (newZoom != currentZoom) {
                                onEvent(CameraUiEvent.SetZoom(newZoom))
                            }
                        }
                    }
            ) {
                val uiState by viewModel.uiState.collectAsState()

                val isPortrait = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                val sensorW = uiState.currentResolution.width.toFloat()
                val sensorH = uiState.currentResolution.height.toFloat()
                val targetRatio = if (isPortrait) sensorH / sensorW else sensorW / sensorH

                val previewW = previewSize.width.toFloat()
                val previewH = previewSize.height.toFloat()
                val previewRatio = if (isPortrait) previewH / previewW else previewW / previewH

                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val screenWidth = maxWidth
                    val screenHeight = maxHeight
                    val screenRatio = screenWidth.value / screenHeight.value

                    val (vfWidth, vfHeight) = if (screenRatio > targetRatio) {
                        (screenHeight * targetRatio) to screenHeight
                    } else {
                        screenWidth to (screenWidth / targetRatio)
                    }

                    Box(modifier = Modifier.fillMaxSize())

                    Box(
                        modifier = Modifier
                            .size(vfWidth, vfHeight)
                            .clipToBounds(),
                        contentAlignment = Alignment.Center
                    ) {
                        val (scaledPreviewW, scaledPreviewH) = if (targetRatio > previewRatio) {
                            vfWidth to (vfWidth / previewRatio)
                        } else {
                            (vfHeight * previewRatio) to vfHeight
                        }

                        CameraPreview(
                            previewSize = previewSize,
                            isRecording = isRecording,
                            isFrontCamera = uiState.cameraId == "-1",
                            onSurfaceTextureAvailable = onSurfaceTextureAvailable,
                            onSurfaceTextureDestroyed = onSurfaceTextureDestroyed,
                            modifier = Modifier
                                .requiredSize(scaledPreviewW, scaledPreviewH)
                        )
                    }
                }

                FocusOverlay(
                    focusPoint = uiState.focusPoint,
                    isLocked = uiState.isAeAfLocked,
                    showIndicator = uiState.showFocusCircle,
                    focusState = uiState.focusState,
                    exposureCompensation = uiState.exposureCompensation,
                    onExposureChange = { ev -> onEvent(CameraUiEvent.SetExposureCompensation(ev)) }
                )

                if (showHistogram && uiState.histogramData != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 140.dp, end = 24.dp)
                    ) {
                        ProfessionalHistogram(
                            data = uiState.histogramData!!,
                            rotation = animatedRotation,
                            modifier = Modifier
                                .width(140.dp)
                                .height(140.dp)
                                .background(Color(0x80000000), RoundedCornerShape(8.dp))
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                }

                if (uiState.cameraMode != CameraMode.PHOTO && uiState.zoomRatio > 1.05f) {
                    Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp)) {
                        Text(
                            text = String.format("%.1fx", uiState.zoomRatio),
                            color = Color(0xFFFFD700),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(top = 54.dp)
                ) {
                    ExpressiveTopBar(
                        resolution = resolutionLabel(
                            uiState.currentResolution,
                            uiState.cameraMode,
                            cameraCharacteristics
                        ),
                        fps = if (uiState.cameraMode == CameraMode.PHOTO) "" else "${uiState.currentFps}",
                        onResolutionClick = { onEvent(CameraUiEvent.ResolutionClicked) },
                        onFpsClick = {
                            if (uiState.cameraMode != CameraMode.PHOTO) onEvent(
                                CameraUiEvent.FpsClicked
                            )
                        },
                        onFlashClick = { onEvent(CameraUiEvent.FlashToggled) },
                        onSettingsClick = { onEvent(CameraUiEvent.SettingsClicked) },
                        flashOn = uiState.flashEnabled,
                        showFlash = uiState.hasHardwareFlash,
                        rotation = animatedRotation,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                if ((uiState.cameraMode == CameraMode.PRO_VIDEO ||
                            uiState.cameraMode == CameraMode.PHOTO ||
                            uiState.cameraMode == CameraMode.RAW_VIDEO)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 220.dp)
                            .fillMaxWidth()
                    ) {
                        ManualControlsOverlay(
                            state = uiState,
                            onEvent = onEvent,
                            activeControl = activeManualControl,
                            onControlSelected = { activeManualControl = it },
                            rotation = animatedRotation
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    ExpressiveBottomBar(
                        isRecording = uiState.isRecording,
                        currentMode = uiState.cameraMode,
                        cameraId = uiState.cameraId,
                        onModeSelected = { mode ->
                            onEvent(
                                CameraUiEvent.SetCaptureMode(
                                    mode
                                )
                            )
                            activeManualControl = null
                        },
                        onRecordClick = { onEvent(CameraUiEvent.RecordButtonClicked) },
                        onGalleryClick = { showGallery = true },
                        onSwitchCameraClick = { onEvent(CameraUiEvent.ToggleCamera) },
                        showHistogram = showHistogram,
                        onHistogramClick = { showHistogram = !showHistogram },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                if (uiState.isRecording) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 140.dp)
                    ) {
                        RecordingIndicator(uiState.recordingDuration)
                    }
                }

                if (uiState.showResolutionDialog) {
                    ResolutionDialog(
                        currentResolution = uiState.currentResolution,
                        availableResolutions = uiState.availableResolutions,
                        onDismiss = { onEvent(CameraUiEvent.HideResolutionDialog) },
                        onSelect = { size -> onEvent(CameraUiEvent.SelectResolution(size)) }
                    )
                }

                if (uiState.showSettingsSheet) {
                    SettingsSheet(
                        onDismiss = { onEvent(CameraUiEvent.ToggleSettings) },
                        state = uiState,
                        onEvent = onEvent
                    )
                }
            }
        }
    }
}

@Composable
fun ProfessionalHistogram(
    data: FloatArray,
    rotation: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.rotate(rotation)) {
        val w = size.width
        val h = size.height
        val paddingBottom = 16.dp.toPx()
        val graphH = h - paddingBottom
        val path = AndroidPath()
        path.moveTo(0f, graphH)
        val stepX = w / data.size
        val maxDisplayHeight = graphH * 0.95f
        for (i in data.indices) {
            val value = data[i].coerceIn(0f, 1f)
            val pointX = i * stepX
            val pointY = graphH - (value * maxDisplayHeight)
            path.lineTo(pointX, pointY)
        }
        path.lineTo(w, graphH)
        path.close()
        drawIntoCanvas { canvas ->
            val fillPaint = Paint().apply {
                shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, graphH,
                    android.graphics.Color.argb(220, 220, 220, 220),
                    android.graphics.Color.argb(20, 50, 50, 50),
                    android.graphics.Shader.TileMode.CLAMP
                )
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.nativeCanvas.drawPath(path, fillPaint)
            val strokePaint = Paint().apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                isAntiAlias = true
            }
            canvas.nativeCanvas.drawPath(path, strokePaint)
            val textPaint = Paint().apply {
                color = android.graphics.Color.LTGRAY
                textSize = 10.dp.toPx()
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            textPaint.textAlign = Paint.Align.LEFT
            canvas.nativeCanvas.drawText("0", 4.dp.toPx(), h - 4.dp.toPx(), textPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.nativeCanvas.drawText("1023", w - 4.dp.toPx(), h - 4.dp.toPx(), textPaint)
        }
        val blackClip = data.take(3).average() > 0.05f
        val whiteClip = data.takeLast(3).average() > 0.05f
        if (blackClip) {
            drawRect(
                color = Color(0xFF7C4DFF),
                topLeft = Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(4.dp.toPx(), graphH)
            )
        }
        if (whiteClip) {
            drawRect(
                color = Color(0xFFFF5252),
                topLeft = Offset(w - 4.dp.toPx(), 0f),
                size = androidx.compose.ui.geometry.Size(4.dp.toPx(), graphH)
            )
        }
    }
}

@Composable
private fun RecordingIndicator(duration: String) {
    val pulseColor by animateColorAsState(
        targetValue = if (duration.takeLast(1).toIntOrNull() ?: 0 % 2 == 0)
            Color.Red.copy(alpha = 0.9f)
        else
            Color.Red.copy(alpha = 0.7f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "RecordingPulse"
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(pulseColor)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = duration,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

@Composable
fun ResolutionDialog(
    currentResolution: Size,
    availableResolutions: List<Size>,
    onDismiss: () -> Unit,
    onSelect: (Size) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1A2020),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "Select Resolution",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(availableResolutions.size) { index ->
                        val size = availableResolutions[index]
                        val isSelected = size == currentResolution
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(size) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                            Text(
                                text = formatResolutionWithAspectRatio(size),
                                color = Color.White
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
}

fun calculateMP(size: Size): Int {
    return Math.round((size.width.toFloat() * size.height) / 1_000_000)
}

fun resolutionLabel(size: Size, mode: CameraMode, characteristics: CameraCharacteristics?): String {
    val fullArray = characteristics?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    val activeArray = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

    return when (mode) {
        CameraMode.PHOTO -> {
            val mp = calculateMP(size)
            when {
                fullArray != null && size.width == fullArray.width && size.height == fullArray.height -> "${mp}MP"
                activeArray != null && size.width == activeArray.width() && size.height == activeArray.height() -> "Full Sensor"
                mp >= 1 -> "${mp}MP"
                else -> "${size.height}p"
            }
        }
        else -> {
            when (size.height) {
                2160 -> "4K"
                1440 -> "1440p"
                1080 -> "1080p"
                720 -> "720p"
                else -> "${size.height}p"
            }
        }
    }
}