import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaCodecList
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MetadataDumper {
    private const val TAG = "MetadataDumper"

    fun dumpCameraState(
        context: Context,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult
    ) {
        Thread {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "camera_dump_$timestamp.txt"

                val outputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/CameraW")
                    }
                    val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                    uri?.let { resolver.openOutputStream(it) }
                } else {
                    val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    val cameraWDir = File(documentsDir, "CameraW")
                    if (!cameraWDir.exists()) cameraWDir.mkdirs()
                    val file = File(cameraWDir, fileName)
                    java.io.FileOutputStream(file)
                }

                if (outputStream == null) {
                    Log.e(TAG, "Failed to create output stream")
                    return@Thread
                }

                val writer = OutputStreamWriter(outputStream)

                writer.append("STATIC CAMERA CHARACTERISTICS\n\n")
                for (key in characteristics.keys) {
                    val value = characteristics.get(key)
                    writer.append("${key.name}: ${formatValue(value)}\n")
                }

                writer.append("\nDYNAMIC CAPTURE RESULT (FRAME METADATA)\n\n")
                for (key in captureResult.keys) {
                    val value = captureResult.get(key)
                    writer.append("${key.name}: ${formatValue(value)}\n")
                }

                writer.append("\nVENDOR TAGS\n\n")
                pullHiddenVendorTag(writer, captureResult, "com.mediatek.3afeature.gyrodata", ByteArray::class.java)
                pullHiddenVendorTag(writer, captureResult, "com.mediatek.3afeature.aeSensorGain", Int::class.java)

                dumpCodecCapabilities(writer)

                writer.flush()
                writer.close()
                outputStream.close()
                Log.i(TAG, "Dump saved successfully to Documents/CameraW/$fileName")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to dump metadata", e)
            }
        }.start()
    }

    private fun dumpCodecCapabilities(writer: Writer) {
        writer.append("\nMEDIA CODEC CAPABILITIES\n\n")
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (codecInfo in codecList.codecInfos) {
                writer.append("Codec: ${codecInfo.name}\n")
                writer.append("  Is Encoder: ${codecInfo.isEncoder}\n")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writer.append("  Hardware Accelerated: ${codecInfo.isHardwareAccelerated}\n")
                    writer.append("  Vendor: ${codecInfo.isVendor}\n")
                }

                for (type in codecInfo.supportedTypes) {
                    writer.append("  Supported Type: $type\n")
                    try {
                        val caps = codecInfo.getCapabilitiesForType(type)

                        if (caps.colorFormats.isNotEmpty()) {
                            writer.append("    Color Formats: ${caps.colorFormats.joinToString(", ") { "0x%08X".format(it) }}\n")
                        }

                        if (caps.profileLevels.isNotEmpty()) {
                            writer.append("    Profiles/Levels: ")
                            writer.append(caps.profileLevels.joinToString(", ") { "P:${it.profile}/L:${it.level}" })
                            writer.append("\n")
                        }

                        val videoCaps = caps.videoCapabilities
                        if (videoCaps != null) {
                            writer.append("    Max Resolution: ${videoCaps.supportedWidths.upper}x${videoCaps.supportedHeights.upper}\n")
                            writer.append("    Max Framerate: ${videoCaps.supportedFrameRates.upper.toInt()} fps\n")
                            writer.append("    Max Bitrate: ${videoCaps.bitrateRange.upper / 1_000_000} Mbps\n")
                        }

                        val audioCaps = caps.audioCapabilities
                        if (audioCaps != null) {
                            writer.append("    Max Sample Rate: ${audioCaps.supportedSampleRateRanges.last().upper} Hz\n")
                            writer.append("    Max Channels: ${audioCaps.maxInputChannelCount}\n")
                        }
                    } catch (e: Exception) {
                        writer.append("    [Error retrieving capabilities for $type]\n")
                    }
                }
                writer.append("\n")
            }
        } catch (e: Exception) {
            writer.append("[Error retrieving codec info: ${e.message}]\n")
        }
    }

    private fun <T> pullHiddenVendorTag(
        writer: Writer,
        result: TotalCaptureResult,
        tagName: String,
        type: Class<T>
    ) {
        try {
            val customKey = CaptureResult.Key(tagName, type)
            val value = result.get(customKey)
            if (value != null) {
                if (tagName == "com.mediatek.3afeature.gyrodata" && value is ByteArray) {
                    writer.append("$tagName:\n${parseMediatekGyroData(value)}")
                } else {
                    writer.append("$tagName: ${formatValue(value)}\n")
                }
            }
        } catch (e: IllegalArgumentException) {
            writer.append("$tagName: NOT FOUND / UNSUPPORTED\n")
        }
    }

    private fun parseMediatekGyroData(data: ByteArray): String {
        val sb = StringBuilder()
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var sampleCount = 0

        while (buffer.remaining() >= 24) {
            val timestamp = buffer.long
            val x = buffer.float
            val y = buffer.float
            val z = buffer.float
            buffer.int 

            sb.append("    [$sampleCount] TS: $timestamp ns | X: $x | Y: $y | Z: $z\n")
            sampleCount++
        }
        return sb.toString()
    }

    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is ByteArray -> value.joinToString(" ") { "%02X".format(it) }
            is IntArray -> value.joinToString(", ")
            is FloatArray -> value.joinToString(", ")
            is DoubleArray -> value.joinToString(", ")
            is LongArray -> value.joinToString(", ")
            is BooleanArray -> value.joinToString(", ")
            is Array<*> -> value.joinToString(", ") { formatValue(it) }
            else -> value.toString()
        }
    }
}