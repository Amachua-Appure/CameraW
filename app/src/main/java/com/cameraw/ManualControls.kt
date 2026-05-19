package com.cameraw

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import com.cameraw.InfiniteRulerDial

enum class ManualControlType { ISO, SHUTTER, FOCUS, WB }

object CameraProUtils {
    val ISO_STOPS = listOf(
        50, 64, 72, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
        800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 12800
    )

    val SHUTTER_STOPS_NS = listOf(
        32_000_000_000L,   // 32s
        30_000_000_000L,   // 30s
        25_000_000_000L,   // 25s
        20_000_000_000L,   // 20s
        15_000_000_000L,   // 15s
        13_000_000_000L,   // 13s
        10_000_000_000L,   // 10s
        8_000_000_000L,    // 8s
        6_000_000_000L,    // 6s
        5_000_000_000L,    // 5s
        4_000_000_000L,    // 4s
        3_200_000_000L,    // 3.2s
        3_000_000_000L,    // 3s
        2_500_000_000L,    // 2.5s
        2_000_000_000L,    // 2s
        1_600_000_000L,    // 1.6s
        1_300_000_000L,    // 1.3s
        1_000_000_000L,    // 1s
        800_000_000L,      // 0.8s
        600_000_000L,      // 0.6s
        500_000_000L,      // 0.5s (1/2)
        400_000_000L,      // 0.4s
        333_333_333L,      // 1/3s
        300_000_000L,      // 0.3s
        250_000_000L,      // 1/4s
        200_000_000L,      // 1/5s
        166_666_666L,      // 1/6s
        125_000_000L,      // 1/8s
        100_000_000L,      // 1/10s
        76_923_076L,       // 1/13s
        66_666_666L,       // 1/15s
        50_000_000L,       // 1/20s
        40_000_000L,       // 1/25s
        33_333_333L,       // 1/30s
        25_000_000L,       // 1/40s
        20_000_000L,       // 1/50s
        16_666_666L,       // 1/60s
        12_500_000L,       // 1/80s
        10_000_000L,       // 1/100s
        8_000_000L,        // 1/125s
        6_250_000L,        // 1/160s
        5_000_000L,        // 1/200s
        4_000_000L,        // 1/250s
        3_125_000L,        // 1/320s
        2_500_000L,        // 1/400s
        2_000_000L,        // 1/500s
        1_562_500L,        // 1/640s
        1_250_000L,        // 1/800s
        1_000_000L,        // 1/1000s
        800_000L,          // 1/1250s
        625_000L,          // 1/1600s
        500_000L,          // 1/2000s
        400_000L,          // 1/2500s
        312_500L,          // 1/3200s
        250_000L,          // 1/4000s
        125_000L,          // 1/8000s
        62_500L,           // 1/16000s
        31_250L            // 1/32000s
    )


    fun getDynamicShutterList(currentFps: Int, minNs: Long, maxNs: Long): List<Long> {
        if (currentFps <= 0) return SHUTTER_STOPS_NS
        val cinematic180 = 1_000_000_000L / (currentFps * 2)
        val cinematic360 = 1_000_000_000L / currentFps
        val flicker50Hz = 1_000_000_000L / 50
        val flicker60Hz = 1_000_000_000L / 60
        val flicker120Hz = 1_000_000_000L / 120

        val combined = SHUTTER_STOPS_NS +
                listOf(cinematic180, cinematic360, flicker50Hz, flicker60Hz, flicker120Hz)

        val maxDuration = 1_000_000_000L / currentFps

        return combined
            .filter { it in minNs..maxNs }
            .filter { it <= maxDuration }
            .distinct()
            .sortedDescending()
    }
}

private val Gold = Color(0xFFFFD700)

@Composable
fun ManualControlsOverlay(
    state: CameraUiState,
    onEvent: (CameraUiEvent) -> Unit,
    activeControl: ManualControlType?,
    onControlSelected: (ManualControlType?) -> Unit,
    rotation: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp)
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = activeControl == null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color.Transparent)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val isoText = state.manualIso?.toString() ?: "${state.activeIso}"
                val shutterText =
                    if (state.manualShutterNano != null)
                        formatShutter(state.manualShutterNano)
                    else
                        formatShutter(state.activeShutter)

                val focusText =
                    if (state.manualFocusDist != null)
                        String.format("%.1f", state.manualFocusDist)
                    else "AF"

                val wbText =
                    if (state.manualWbTemp != null)
                        "${state.manualWbTemp}K"
                    else "${state.activeWb}K"

                ControlItem(
                    label = "ISO",
                    value = isoText,
                    icon = Icons.Outlined.Iso,
                    isActive = state.manualIso != null,
                    rotation = rotation
                ) { onControlSelected(ManualControlType.ISO) }

                ControlItem(
                    label = "Shutter",
                    value = shutterText,
                    icon = Icons.Outlined.ShutterSpeed,
                    isActive = state.manualShutterNano != null,
                    rotation = rotation
                ) { onControlSelected(ManualControlType.SHUTTER) }

                ControlItem(
                    label = "WB",
                    value = wbText,
                    icon = Icons.Outlined.WbSunny,
                    isActive = state.manualWbTemp != null,
                    rotation = rotation
                ) { onControlSelected(ManualControlType.WB) }

                ControlItem(
                    label = "Focus",
                    value = focusText,
                    icon = Icons.Outlined.CenterFocusWeak,
                    isActive = state.manualFocusDist != null,
                    rotation = rotation
                ) { onControlSelected(ManualControlType.FOCUS) }
            }
        }

        AnimatedVisibility(
            visible = activeControl != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            if (activeControl != null) {
                CompactControlRow(
                    type = activeControl,
                    state = state,
                    onEvent = onEvent
                ) { onControlSelected(null) }
            }
        }
    }
}

