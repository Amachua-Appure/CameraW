package com.cameraw

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.media.*
import android.os.Build
import android.provider.MediaStore
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import kotlin.math.max
import kotlin.math.roundToInt
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class Recorder(private val context: Context, private val prefs: SharedPreferences) {

    companion object {
        private const val TAG = "Recorder"

        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_CHANNELS = 2
        private const val PCM_BYTES_PER_SAMPLE = 2

        private const val OPUS_BITRATE = 320_000
        private const val OPUS_PRE_SKIP = 312

        private fun escapeRbsp(input: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(input.size + 10)
            var zeroCount = 0
            for (b in input) {
                val byteVal = b.toInt() and 0xFF
                if (zeroCount == 2 && byteVal <= 3) {
                    out.write(0x03)
                    zeroCount = 0
                }
                if (byteVal == 0) {
                    zeroCount++
                } else {
                    zeroCount = 0
                }
                out.write(byteVal)
            }
            return out.toByteArray()
        }
    }

    data class RecordConfig(
        val width: Int,
        val height: Int,
        val fps: Int,
        val videoBitrate: Int,
        val videoCodecMime: String,
        val isHdr: Boolean,
        val isSdrPassthrough: Boolean = false,
        val isTrueHdr: Boolean,
        val sarNum: Int = 1,
        val sarDen: Int = 1,
        val dynamicMetadataMode: Int = 1
    )

    var encoderSurface: Surface? = null
        private set

    enum class AudioCodec { WAV, OPUS, NONE }

    private var config: RecordConfig? = null
    private var fileName: String? = null

    private var tempMp4File: File? = null
    private var tempAudioFile: File? = null
    private var selectedAudioCodec: AudioCodec = AudioCodec.WAV

    private var videoEncoder: MediaCodec? = null
    private var aacEncoder: MediaCodec? = null
    private var opusEncoder: MediaCodec? = null

    private var audioRecord: AudioRecord? = null
    private val audioFeederRunning = AtomicBoolean(false)
    private var audioFeederThread: Thread? = null

    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var videoFlushed = false
    private var audioFlushed = false

    private var audioOutputFile: FileOutputStream? = null
    private var audioFileSize: Long = 0

    @Volatile private var recordingStartNs = 0L
    private var oggOpusWriter: OggOpusWriter? = null

    var lastVideoFile: File? = null
        private set

    private data class DynamicMetadataPacket(
        val hdr10p: ByteArray,
        val dvMin: Int,
        val dvMax: Int,
        val dvAvg: Int
    )

    private val dynamicMetadataQueue = ConcurrentSkipListMap<Long, DynamicMetadataPacket>()

    fun updateDynamicMetadata(metadataBytes: ByteArray, dvMin: Int, dvMax: Int, dvAvg: Int, timestampNs: Long) {
        dynamicMetadataQueue[timestampNs] = DynamicMetadataPacket(metadataBytes, dvMin, dvMax, dvAvg)
        Log.v(TAG, "Queued dynamic metadata, timestamp=${timestampNs}ns, HDR10+ size=${metadataBytes.size}, DV ($dvMin, $dvMax, $dvAvg)")
    }

    private fun escapeRbsp(input: ByteArray): ByteArray = Companion.escapeRbsp(input)

    fun buildHdr10PlusSeiNalu(payload: ByteArray): ByteArray {
        val payloadType = 4.toByte()
        var remainingSize = payload.size
        val sizeBytes = mutableListOf<Byte>()
        while (remainingSize >= 255) {
            sizeBytes.add(0xFF.toByte())
            remainingSize -= 255
        }
        sizeBytes.add(remainingSize.toByte())

        val rbspTrailingBits = byteArrayOf(0x80.toByte())

        val unescapedRbsp = ByteBuffer.allocate(1 + sizeBytes.size + payload.size + 1).apply {
            put(payloadType)
            put(sizeBytes.toByteArray())
            put(payload)
            put(rbspTrailingBits)
        }.array()

        val escapedRbsp = escapeRbsp(unescapedRbsp)

        val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        val nalHeader = byteArrayOf(0x4E.toByte(), 0x01.toByte())

        val finalNalu = ByteBuffer.allocate(startCode.size + nalHeader.size + escapedRbsp.size).apply {
            put(startCode)
            put(nalHeader)
            put(escapedRbsp)
        }.array()

        return finalNalu
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(recConfig: RecordConfig, filename: String): Boolean {
        return try {
            cleanup()
            config = recConfig
            fileName = filename

            tempMp4File = File(context.cacheDir, "$filename.mp4")
            lastVideoFile = tempMp4File
            val audioCodecStr = prefs.getString("audio_codec", "0") ?: "0"
            selectedAudioCodec = when (audioCodecStr) {
                "1" -> AudioCodec.OPUS
                "2" -> AudioCodec.NONE
                else -> AudioCodec.WAV
            }
            val audioExt = when (selectedAudioCodec) {
                AudioCodec.OPUS -> "opus"
                AudioCodec.WAV -> "wav"
                AudioCodec.NONE -> null
            }
            if (audioExt != null) {
                tempAudioFile = File(context.cacheDir, "$filename.$audioExt")
            }

            recordingStartNs = System.nanoTime()
            setupVideoEncoder()
            startPipelines()
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            CrashLogger.Companion.logHandledException(context, TAG, "Recorder start failed (MediaCodec config error)", e)
            cleanup()
            false
        }
    }

    fun stop() {
        audioFeederRunning.set(false)
        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (_: Exception) {
        }

        val t0 = System.currentTimeMillis()
        while (!videoFlushed || !audioFlushed) {
            if (System.currentTimeMillis() - t0 > 2500) break
            Thread.sleep(25)
        }

        stopAudioRecord()
        stopMp4AndAudio()

        val dynamicMode = config?.dynamicMetadataMode ?: 0
        val isTrueHdr = config?.isTrueHdr == true

        val needsRemux = (config?.sarNum != 1 || config?.sarDen != 1) ||
                (isTrueHdr && dynamicMode == 2)

        if (needsRemux && tempMp4File != null && tempMp4File!!.exists()) {
            val remuxedFile = File(context.cacheDir, "$fileName--.mp4")
            Log.i(TAG, "Starting fast FFmpeg remux...")

            val remuxHdrMode = if (isTrueHdr) dynamicMode else 0

            val success = VulkanHdrBridge.nativeRemuxVideo(
                tempMp4File!!.absolutePath,
                remuxedFile.absolutePath,
                config?.sarNum ?: 1,
                config?.sarDen ?: 1,
                remuxHdrMode
            )

            if (success && remuxedFile.exists()) {
                tempMp4File?.delete()
                val finalFile = File(context.cacheDir, "$fileName.mp4")
                remuxedFile.renameTo(finalFile)
                tempMp4File = finalFile
                lastVideoFile = finalFile
            } else {
                Log.e(TAG, "FFmpeg remux failed, keeping original file.")
            }
        }

        copyToMediaStore()
    }

    private fun buildHdrStaticInfo(maxNits: Float, minNits: Float, useBt2020: Boolean): ByteBuffer {
        val MAX_CHROMATICITY = 50000f
        val buffer = ByteBuffer.allocateDirect(25)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(0x00.toByte())

        if (useBt2020) {
            buffer.putShort(((0.708f * MAX_CHROMATICITY).roundToInt() and 0xFFFF).toShort())
            buffer.putShort(((0.292f * MAX_CHROMATICITY).roundToInt() and 0xFFFF).toShort())
            buffer.putShort(((0.170f * MAX_CHROMATICITY).roundToInt() and 0xFFFF).toShort())
            buffer.putShort(((0.797f * MAX_CHROMATICITY).roundToInt() and 0xFFFF).toShort())
            buffer.putShort(((0.131f * MAX_CHROMATICITY).roundToInt() and 0xFFFF).toShort())
            buffer.putShort(((0.046f * MAX_CHROMATICITY).roundToInt() and 0xFFFF).toShort())
        } else {
            buffer.putShort(((0.680f * MAX_CHROMATICITY).roundToInt() and 0xFFFF).toShort())
            buffer.putShort(((0.320f * MAX_CHROMATICITY).roundToInt() and 0xFFFF).toShort())
            buffer.putShort(((0.265f * MAX_CHROMATICITY).roundToInt() and 0xFFFF).toShort())
            buffer.putShort(((0.690f * MAX_CHROMATICITY).roundToInt() and 0xFFFF).toShort())
            buffer.putShort(((0.150f * MAX_CHROMATICITY).roundToInt() and 0xFFFF).toShort())
            buffer.putShort(((0.060f * MAX_CHROMATICITY).roundToInt() and 0xFFFF).toShort())
        }

        buffer.putShort(((0.3127f * MAX_CHROMATICITY).roundToInt() and 0xFFFF).toShort())
        buffer.putShort(((0.3290f * MAX_CHROMATICITY).roundToInt() and 0xFFFF).toShort())

        val maxShort = (maxNits.coerceIn(0f, 65535f).roundToInt() and 0xFFFF).toShort()
        val minShort = (minNits.coerceIn(0f, 65535f).roundToInt() and 0xFFFF).toShort()
        buffer.putShort(maxShort)
        buffer.putShort(minShort)

        buffer.putShort(1000.toShort())
        buffer.putShort(400.toShort())

        buffer.flip()
        return buffer
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setupVideoEncoder() {
        val cfg = config ?: error("Config not set")

        val actualMime = cfg.videoCodecMime

        val fmt = MediaFormat.createVideoFormat(actualMime, cfg.width, cfg.height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_BIT_RATE, cfg.videoBitrate)
            setInteger(MediaFormat.KEY_OPERATING_RATE, cfg.fps)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            if (cfg.isHdr || cfg.isTrueHdr || cfg.isSdrPassthrough) {
                require(cfg.videoCodecMime.contains("hevc", ignoreCase = true)) { "HDR/Log requires HEVC" }

                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

                if (cfg.isSdrPassthrough) {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                } else {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)

                    val dynamicHdrInfo = buildHdrStaticInfo(1000f, 0.0001f, useBt2020 = cfg.isTrueHdr)
                    setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, dynamicHdrInfo)

                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61)
                }

                arrayOf("bit-depth", "mtk-enc-bitdepth", "vendor.qti-ext-enc-bitdepth.value").forEach { key ->
                    try { setInteger(key, 10) } catch (_: Exception) {}
                }
            } else {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                if (cfg.videoCodecMime.contains("hevc", ignoreCase = true)) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61)
                }
            }

            if (cfg.sarNum != 1 || cfg.sarDen != 1) {
                setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH, cfg.sarNum)
                setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT, cfg.sarDen)
                Log.i(TAG, "Anamorphic PAR ${cfg.sarNum}:${cfg.sarDen} injected into MediaFormat")
            }
        }

        videoEncoder = MediaCodec.createEncoderByType(cfg.videoCodecMime).apply {
            var isConfigured = false

            if (cfg.isTrueHdr) {
                try {
                    fmt.setInteger(MediaFormat.KEY_PROFILE, 0x2000)
                    configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    isConfigured = true
                    Log.i(TAG, "SUCCESS: Hardware Encoder natively accepted HDR10+ profile for True HDR!")
                } catch (e: Exception) {
                    Log.w(TAG, "FAILED: Hardware rejected HDR10+. Falling back to standard HDR10.", e)
                    fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                }
            }

            if (!isConfigured) {
                configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }

            val destinationSurface = createInputSurface()
            start()

            encoderSurface = destinationSurface
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPipelines() {
        muxer = MediaMuxer(tempMp4File!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        setupAudioRecord()
        startAudioThread()
        startVideoOutput()
    }

    private class MuxerData(val data: ByteArray, val info: MediaCodec.BufferInfo, val trackIndex: Int)

    private fun startVideoOutput() {
        val enc = videoEncoder ?: return

        val muxerQueue = LinkedBlockingQueue<MuxerData>()
        var muxerThreadRunning = true

        thread(isDaemon = true, name = "VideoMuxer") {
            try {
                while (muxerThreadRunning || muxerQueue.isNotEmpty()) {
                    val packet = muxerQueue.poll(10, TimeUnit.MILLISECONDS)
                    if (packet != null && muxerStarted) {
                        val buffer = ByteBuffer.wrap(packet.data)
                        muxer?.writeSampleData(packet.trackIndex, buffer, packet.info)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Muxer thread error", e)
            }
        }

        thread(isDaemon = true, name = "VideoOutput") {
            val info = MediaCodec.BufferInfo()
            var gotFormat = false
            var firstVideoPts = -1L

            try {
                while (true) {
                    val ix = enc.dequeueOutputBuffer(info, 10_000)
                    when {
                        ix == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!gotFormat) {
                                val outFmt = enc.outputFormat
                                val dynamicMode = config?.dynamicMetadataMode ?: 0

                                if (config?.isTrueHdr == true) {
                                    outFmt.setInteger(
                                        MediaFormat.KEY_PROFILE,
                                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                                    )
                                    Log.i(TAG, "Spoofed MediaFormat Profile to HDR10+ (8192) for Muxer")
                                }

                                videoTrackIndex = muxer!!.addTrack(outFmt)
                                gotFormat = true

                                val profile = if (outFmt.containsKey(MediaFormat.KEY_PROFILE)) {
                                    outFmt.getInteger(MediaFormat.KEY_PROFILE)
                                } else {
                                    "N/A"
                                }
                                Log.i(TAG, "🎥 OUTPUT: ${outFmt.getString(MediaFormat.KEY_MIME)} Profile=$profile")

                                startMuxerIfReady()
                            }
                        }
                        ix >= 0 -> {
                            val buf = enc.getOutputBuffer(ix)
                            if (buf != null && info.size > 0 && muxerStarted) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)

                                if (firstVideoPts == -1L) {
                                    firstVideoPts = info.presentationTimeUs
                                }

                                val originalPtsUs = info.presentationTimeUs

                                val dataCopy = ByteArray(info.size)
                                buf.get(dataCopy)

                                var finalData = dataCopy
                                var finalInfo = MediaCodec.BufferInfo()

                                val dynamicMode = config?.dynamicMetadataMode ?: 0
                                val manualInject = config?.isTrueHdr == true && dynamicMode > 0

                                if (manualInject) {
                                    val frameNs = originalPtsUs * 1000L

                                    val floor = dynamicMetadataQueue.floorEntry(frameNs)
                                    val ceil = dynamicMetadataQueue.ceilingEntry(frameNs)

                                    val entry = when {
                                        floor == null -> ceil
                                        ceil == null -> floor
                                        (frameNs - floor.key) <= (ceil.key - frameNs) -> floor
                                        else -> ceil
                                    }

                                    if (entry != null && Math.abs(frameNs - entry.key) < 50_000_000L) {
                                        val packetData = entry.value

                                        val metadataNalu = if (dynamicMode == 1) {
                                            buildHdr10PlusSeiNalu(packetData.hdr10p)
                                        } else {
                                            ByteArray(0)
                                        }

                                        if (metadataNalu.isNotEmpty()) {
                                            val combined = ByteArray(metadataNalu.size + dataCopy.size)
                                            System.arraycopy(metadataNalu, 0, combined, 0, metadataNalu.size)
                                            System.arraycopy(dataCopy, 0, combined, metadataNalu.size, dataCopy.size)

                                            finalData = combined
                                            finalInfo.set(0, combined.size,
                                                (originalPtsUs - firstVideoPts).coerceAtLeast(0),
                                                info.flags)

                                            Log.v(TAG, "INJECTED HDR10+ SEI NALU! PTS: $originalPtsUs")
                                        } else {
                                            finalInfo.set(0, dataCopy.size,
                                                (originalPtsUs - firstVideoPts).coerceAtLeast(0),
                                                info.flags)
                                        }

                                        dynamicMetadataQueue.headMap(entry.key, true).clear()
                                    } else {
                                        Log.w(TAG, "MISSED dynamic metadata for PTS: $originalPtsUs")
                                        finalInfo.set(0, dataCopy.size,
                                            (originalPtsUs - firstVideoPts).coerceAtLeast(0),
                                            info.flags)
                                    }
                                } else {
                                    finalInfo.set(0, dataCopy.size,
                                        (originalPtsUs - firstVideoPts).coerceAtLeast(0),
                                        info.flags)
                                }

                                muxerQueue.put(MuxerData(finalData, finalInfo, videoTrackIndex))
                            }

                            enc.releaseOutputBuffer(ix, false)

                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                videoFlushed = true
                                break
                            }
                        }
                        else -> if (videoFlushed) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Video output error", e)
            } finally {
                videoFlushed = true
                muxerThreadRunning = false
            }
        }
    }

    private fun startMuxerIfReady() {
        if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            try {
                muxer?.start()
                muxerStarted = true
            } catch (e: Exception) {
                Log.e(TAG, "Muxer start error", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudioRecord() {
        val ch = if (AUDIO_CHANNELS == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, ch, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AUDIO_SAMPLE_RATE,
            ch,
            AudioFormat.ENCODING_PCM_16BIT,
            (minBuf * 2).coerceAtLeast(4096)
        )
    }

    @SuppressLint("MissingPermission")
    private fun startAudioThread() {
        aacEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm").apply {
            val fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", AUDIO_SAMPLE_RATE, AUDIO_CHANNELS)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 320_000)
            configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        if (selectedAudioCodec == AudioCodec.OPUS) {
            try {
                opusEncoder = MediaCodec.createEncoderByType("audio/opus").apply {
                    val ofmt = MediaFormat.createAudioFormat("audio/opus", AUDIO_SAMPLE_RATE, AUDIO_CHANNELS)
                    ofmt.setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE)
                    ofmt.setInteger(MediaFormat.KEY_CHANNEL_COUNT, AUDIO_CHANNELS)
                    configure(ofmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
                audioOutputFile = FileOutputStream(tempAudioFile)
                oggOpusWriter = OggOpusWriter(
                    audioOutputFile!!,
                    AUDIO_SAMPLE_RATE,
                    AUDIO_CHANNELS,
                    OPUS_PRE_SKIP
                ).apply { writeHeaders() }
            } catch (e: Exception) {
                Log.e(TAG, "Opus init failed, fallback to WAV", e)
                selectedAudioCodec = AudioCodec.WAV
                safeCloseAudioOut()
                tempAudioFile = File(context.cacheDir, "$fileName.wav")
                audioOutputFile = FileOutputStream(tempAudioFile)
                writeWavHeader(audioOutputFile!!, AUDIO_CHANNELS, AUDIO_SAMPLE_RATE)
                audioFileSize = 44
            }
        } else if (selectedAudioCodec == AudioCodec.WAV) {
            audioOutputFile = FileOutputStream(tempAudioFile)
            writeWavHeader(audioOutputFile!!, AUDIO_CHANNELS, AUDIO_SAMPLE_RATE)
            audioFileSize = 44
        }

        audioFeederRunning.set(true)
        audioRecord?.startRecording()

        audioFeederThread = thread(isDaemon = true, name = "AudioFeeder") {
            val chCfg = if (AUDIO_CHANNELS == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, chCfg, AudioFormat.ENCODING_PCM_16BIT)
            val pcm = ByteArray(minBuf)

            val aacInfo = MediaCodec.BufferInfo()
            val opusInfo = MediaCodec.BufferInfo()
            var aacTracked = false
            var audioSampleCount = 0L

            var prevPkt: ByteArray? = null
            var prevPtsUs: Long = -1L

            try {
                while (audioFeederRunning.get()) {
                    val read = audioRecord?.read(pcm, 0, pcm.size) ?: 0
                    if (read > 0) {
                        if (selectedAudioCodec == AudioCodec.WAV) {
                            audioOutputFile?.write(pcm, 0, read)
                            audioFileSize += read
                        }

                        var off = 0
                        while (off < read && audioFeederRunning.get()) {
                            val ix = aacEncoder?.dequeueInputBuffer(100) ?: -1
                            if (ix >= 0) {
                                val inBuf = aacEncoder!!.getInputBuffer(ix)!!
                                val push = minOf(inBuf.capacity(), read - off)
                                inBuf.clear()
                                inBuf.put(pcm, off, push)
                                val pts = (audioSampleCount * 1_000_000L) / AUDIO_SAMPLE_RATE
                                aacEncoder!!.queueInputBuffer(ix, 0, push, pts, 0)
                                audioSampleCount += (push / (PCM_BYTES_PER_SAMPLE * AUDIO_CHANNELS))
                                off += push
                            } else {
                                Thread.sleep(1)
                            }
                        }

                        if (selectedAudioCodec == AudioCodec.OPUS && opusEncoder != null) {
                            var oOff = 0
                            var localSampleCursor = audioSampleCount
                            while (oOff < read && audioFeederRunning.get()) {
                                val ix = opusEncoder!!.dequeueInputBuffer(100)
                                if (ix >= 0) {
                                    val inBuf = opusEncoder!!.getInputBuffer(ix)!!
                                    val push = minOf(inBuf.capacity(), read - oOff)
                                    inBuf.clear()
                                    inBuf.put(pcm, oOff, push)
                                    val pts = (localSampleCursor * 1_000_000L) / AUDIO_SAMPLE_RATE
                                    opusEncoder!!.queueInputBuffer(ix, 0, push, pts, 0)
                                    localSampleCursor += (push / (PCM_BYTES_PER_SAMPLE * AUDIO_CHANNELS))
                                    oOff += push
                                } else {
                                    Thread.sleep(1)
                                }
                            }
                        }
                    }

                    while (true) {
                        val ox = aacEncoder?.dequeueOutputBuffer(aacInfo, 0) ?: -1
                        when {
                            ox >= 0 -> {
                                if ((aacInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    if (!aacTracked && aacInfo.size > 0) {
                                        audioTrackIndex = muxer!!.addTrack(aacEncoder!!.outputFormat)
                                        aacTracked = true
                                        startMuxerIfReady()
                                    }
                                    if (muxerStarted && aacInfo.size > 0) {
                                        val buf = aacEncoder!!.getOutputBuffer(ox)!!
                                        buf.position(aacInfo.offset)
                                        buf.limit(aacInfo.offset + aacInfo.size)
                                        muxer?.writeSampleData(audioTrackIndex, buf, aacInfo)
                                    }
                                }
                                aacEncoder!!.releaseOutputBuffer(ox, false)
                            }
                            ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (!aacTracked) {
                                    audioTrackIndex = muxer!!.addTrack(aacEncoder!!.outputFormat)
                                    aacTracked = true
                                    startMuxerIfReady()
                                }
                            }
                            else -> break
                        }
                    }

                    if (selectedAudioCodec == AudioCodec.OPUS && opusEncoder != null) {
                        while (true) {
                            val ox = opusEncoder!!.dequeueOutputBuffer(opusInfo, 0)
                            if (ox >= 0) {
                                if ((opusInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    val out = opusEncoder!!.getOutputBuffer(ox)!!
                                    val size = opusInfo.size
                                    if (size > 0) {
                                        out.position(opusInfo.offset)
                                        out.limit(opusInfo.offset + size)
                                        val curPkt = ByteArray(size)
                                        out.get(curPkt)
                                        val curPtsUs = opusInfo.presentationTimeUs

                                        if (prevPkt != null && prevPtsUs >= 0) {
                                            val durUs = (curPtsUs - prevPtsUs).coerceAtLeast(20_000L)
                                            val endSamples = ((prevPtsUs * 48000L) / 1_000_000L) +
                                                    ((durUs * 48000L) / 1_000_000L)
                                            oggOpusWriter?.writePacketWithGranule(prevPkt!!, endSamples)
                                        }
                                        prevPkt = curPkt
                                        prevPtsUs = curPtsUs
                                    }
                                }
                                opusEncoder!!.releaseOutputBuffer(ox, false)
                                if ((opusInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                            } else break
                        }
                    }
                }

                try { aacEncoder?.signalEndOfInputStream() } catch (_: Throwable) {}
                if (selectedAudioCodec == AudioCodec.OPUS) {
                    try { opusEncoder?.signalEndOfInputStream() } catch (_: Throwable) {}
                }

                val info = MediaCodec.BufferInfo()
                while (true) {
                    val ox = aacEncoder?.dequeueOutputBuffer(info, 10_000) ?: -1
                    if (ox >= 0) {
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted && info.size > 0) {
                            val buf = aacEncoder!!.getOutputBuffer(ox)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer?.writeSampleData(audioTrackIndex, buf, info)
                        }
                        aacEncoder!!.releaseOutputBuffer(ox, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    } else if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (audioTrackIndex < 0) {
                            audioTrackIndex = muxer!!.addTrack(aacEncoder!!.outputFormat)
                            startMuxerIfReady()
                        }
                    } else break
                }

                if (selectedAudioCodec == AudioCodec.OPUS && opusEncoder != null) {
                    val oi = MediaCodec.BufferInfo()
                    while (true) {
                        val ox = opusEncoder!!.dequeueOutputBuffer(oi, 10_000)
                        if (ox >= 0) {
                            if ((oi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                val out = opusEncoder!!.getOutputBuffer(ox)!!
                                val size = oi.size
                                if (size > 0) {
                                    out.position(oi.offset)
                                    out.limit(oi.offset + size)
                                    val curPkt = ByteArray(size)
                                    out.get(curPkt)
                                    val curPtsUs = oi.presentationTimeUs

                                    if (prevPkt != null && prevPtsUs >= 0) {
                                        val durUs = (curPtsUs - prevPtsUs).coerceAtLeast(20_000L)
                                        val endSamples = ((prevPtsUs * 48000L) / 1_000_000L) +
                                                ((durUs * 48000L) / 1_000_000L)
                                        oggOpusWriter?.writePacketWithGranule(prevPkt!!, endSamples)
                                    }
                                    prevPkt = curPkt
                                    prevPtsUs = curPtsUs
                                }
                            }
                            opusEncoder!!.releaseOutputBuffer(ox, false)
                            if ((oi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        } else break
                    }
                    if (prevPkt != null && prevPtsUs >= 0) {
                        val durUs = 20_000L
                        val endSamples = ((prevPtsUs * 48000L) / 1_000_000L) +
                                ((durUs * 48000L) / 1_000_000L)
                        oggOpusWriter?.writePacketWithGranule(prevPkt!!, endSamples)
                    }
                    oggOpusWriter?.finish()
                }

                audioFlushed = true
            } catch (e: Exception) {
                Log.e(TAG, "Audio thread error", e)
                audioFlushed = true
            } finally {
                try { aacEncoder?.stop(); aacEncoder?.release() } catch (_: Exception) {}
                try { opusEncoder?.stop(); opusEncoder?.release() } catch (_: Exception) {}
                safeCloseAudioOut()
            }
        }
    }

    private fun stopMp4AndAudio() {
        try { videoEncoder?.stop() } catch (_: Exception) {}

        if (selectedAudioCodec == AudioCodec.WAV && tempAudioFile != null && audioFileSize > 44) {
            try { updateWavHeader(tempAudioFile!!, audioFileSize) } catch (e: Exception) {
                Log.e(TAG, "WAV header update error", e)
            }
        }

        if (muxerStarted) {
            try { muxer?.stop() } catch (_: Exception) {}
        }
        try { muxer?.release() } catch (_: Exception) {}

        muxer = null
        muxerStarted = false
        videoTrackIndex = -1
        audioTrackIndex = -1
        encoderSurface = null
    }

    @SuppressLint("MissingPermission")
    private fun stopAudioRecord() {
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    @SuppressLint("MissingPermission")
    private fun copyToMediaStore() {
        val mp4File = tempMp4File ?: return
        if (!mp4File.exists()) return

        try {
            val resolver = context.contentResolver

            val mp4Values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, mp4File.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/CameraW")
                }
            }
            resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), mp4Values)?.let { uri ->
                resolver.openOutputStream(uri)?.use { out -> mp4File.inputStream().use { it.copyTo(out) } }
            }

            val audioFile = tempAudioFile
            if (selectedAudioCodec != AudioCodec.NONE && audioFile != null && audioFile.exists()) {
                val audioMime = when (selectedAudioCodec) {
                    AudioCodec.WAV -> "audio/wav"
                    AudioCodec.OPUS -> "audio/opus"
                    AudioCodec.NONE -> return
                }
                val audioValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, audioFile.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, audioMime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Recordings")
                    }
                }
                resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), audioValues)?.let { uri ->
                    resolver.openOutputStream(uri)?.use { out -> audioFile.inputStream().use { it.copyTo(out) } }
                    audioFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "copyToMediaStore error", e)
        }
    }

    private fun writeWavHeader(out: FileOutputStream, channels: Int, sampleRate: Int) {
        val header = ByteArray(44)
        val bitDepth = 16
        val byteRate = sampleRate * channels * bitDepth / 8
        val blockAlign = channels * bitDepth / 8

        "RIFF".toByteArray().copyInto(header, 0)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        sampleRate.le32().copyInto(header, 24)
        byteRate.le32().copyInto(header, 28)
        header[32] = blockAlign.toByte(); header[33] = 0
        header[34] = bitDepth.toByte(); header[35] = 0
        "data".toByteArray().copyInto(header, 36)

        out.write(header)
    }

    private fun updateWavHeader(wavFile: File, totalFileSize: Long) {
        try {
            RandomAccessFile(wavFile, "rw").use { f ->
                val riffSize = (totalFileSize - 8).le32()
                f.seek(4); f.write(riffSize)
                val dataSize = (totalFileSize - 44).le32()
                f.seek(40); f.write(dataSize)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WAV header", e)
        }
    }

    private fun Int.le32(): ByteArray = byteArrayOf(
        (this and 0xFF).toByte(),
        ((this shr 8) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(),
        ((this shr 24) and 0xFF).toByte()
    )

    private fun Long.le32(): ByteArray = byteArrayOf(
        (this and 0xFF).toByte(),
        ((this shr 8) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(),
        ((this shr 24) and 0xFF).toByte()
    )

    private fun safeCloseAudioOut() {
        try { audioOutputFile?.flush() } catch (_: Exception) {}
        try { audioOutputFile?.close() } catch (_: Exception) {}
        audioOutputFile = null
    }

    private fun cleanup() {
        audioFeederRunning.set(false)
        try { videoEncoder?.stop(); videoEncoder?.release() } catch (_: Exception) {}
        try { aacEncoder?.stop(); aacEncoder?.release() } catch (_: Exception) {}
        try { opusEncoder?.stop(); opusEncoder?.release() } catch (_: Exception) {}
        stopAudioRecord()
        encoderSurface = null
        muxer = null
        tempMp4File?.delete()
        tempAudioFile?.delete()
        safeCloseAudioOut()
    }

    private class OggOpusWriter(
        private val out: FileOutputStream,
        private val sampleRate: Int,
        private val channels: Int,
        private val preSkip: Int
    ) {
        private val serial: Int = (System.nanoTime() xor 0x6f707573L).toInt()
        private var pageSeq = 0
        private var granulePos: Long = 0
        private var lastGranule: Long = 0

        private val OGG_CRC_TABLE = IntArray(256).apply {
            for (i in 0..255) {
                var r = i shl 24
                repeat(8) {
                    r = if ((r and 0x80000000.toInt()) != 0) (r shl 1) xor 0x04C11DB7 else r shl 1
                }
                this[i] = r
            }
        }

        private fun oggCrc32(data: ByteArray): Int {
            var crc = 0
            for (b in data) {
                val idx = ((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF
                crc = (crc shl 8) xor OGG_CRC_TABLE[idx]
            }
            return crc
        }

        fun writeHeaders() {
            val head = ByteArray(19)
            "OpusHead".toByteArray().copyInto(head, 0)
            head[8] = 1
            head[9] = channels.toByte()
            head[10] = (preSkip and 0xFF).toByte()
            head[11] = ((preSkip shr 8) and 0xFF).toByte()
            head[12] = (sampleRate and 0xFF).toByte()
            head[13] = ((sampleRate shr 8) and 0xFF).toByte()
            head[14] = ((sampleRate shr 16) and 0xFF).toByte()
            head[15] = ((sampleRate shr 24) and 0xFF).toByte()
            head[16] = 0; head[17] = 0; head[18] = 0
            writePage(head, bos = true, eos = false)

            val vendor = "CameraW".toByteArray()
            val tags = ByteArray(8 + 4 + vendor.size + 4)
            "OpusTags".toByteArray().copyInto(tags, 0)
            tags[8] = (vendor.size and 0xFF).toByte()
            tags[9] = ((vendor.size shr 8) and 0xFF).toByte()
            tags[10] = ((vendor.size shr 16) and 0xFF).toByte()
            tags[11] = ((vendor.size shr 24) and 0xFF).toByte()
            vendor.copyInto(tags, 12)
            val base = 12 + vendor.size
            tags[base] = 0; tags[base + 1] = 0; tags[base + 2] = 0; tags[base + 3] = 0
            writePage(tags, bos = false, eos = false)
        }

        fun writePacketWithGranule(packet: ByteArray, absoluteGranule: Long) {
            var g = max(0L, absoluteGranule - preSkip)
            if (g < lastGranule) g = lastGranule
            granulePos = g
            lastGranule = g
            writePage(packet, bos = false, eos = false)
        }

        fun finish() {
            writePage(ByteArray(0), bos = false, eos = true)
            out.flush()
        }

        private fun writePage(payload: ByteArray, bos: Boolean, eos: Boolean) {
            val header = ByteArray(27)
            "OggS".toByteArray().copyInto(header, 0)
            header[4] = 0
            var headerType = 0
            if (bos) headerType = headerType or 0x02
            if (eos) headerType = headerType or 0x04
            header[5] = headerType.toByte()
            for (i in 0..7) header[6 + i] = ((granulePos shr (i * 8)) and 0xFF).toByte()
            for (i in 0..3) header[14 + i] = ((serial shr (i * 8)) and 0xFF).toByte()
            for (i in 0..3) header[18 + i] = ((pageSeq shr (i * 8)) and 0xFF).toByte()
            pageSeq++
            header[22] = 0; header[23] = 0; header[24] = 0; header[25] = 0

            val segs = segmentTableNoTrailing255(payload.size)
            header[26] = segs.size.toByte()

            val page = ByteArray(header.size + segs.size + payload.size)
            header.copyInto(page, 0)
            segs.copyInto(page, header.size)
            payload.copyInto(page, header.size + segs.size)

            val c = oggCrc32(page)
            page[22] = (c and 0xFF).toByte()
            page[23] = ((c ushr 8) and 0xFF).toByte()
            page[24] = ((c ushr 16) and 0xFF).toByte()
            page[25] = ((c ushr 24) and 0xFF).toByte()

            out.write(page)
        }

        private fun segmentTableNoTrailing255(len: Int): ByteArray {
            if (len == 0) return ByteArray(0)
            var remaining = len
            val segs = ArrayList<Byte>(len / 255 + 1)
            while (remaining >= 255) {
                segs.add(255.toByte())
                remaining -= 255
            }
            segs.add(remaining.toByte())
            return segs.toByteArray()
        }
    }
}