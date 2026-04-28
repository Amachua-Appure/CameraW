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
    val ADOBE_REC2020_PQ_ICC = byteArrayOf(
        0x52.toByte(), 0x65.toByte(), 0x63.toByte(), 0x2E.toByte(), 0x20.toByte(), 0x32.toByte(), 0x30.toByte(), 0x32.toByte(), 0x30.toByte(), 0x20.toByte(), 0x50.toByte(), 0x51.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x78.toByte(), 0x9C.toByte(), 0xED.toByte(), 0x99.toByte(), 0xF9.toByte(), 0x53.toByte(), 0x14.toByte(), 0x67.toByte(), 0x1A.toByte(), 0xC7.toByte(),
        0x9F.toByte(), 0xB7.toByte(), 0x7B.toByte(), 0xEE.toByte(), 0x03.toByte(), 0x18.toByte(), 0x60.toByte(), 0xB8.toByte(), 0x8F.toByte(), 0xE1.toByte(), 0x18.toByte(), 0x44.toByte(),
        0x2E.toByte(), 0x01.toByte(), 0x15.toByte(), 0x04.toByte(), 0x15.toByte(), 0x11.toByte(), 0x06.toByte(), 0xD0.toByte(), 0x70.toByte(), 0xC9.toByte(), 0x25.toByte(), 0xA7.toByte(),
        0x08.toByte(), 0x0C.toByte(), 0x03.toByte(), 0x88.toByte(), 0x20.toByte(), 0x64.toByte(), 0x18.toByte(), 0x10.toByte(), 0x44.toByte(), 0xC5.toByte(), 0x83.toByte(), 0x23.toByte(),
        0x46.toByte(), 0x0D.toByte(), 0x1E.toByte(), 0xC8.toByte(), 0x82.toByte(), 0x12.toByte(), 0x0E.toByte(), 0x51.toByte(), 0x54.toByte(), 0x2E.toByte(), 0x45.toByte(), 0x08.toByte(),
        0xBA.toByte(), 0x82.toByte(), 0xC2.toByte(), 0x4A.toByte(), 0xD4.toByte(), 0x48.toByte(), 0x88.toByte(), 0x12.toByte(), 0x41.toByte(), 0xD4.toByte(), 0x28.toByte(), 0x5E.toByte(),
        0xE1.toByte(), 0x30.toByte(), 0x48.toByte(), 0x08.toByte(), 0x11.toByte(), 0x3C.toByte(), 0x82.toByte(), 0x41.toByte(), 0xB6.toByte(), 0x07.toByte(), 0x52.toByte(), 0x6B.toByte(),
        0x6D.toByte(), 0xD5.toByte(), 0x66.toByte(), 0xFF.toByte(), 0x01.toByte(), 0xE7.toByte(), 0xFB.toByte(), 0xA9.toByte(), 0xEE.toByte(), 0xFE.toByte(), 0xD6.toByte(), 0xD3.toByte(),
        0x4F.toByte(), 0x3D.toByte(), 0xFD.toByte(), 0xBE.toByte(), 0xDD.toByte(), 0xBF.toByte(), 0x7C.toByte(), 0xAB.toByte(), 0x1A.toByte(), 0x40.toByte(), 0xBD.toByte(), 0x6B.toByte(),
        0xA5.toByte(), 0xC0.toByte(), 0xC9.toByte(), 0x85.toByte(), 0xC4.toByte(), 0x03.toByte(), 0x48.toByte(), 0xDC.toByte(), 0x24.toByte(), 0x11.toByte(), 0xFB.toByte(), 0xBA.toByte(),
        0x39.toByte(), 0xF1.toByte(), 0x82.toByte(), 0x82.toByte(), 0x43.toByte(), 0x78.toByte(), 0xD4.toByte(), 0x21.toByte(), 0x40.toByte(), 0x04.toByte(), 0x2C.toByte(), 0x20.toByte(),
        0x14.toByte(), 0x29.toByte(), 0x4C.toByte(), 0x49.toByte(), 0x86.toByte(), 0xBF.toByte(), 0x17.toByte(), 0x02.toByte(), 0x78.toByte(), 0xD3.toByte(), 0x27.toByte(), 0x3D.toByte(),
        0x03.toByte(), 0xF4.toByte(), 0x98.toByte(), 0x49.toByte(), 0x67.toByte(), 0xFD.toByte(), 0x9F.toByte(), 0xDE.toByte(), 0xFF.toByte(), 0x25.toByte(), 0x86.toByte(), 0x70.toByte(),
        0x83.toByte(), 0x50.toByte(), 0x3A.toByte(), 0xFF.toByte(), 0x4F.toByte(), 0xE2.toByte(), 0x60.toByte(), 0x6D.toByte(), 0x96.toByte(), 0x24.toByte(), 0x4B.toByte(), 0x88.toByte(),
        0x91.toByte(), 0x34.toByte(), 0xC2.toByte(), 0x2B.toByte(), 0x8B.toByte(), 0x89.toByte(), 0x85.toByte(), 0x10.toByte(), 0x5E.toByte(), 0x4B.toByte(), 0xEA.toByte(), 0x63.toByte(),
        0xE7.toByte(), 0xBC.toByte(), 0xA5.toByte(), 0xD4.toByte(), 0x47.toByte(), 0xCD.toByte(), 0x79.toByte(), 0xC1.toByte(), 0x6C.toByte(), 0x8F.toByte(), 0xBF.toByte(), 0xAF.toByte(),
        0x33.toByte(), 0xE1.toByte(), 0x83.toByte(), 0x00.toByte(), 0x68.toByte(), 0xAC.toByte(), 0xD8.toByte(), 0x59.toByte(), 0x4F.toByte(), 0x8F.toByte(), 0x96.toByte(), 0xFA.toByte(),
        0xA8.toByte(), 0x59.toByte(), 0xCF.toByte(), 0x49.toByte(), 0x96.toByte(), 0xFA.toByte(), 0x68.toByte(), 0x51.toByte(), 0x8A.toByte(), 0x10.toByte(), 0x40.toByte(), 0x7D.toByte(),
        0x2B.toByte(), 0xD1.toByte(), 0xAF.toByte(), 0x25.toByte(), 0x4C.toByte(), 0x16.toByte(), 0x13.toByte(), 0xF3.toByte(), 0xD5.toByte(), 0x8B.toByte(), 0x08.toByte(), 0xBF.toByte(),
        0xE8.toByte(), 0xAF.toByte(), 0xE7.toByte(), 0x02.toByte(), 0x5D.toByte(), 0x81.toByte(), 0x18.toByte(), 0x40.toByte(), 0xEC.toByte(), 0x79.toByte(), 0x6E.toByte(), 0x39.toByte(),
        0x13.toByte(), 0x3E.toByte(), 0xB3.toByte(), 0x7B.toByte(), 0x41.toByte(), 0x2A.toByte(), 0x5D.toByte(), 0x1F.toByte(), 0x6B.toByte(), 0xB5.toByte(), 0xF1.toByte(), 0x00.toByte(),
        0x6E.toByte(), 0x29.toByte(), 0x33.toByte(), 0x33.toByte(), 0x33.toByte(), 0xBB.toByte(), 0x3F.toByte(), 0xD6.toByte(), 0x4C.toByte(), 0x36.toByte(), 0x12.toByte(), 0xF5.toByte(),
        0xA7.toByte(), 0x00.toByte(), 0xD4.toByte(), 0xFA.toByte(), 0x8F.toByte(), 0x35.toByte(), 0x1E.toByte(), 0x15.toByte(), 0x80.toByte(), 0x59.toByte(), 0x0B.toByte(), 0xD0.toByte(),
        0x45.toByte(), 0x17.toByte(), 0xA6.toByte(), 0x8A.toByte(), 0xD3.toByte(), 0xE6.toByte(), 0x6A.toByte(), 0xA4.toByte(), 0xBF.toByte(), 0x7B.toByte(), 0x6B.toByte(), 0xFF.toByte(),
        0x0D.toByte(), 0xF6.toByte(), 0x1F.toByte(), 0xF0.toByte(), 0xBF.toByte(), 0x20.toByte(), 0xCD.toByte(), 0x42.toByte(), 0x9E.toByte(), 0x85.toByte(), 0x32.toByte(), 0x0B.toByte(),
        0x95.toByte(), 0x80.toByte(), 0x46.toByte(), 0x40.toByte(), 0x9F.toByte(), 0x85.toByte(), 0x41.toByte(), 0xC0.toByte(), 0x24.toByte(), 0xBE.toByte(), 0x10.toByte(), 0x0B.toByte(),
        0xD8.toByte(), 0x04.toByte(), 0x72.toByte(), 0x20.toByte(), 0x4F.toByte(), 0xA0.toByte(), 0x00.toByte(), 0x1C.toByte(), 0x02.toByte(), 0x45.toByte(), 0x50.toByte(), 0x02.toByte(),
        0x65.toByte(), 0x02.toByte(), 0x2E.toByte(), 0xA8.toByte(), 0x80.toByte(), 0x2A.toByte(), 0xA8.toByte(), 0x81.toByte(), 0x3A.toByte(), 0x68.toByte(), 0x80.toByte(), 0x26.toByte(),
        0x68.toByte(), 0x81.toByte(), 0x36.toByte(), 0xE8.toByte(), 0x80.toByte(), 0x2E.toByte(), 0xF0.toByte(), 0x40.toByte(), 0x0F.toByte(), 0xF4.toByte(), 0xC1.toByte(), 0x00.toByte(),
        0x0C.toByte(), 0x81.toByte(), 0x0F.toByte(), 0xF3.toByte(), 0xC0.toByte(), 0x18.toByte(), 0xE6.toByte(), 0x83.toByte(), 0x09.toByte(), 0x98.toByte(), 0x81.toByte(), 0x39.toByte(),
        0x58.toByte(), 0x80.toByte(), 0x25.toByte(), 0x58.toByte(), 0xC1.toByte(), 0x42.toByte(), 0x58.toByte(), 0x04.toByte(), 0x36.toByte(), 0x60.toByte(), 0x0B.toByte(), 0x76.toByte(),
        0xB0.toByte(), 0x14.toByte(), 0x96.toByte(), 0x81.toByte(), 0x03.toByte(), 0x38.toByte(), 0x82.toByte(), 0x13.toByte(), 0x38.toByte(), 0x83.toByte(), 0x0B.toByte(), 0xB8.toByte(),
        0xC1.toByte(), 0x6A.toByte(), 0x70.toByte(), 0x07.toByte(), 0x4F.toByte(), 0xF0.toByte(), 0x06.toByte(), 0x1F.toByte(), 0xF0.toByte(), 0x83.toByte(), 0x00.toByte(), 0x08.toByte(),
        0x84.toByte(), 0x10.toByte(), 0x08.toByte(), 0x83.toByte(), 0x70.toByte(), 0x88.toByte(), 0x00.toByte(), 0x21.toByte(), 0x88.toByte(), 0x20.toByte(), 0x0E.toByte(), 0xE2.toByte(),
        0x21.toByte(), 0x11.toByte(), 0x92.toByte(), 0x40.toByte(), 0x0C.toByte(), 0xA9.toByte(), 0xB0.toByte(), 0x19.toByte(), 0xB6.toByte(), 0xC0.toByte(), 0x36.toByte(), 0xD8.toByte(),
        0x01.toByte(), 0xBB.toByte(), 0x21.toByte(), 0x17.toByte(), 0xF6.toByte(), 0xC0.toByte(), 0x3E.toByte(), 0xC8.toByte(), 0x87.toByte(), 0x43.toByte(), 0x70.toByte(), 0x04.toByte(),
        0x8A.toByte(), 0xA0.toByte(), 0x04.toByte(), 0xCA.toByte(), 0xE0.toByte(), 0x38.toByte(), 0x54.toByte(), 0xC1.toByte(), 0x19.toByte(), 0xA8.toByte(), 0x83.toByte(), 0x06.toByte(),
        0x68.toByte(), 0x82.toByte(), 0x8B.toByte(), 0xD0.toByte(), 0x0A.toByte(), 0xED.toByte(), 0xF0.toByte(), 0x2D.toByte(), 0x7C.toByte(), 0x07.toByte(), 0x5D.toByte(), 0xD0.toByte(),
        0x0D.toByte(), 0xBD.toByte(), 0x70.toByte(), 0x1F.toByte(), 0xFA.toByte(), 0xE1.toByte(), 0x19.toByte(), 0x0C.toByte(), 0xC2.toByte(), 0x08.toByte(), 0x8C.toByte(), 0xC1.toByte(),
        0x04.toByte(), 0xBC.toByte(), 0x83.toByte(), 0x69.toByte(), 0x84.toByte(), 0x10.toByte(), 0x05.toByte(), 0x31.toByte(), 0x91.toByte(), 0x02.toByte(), 0xE2.toByte(), 0x22.toByte(),
        0x4D.toByte(), 0xC4.toByte(), 0x43.toByte(), 0x7C.toByte(), 0x64.toByte(), 0x8A.toByte(), 0x2C.toByte(), 0x91.toByte(), 0x0D.toByte(), 0x5A.toByte(), 0x86.toByte(), 0x56.toByte(),
        0x22.toByte(), 0x37.toByte(), 0xE4.toByte(), 0x89.toByte(), 0xFC.toByte(), 0x50.toByte(), 0x30.toByte(), 0x5A.toByte(), 0x8F.toByte(), 0x44.toByte(), 0x28.toByte(), 0x01.toByte(),
        0x89.toByte(), 0x51.toByte(), 0x06.toByte(), 0xCA.toByte(), 0x42.toByte(), 0xB9.toByte(), 0x68.toByte(), 0x3F.toByte(), 0x2A.toByte(), 0x40.toByte(), 0xC7.toByte(), 0xD0.toByte(),
        0x71.toByte(), 0x74.toByte(), 0x06.toByte(), 0x9D.toByte(), 0x43.toByte(), 0x17.toByte(), 0x50.toByte(), 0x1B.toByte(), 0xBA.toByte(), 0x8E.toByte(), 0x6E.toByte(), 0xA1.toByte(),
        0x3E.toByte(), 0xF4.toByte(), 0x18.toByte(), 0x0D.toByte(), 0xA1.toByte(), 0x31.toByte(), 0xF4.toByte(), 0x06.toByte(), 0x7D.toByte(), 0xC0.toByte(), 0x28.toByte(), 0x98.toByte(),
        0x3C.toByte(), 0xA6.toByte(), 0x8A.toByte(), 0xF1.toByte(), 0xB0.toByte(), 0xF9.toByte(), 0x98.toByte(), 0x35.toByte(), 0xB6.toByte(), 0x14.toByte(), 0x13.toByte(), 0x60.toByte(),
        0x9E.toByte(), 0x58.toByte(), 0x20.toByte(), 0x16.toByte(), 0x81.toByte(), 0xC5.toByte(), 0x63.toByte(), 0x12.toByte(), 0x6C.toByte(), 0x3B.toByte(), 0xF6.toByte(), 0x05.toByte(),
        0x76.toByte(), 0x08.toByte(), 0x2B.toByte(), 0xC1.toByte(), 0xAA.toByte(), 0xB0.toByte(), 0x06.toByte(), 0xAC.toByte(), 0x05.toByte(), 0xBB.toByte(), 0x8E.toByte(), 0xFD.toByte(),
        0x88.toByte(), 0x3D.toByte(), 0xC2.toByte(), 0x86.toByte(), 0xB1.toByte(), 0x09.toByte(), 0xEC.toByte(), 0x03.toByte(), 0xCE.toByte(), 0xC0.toByte(), 0xB9.toByte(), 0xB8.toByte(),
        0x1E.toByte(), 0x6E.toByte(), 0x8E.toByte(), 0xDB.toByte(), 0xE3.toByte(), 0xAE.toByte(), 0xB8.toByte(), 0x2F.toByte(), 0xBE.toByte(), 0x1E.toByte(), 0x4F.toByte(), 0xC0.toByte(),
        0x33.toByte(), 0xF0.toByte(), 0x5C.toByte(), 0xFC.toByte(), 0x30.toByte(), 0x5E.toByte(), 0x8E.toByte(), 0xD7.toByte(), 0xE3.toByte(), 0xAD.toByte(), 0x78.toByte(), 0x27.toByte(),
        0x7E.toByte(), 0x1F.toByte(), 0x1F.toByte(), 0xC2.toByte(), 0x27.toByte(), 0x49.toByte(), 0x38.toByte(), 0x89.toByte(), 0x43.toByte(), 0xE2.toByte(), 0x91.toByte(), 0x16.toByte(),
        0x90.toByte(), 0x56.toByte(), 0x90.toByte(), 0xBC.toByte(), 0x48.toByte(), 0xE1.toByte(), 0xA4.toByte(), 0x4D.toByte(), 0xA4.toByte(), 0xED.toByte(), 0xA4.toByte(), 0xAF.toByte(),
        0x48.toByte(), 0xA5.toByte(), 0xA4.toByte(), 0xB3.toByte(), 0xA4.toByte(), 0x36.toByte(), 0x52.toByte(), 0x37.toByte(), 0xE9.toByte(), 0x19.toByte(), 0x69.toByte(), 0x82.toByte(),
        0x4C.toByte(), 0x22.toByte(), 0xAB.toByte(), 0x90.toByte(), 0x8D.toByte(), 0xC9.toByte(), 0xF6.toByte(), 0x64.toByte(), 0x0F.toByte(), 0xF2.toByte(), 0x7A.toByte(), 0xB2.toByte(),
        0x98.toByte(), 0x9C.toByte(), 0x43.toByte(), 0x2E.toByte(), 0x22.toByte(), 0xD7.toByte(), 0x90.toByte(), 0xDB.toByte(), 0xC8.toByte(), 0x3D.toByte(), 0xE4.toByte(), 0x61.toByte(),
        0xF2.toByte(), 0x7B.toByte(), 0x8A.toByte(), 0x3C.toByte(), 0xC5.toByte(), 0x90.toByte(), 0x62.toByte(), 0x47.toByte(), 0xF1.toByte(), 0xA2.toByte(), 0x44.toByte(), 0x53.toByte(),
        0x32.toByte(), 0x28.toByte(), 0xF9.toByte(), 0x94.toByte(), 0x2A.toByte(), 0x4A.toByte(), 0x2B.toByte(), 0xA5.toByte(), 0x87.toByte(), 0x32.toByte(), 0x42.toByte(), 0x45.toByte(),
        0x54.toByte(), 0x35.toByte(), 0xAA.toByte(), 0x25.toByte(), 0x75.toByte(), 0x15.toByte(), 0x35.toByte(), 0x82.toByte(), 0x9A.toByte(), 0x4E.toByte(), 0x3D.toByte(), 0x48.toByte(),
        0xAD.toByte(), 0xA6.toByte(), 0x76.toByte(), 0x50.toByte(), 0xFB.toByte(), 0xA9.toByte(), 0x6F.toByte(), 0x69.toByte(), 0x1C.toByte(), 0x9A.toByte(), 0x29.toByte(), 0xCD.toByte(),
        0x95.toByte(), 0x16.toByte(), 0x49.toByte(), 0xDB.toByte(), 0x4A.toByte(), 0x2B.toByte(), 0xA2.toByte(), 0x35.toByte(), 0xD1.toByte(), 0xBA.toByte(), 0x69.toByte(), 0xA3.toByte(),
        0x74.toByte(), 0x3A.toByte(), 0xDD.toByte(), 0x88.toByte(), 0x2E.toByte(), 0xA0.toByte(), 0x47.toByte(), 0xD2.toByte(), 0xB3.toByte(), 0xE8.toByte(), 0xA5.toByte(), 0xF4.toByte(),
        0x56.toByte(), 0xFA.toByte(), 0x43.toByte(), 0xFA.toByte(), 0x7B.toByte(), 0x86.toByte(), 0x06.toByte(), 0xC3.toByte(), 0x9E.toByte(), 0x11.toByte(), 0xC2.toByte(), 0xC8.toByte(),
        0x64.toByte(), 0x94.toByte(), 0x30.toByte(), 0xAE.toByte(), 0x30.toByte(), 0x9E.toByte(), 0x30.toByte(), 0x11.toByte(), 0xD3.toByte(), 0x90.toByte(), 0xE9.toByte(), 0xCA.toByte(),
        0x8C.toByte(), 0x67.toByte(), 0xEE.toByte(), 0x67.toByte(), 0x36.toByte(), 0x30.toByte(), 0xEF.toByte(), 0x32.toByte(), 0xA7.toByte(), 0x58.toByte(), 0xBA.toByte(), 0x2C.toByte(),
        0x01.toByte(), 0x2B.toByte(), 0x9E.toByte(), 0x75.toByte(), 0x80.toByte(), 0x75.toByte(), 0x81.toByte(), 0xF5.toByte(), 0x98.toByte(), 0x4D.toByte(), 0x66.toByte(), 0x9B.toByte(),
        0xB3.toByte(), 0x03.toByte(), 0xD8.toByte(), 0xDB.toByte(), 0xD9.toByte(), 0xA7.toByte(), 0xD8.toByte(), 0xDD.toByte(), 0xEC.toByte(), 0x29.toByte(), 0x39.toByte(), 0xBE.toByte(),
        0x9C.toByte(), 0xB7.toByte(), 0x5C.toByte(), 0xA6.toByte(), 0x5C.toByte(), 0x95.toByte(), 0xDC.toByte(), 0x1D.toByte(), 0xB9.toByte(), 0x19.toByte(), 0x79.toByte(), 0x33.toByte(),
        0xF9.toByte(), 0x60.toByte(), 0xF9.toByte(), 0x3C.toByte(), 0xF9.toByte(), 0x66.toByte(), 0xF9.toByte(), 0x01.toByte(), 0x05.toByte(), 0x65.toByte(), 0x05.toByte(), 0x81.toByte(),
        0x82.toByte(), 0x44.toByte(), 0xE1.toByte(), 0xA4.toByte(), 0x42.toByte(), 0x1F.toByte(), 0x87.toByte(), 0xC6.toByte(), 0xB1.toByte(), 0xE7.toByte(), 0x24.toByte(), 0x72.toByte(),
        0xCA.toByte(), 0x39.toByte(), 0xBD.toByte(), 0x8A.toByte(), 0x34.toByte(), 0xC5.toByte(), 0xE5.toByte(), 0x8A.toByte(), 0x29.toByte(), 0x8A.toByte(), 0xA7.toByte(), 0x15.toByte(),
        0x9F.toByte(), 0x28.toByte(), 0xA9.toByte(), 0x28.toByte(), 0x79.toByte(), 0x29.toByte(), 0xE5.toByte(), 0x2A.toByte(), 0x5D.toByte(), 0x51.toByte(), 0x7A.toByte(), 0xA7.toByte(),
        0x6C.toByte(), 0xA5.toByte(), 0x9C.toByte(), 0xA0.toByte(), 0x7C.toByte(), 0x4A.toByte(), 0xF9.toByte(), 0x39.toByte(), 0x57.toByte(), 0x97.toByte(), 0x1B.toByte(), 0xCA.toByte(),
        0x2D.toByte(), 0xE6.toByte(), 0xDE.toByte(), 0x53.toByte(), 0xE1.toByte(), 0xAA.toByte(), 0xF8.toByte(), 0xAB.toByte(), 0x14.toByte(), 0xA8.toByte(), 0xDC.toByte(), 0x55.toByte(),
        0x55.toByte(), 0x51.toByte(), 0x0D.toByte(), 0x52.toByte(), 0x3D.toByte(), 0xAA.toByte(), 0xDA.toByte(), 0xAF.toByte(), 0xA6.toByte(), 0xA7.toByte(), 0x26.toByte(), 0x52.toByte(),
        0xAB.toByte(), 0x56.toByte(), 0x1B.toByte(), 0x57.toByte(), 0xB7.toByte(), 0x51.toByte(), 0xDF.toByte(), 0xA6.toByte(), 0x7E.toByte(), 0x4D.toByte(), 0x83.toByte(), 0xAD.toByte(),
        0x11.toByte(), 0xA8.toByte(), 0x51.toByte(), 0xA1.toByte(), 0xF1.toByte(), 0x52.toByte(), 0xD3.toByte(), 0x56.toByte(), 0x73.toByte(), 0xB7.toByte(), 0xE6.toByte(), 0x6D.toByte(),
        0x2D.toByte(), 0x6D.toByte(), 0xAD.toByte(), 0x8D.toByte(), 0x5A.toByte(), 0x97.toByte(), 0xB4.toByte(), 0x19.toByte(), 0xDA.toByte(), 0xA1.toByte(), 0xDA.toByte(), 0xB5.toByte(),
        0xDA.toByte(), 0x33.toByte(), 0x3A.toByte(), 0xBE.toByte(), 0x3A.toByte(), 0x27.toByte(), 0x75.toByte(), 0xA6.toByte(), 0x74.toByte(), 0xBD.toByte(), 0x75.toByte(), 0xAB.toByte(),
        0x74.toByte(), 0xA7.toByte(), 0x79.toByte(), 0x01.toByte(), 0xBC.toByte(), 0x7A.toByte(), 0x3D.toByte(), 0xAA.toByte(), 0x5E.toByte(), 0x94.toByte(), 0xDE.toByte(), 0x65.toByte(),
        0x7D.toByte(), 0x75.toByte(), 0xFD.toByte(), 0x34.toByte(), 0xFD.toByte(), 0x5E.toByte(), 0x83.toByte(), 0x85.toByte(), 0x06.toByte(), 0x87.toByte(), 0x0D.toByte(), 0x26.toByte(),
        0x0D.toByte(), 0xD7.toByte(), 0x1A.toByte(), 0x5E.toByte(), 0xE4.toByte(), 0x6B.toByte(), 0xF1.toByte(), 0x77.toByte(), 0xF0.toByte(), 0x87.toByte(), 0x8C.toByte(), 0x3C.toByte(),
        0x8C.toByte(), 0x9A.toByte(), 0xE6.toByte(), 0x69.toByte(), 0xCD.toByte(), 0xCB.toByte(), 0x99.toByte(), 0x37.toByte(), 0x6E.toByte(), 0x1C.toByte(), 0x6A.toByte(), 0x7C.toByte(),
        0x63.toByte(), 0xFE.toByte(), 0xE2.toByte(), 0xF9.toByte(), 0x95.toByte(), 0x26.toByte(), 0x4A.toByte(), 0x26.toByte(), 0xD9.toByte(), 0x26.toByte(), 0xAF.toByte(), 0x4D.toByte(),
        0xE3.toByte(), 0x4C.toByte(), 0x1F.toByte(), 0x99.toByte(), 0xF9.toByte(), 0x98.toByte(), 0xDD.toByte(), 0x30.toByte(), 0x77.toByte(), 0x30.toByte(), 0x6F.toByte(), 0xB6.toByte(),
        0x58.toByte(), 0x60.toByte(), 0x51.toByte(), 0xBD.toByte(), 0xC0.toByte(), 0x68.toByte(), 0x41.toByte(), 0xA5.toByte(), 0xA5.toByte(), 0x9E.toByte(), 0x65.toByte(), 0xB9.toByte(),
        0x95.toByte(), 0xAE.toByte(), 0x55.toByte(), 0x99.toByte(), 0xB5.toByte(), 0xAE.toByte(), 0x75.toByte(), 0xF9.toByte(), 0x42.toByte(), 0xDE.toByte(), 0xC2.toByte(), 0x8A.toByte(),
        0x45.toByte(), 0xFA.toByte(), 0x8B.toByte(), 0x2A.toByte(), 0x17.toByte(), 0xF3.toByte(), 0x17.toByte(), 0x57.toByte(), 0xD9.toByte(), 0x18.toByte(), 0xDB.toByte(), 0x54.toByte(),
        0xDB.toByte(), 0x9A.toByte(), 0xD9.toByte(), 0x9E.toByte(), 0x5D.toByte(), 0x62.toByte(), 0xB5.toByte(), 0xA4.toByte(), 0xC9.toByte(), 0xCE.toByte(), 0xD6.toByte(), 0xAE.toByte(),
        0xC5.toByte(), 0xDE.toByte(), 0xC1.toByte(), 0xFE.toByte(), 0xEA.toByte(), 0x52.toByte(), 0xD7.toByte(), 0xA5.toByte(), 0x9D.toByte(), 0xCB.toByte(), 0xBC.toByte(), 0x96.toByte(),
        0xF5.toByte(), 0x2C.toByte(), 0x0F.toByte(), 0x5A.toByte(), 0xDE.toByte(), 0xEF.toByte(), 0x10.toByte(), 0xE5.toByte(), 0xF0.toByte(), 0x62.toByte(), 0x45.toByte(), 0xE2.toByte(),
        0x8A.toByte(), 0x09.toByte(), 0xC7.toByte(), 0x8C.toByte(), 0x95.toByte(), 0xB0.toByte(), 0x32.toByte(), 0xD7.toByte(), 0x89.toByte(), 0xED.toByte(), 0x54.toByte(), 0xE0.toByte(),
        0xAC.toByte(), 0xE9.toByte(), 0x5C.toByte(), 0x29.toByte(), 0x30.toByte(), 0x11.toByte(), 0x9C.toByte(), 0x77.toByte(), 0xB1.toByte(), 0x77.toByte(), 0xE9.toByte(), 0x70.toByte(),
        0x75.toByte(), 0x77.toByte(), 0xED.toByte(), 0x75.toByte(), 0x5B.toByte(), 0xE7.toByte(), 0x36.toByte(), 0xB4.toByte(), 0x2A.toByte(), 0x79.toByte(), 0xD5.toByte(), 0x1F.toByte(),
        0xAB.toByte(), 0x77.toByte(), 0x7F.toByte(), 0xC6.toByte(), 0xFE.toByte(), 0xAC.toByte(), 0xC8.toByte(), 0x5D.toByte(), 0xDF.toByte(), 0xBD.toByte(), 0xCE.toByte(), 0x63.toByte(),
        0x89.toByte(), 0x47.toByte(), 0x87.toByte(), 0xA7.toByte(), 0x97.toByte(), 0xE7.toByte(), 0x4F.toByte(), 0x5E.toByte(), 0x31.toByte(), 0x5E.toByte(), 0xAF.toByte(), 0xBC.toByte(),
        0xB3.toByte(), 0xD6.toByte(), 0x30.toByte(), 0xD7.toByte(), 0x14.toByte(), 0xF9.toByte(), 0xF0.toByte(), 0x7D.toByte(), 0xCE.toByte(), 0xFB.toByte(), 0x3A.toByte(), 0xFA.toByte(),
        0xDE.toByte(), 0xF2.toByte(), 0x0B.toByte(), 0xF5.toByte(), 0x1B.toByte(), 0xF1.toByte(), 0x4F.toByte(), 0x0F.toByte(), 0xA0.toByte(), 0x06.toByte(), 0x14.toByte(), 0xAE.toByte(),
        0xE5.toByte(), 0xAF.toByte(), 0x6D.toByte(), 0x0A.toByte(), 0x14.toByte(), 0x04.toByte(), 0xF6.toByte(), 0x04.toByte(), 0x45.toByte(), 0x07.toByte(), 0x4D.toByte(), 0x06.toByte(),
        0x67.toByte(), 0x87.toByte(), 0x70.toByte(), 0x43.toByte(), 0xAA.toByte(), 0x42.toByte(), 0x6D.toByte(), 0x43.toByte(), 0x6F.toByte(), 0x86.toByte(), 0x85.toByte(), 0x86.toByte(),
        0x8D.toByte(), 0xAE.toByte(), 0xCB.toByte(), 0x0A.toByte(), 0xE7.toByte(), 0x84.toByte(), 0x9F.toByte(), 0x58.toByte(), 0x6F.toByte(), 0xBB.toByte(), 0xBE.toByte(), 0x33.toByte(),
        0x62.toByte(), 0x5D.toByte(), 0xC4.toByte(), 0xEF.toByte(), 0x91.toByte(), 0x39.toByte(), 0x51.toByte(), 0xEA.toByte(), 0x51.toByte(), 0x75.toByte(), 0x42.toByte(), 0x27.toByte(),
        0xE1.toByte(), 0xBD.toByte(), 0xE8.toByte(), 0x04.toByte(), 0x11.toByte(), 0x12.toByte(), 0x15.toByte(), 0xC6.toByte(), 0x98.toByte(), 0xC5.toByte(), 0x5C.toByte(), 0x8D.toByte(),
        0x0D.toByte(), 0x8E.toByte(), 0xFD.toByte(), 0x2D.toByte(), 0x2E.toByte(), 0x77.toByte(), 0x83.toByte(), 0xF6.toByte(), 0x86.toByte(), 0xA6.toByte(), 0x78.toByte(), 0x8F.toByte(),
        0xF8.toByte(), 0x81.toByte(), 0x8D.toByte(), 0x5B.toByte(), 0x13.toByte(), 0x94.toByte(), 0x13.toByte(), 0xEA.toByte(), 0x12.toByte(), 0x5D.toByte(), 0x12.toByte(), 0x1F.toByte(),
        0x6F.toByte(), 0x4A.toByte(), 0x4F.toByte(), 0xE2.toByte(), 0x24.toByte(), 0xD5.toByte(), 0x24.toByte(), 0xBB.toByte(), 0x24.toByte(), 0x3F.toByte(), 0xF9.toByte(), 0x7C.toByte(),
        0x8B.toByte(), 0x98.toByte(), 0x2B.toByte(), 0x3E.toByte(), 0x97.toByte(), 0xE2.toByte(), 0x9E.toByte(), 0x32.toByte(), 0x2C.toByte(), 0xD9.toByte(), 0x95.toByte(), 0xAA.toByte(),
        0x93.toByte(), 0xDA.toByte(), 0x92.toByte(), 0x16.toByte(), 0x98.toByte(), 0x36.toByte(), 0xB9.toByte(), 0xF9.toByte(), 0x60.toByte(), 0xFA.toByte(), 0x82.toByte(), 0xF4.toByte(),
        0xAE.toByte(), 0x8C.toByte(), 0xF8.toByte(), 0x2D.toByte(), 0xB4.toByte(), 0x2D.toByte(), 0xA7.toByte(), 0x32.toByte(), 0x5D.toByte(), 0x33.toByte(), 0x07.toByte(), 0xB6.toByte(),
        0x66.toByte(), 0x6F.toByte(), 0x33.toByte(), 0xD8.toByte(), 0xD6.toByte(), 0xB1.toByte(), 0x5D.toByte(), 0x98.toByte(), 0x85.toByte(), 0x67.toByte(), 0x9D.toByte(), 0xD8.toByte(),
        0x21.toByte(), 0xD8.toByte(), 0x31.toByte(), 0xB8.toByte(), 0x33.toByte(), 0x6F.toByte(), 0x97.toByte(), 0xF1.toByte(), 0xAE.toByte(), 0xCE.toByte(), 0xDD.toByte(), 0x09.toByte(),
        0xD9.toByte(), 0xF2.toByte(), 0xD9.toByte(), 0x0D.toByte(), 0x39.toByte(), 0xFE.toByte(), 0x39.toByte(), 0x6F.toByte(), 0x73.toByte(), 0x8F.toByte(), 0xE5.toByte(), 0xAD.toByte(),
        0xC8.toByte(), 0xFB.toByte(), 0xF9.toByte(), 0x8B.toByte(), 0xBC.toByte(), 0x3D.toByte(), 0xA6.toByte(), 0x7B.toByte(), 0xBA.toByte(), 0xBF.toByte(), 0x4C.toByte(), 0xDD.toByte(),
        0xAB.toByte(), 0xB9.toByte(), 0xB7.toByte(), 0x7D.toByte(), 0x5F.toByte(), 0xCC.toByte(), 0x7E.toByte(), 0xD6.toByte(), 0xFE.toByte(), 0xC6.toByte(), 0xAF.toByte(), 0x82.toByte(),
        0xF3.toByte(), 0x21.toByte(), 0xFF.toByte(), 0xF4.toByte(), 0x01.toByte(), 0xEF.toByte(), 0x03.toByte(), 0x6F.toByte(), 0x0F.toByte(), 0x96.toByte(), 0x1D.toByte(), 0x5A.toByte(),
        0x75.toByte(), 0x68.toByte(), 0xFC.toByte(), 0x70.toByte(), 0x71.toByte(), 0x81.toByte(), 0x53.toByte(), 0xC1.toByte(), 0xE8.toByte(), 0x91.toByte(), 0xC2.toByte(), 0x42.toByte(),
        0xC7.toByte(), 0xC2.toByte(), 0x91.toByte(), 0x7F.toByte(), 0x1C.toByte(), 0x29.toByte(), 0x72.toByte(), 0x2C.toByte(), 0x7A.toByte(), 0x59.toByte(), 0x5C.toByte(), 0x78.toByte(),
        0xD4.toByte(), 0xE9.toByte(), 0xE8.toByte(), 0xD8.toByte(), 0xB1.toByte(), 0x63.toByte(), 0x25.toByte(), 0x6E.toByte(), 0x25.toByte(), 0x93.toByte(), 0x5F.toByte(), 0x57.toByte(),
        0x94.toByte(), 0x7A.toByte(), 0x97.toByte(), 0x4E.toByte(), 0x97.toByte(), 0xD5.toByte(), 0x94.toByte(), 0x07.toByte(), 0x57.toByte(), 0xD0.toByte(), 0x2A.toByte(), 0x2E.toByte(),
        0x1C.toByte(), 0x8F.toByte(), 0xA9.toByte(), 0xE4.toByte(), 0x56.toByte(), 0x5E.toByte(), 0x3B.toByte(), 0x91.toByte(), 0x7A.toByte(), 0xD2.toByte(), 0xE8.toByte(), 0x64.toByte(),
        0x5F.toByte(), 0x55.toByte(), 0xDE.toByte(), 0x29.toByte(), 0xBB.toByte(), 0x53.toByte(), 0x23.toByte(), 0xA7.toByte(), 0x4B.toByte(), 0xCE.toByte(), 0x78.toByte(), 0x55.toByte(),
        0xA3.toByte(), 0xEA.toByte(), 0xC6.toByte(), 0x9A.toByte(), 0x98.toByte(), 0x5A.toByte(), 0x8D.toByte(), 0xDA.toByte(), 0x1F.toByte(), 0xEA.toByte(), 0x76.toByte(), 0xD6.toByte(),
        0x2F.toByte(), 0xAE.toByte(), 0x1F.toByte(), 0x39.toByte(), 0x5B.toByte(), 0x76.toByte(), 0xCE.toByte(), 0xBF.toByte(), 0x81.toByte(), 0xDE.toByte(), 0xD0.toByte(), 0x76.toByte(),
        0x3E.toByte(), 0xB5.toByte(), 0xD1.toByte(), 0xAC.toByte(), 0xF1.toByte(), 0x79.toByte(), 0x53.toByte(), 0xF1.toByte(), 0x37.toByte(), 0x6B.toByte(), 0x9A.toByte(), 0x69.toByte(),
        0xCD.toByte(), 0xED.toByte(), 0x17.toByte(), 0xD2.toByte(), 0x2F.toByte(), 0x5A.toByte(), 0x5E.toByte(), 0xFC.toByte(), 0xE5.toByte(), 0x9F.toByte(), 0xC7.toByte(), 0x2F.toByte(),
        0x85.toByte(), 0xB5.toByte(), 0x70.toByte(), 0x5B.toByte(), 0x6E.toByte(), 0xB7.toByte(), 0x7E.toByte(), 0x79.toByte(), 0x59.toByte(), 0x70.toByte(), 0x79.toByte(), 0xE6.toByte(),
        0x4A.toByte(), 0x4B.toByte(), 0x5B.toByte(), 0x5A.toByte(), 0xBB.toByte(), 0x75.toByte(), 0xFB.toByte(), 0xD8.toByte(), 0xBF.toByte(), 0x6A.toByte(), 0xAE.toByte(), 0xC6.toByte(),
        0x75.toByte(), 0xF0.toByte(), 0x3B.toByte(), 0x9E.toByte(), 0x7F.toByte(), 0x5B.toByte(), 0x7E.toByte(), 0x2D.toByte(), 0xFC.toByte(), 0xBA.toByte(), 0xD6.toByte(), 0xF5.toByte(),
        0x87.toByte(), 0x37.toByte(), 0x8E.toByte(), 0x7E.toByte(), 0x17.toByte(), 0x72.toByte(), 0x53.toByte(), 0xFD.toByte(), 0xE6.toByte(), 0x83.toByte(), 0xCE.toByte(), 0xE2.toByte(),
        0xEF.toByte(), 0x43.toByte(), 0xBA.toByte(), 0x34.toByte(), 0xBB.toByte(), 0x1E.toByte(), 0xFD.toByte(), 0x50.toByte(), 0x7A.toByte(), 0x2B.toByte(), 0xE2.toByte(), 0xB6.toByte(),
        0xFE.toByte(), 0xED.toByte(), 0xC1.toByte(), 0xEE.toByte(), 0xD3.toByte(), 0x3F.toByte(), 0x6E.toByte(), 0xBC.toByte(), 0x63.toByte(), 0x71.toByte(), 0xE7.toByte(), 0x55.toByte(),
        0x4F.toByte(), 0x73.toByte(), 0x6F.toByte(), 0xE6.toByte(), 0x5D.toByte(), 0xC7.toByte(), 0x3E.toByte(), 0x72.toByte(), 0x5F.toByte(), 0xE7.toByte(), 0xBD.toByte(), 0x03.toByte(),
        0xF7.toByte(), 0xD7.toByte(), 0x3E.toByte(), 0xD0.toByte(), 0x7E.toByte(), 0x30.toByte(), 0xF0.toByte(), 0x53.toByte(), 0xCD.toByte(), 0x43.toByte(), 0xF1.toByte(), 0x23.toByte(),
        0xFB.toByte(), 0x7E.toByte(), 0xAC.toByte(), 0xFF.toByte(), 0xFB.toByte(), 0xC7.toByte(), 0x05.toByte(), 0x4F.toByte(), 0xD6.toByte(), 0x3D.toByte(), 0x35.toByte(), 0x7A.toByte(),
        0x3A.toByte(), 0xFE.toByte(), 0xEC.toByte(), 0xD2.toByte(), 0xF3.toByte(), 0x9C.toByte(), 0x9F.toByte(), 0xD7.toByte(), 0x0C.toByte(), 0x68.toByte(), 0x0E.toByte(), 0x0C.toByte(),
        0x0F.toByte(), 0x9E.toByte(), 0x1F.toByte(), 0xCA.toByte(), 0x1A.toByte(), 0x76.toByte(), 0x7F.toByte(), 0xA1.toByte(), 0xF6.toByte(), 0x62.toByte(), 0xF0.toByte(), 0x97.toByte(),
        0xC6.toByte(), 0x91.toByte(), 0x9D.toByte(), 0x2F.toByte(), 0xBD.toByte(), 0x47.toByte(), 0x75.toByte(), 0x46.toByte(), 0x7F.toByte(), 0xFD.toByte(), 0xB5.toByte(), 0x75.toByte(),
        0x6C.toByte(), 0xDF.toByte(), 0x6F.toByte(), 0x61.toByte(), 0xE3.toByte(), 0x66.toByte(), 0xE3.toByte(), 0xD3.toByte(), 0xBF.toByte(), 0xDF.toByte(), 0x7A.toByte(), 0x55.toByte(),
        0x36.toByte(), 0xF1.toByte(), 0xF9.toByte(), 0xA4.toByte(), 0xF3.toByte(), 0x6B.toByte(), 0xEE.toByte(), 0xEB.toByte(), 0x17.toByte(), 0x6F.toByte(), 0x5A.toByte(), 0xDE.toByte(),
        0xE6.toByte(), 0xBF.toByte(), 0x13.toByte(), 0xFE.toByte(), 0x61.toByte(), 0x3B.toByte(), 0xC5.toByte(), 0x9C.toByte(), 0x7A.toByte(), 0xFA.toByte(), 0xBE.toByte(), 0xF9.toByte(),
        0xCF.toByte(), 0xBD.toByte(), 0xD3.toByte(), 0x91.toByte(), 0x1F.toByte(), 0x96.toByte(), 0xCC.toByte(), 0xC8.toByte(), 0xCD.toByte(), 0x0C.toByte(), 0xCE.toByte(), 0xC8.toByte(),
        0xF4.toByte(), 0xC9.toByte(), 0x4A.toByte(), 0x96.toByte(), 0x05.toByte(), 0x64.toByte(), 0x59.toByte(), 0x40.toByte(), 0x96.toByte(), 0x05.toByte(), 0x64.toByte(), 0x59.toByte(),
        0x40.toByte(), 0x96.toByte(), 0x05.toByte(), 0x64.toByte(), 0x59.toByte(), 0x40.toByte(), 0x96.toByte(), 0x05.toByte(), 0x3E.toByte(), 0x6D.toByte(), 0xC9.toByte(), 0xB2.toByte(),
        0x80.toByte(), 0x2C.toByte(), 0x0B.toByte(), 0xC8.toByte(), 0xB2.toByte(), 0x80.toByte(), 0x2C.toByte(), 0x0B.toByte(), 0xC8.toByte(), 0xB2.toByte(), 0x80.toByte(), 0x2C.toByte(),
        0x0B.toByte(), 0xC8.toByte(), 0xB2.toByte(), 0xC0.toByte(), 0xA7.toByte(), 0xAD.toByte(), 0xB9.toByte(), 0xFF.toByte(), 0x12.toByte(), 0xB3.toByte(), 0x62.toByte(), 0xFB.toByte(),
        0x8A.toByte(), 0x84.toByte(), 0x16.toByte(), 0x3C.toByte(), 0x6B.toByte(), 0x4B.toByte(), 0x6B.toByte(), 0x4B.toByte(), 0xDE.toByte(), 0x1A.toByte(), 0x1F.toByte(), 0x69.toByte(),
        0x41.toByte(), 0x22.toByte(), 0x4A.toByte(), 0x97.toByte(), 0x48.toByte(), 0xAF.toByte(), 0xCE.toByte(), 0x49.toByte(), 0xC9.toByte(), 0x19.toByte(), 0xE2.toByte(), 0x0D.toByte(),
        0xB1.toByte(), 0x71.toByte(), 0x12.toByte(), 0xE9.toByte(), 0x4D.toByte(), 0x6B.toByte(), 0xDE.toByte(), 0xCA.toByte(), 0xE8.toByte(), 0xA4.toByte(), 0x28.toByte(), 0x11.toByte(),
        0xCF.toByte(), 0x2F.toByte(), 0x23.toByte(), 0x45.toByte(), 0x22.toByte(), 0x4A.toByte(), 0x4C.toByte(), 0xE1.toByte(), 0xAD.toByte(), 0xDE.toByte(), 0x24.toByte(), 0x4C.toByte(),
        0x12.toByte(), 0x27.toByte(), 0x27.toByte(), 0x89.toByte(), 0x23.toByte(), 0x25.toByte(), 0xA2.toByte(), 0x68.toByte(), 0xA2.toByte(), 0xF7.toByte(), 0xDF.toByte(), 0xE6.toByte(),
        0xA8.toByte(), 0x81.toByte(), 0x3F.toByte()
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
                        writeChunk(bos, "iCCP", ADOBE_REC2020_PQ_ICC)

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