@Composable
fun ControlItem(
    label: String,
    value: String,
    icon: ImageVector,
    isActive: Boolean,
    rotation: Float,
    onClick: () -> Unit
) {
    val inactiveIconColor = MaterialTheme.colorScheme.onSurfaceVariant
    val inactiveTextColor = MaterialTheme.colorScheme.onSurface

    val iconTint by animateColorAsState(targetValue = if (isActive) Gold else inactiveIconColor, animationSpec = spring(stiffness = Spring.StiffnessLow))
    val textTint by animateColorAsState(targetValue = if (isActive) Gold else inactiveTextColor, animationSpec = spring(stiffness = Spring.StiffnessLow))

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .rotate(rotation)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
            .sizeIn(minWidth = 56.dp, minHeight = 56.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = textTint,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun CompactControlRow(
    type: ManualControlType,
    state: CameraUiState,
    onEvent: (CameraUiEvent) -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Button(
            onClick = {
                when (type) {
                    ManualControlType.ISO -> onEvent(CameraUiEvent.SetManualIso(null))
                    ManualControlType.SHUTTER -> onEvent(CameraUiEvent.SetShutterSpeed(null))
                    ManualControlType.FOCUS -> onEvent(CameraUiEvent.SetFocusDistance(null))
                    ManualControlType.WB -> onEvent(CameraUiEvent.SetWhiteBalance(null))
                }
                onClose()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                contentColor = MaterialTheme.colorScheme.primary
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(36.dp)
        ) {
            Text("Auto", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
        }

        Box(modifier = Modifier.weight(1f)) {
            when (type) {
                ManualControlType.ISO -> {
                    val min = state.minIso.toFloat()
                    val max = state.maxIso.toFloat()
                    val current = (state.manualIso ?: state.activeIso).toFloat().coerceIn(min, max)
                    val initialPos = if (max <= min) 0f else ((current - min) / (max - min)) * 1000f

                    val steps = CameraProUtils.ISO_STOPS.filter { it in state.minIso..state.maxIso }.sorted()

                    SliderWithFastSteps(
                        onStepDown = {
                            val next = steps.lastOrNull { it < current.roundToInt() } ?: state.minIso
                            onEvent(CameraUiEvent.SetManualIso(next))
                        },
                        onStepUp = {
                            val next = steps.firstOrNull { it > current.roundToInt() } ?: state.maxIso
                            onEvent(CameraUiEvent.SetManualIso(next))
                        }
                    ) {
                        ContinuousSlider(
                            initialPosition = initialPos,
                            sensitivity = 1.0f,
                            formatLabel = { pos ->
                                val iso = (min + (pos / 1000f) * (max - min)).roundToInt()
                                "ISO $iso"
                            },
                            onValueChange = { pos ->
                                val iso = (min + (pos / 1000f) * (max - min)).roundToInt()
                                onEvent(CameraUiEvent.SetManualIso(iso))
                            }
                        )
                    }
                }
                ManualControlType.SHUTTER -> {
                    val minNs = state.minShutter.coerceAtLeast(1L).toDouble()
                    val maxDuration = 1_000_000_000L / (if (state.currentFps > 0) state.currentFps else 30)
                    val maxNs = state.maxShutter.coerceAtMost(maxDuration).toDouble()

                    val logMin = Math.log(minNs)
                    val logMax = Math.log(maxNs)

                    val currentNs = (state.manualShutterNano ?: state.activeShutter).toDouble().coerceIn(minNs, maxNs)
                    val initialPos = if (logMax <= logMin) 0f else (((logMax - Math.log(currentNs)) / (logMax - logMin)) * 1000f).toFloat()

                    val steps = CameraProUtils.getDynamicShutterList(state.currentFps, state.minShutter, state.maxShutter)

                    SliderWithFastSteps(
                        onStepDown = {
                            val next = steps.lastOrNull { it > currentNs.toLong() } ?: maxNs.toLong()
                            onEvent(CameraUiEvent.SetShutterSpeed(next))
                        },
                        onStepUp = {
                            val next = steps.firstOrNull { it < currentNs.toLong() } ?: minNs.toLong()
                            onEvent(CameraUiEvent.SetShutterSpeed(next))
                        }
                    ) {
                        ContinuousSlider(
                            initialPosition = initialPos,
                            sensitivity = 1.0f,
                            formatLabel = { pos ->
                                val logVal = logMax - (pos / 1000f) * (logMax - logMin)
                                val ns = Math.exp(logVal).toLong()
                                formatShutter(ns)
                            },
                            onValueChange = { pos ->
                                val logVal = logMax - (pos / 1000f) * (logMax - logMin)
                                val ns = Math.exp(logVal).toLong()
                                onEvent(CameraUiEvent.SetShutterSpeed(ns))
                            }
                        )
                    }
                }
                ManualControlType.WB -> {
                    val current = (state.manualWbTemp ?: state.activeWb).toFloat().coerceIn(2000f, 10000f)
                    val initialPos = ((current - 2000f) / 8000f) * 1000f
                    val steps = (2000..10000 step 500).toList()

                    SliderWithFastSteps(
                        onStepDown = {
                            val next = steps.lastOrNull { it < current.roundToInt() } ?: 2000
                            onEvent(CameraUiEvent.SetWhiteBalance(next))
                        },
                        onStepUp = {
                            val next = steps.firstOrNull { it > current.roundToInt() } ?: 10000
                            onEvent(CameraUiEvent.SetWhiteBalance(next))
                        }
                    ) {
                        ContinuousSlider(
                            initialPosition = initialPos,
                            sensitivity = 1.2f,
                            formatLabel = { pos ->
                                val wb = (2000f + (pos / 1000f) * 8000f).roundToInt()
                                "${wb}K"
                            },
                            onValueChange = { pos ->
                                val wb = (2000f + (pos / 1000f) * 8000f).roundToInt()
                                onEvent(CameraUiEvent.SetWhiteBalance(wb))
                            }
                        )
                    }
                }
                ManualControlType.FOCUS -> {
                    val max = state.maxFocusDist
                    val current = (state.manualFocusDist ?: state.activeFocusDist).coerceIn(0f, max)
                    val initialPos = if (max <= 0f) 0f else ((max - current) / max) * 1000f
                    val stepSize = max / 10f

                    SliderWithFastSteps(
                        onStepDown = {
                            val next = (current + stepSize).coerceAtMost(max)
                            onEvent(CameraUiEvent.SetFocusDistance(next))
                        },
                        onStepUp = {
                            val next = (current - stepSize).coerceAtLeast(0f)
                            onEvent(CameraUiEvent.SetFocusDistance(next))
                        }
                    ) {
                        ContinuousSlider(
                            initialPosition = initialPos,
                            sensitivity = 60.0f,
                            formatLabel = { pos ->
                                val normalized = (pos / 1000f).coerceIn(0f, 1f)
                                if (normalized >= 0.99f) "Inf"
                                else if (normalized <= 0.01f) "Macro"
                                else String.format("%.2f", normalized)
                            },
                            onValueChange = { pos ->
                                val dist = max - (pos / 1000f) * max
                                onEvent(CameraUiEvent.SetFocusDistance(dist))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SliderWithFastSteps(
    onStepDown: () -> Unit,
    onStepUp: () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(onClick = onStepDown) {
            Icon(
                imageVector = Icons.Outlined.ChevronLeft,
                contentDescription = "Decrease",
                tint = Color.White
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            content()
        }

        IconButton(onClick = onStepUp) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "Increase",
                tint = Color.White
            )
        }
    }
}

@Composable
fun ContinuousSlider(
    initialPosition: Float,
    sensitivity: Float,
    formatLabel: (Float) -> String,
    onValueChange: (Float) -> Unit
) {
    var sliderPosition by remember(initialPosition) { mutableFloatStateOf(initialPosition) }
    var lastHapticPos by remember { mutableFloatStateOf(initialPosition) }
    val view = LocalView.current

    InfiniteRulerDial(
        currentValue = sliderPosition,
        minValue = 0f,
        maxValue = 1000f,
        sensitivity = sensitivity,
        onValueChange = { newValue ->
            sliderPosition = newValue

            if (abs(newValue - lastHapticPos) > 10f) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                lastHapticPos = newValue
            }

            onValueChange(newValue)
        },
        formatLabel = { formatLabel(it) }
    )
}


private fun formatShutter(nano: Long?): String {
    if (nano == null || nano == 0L) return "Auto"
    if (abs(nano - 20_833_333L) < 100_000) return "1/48"
    if (abs(nano - 41_666_666L) < 100_000) return "1/24"
    if (abs(nano - 8_333_333L) < 100_000) return "1/120"

    val sec = nano / 1_000_000_000.0
    if (sec >= 1.0) return String.format("%.1f\"", sec)
    return "1/${(1.0/sec).roundToInt()}"
}
