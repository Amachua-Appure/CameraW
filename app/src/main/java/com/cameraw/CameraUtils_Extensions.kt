package com.cameraw

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.LensShadingMap
import android.media.ExifInterface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import android.util.Rational
import android.util.Size
import androidx.annotation.RequiresApi
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.min
import java.io.ByteArrayInputStream

enum class CameraMode(val label: String) {
    PHOTO("Photo"),
    PRO_VIDEO("Pro Video"),
    RAW_VIDEO("Raw Video");
    override fun toString(): String = label
}
data class FrameEntry(val raw: ShortArray, val dx: Float, val dy: Float)

object CameraWISP {
    init { System.loadLibrary("cameraw_isp") }
    external fun processBurstNative(
        frames: Array<ShortArray>,
        outRgb: ByteArray?,
        width: Int, height: Int, blackLevel: Int, rGain: Float, bGain: Float,
        maxVal: Float, matrix: FloatArray, bitDepth: Int,
        lscMapArray: FloatArray, lscMapW: Int, lscMapH: Int,
        noiseScale: Float, noiseOffset: Float
    ): ByteArray?

    external fun initYuvAccumulator(width: Int, height: Int)
    external fun addYuvFrame(
        yBuf: ByteBuffer,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer,
        width: Int,
        height: Int,
        yStride: Int,
        uvStride: Int,
        uvPixelStride: Int
    )
    external fun finishYuvAccumulator(width: Int, height: Int): ByteArray
}

object ImageUtils {
    private val XYZ_D50_TO_BT2020_D65 = floatArrayOf(
        1.64734f, -0.39357f, -0.23599f,
        -0.68259f,  1.64758f,  0.01281f,
        0.02963f, -0.06288f,  1.25313f
    )

    private fun extractTransform(transform: ColorSpaceTransform?): FloatArray {
        val matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        transform?.let {
            val rationals = arrayOfNulls<Rational>(9)
            it.copyElements(rationals, 0)
            for (i in 0..8) matrix[i] = rationals[i]?.toFloat() ?: 0f
        }
        return matrix
    }

    private fun getKelvin(illuminant: Int): Float = when (illuminant) {
        1 -> 6500f; 2 -> 5000f; 3 -> 3400f; 9 -> 11000f; 10 -> 6500f
        11 -> 4800f; 12 -> 6500f; 13 -> 5000f; 14 -> 4000f; 15 -> 3000f
        17 -> 2850f; 18 -> 4800f; 19 -> 6774f; 20 -> 5500f; 21 -> 6500f
        22 -> 7500f; 23 -> 5000f; 24 -> 3200f; else -> 5500f
    }

