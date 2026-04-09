package com.cameraw

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    state: CameraUiState,
    onEvent: (CameraUiEvent) -> Unit
) {
    val pureBlack = Color.Black
    var showVideoCodecDialog by remember { mutableStateOf(false) }
    var showAudioCodecDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showNoiseDialog by remember { mutableStateOf(false) }
    var showBurstDialog by remember { mutableStateOf(false) }
    var showPngDialog by remember { mutableStateOf(false) }
    var showFormatDialog by remember { mutableStateOf(false) }
    var showMetadataDialog by remember { mutableStateOf(false) }
    var showVideoFormatDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = pureBlack,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(48.dp)
                    .height(6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = CircleShape
            ) {}
        }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.padding(start = 12.dp, bottom = 16.dp, top = 8.dp)
                )
            }

            item {
                Text(
                    text = "Photo",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                )
            }

            item {
                ExpressiveSettingRow(
                    icon = Icons.Outlined.HdrAuto,
                    label = "Photo Format",
                    value = when (state.photoBitDepth) {
                        16 -> "16-bit HDR (PQ)/PNG"
                        14 -> "16-bit RAW (DNG)"
                        10 -> "10-bit HLG/AVIF"
                        else -> "8-bit ISP (JPEG)"
                    },
                    highlight = true,
                    onClick = { showFormatDialog = true }
                )
            }

            item {
                ExpressiveSettingRow(
                    icon = Icons.Outlined.BurstMode,
                    label = "Frame Stacking",
                    value = "${state.burstFrames} Frames",
                    highlight = true,
                    onClick = { showBurstDialog = true }
                )
            }

            item {
                ExpressiveSettingRow(
                    icon = Icons.Outlined.Compress,
                    label = "PNG Compression",
                    value = "Level ${state.pngCompression}",
                    highlight = true,
                    onClick = { showPngDialog = true }
                )
            }

            item {
                ExpressiveSettingRow(
                    icon = Icons.Outlined.HdrAuto,
                    label = "Noise Reduction",
                    value = when (state.noiseReductionMode) {
                        0 -> "Off"
                        1 -> "Fast"
                        2 -> "High Quality"
                        3 -> "Minimal"
                        4 -> "ZSL"
                        else -> "Off"
                    },
                    highlight = true,
                    onClick = { showNoiseDialog = true }
                )
            }

            item {
                Text(
                    text = "Video & Audio",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                )
            }

            item {
                ExpressiveSettingRow(
                    icon = if (state.videoFormat == 1) Icons.Outlined.HdrOn else Icons.Outlined.HdrOff,
                    label = "Dynamic Range",
                    value = if (state.videoFormat == 1) "HDR10 (10-bit Rec.2020)" else "SDR (10-bit Rec.709)",
                    highlight = true,
                    onClick = { showVideoFormatDialog = true }
                )
            }

            item {
                ExpressiveSettingRow(
                    icon = Icons.Outlined.Movie,
                    label = "Video Codec",
                    value = if (state.videoCodec.contains("hevc")) "HEVC (H.265)" else "AVC (H.264)",
                    highlight = true,
                    onClick = { showVideoCodecDialog = true }
                )
            }

            item {
                ExpressiveSettingRow(
                    icon = Icons.Outlined.Speed,
                    label = "Bitrate",
                    value = "${state.quality} Mbps",
                    highlight = true,
                    onClick = { showQualityDialog = true }
                )
            }

            if (state.cameraMode == CameraMode.PRO_VIDEO && state.videoFormat == 1) {
                item {
                    ExpressiveSettingRow(
                        icon = Icons.Outlined.Movie,
                        label = "Dynamic Metadata",
                        value = when (state.dynamicMetadataMode) {
                            0 -> "None (Static HDR10)"
                            1 -> "HDR10+ (ST.2094-40)"
                            else -> "HDR10+"
                        },
                        highlight = true,
                        onClick = { showMetadataDialog = true }
                    )
                }
            }

            item {
                ExpressiveSettingRow(
                    icon = Icons.Outlined.GraphicEq,
                    label = "Additional Audio Codec",
                    value = when (state.audioCodec) {
                        "1" -> "Opus (High Efficiency)"
                        "2" -> "None"
                        else -> "WAV (Lossless)"
                    },
                    highlight = true,
                    onClick = { showAudioCodecDialog = true }
                )
            }

            if (state.cameraMode == CameraMode.PRO_VIDEO || state.cameraMode == CameraMode.RAW_VIDEO) {
                item {
                    ExpressiveSettingRow(
                        icon = if (state.saveGyroData) Icons.Outlined.Sensors else Icons.Outlined.SensorsOff,
                        label = "Log Gyroflow Data",
                        value = if (state.saveGyroData) "Enabled (.gcsv)" else "Disabled",
                        highlight = state.saveGyroData,
                        onClick = { onEvent(CameraUiEvent.ToggleSaveGyroData) }
                    )
                }
            }
        }
    }

    if (showFormatDialog) {
        ExpressiveSelectionDialog(
            title = "Photo Format",
            options = listOf(
                16 to "16-bit HDR (PQ)/PNG",
                14 to "16-bit RAW (DNG)",
                10 to "10-bit HLG/AVIF",
                8 to "8-bit ISP (JPEG)"
            ),
            currentValue = state.photoBitDepth,
            onDismiss = { showFormatDialog = false },
            onSelect = { onEvent(CameraUiEvent.SetPhotoFormat(it)) }
        )
    }

    if (showBurstDialog) {
        ExpressiveCounterDialog(
            title = "Frame Stacking",
            currentValue = state.burstFrames,
            range = 1..12,
            unit = "Frames",
            onDismiss = { showBurstDialog = false },
            onCommit = { onEvent(CameraUiEvent.SetBurstFrames(it)) }
        )
    }

    if (showPngDialog) {
        ExpressiveQuantityDialog(
            title = "PNG Compression",
            currentValue = state.pngCompression,
            range = 0f..9f,
            step = 1,
            unit = "Level",
            onDismiss = { showPngDialog = false },
            onCommit = { onEvent(CameraUiEvent.SetPngCompression(it)) }
        )
    }

    if (showNoiseDialog) {
        ExpressiveSelectionDialog(
            title = "Noise Reduction",
            options = listOf(
                0 to "Off",
                1 to "Fast",
                2 to "High Quality",
                3 to "Minimal",
                4 to "ZSL"
            ),
            currentValue = state.noiseReductionMode,
            onDismiss = { showNoiseDialog = false },
            onSelect = { onEvent(CameraUiEvent.SetNoiseReduction(it)) }
        )
    }

    if (showVideoFormatDialog) {
        ExpressiveSelectionDialog(
            title = "Dynamic Range",
            options = listOf(
                1 to "HDR10 (10-bit Rec.2020)",
                0 to "SDR (10-bit Rec.709)"
            ),
            currentValue = state.videoFormat,
            onDismiss = { showVideoFormatDialog = false },
            onSelect = { onEvent(CameraUiEvent.SetVideoFormat(it)) }
        )
    }

    if (showVideoCodecDialog) {
        ExpressiveSelectionDialog(
            title = "Video Codec",
            options = listOf(
                "video/hevc" to "HEVC (H.265)",
                "video/avc" to "AVC (H.264)"
            ),
            currentValue = state.videoCodec,
            onDismiss = { showVideoCodecDialog = false },
            onSelect = { onEvent(CameraUiEvent.SetVideoCodec(it)) }
        )
    }

    if (showQualityDialog) {
        ExpressiveQuantityDialog(
            title = "Video Bitrate",
            currentValue = state.quality,
            range = 40f..600f,
            step = 10,
            unit = "Mbps",
            onDismiss = { showQualityDialog = false },
            onCommit = { onEvent(CameraUiEvent.SetQuality(it)) }
        )
    }

    if (showMetadataDialog) {
        ExpressiveSelectionDialog(
            title = "Dynamic Metadata",
            options = listOf(
                0 to "None (Static HDR10)",
                1 to "HDR10+ (ST.2094-40)"
            ),
            currentValue = state.dynamicMetadataMode,
            onDismiss = { showMetadataDialog = false },
            onSelect = { onEvent(CameraUiEvent.SetDynamicMetadataMode(it)) }
        )
    }

    if (showAudioCodecDialog) {
        ExpressiveSelectionDialog(
            title = "Audio Codec",
            options = listOf(
                "0" to "WAV (Lossless)",
                "1" to "Opus (High Efficiency)",
                "2" to "None"
            ),
            currentValue = state.audioCodec,
            onDismiss = { showAudioCodecDialog = false },
            onSelect = { onEvent(CameraUiEvent.SetAudioCodec(it)) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveQuantityDialog(
    title: String,
    currentValue: Int,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    step: Int = 0,
    onDismiss: () -> Unit,
    onCommit: (Int) -> Unit
) {
    val view = LocalView.current
    var sliderValue by remember { mutableFloatStateOf(currentValue.toFloat()) }
    var lastHapticStep by remember { mutableIntStateOf(currentValue) }

    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    val animatedScale by animateFloatAsState(
        targetValue = if (isDragged) 1.2f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121212),
        shape = RoundedCornerShape(32.dp),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "${sliderValue.roundToInt()}",
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.scale(animatedScale)
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        },
        text = {
            Column {
                Spacer(modifier = Modifier.height(16.dp))
                Slider(
                    value = sliderValue,
                    onValueChange = { newValue ->
                        sliderValue = newValue
                        val intValue = newValue.roundToInt()
                        if (intValue != lastHapticStep) {
                            if (step > 0) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            } else {
                                if (intValue % 50 == 0) {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                } else {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }
                            lastHapticStep = intValue
                        }
                    },
                    onValueChangeFinished = {
                        onCommit(sliderValue.roundToInt())
                    },
                    valueRange = range,
                    steps = if (step > 0) ((range.endInclusive - range.start) / step).toInt() - 1 else 0,
                    interactionSource = interactionSource,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${range.start.toInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Text(
                        text = "${range.endInclusive.toInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
                if (unit == "Level") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if(sliderValue < 3) "Fast Save (Larger Size)" else if (sliderValue > 6) "Slow Save (Small Size)" else "Balanced",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
            ) {
                Text("Done", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
            }
        }
    )
}

@Composable
fun ExpressiveCounterDialog(
    title: String,
    currentValue: Int,
    range: IntRange,
    unit: String,
    onDismiss: () -> Unit,
    onCommit: (Int) -> Unit
) {
    val view = LocalView.current
    var value by remember { mutableIntStateOf(currentValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121212),
        shape = RoundedCornerShape(32.dp),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "$value",
                    style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        },
        text = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = {
                        if (value > range.first) {
                            value--
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    },
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease")
                }

                FilledIconButton(
                    onClick = {
                        if (value < range.last) {
                            value++
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    },
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Increase")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCommit(value)
                    onDismiss()
                },
                modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
            ) {
                Text("Done", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
            }
        }
    )
}

@Composable
fun ExpressiveSettingRow(
    icon: ImageVector,
    label: String,
    value: String,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        ),
        headlineContent = {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = Color.White
            )
        },
        supportingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (highlight) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f)
            )
        },
        leadingContent = {
            androidx.compose.animation.Crossfade(
                targetState = icon,
                label = "icon_transition"
            ) { targetIcon ->
                Icon(
                    imageVector = targetIcon,
                    contentDescription = null,
                    tint = if (highlight) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f)
                )
            }
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(20.dp)
            )
        }
    )
}

@Composable
fun <T> ExpressiveSelectionDialog(
    title: String,
    options: List<Pair<T, String>>,
    currentValue: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121212),
        shape = RoundedCornerShape(32.dp),
        title = {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { (value, label) ->
                    val isSelected = value == currentValue
                    val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(containerColor)
                            .clickable {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = contentColor,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = contentColor
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}