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

enum class ManualControlType { ISO, SHUTTER, FOCUS, WB }

object CameraProUtils {
    val ISO_STOPS = listOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
        800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 12800
    )

    val SHUTTER_STOPS_NS = listOf(
        32_000_000_000L, 15_000_000_000L, 10_000_000_000L, 8_000_000_000L, 6_000_000_000L,
        5_000_000_000L, 4_000_000_000L, 3_000_000_000L, 2_000_000_000L,
        1_000_000_000L, 500_000_000L, 250_000_000L, 125_000_000L,
        100_000_000L, 66_666_666L, 50_000_000L, 40_000_000L, 33_333_333L,
        25_000_000L, 20_000_000L, 16_666_666L, 12_500_000L, 10_000_000L,
        8_000_000L, 5_000_000L, 4_000_000L, 2_000_000L, 1_000_000L,
        500_000L, 250_000L, 125_000L, 62_500L
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                    val steps = remember(state.minIso, state.maxIso) {
                        CameraProUtils.ISO_STOPS.filter { it in state.minIso..state.maxIso }
                            .ifEmpty { listOf(state.minIso) }
                    }
                    val current = state.manualIso ?: state.currentISO
                    val initialIndex = steps.indexOfFirst { it >= current }.coerceAtLeast(0)

                    CompactSlider(
                        value = initialIndex,
                        steps = steps.size,
                        labelMapper = { steps[it].toString() },
                        minLabel = "${steps.first()}",
                        maxLabel = "${steps.last()}",
                        onValueChange = { idx -> onEvent(CameraUiEvent.SetManualIso(steps[idx])) }
                    )
                }
                ManualControlType.SHUTTER -> {
                    val steps = remember(state.minShutter, state.maxShutter, state.currentFps) {
                        CameraProUtils.getDynamicShutterList(
                            currentFps = state.currentFps,
                            minNs = state.minShutter,
                            maxNs = state.maxShutter
                        ).ifEmpty { listOf(16_666_666L) }
                    }
                    val current = state.manualShutterNano ?: 16_666_666L
                    val initialIndex = steps.indices.minByOrNull { abs(steps[it] - current) } ?: 0

                    CompactSlider(
                        value = initialIndex,
                        steps = steps.size,
                        labelMapper = { formatShutter(steps[it]) },
                        minLabel = formatShutter(steps.first()),
                        maxLabel = formatShutter(steps.last()),
                        onValueChange = { idx -> onEvent(CameraUiEvent.SetShutterSpeed(steps[idx])) }
                    )
                }
                ManualControlType.WB -> {
                    val steps = (2000..10000 step 100).toList()
                    val current = state.manualWbTemp ?: 5500
                    val initialIndex = steps.indexOfFirst { it >= current }.coerceAtLeast(0)

                    CompactSlider(
                        value = initialIndex,
                        steps = steps.size,
                        labelMapper = { "${steps[it]}K" },
                        minLabel = "2000K",
                        maxLabel = "10000K",
                        onValueChange = { idx -> onEvent(CameraUiEvent.SetWhiteBalance(steps[idx])) }
                    )
                }
                ManualControlType.FOCUS -> {
                    val max = state.maxFocusDist
                    val steps = 100
                    val current = state.manualFocusDist ?: 0f
                    val initialIndex = ((current / max) * 100).toInt().coerceIn(0, 100)

                    CompactSlider(
                        value = initialIndex,
                        steps = 101,
                        labelMapper = { String.format("%.1f", (it / 100f) * max) },
                        minLabel = "Inf",
                        maxLabel = "Macro",
                        onValueChange = { idx ->
                            val dist = (idx / 100f) * max
                            onEvent(CameraUiEvent.SetFocusDistance(dist))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun CompactSlider(
    value: Int,
    steps: Int,
    labelMapper: (Int) -> String,
    minLabel: String,
    maxLabel: String,
    onValueChange: (Int) -> Unit
) {
    val view = LocalView.current
    var sliderPosition by remember(value) { mutableFloatStateOf(value.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }

    val currentValueLabel = remember(sliderPosition) {
        val index = sliderPosition.roundToInt().coerceIn(0, steps - 1)
        labelMapper(index)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = currentValueLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 2.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = minLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(end = 8.dp)
            )

            Slider(
                value = sliderPosition,
                onValueChange = {
                    isDragging = true
                    val newIndex = it.roundToInt()
                    if (newIndex != sliderPosition.roundToInt()) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onValueChange(newIndex)
                    }
                    sliderPosition = it
                },
                onValueChangeFinished = { isDragging = false },
                valueRange = 0f..(steps - 1).toFloat(),
                steps = 0,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )

            Text(
                text = maxLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

private fun formatShutter(nano: Long?): String {
    if (nano == null || nano == 0L) return "Auto"
    if (abs(nano - 20_833_333L) < 5000) return "1/48"
    if (abs(nano - 41_666_666L) < 5000) return "1/24"
    if (abs(nano - 8_333_333L) < 5000) return "1/120"

    val sec = nano / 1_000_000_000.0
    if (sec >= 1.0) return String.format("%.1f\"", sec)
    return "1/${(1.0/sec).roundToInt()}"
}