    private fun interpolateForwardMatrices(
        fm1: FloatArray, fm2: FloatArray, ill1: Int, ill2: Int, wbTemp: Int
    ): FloatArray {
        val t1 = getKelvin(ill1)
        val t2 = getKelvin(ill2)
        val invT = 1.0f / wbTemp.toFloat().coerceAtLeast(1000f)
        val invT1 = 1.0f / t1
        val invT2 = 1.0f / t2
        val t = if (invT1 == invT2) 0.0f else ((invT - invT1) / (invT2 - invT1)).coerceIn(0f, 1f)
        val result = FloatArray(9)
        for (i in 0..8) result[i] = fm1[i] * (1f - t) + fm2[i] * t
        return result
    }

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun processBurst(
        images: List<Image>, file: File, whiteLevel: Int, blackLevel: Int, shadingMap: LensShadingMap?,
        sensorOrientation: Int, deviceOrientation: Int, iso: Int, shutter: Long, wbTemp: Int,
        compressionLevel: Int, bitDepth: Int, rGain: Float, bGain: Float,
        isFrontCamera: Boolean, noiseScale: Float, noiseOffset: Float,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult
    ) {
        if (images.isEmpty()) return
        val startTime = System.currentTimeMillis()

        val width = images[0].width; val height = images[0].height; val count = images.size

        Log.d("CameraWISP", "Unpacking RAW data...")
        val rawArrays = Array(count) { unpackRaw(images[it]) }

        val safeMaxRaw = (whiteLevel - blackLevel).toFloat()
        val fm1 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)
        val fm2 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)
        val ill1 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt() ?: 21
        val ill2 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 17

        val fm1Array = extractTransform(fm1)
        val fm2Array = extractTransform(fm2)

        val cameraToXyz = interpolateForwardMatrices(fm1Array, fm2Array, ill1, ill2, wbTemp)
        val finalMatrix = multiplyMatrices(XYZ_D50_TO_BT2020_D65, cameraToXyz)
        val cicp = if (bitDepth == 16) byteArrayOf(9, 16, 0, 1) else byteArrayOf(9, 18, 0, 1)

        val lscW = shadingMap?.columnCount ?: 0
        val lscH = shadingMap?.rowCount ?: 0
        val lscArray = FloatArray(shadingMap?.gainFactorCount ?: 0)
        shadingMap?.copyGainFactors(lscArray, 0)

        Log.d("CameraWISP", "Firing GPU Pipeline ($bitDepth-bit)...")

        when (bitDepth) {
            14 -> {
                val rawBytes = CameraWISP.processBurstNative(
                    rawArrays, null, width, height, blackLevel, rGain, bGain,
                    safeMaxRaw, finalMatrix, bitDepth, lscArray, lscW, lscH,
                    noiseScale, noiseOffset
                )

                if (rawBytes != null) {
                    val dngCreator = DngCreator(characteristics, captureResult)

                    val rotationDegrees = if (isFrontCamera) {
                        (sensorOrientation + deviceOrientation) % 360
                    } else {
                        (sensorOrientation - deviceOrientation + 360) % 360
                    }

                    val exifOrientation = when (rotationDegrees) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90
                        180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270
                        else -> ExifInterface.ORIENTATION_NORMAL
                    }
                    dngCreator.setOrientation(exifOrientation)

                    FileOutputStream(file).use { fos ->
                        dngCreator.writeInputStream(
                            fos,
                            Size(width, height),
                            ByteArrayInputStream(rawBytes),
                            0
                        )
                    }
                    dngCreator.close()
                    Log.d("CameraWISP", "DNG Saved Successfully.")
                } else {
                    Log.e("CameraWISP", "Fatal: DNG Native Extraction failed!")
                }
            }
            16 -> {
                val bytesPerPixel = 6
                val rowStride = width * bytesPerPixel + 1
                val outRgb = ByteArray(height * rowStride)

                CameraWISP.processBurstNative(
                    rawArrays, outRgb, width, height, blackLevel, rGain, bGain,
                    safeMaxRaw, finalMatrix, bitDepth, lscArray, lscW, lscH,
                    noiseScale, noiseOffset
                )

                FileOutputStream(file).use { fos ->
                    BufferedOutputStream(fos).use { bos ->
                        bos.write(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()))
                        writeChunk(bos, "IHDR", createIhdr(width, height, 16))
                        writeChunk(bos, "cICP", cicp)

                        val deflater = Deflater(compressionLevel)
                        val compBuffer = ByteArray(32768)
                        deflater.setInput(outRgb)
                        deflater.finish()

                        while (!deflater.finished()) {
                            val len = deflater.deflate(compBuffer)
                            if (len > 0) writeChunk(bos, "IDAT", Arrays.copyOf(compBuffer, len))
                        }
                        writeChunk(bos, "IEND", ByteArray(0))
                    }
                }

                try {
                    val exif = ExifInterface(file.absolutePath)
                    val rotationDegrees = if (isFrontCamera) {
                        (sensorOrientation + deviceOrientation) % 360
                    } else {
                        (sensorOrientation - deviceOrientation + 360) % 360
                    }

                    val exifOrientation = when (rotationDegrees) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90
                        180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270
                        else -> ExifInterface.ORIENTATION_NORMAL
                    }
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                    exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (shutter / 1_000_000_000.0).toString())
                    exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, iso.toString())
                    exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, "0")
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "Hardware Gains: R=$rGain, B=$bGain")
                    exif.saveAttributes()
                    Log.d("CameraWISP", "EXIF Data Successfully Written to PNG.")
                } catch (e: Exception) {
                    Log.e("CameraWISP", "Failed to write EXIF data", e)
                }
            }
            else -> {
                val avifBytes = CameraWISP.processBurstNative(
                    rawArrays, null, width, height, blackLevel, rGain, bGain,
                    safeMaxRaw, finalMatrix, bitDepth, lscArray, lscW, lscH,
                    noiseScale, noiseOffset
                )

                if (avifBytes != null) {
                    FileOutputStream(file).use { fos -> fos.write(avifBytes) }
                    Log.d("CameraWISP", "AVIF Encoded flawlessly via Bytedeco FFmpeg (10-bit HLG YUV420)")
                } else {
                    Log.e("CameraWISP", "Fatal: AVIF Native Encoding failed!")
                }
            }
        }

        Log.d("CameraWISP", "Total Burst Time: ${System.currentTimeMillis() - startTime}ms")
    }

    private fun unpackRaw(img: Image): ShortArray {
        val w = img.width
        val h = img.height
        val p = img.planes[0]
        val b = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val o = ShortArray(w * h)
        val r = ByteArray(rs)
        b.rewind()
        for (y in 0 until h) {
            b.position(y * rs)
            b.get(r, 0, min(rs, b.remaining()))
            for (x in 0 until w) {
                val i = x * ps
                o[y * w + x] = ((r[i].toInt() and 0xFF) or ((r[i + 1].toInt() and 0xFF) shl 8)).toShort()
            }
        }
        return o
    }

    private fun multiplyMatrices(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) for (k in 0..2) r[i * 3 + j] += a[i * 3 + k] * b[k * 3 + j]
        return r
    }

    private fun createIhdr(w: Int, h: Int, bitDepth: Int) = ByteBuffer.allocate(13).apply {
        order(ByteOrder.BIG_ENDIAN)
        putInt(w)
        putInt(h)
        put(bitDepth.toByte())
        put(2)
        put(0)
        put(0)
        put(0)
    }.array()

    private fun writeChunk(o: BufferedOutputStream, t: String, d: ByteArray) {
        val tb = t.toByteArray()
        o.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(d.size).array())
        o.write(tb)
        o.write(d)
        val c = CRC32()
        c.update(tb)
        c.update(d)
        o.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(c.value.toInt()).array())
    }
}