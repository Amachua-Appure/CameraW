package com.cameraw

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import coil.ImageLoader
import coil.decode.BitmapFactoryDecoder
import coil.request.CachePolicy
import coil.util.DebugLogger
import com.github.awxkee.avifcoil.decoder.HeifDecoder

object CoilUtils {
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun createImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(HeifDecoder.Factory())
                add(BitmapFactoryDecoder.Factory())
            }
            .bitmapConfig(Bitmap.Config.RGBA_1010102)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .respectCacheHeaders(false)
            .logger(DebugLogger())
            .build()
    }
}