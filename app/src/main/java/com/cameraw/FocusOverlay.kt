package com.cameraw

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun FocusOverlay(
    focusPoint: Offset?,
    isLocked: Boolean,
    showIndicator: Boolean,
    focusState: FocusState,
    exposureCompensation: Int,
    onExposureChange: (Int) -> Unit
) {
    if (focusPoint == null || !showIndicator) return

    val targetColor = when {
        focusState == FocusState.FAILED -> Color(0xFFFF5252)
        isLocked || focusState == FocusState.FOCUSED -> Color(0xFFFF0000)
        else -> Color.White
    }
    val color by animateColorAsState(targetColor, label = "FocusColor")

    val infiniteTransition = rememberInfiniteTransition(label = "LockedPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isLocked) 0.5f else 1f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "PulseAlpha"
    )

    val scaleAnim = remember { Animatable(1.5f) }
    LaunchedEffect(focusPoint) {
        scaleAnim.snapTo(1.5f)
        scaleAnim.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val boxSizePx = 70.dp.toPx()
            val currentScale = scaleAnim.value
            val topLeft = Offset(
                focusPoint.x - (boxSizePx * currentScale / 2),
                focusPoint.y - (boxSizePx * currentScale / 2)
            )

            drawRect(
                color = color.copy(alpha = if (isLocked) pulseAlpha else 1f),
                topLeft = topLeft,
                size = Size(boxSizePx * currentScale, boxSizePx * currentScale),
                style = Stroke(width = 2.dp.toPx())
            )

            val cornerLen = 15.dp.toPx()
            drawLine(color, topLeft, topLeft + Offset(cornerLen, 0f), 4f)
            drawLine(color, topLeft, topLeft + Offset(0f, cornerLen), 4f)

            val topRight = topLeft + Offset(boxSizePx * currentScale, 0f)
            drawLine(color, topRight, topRight - Offset(cornerLen, 0f), 4f)
            drawLine(color, topRight, topRight + Offset(0f, cornerLen), 4f)

            val botLeft = topLeft + Offset(0f, boxSizePx * currentScale)
            drawLine(color, botLeft, botLeft + Offset(cornerLen, 0f), 4f)
            drawLine(color, botLeft, botLeft - Offset(0f, cornerLen), 4f)

            val botRight = topLeft + Offset(boxSizePx * currentScale, boxSizePx * currentScale)
            drawLine(color, botRight, botRight - Offset(cornerLen, 0f), 4f)
            drawLine(color, botRight, botRight - Offset(0f, cornerLen), 4f)
        }

        if (showIndicator) {
            val sliderHeight = 120.dp
            val sliderWidth = 40.dp

            var smoothExposure by remember { mutableFloatStateOf(exposureCompensation.toFloat()) }

            LaunchedEffect(focusPoint) {
                smoothExposure = exposureCompensation.toFloat()
            }

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (focusPoint.x + 45.dp.toPx()).roundToInt(),
                            y = (focusPoint.y - (sliderHeight.toPx() / 2)).roundToInt()
                        )
                    }
                    .size(width = sliderWidth, height = sliderHeight)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()

                            val sensitivity = 0.015f
                            smoothExposure = (smoothExposure - dragAmount * sensitivity).coerceIn(-6f, 6f)

                            val newEvInt = smoothExposure.roundToInt()
                            if (newEvInt != exposureCompensation) {
                                onExposureChange(newEvInt)
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val centerX = size.width / 2f

                    drawLine(
                        color = Color.White.copy(alpha = 0.3f),
                        start = Offset(centerX, 0f),
                        end = Offset(centerX, size.height),
                        strokeWidth = 1.dp.toPx()
                    )

                    val normalizedEv = (smoothExposure + 6f) / 12f
                    val indicatorY = size.height - (normalizedEv * size.height)

                    val sunColor = Color(0xFFFF0000)

                    drawCircle(
                        color = sunColor,
                        radius = 4.dp.toPx(),
                        center = Offset(centerX, indicatorY)
                    )

                    val rayLength = 8.dp.toPx()
                    val rayOffset = 6.dp.toPx()
                    for (i in 0 until 8) {
                        val angle = (i * 45f) * (Math.PI / 180f)
                        val startX = centerX + rayOffset * Math.cos(angle).toFloat()
                        val startY = indicatorY + rayOffset * Math.sin(angle).toFloat()
                        val endX = centerX + rayLength * Math.cos(angle).toFloat()
                        val endY = indicatorY + rayLength * Math.sin(angle).toFloat()

                        drawLine(
                            color = sunColor,
                            start = Offset(startX, startY),
                            end = Offset(endX, endY),
                            strokeWidth = 1.5.dp.toPx()
                        )
                    }
                }
            }
        }

        if (isLocked) {
            Text(
                text = "AE/AF LOCKED",
                color = Color(0xFFFFB7C5),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall.copy(
                    shadow = Shadow(Color.Black, Offset(2f, 2f), 4f)
                ),
                modifier = Modifier.offset {
                    IntOffset(
                        x = (focusPoint.x - 40.dp.toPx()).roundToInt(),
                        y = (focusPoint.y - 60.dp.toPx()).roundToInt()
                    )
                }
            )
        }
    }
}