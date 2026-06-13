package com.cameraw

import android.view.Surface
import android.hardware.HardwareBuffer
import androidx.annotation.Keep

class VulkanHdrBridge {
    interface HdrMetadataListener {
        fun onDynamicMetadataReady(metadata: ByteArray, timestampNs: Long)
    }

    var metadataListener: HdrMetadataListener? = null
    @Keep
    private fun onDynamicMetadata(metadata: ByteArray, timestampNs: Long) {
        metadataListener?.onDynamicMetadataReady(metadata, timestampNs)
    }
    @Keep
    private fun onFrameReleased(timestampNs: Long) {
    }

    external fun nativeCreate(
        outW: Int, outH: Int,
        intendedW: Int, intendedH: Int,
        sensorW: Int, sensorH: Int,
        black: Int, white: Int,
        hdrMode: Int,
        logProfile: Int,
        cfa: Int
    ): Long

    external fun nativeBindEncoderSurface(handle: Long, surface: Surface): Boolean

    external fun nativeSetLut(handle: Long, lutData: FloatArray?, lutSize: Int, enabled: Boolean)

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
        external fun nativeRemuxVideo(inputPath: String, outputPath: String, sarNum: Int, sarDen: Int, hdrMode: Int, logProfile: Int): Boolean

        init { System.loadLibrary("cameraw_isp") }
    }
}