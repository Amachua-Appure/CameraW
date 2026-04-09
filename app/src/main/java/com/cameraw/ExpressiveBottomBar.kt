package com.cameraw

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.PageSize
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@Composable
fun ExpressiveBottomBar(
    isRecording: Boolean,
    currentMode: CameraMode,
    cameraId: String,
    onModeSelected: (CameraMode) -> Unit,
    onRecordClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onSwitchCameraClick: () -> Unit,
    showHistogram: Boolean,
    onHistogramClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sakuraPink = Color(0xFFFFB7C5)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(180.dp)
            .background(Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            if (!isRecording) {
                PeekingCarouselModeSelector(
                    modes = listOf(
                        CameraMode.RAW_VIDEO,
                        CameraMode.PRO_VIDEO,
                        CameraMode.PHOTO
                    ),
                    currentMode = currentMode,
                    onModeSelected = onModeSelected
                )
                Spacer(modifier = Modifier.height(20.dp))
            } else {
                Spacer(modifier = Modifier.height(60.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!isRecording) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .clickable { onGalleryClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoLibrary,
                            contentDescription = "Gallery",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                ExpressiveRecordButton(
                    isRecording = isRecording,
                    onClick = onRecordClick,
                    size = 90.dp,
                    modifier = Modifier.align(Alignment.Center)
                )

                if (!isRecording) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = 98.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .clickable { onSwitchCameraClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        val rotation by animateFloatAsState(
                            targetValue = if (cameraId == "1") 180f else 0f,
                            label = "cameraFlip"
                        )
                        Icon(
                            imageVector = Icons.Outlined.FlipCameraAndroid,
                            contentDescription = "Switch Camera",
                            tint = Color.White,
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(rotation)
                        )
                    }
                }

                if (!isRecording) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(if (showHistogram) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onHistogramClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = "Histogram",
                            tint = if (showHistogram) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PeekingCarouselModeSelector(
    modes: List<CameraMode>,
    currentMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val sakuraPink = Color(0xFFFFD700)
    val coroutineScope = rememberCoroutineScope()

    val initialPage = remember { modes.indexOf(currentMode).coerceAtLeast(0) }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { modes.size }
    )

    LaunchedEffect(pagerState.currentPage) {
        val selectedMode = modes[pagerState.currentPage]
        if (selectedMode == currentMode) return@LaunchedEffect
        delay(150)
        onModeSelected(selectedMode)
    }

    LaunchedEffect(currentMode) {
        val targetPage = modes.indexOf(currentMode)
        if (targetPage != -1 && targetPage != pagerState.currentPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val itemWidth = 100.dp
    val horizontalPadding = (screenWidth - itemWidth) / 2

    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        pageSize = PageSize.Fixed(itemWidth),
        verticalAlignment = Alignment.CenterVertically,
        beyondViewportPageCount = 2
    ) { page ->
        val mode = modes[page]
        val isSelected = pagerState.currentPage == page

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                    val scale = 1f - (pageOffset * 0.3f).coerceIn(0f, 0.3f)
                    val alpha = 1f - (pageOffset * 0.6f).coerceIn(0f, 0.6f)
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(page)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = mode.label,
                color = if (isSelected) sakuraPink else Color.White,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}