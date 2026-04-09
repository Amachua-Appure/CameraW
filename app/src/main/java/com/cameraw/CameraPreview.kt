package com.cameraw

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.max

class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    fun setAspectRatio(width: Int, height: Int) {
        require(width >= 0 && height >= 0) { "Size cannot be negative." }
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
        } else {
            if (width < height * ratioWidth / ratioHeight) {
                setMeasuredDimension(width, width * ratioHeight / ratioWidth)
            } else {
                setMeasuredDimension(height * ratioWidth / ratioHeight, height)
            }
        }
    }
}

@Composable
fun CameraPreview(
    previewSize: Size,
    isRecording: Boolean,
    isFrontCamera: Boolean,
    onSurfaceTextureAvailable: (SurfaceTexture, Int, Int) -> Unit,
    onSurfaceTextureDestroyed: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val displayRotation = LocalView.current.display.rotation

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { context ->
                AutoFitTextureView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                            setAspectRatio(previewSize.height, previewSize.width)
                            surface.setDefaultBufferSize(previewSize.width, previewSize.height)
                            updateTransform(this@apply, w, h, previewSize, displayRotation, isFrontCamera)
                            onSurfaceTextureAvailable(surface, w, h)
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {
                            surface.setDefaultBufferSize(previewSize.width, previewSize.height)
                            updateTransform(this@apply, w, h, previewSize, displayRotation, isFrontCamera)
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean =
                            onSurfaceTextureDestroyed()

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) { }
                    }
                }
            },
            modifier = Modifier.wrapContentSize(),
            update = { view ->
                if (view.isAvailable) {
                    view.setAspectRatio(previewSize.height, previewSize.width)
                    updateTransform(view, view.width, view.height, previewSize, displayRotation, isFrontCamera)
                }
            }
        )
    }
}

private fun updateTransform(
    view: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    previewSize: Size,
    rotation: Int,
    isFrontCamera: Boolean
) {
    if (viewWidth == 0 || viewHeight == 0 || previewSize.width == 0 || previewSize.height == 0) return

    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()

    if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = max(
            viewHeight.toFloat() / previewSize.height,
            viewWidth.toFloat() / previewSize.width
        )
        matrix.postScale(scale, scale, centerX, centerY)
        matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
    } else if (Surface.ROTATION_180 == rotation) {
        matrix.postRotate(180f, centerX, centerY)
    }

    if (isFrontCamera) {
        matrix.postScale(-1f, 1f, centerX, centerY)
    }

    view.setTransform(matrix)
}