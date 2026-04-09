package com.cameraw

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ExpressiveTopBar(
    resolution: String,
    fps: String,
    onResolutionClick: () -> Unit,
    onFpsClick: () -> Unit,
    onFlashClick: () -> Unit,
    onSettingsClick: () -> Unit,
    flashOn: Boolean = false,
    showFlash: Boolean = true,
    rotation: Float = 0f,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 22.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (showFlash) {
                IconButton(
                    onClick = onFlashClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = "Flash",
                        tint = if (flashOn) Color(0xFFFFD700) else Color.White,
                        modifier = Modifier.rotate(rotation)
                    )
                }
            }
        }

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.rotate(rotation),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = resolution,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onResolutionClick() }
                )

                if (fps.isNotBlank() && fps != "0") {
                    Text(
                        text = "•",
                        color = Color(0xFF424242),
                        fontSize = 22.sp
                    )

                    Text(
                        text = fps,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onFpsClick() }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(end = 22.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.rotate(rotation)
                )
            }
        }
    }
}