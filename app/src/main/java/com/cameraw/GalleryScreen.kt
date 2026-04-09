package com.cameraw

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.ViewGroup
import android.content.pm.ActivityInfo
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class CameraWMedia(
    val uri: Uri,
    val isVideo: Boolean,
    val name: String,
    val dateModified: Long
)

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(initialUri: Uri? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var mediaList by remember { mutableStateOf<List<CameraWMedia>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val sakuraPink = Color(0xFFFFB7C5)
    val pureBlack = Color(0xFF000000)

    DisposableEffect(Unit) {
        val window = activity?.window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window?.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                window?.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val fetchedMedia = mutableListOf<CameraWMedia>()
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )
            val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND (" +
                    "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " +
                    "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?)"
            val selectionArgs = arrayOf("%DCIM/CameraW%", "image/%", "video/%")
            val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            val queryUri = MediaStore.Files.getContentUri("external")
            context.contentResolver.query(queryUri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val mime = cursor.getString(mimeCol) ?: ""
                    val name = cursor.getString(nameCol) ?: "Unknown"
                    val date = cursor.getLong(dateCol)
                    val isVideo = mime.startsWith("video/")
                    val baseUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val uri = ContentUris.withAppendedId(baseUri, id)
                    fetchedMedia.add(CameraWMedia(uri, isVideo, name, date))
                }
            }
            mediaList = fetchedMedia
            isLoading = false
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(pureBlack), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = sakuraPink)
        }
        return
    }

    if (mediaList.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(pureBlack), contentAlignment = Alignment.Center) {
            Text("No media found in CameraW folder", color = sakuraPink)
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = sakuraPink)
            }
        }
        return
    }

    val initialIndex = mediaList.indexOfFirst { it.uri == initialUri }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { mediaList.size })
    val isZoomed = remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().background(pureBlack)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !isZoomed.value
        ) { page ->
            val media = mediaList[page]
            if (abs(pagerState.currentPage - page) <= 1) {
                MediaViewer(
                    media = media,
                    isCurrentPage = pagerState.currentPage == page,
                    onZoomChanged = { zoomed ->
                        isZoomed.value = zoomed
                    }
                )
            }
        }

        val currentMedia = mediaList[pagerState.currentPage]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.background(pureBlack.copy(alpha = 0.5f), MaterialTheme.shapes.small)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = sakuraPink)
            }

            IconButton(
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = if (currentMedia.isVideo) "video/*" else "image/*"
                        putExtra(Intent.EXTRA_STREAM, currentMedia.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Media"))
                },
                modifier = Modifier.background(pureBlack.copy(alpha = 0.5f), MaterialTheme.shapes.small)
            ) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = sakuraPink)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@OptIn(UnstableApi::class)
@Composable
fun MediaViewer(media: CameraWMedia, isCurrentPage: Boolean, onZoomChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = context.getSystemService(AudioManager::class.java)

    if (media.isVideo) {
        val exoPlayer = remember {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(media.uri))
                prepare()
            }
        }

        LaunchedEffect(isCurrentPage) {
            exoPlayer.playWhenReady = isCurrentPage
        }

        DisposableEffect(media.uri) {
            onDispose { exoPlayer.release() }
        }

        val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat() }
        var currentVolume by remember { mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume) }
        var currentBrightness by remember { mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.coerceAtLeast(0f) ?: 0.5f) }

        var showVolumeIndicator by remember { mutableStateOf(false) }
        var showBrightnessIndicator by remember { mutableStateOf(false) }
        var activeDragArea by remember { mutableIntStateOf(0) }

        Box(modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        if (offset.x < size.width / 2) {
                            activeDragArea = 1
                            showBrightnessIndicator = true
                            val windowBright = activity?.window?.attributes?.screenBrightness ?: -1f
                            currentBrightness = if (windowBright < 0) 0.5f else windowBright
                        } else {
                            activeDragArea = 2
                            showVolumeIndicator = true
                            currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume
                        }
                    },
                    onDragEnd = {
                        showBrightnessIndicator = false
                        showVolumeIndicator = false
                        activeDragArea = 0
                    },
                    onDragCancel = {
                        showBrightnessIndicator = false
                        showVolumeIndicator = false
                        activeDragArea = 0
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val delta = -(dragAmount / size.height)

                        if (activeDragArea == 1) {
                            currentBrightness = (currentBrightness + delta).coerceIn(0f, 1f)
                            activity?.window?.let { window ->
                                val attributes = window.attributes
                                attributes.screenBrightness = currentBrightness
                                window.attributes = attributes
                            }
                        } else if (activeDragArea == 2) {
                            currentVolume = (currentVolume + delta).coerceIn(0f, 1f)
                            audioManager.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                (currentVolume * maxVolume).toInt(),
                                0
                            )
                        }
                    }
                )
            }
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        setFullscreenButtonClickListener { isFullScreen ->
                            val window = activity?.window ?: return@setFullscreenButtonClickListener
                            val insetsController = WindowInsetsControllerCompat(window, window.decorView)

                            if (isFullScreen) {
                                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                insetsController.show(WindowInsetsCompat.Type.systemBars())
                                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (showBrightnessIndicator) {
                IndicatorOverlay(
                    iconText = "☀",
                    value = currentBrightness,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }

            if (showVolumeIndicator) {
                IndicatorOverlay(
                    iconText = "🔊",
                    value = currentVolume,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    } else {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val imageLoader = remember { CoilUtils.createImageLoader(context) }
        var painterState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

        val isPng = media.name.endsWith(".png", ignoreCase = true)
        val request = ImageRequest.Builder(context)
            .data(media.uri)
            .size(4000)
            .allowHardware(true)
            .apply {
                if (isPng && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    colorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.BT2020_PQ))
                }
            }
            .build()

        LaunchedEffect(isCurrentPage) {
            if (!isCurrentPage) {
                scale = 1f
                offset = Offset.Zero
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = request,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 10f)
                            scale = newScale
                            offset += pan
                            onZoomChanged(newScale > 1f)
                        }
                    },
                onState = { painterState = it }
            )

            if (painterState is AsyncImagePainter.State.Loading) {
                CircularProgressIndicator(
                    color = Color(0xFFFFB7C5),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (painterState is AsyncImagePainter.State.Error) {
                Text(
                    "Error loading image",
                    color = Color.Red,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
fun IndicatorOverlay(iconText: String, value: Float, modifier: Modifier) {
    Column(
        modifier = modifier
            .padding(32.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = iconText, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .width(6.dp)
                .height(120.dp)
                .background(Color.DarkGray, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(value)
                    .background(Color(0xFFFFB7C5), RoundedCornerShape(3.dp))
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "${(value * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}