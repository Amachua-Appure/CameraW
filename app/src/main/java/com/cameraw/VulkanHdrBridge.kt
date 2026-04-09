package com.cameraw

import android.view.Surface
import android.hardware.HardwareBuffer
import androidx.annotation.Keep

class VulkanHdrBridge {
    interface HdrMetadataListener {
        fun onDynamicMetadataReady(metadata: ByteArray, dvMin: Int, dvMax: Int, dvAvg: Int, timestampNs: Long)
    }

    var metadataListener: HdrMetadataListener? = null

    @Keep
    private fun onDynamicMetadata(metadata: ByteArray, dvMin: Int, dvMax: Int, dvAvg: Int, timestampNs: Long) {
        metadataListener?.onDynamicMetadataReady(metadata, dvMin, dvMax, dvAvg, timestampNs)
    }

    external fun nativeCreate(
        outW: Int, outH: Int,
        intendedW: Int, intendedH: Int,
        sensorW: Int, sensorH: Int,
        black: Int, white: Int,
        hdrMode: Int,
        cfa: Int
    ): Long

    external fun nativeBindEncoderSurface(handle: Long, surface: Surface): Boolean
    external fun nativeSetColorMatrix(handle: Long, matrix: FloatArray)
    external fun nativeGetLastMetadata(handle: Long): IntArray?

    external fun nativeProcessFrameBuffer(
        handle: Long,
        hb: HardwareBuffer,
        ts: Long,
        wbGains: FloatArray,
        ccm: FloatArray?,
        expNs: Long,
        iso: Int,
        fenceFd: Int,
        lscMap: FloatArray,
        lscW: Int,
        lscH: Int
    ): String

    external fun nativeDestroy(handle: Long)

    companion object {
        @JvmStatic
        external fun nativeRemuxVideo(inputPath: String, outputPath: String, sarNum: Int, sarDen: Int, hdrMode: Int): Boolean

        init { System.loadLibrary("cameraw_isp") }
    }
}