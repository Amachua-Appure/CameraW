package com.cameraw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

@Composable
fun InfiniteRulerDial(
    currentValue: Float,
    minValue: Float,
    maxValue: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    isLogarithmic: Boolean = false,
    sensitivity: Float = 1.2f,
    formatLabel: (Float) -> String
) {
    val tickSpacing = 45f
    val currentVal by rememberUpdatedState(currentValue)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = formatLabel(currentValue),
            color = Color(0xFFFFD700),
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .pointerInput(Unit) {
                    var dragAccumulator = 0f
                    var startValue = 0f

                    detectHorizontalDragGestures(
                        onDragStart = {
                            startValue = currentVal
                            dragAccumulator = 0f
                        },
                        onDragEnd = {
                            onValueChange(currentVal.roundToInt().toFloat())
                        },
                        onDragCancel = {
                            onValueChange(currentVal.roundToInt().toFloat())
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        dragAccumulator += dragAmount

                        val deltaIndex = -(dragAccumulator / tickSpacing) * sensitivity
                        val newValue = (startValue + deltaIndex).coerceIn(minValue, maxValue)
                        onValueChange(newValue)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val center = width / 2f

                val minTick = ceil(minValue).toInt()
                val maxTick = floor(maxValue).toInt()

                for (i in minTick..maxTick) {
                    val offsetX = center + (i - currentValue) * tickSpacing

                    if (offsetX in 0f..width) {
                        val isMajorTick = i % 5 == 0
                        val tickHeight = if (isMajorTick) height * 0.7f else height * 0.4f
                        val startY = (height - tickHeight) / 2f

                        drawLine(
                            color = Color.White.copy(alpha = 0.6f),
                            start = Offset(offsetX, startY),
                            end = Offset(offsetX, startY + tickHeight),
                            strokeWidth = if (isMajorTick) 4f else 2f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                drawLine(
                    color = Color(0xFFFFD700),
                    start = Offset(center, 0f),
                    end = Offset(center, height),
                    strokeWidth = 6f,
                    cap = StrokeCap.Round
                )

                val edgeWidth = width * 0.25f
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black,
                        startX = 0f,
                        endX = edgeWidth
                    ),
                    blendMode = BlendMode.DstIn
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Black,
                        1f to Color.Transparent,
                        startX = width - edgeWidth,
                        endX = width
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
        }
    }
}