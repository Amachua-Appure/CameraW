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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Immutable
data class SheetConfigState(
    val photoBitDepth: Int,
    val burstFrames: Int,
    val pngCompression: Int,
    val noiseReductionMode: Int,
    val videoFormat: Int,
    val videoCodec: String,
    val quality: Int,
    val dynamicMetadataMode: Int,
    val audioCodec: String,
    val saveGyroData: Boolean,
    val cameraMode: CameraMode,
    val logProfile: Int,
    val selectedLut: String,
    val availableLuts: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    config: SheetConfigState,
    onEvent: (CameraUiEvent) -> Unit,
    onImportLut: () -> Unit
) {
    val pureBlack = Color.Black
    val context = LocalContext.current

    var showVideoCodecDialog by remember { mutableStateOf(false) }
    var showAudioCodecDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showNoiseDialog by remember { mutableStateOf(false) }
    var showBurstDialog by remember { mutableStateOf(false) }
    var showPngDialog by remember { mutableStateOf(false) }
    var showFormatDialog by remember { mutableStateOf(false) }
    var showMetadataDialog by remember { mutableStateOf(false) }
    var showVideoFormatDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var showLutDialog by remember { mutableStateOf(false) }

    val photoFormatValue = remember(config.photoBitDepth) {
        when (config.photoBitDepth) {
            16 -> "16-bit HDR (PQ)/PNG"
            14 -> "16-bit RAW (DNG)"
            10 -> "10-bit HLG/AVIF"
            else -> "8-bit ISP (JPEG)"
        }
    }

    val noiseReductionValue = remember(config.noiseReductionMode) {
        when (config.noiseReductionMode) {
            0 -> "Off"
            1 -> "Fast"
            2 -> "High Quality"
            3 -> "Minimal"
            4 -> "ZSL"
            else -> "Off"
        }
    }

    val dynamicMetadataValue = remember(config.dynamicMetadataMode) {
        when (config.dynamicMetadataMode) {
            0 -> "None (Static HDR10)"
            1 -> "HDR10+ (ST.2094-40)"
            else -> "HDR10+"
        }
    }

    val audioCodecValue = remember(config.audioCodec) {
        when (config.audioCodec) {
            "1" -> "Opus (High Efficiency)"
            "2" -> "None"
            else -> "WAV (Lossless)"
        }
    }

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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 48.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(start = 12.dp, bottom = 16.dp, top = 8.dp)
            )

            Text(
                text = "Photo",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
            )

            ExpressiveSettingRow(
                icon = Icons.Outlined.HdrAuto,
                label = "Photo Format",
                value = photoFormatValue,
                highlight = true,
                onClick = remember { { showFormatDialog = true } }
            )

            ExpressiveSettingRow(
                icon = Icons.Outlined.BurstMode,
                label = "Frame Stacking",
                value = "${config.burstFrames} Frames",
                highlight = true,
                onClick = remember { { showBurstDialog = true } }
            )

            ExpressiveSettingRow(
                icon = Icons.Outlined.Compress,
                label = "PNG Compression",
                value = "Level ${config.pngCompression}",
                highlight = true,
                onClick = remember { { showPngDialog = true } }
            )

            ExpressiveSettingRow(
                icon = Icons.Outlined.HdrAuto,
                label = "Noise Reduction",
                value = noiseReductionValue,
                highlight = true,
                onClick = remember { { showNoiseDialog = true } }
            )

            Text(
                text = "Video & Audio",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            )

            if (config.logProfile == 0) {
                ExpressiveSettingRow(
                    icon = if (config.videoFormat == 1) Icons.Outlined.HdrOn else Icons.Outlined.HdrOff,
                    label = "Dynamic Range",
                    value = if (config.videoFormat == 1) "HDR10 (10-bit Rec.2020)" else "SDR (10-bit Rec.709)",
                    highlight = true,
                    onClick = remember { { showVideoFormatDialog = true } }
                )
            }

            ExpressiveSettingRow(
                icon = Icons.Outlined.Movie,
                label = "Video Codec",
                value = if (config.videoCodec.contains("hevc")) "HEVC (H.265)" else "AVC (H.264)",
                highlight = true,
                onClick = remember { { showVideoCodecDialog = true } }
            )

            ExpressiveSettingRow(
                icon = Icons.Outlined.Speed,
                label = "Bitrate",
                value = "${config.quality} Mbps",
                highlight = true,
                onClick = remember { { showQualityDialog = true } }
            )

            if (config.cameraMode == CameraMode.PRO_VIDEO && config.videoFormat == 1 && config.logProfile == 0) {
                ExpressiveSettingRow(
                    icon = Icons.Outlined.Movie,
                    label = "Dynamic Metadata",
                    value = dynamicMetadataValue,
                    highlight = true,
                    onClick = remember { { showMetadataDialog = true } }
                )
            }

            ExpressiveSettingRow(
                icon = Icons.Outlined.GraphicEq,
                label = "Additional Audio Codec",
                value = audioCodecValue,
                highlight = true,
                onClick = remember { { showAudioCodecDialog = true } }
            )

            if (config.cameraMode == CameraMode.PRO_VIDEO || config.cameraMode == CameraMode.RAW_VIDEO) {
                ExpressiveSettingRow(
                    icon = if (config.saveGyroData) Icons.Outlined.Sensors else Icons.Outlined.SensorsOff,
                    label = "Log Gyroflow Data",
                    value = if (config.saveGyroData) "Enabled (.gcsv)" else "Disabled",
                    highlight = config.saveGyroData,
                    onClick = remember(onEvent) { { onEvent(CameraUiEvent.ToggleSaveGyroData) } }
                )
            }

            if (config.cameraMode == CameraMode.PRO_VIDEO) {
                ExpressiveSettingRow(
                    icon = Icons.Outlined.Movie,
                    label = "Log Profile",
                    value = when (config.logProfile) {
                        1 -> "Apple Log"
                        2 -> "Apple Log 2 (AWG)"
                        3 -> "Samsung Log"
                        4 -> "Sony S-Log3"
                        5 -> "Panasonic V-Log"
                        6 -> "ARRI LogC3"
                        else -> "Off"
                    },
                    highlight = true,
                    onClick = remember { { showLogDialog = true } }
                )

                if (config.logProfile > 0) {
                    ExpressiveSettingRow(
                        icon = Icons.Outlined.ColorLens,
                        label = "Custom LUT",
                        value = config.selectedLut,
                        highlight = config.selectedLut != "None",
                        onClick = remember { { showLutDialog = true } }
                    )
                }
            }

            Text(
                text = "Developer",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            )

            ExpressiveSettingRow(
                icon = Icons.Outlined.BugReport,
                label = "Dump Camera Metadata",
                value = "Save state and frame data to .txt",
                highlight = false,
                onClick = remember(onEvent, onDismiss) {
                    {
                        onEvent(CameraUiEvent.DumpMetadataClicked)
                        onDismiss()
                    }
                }
            )
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
            currentValue = config.photoBitDepth,
            onDismiss = { showFormatDialog = false },
            onSelect = { onEvent(CameraUiEvent.SetPhotoFormat(it)) }
        )
    }

    if (showBurstDialog) {
        ExpressiveCounterDialog(
            title = "Frame Stacking",
            currentValue = config.burstFrames,
            range = 1..14,
            unit = "Frames",
            onDismiss = { showBurstDialog = false },
            onCommit = { onEvent(CameraUiEvent.SetBurstFrames(it)) }
        )
    }

    if (showPngDialog) {
        ExpressiveQuantityDialog(
            title = "PNG Compression",
            currentValue = config.pngCompression,
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
            currentValue = config.noiseReductionMode,
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
            currentValue = config.videoFormat,
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
            currentValue = config.videoCodec,
            onDismiss = { showVideoCodecDialog = false },
            onSelect = { onEvent(CameraUiEvent.SetVideoCodec(it)) }
        )
    }

    if (showQualityDialog) {
        ExpressiveQuantityDialog(
            title = "Video Bitrate",
            currentValue = config.quality,
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
            currentValue = config.dynamicMetadataMode,
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
            currentValue = config.audioCodec,
            onDismiss = { showAudioCodecDialog = false },
            onSelect = { onEvent(CameraUiEvent.SetAudioCodec(it)) }
        )
    }

    if (showLogDialog) {
        ExpressiveSelectionDialog(
            title = "Log Profile",
            options = listOf(
                0 to "Off (Standard HDR)",
                1 to "Apple Log",
                2 to "Apple Log 2 (AWG)",
                3 to "Samsung Log",
                4 to "Sony S-Log3",
                5 to "Panasonic V-Log",
                6 to "ARRI LogC3"
            ),
            currentValue = config.logProfile,
            onDismiss = { showLogDialog = false },
            onSelect = { onEvent(CameraUiEvent.SetLogProfile(it)) }
        )
    }

    if (showLutDialog) {
        val options = (listOf("None") + config.availableLuts + "Import .cube LUT...").distinct()
        ExpressiveSelectionDialog(
            title = "Custom LUT",
            options = options.map { it to it },
            currentValue = config.selectedLut,
            onDismiss = { showLutDialog = false },
            onSelect = { selectedOption ->
                when {
                    selectedOption == "Import .cube LUT..." -> onImportLut()
                    else -> onEvent(CameraUiEvent.SetLut(context, selectedOption))
                }
            }
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