package com.wmods.wppenhacer.xposed.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.wmods.wppenhacer.xposed.core.FeatureLoader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.zip.ZipFile
import kotlin.math.min

object AudioOpusConverter {

    private const val TAG = "AudioOpusConverter"
    private const val OPUS_SAMPLE_RATE = 48_000
    private const val OPUS_CHANNEL_COUNT = 1
    private const val TIMEOUT_US = 10_000L

    @Volatile
    private var nativeLoaded = false

    @Volatile
    var lastError: String? = null
        private set

    init {
        ensureNativeLoaded()
    }

    /**
     * Loads `libaudio_opus_converter.so` reliably both inside `com.wmods.wppenhacer` and
     * inside `com.whatsapp` / `com.whatsapp.w4b` when injected via LSPosed / YukiHookAPI.
     */
    @JvmStatic
    @Synchronized
    fun ensureNativeLoaded(): Boolean {
        if (nativeLoaded) return true

        // Stage 1: Standard System.loadLibrary (succeeds inside WA-NI-ULTRA app process)
        try {
            System.loadLibrary("audio_opus_converter")
            nativeLoaded = true
            lastError = null
            return true
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
        }

        val moduleInfo = runCatching { FeatureLoader.moduleContext.applicationInfo }.getOrNull()
        if (moduleInfo != null) {
            // Stage 2: Load from extracted module nativeLibraryDir
            val nativeDir = moduleInfo.nativeLibraryDir
            if (!nativeDir.isNullOrEmpty()) {
                val soFile = File(nativeDir, "libaudio_opus_converter.so")
                if (soFile.exists() && soFile.canRead()) {
                    try {
                        System.load(soFile.absolutePath)
                        nativeLoaded = true
                        lastError = null
                        return true
                    } catch (t: Throwable) {
                        lastError = "Stage2(${soFile.absolutePath}): ${t.message}"
                    }
                }
            }

            // Stage 3: Load directly from uncompressed APK entry via Bionic linker (!/lib/<abi>/...)
            val sourceApk = moduleInfo.sourceDir
            if (!sourceApk.isNullOrEmpty()) {
                for (abi in Build.SUPPORTED_ABIS) {
                    try {
                        System.load("$sourceApk!/lib/$abi/libaudio_opus_converter.so")
                        nativeLoaded = true
                        lastError = null
                        return true
                    } catch (_: Throwable) {
                    }
                }
            }

            // Stage 4: Extract from module APK zip into host codeCacheDir as read-only (Android 14+ W^X compliant)
            val hostApp = runCatching { Utils.application }.getOrNull()
            if (!sourceApk.isNullOrEmpty() && hostApp != null) {
                try {
                    ZipFile(sourceApk).use { zip ->
                        for (abi in Build.SUPPORTED_ABIS) {
                            val entry = zip.getEntry("lib/$abi/libaudio_opus_converter.so") ?: continue
                            val outDir = File(hostApp.codeCacheDir, "wae_jni").apply { mkdirs() }
                            val outSo = File(outDir, "libaudio_opus_converter_${abi}_${entry.crc}.so")
                            if (!outSo.exists() || outSo.length() != entry.size) {
                                if (outSo.exists()) {
                                    outSo.setWritable(true)
                                    outSo.delete()
                                }
                                zip.getInputStream(entry).use { input ->
                                    outSo.outputStream().use { output -> input.copyTo(output) }
                                }
                                outSo.setReadable(true, false)
                                outSo.setExecutable(true, false)
                                outSo.setReadOnly()
                            }
                            System.load(outSo.absolutePath)
                            nativeLoaded = true
                            lastError = null
                            return true
                        }
                    }
                } catch (t: Throwable) {
                    lastError = "Stage4: ${t.javaClass.simpleName}: ${t.message}"
                    Log.e(TAG, "Stage 4 load failed", t)
                }
            }
        }
        return false
    }

    @JvmStatic
    private external fun nativeInitOpusEncoder(
        outputPath: String,
        sampleRate: Int,
        channels: Int
    ): Long

    @JvmStatic
    private external fun nativeEncodeOpus(handle: Long, pcmData: ByteArray, lengthBytes: Int)

    @JvmStatic
    private external fun nativeCloseOpusEncoder(handle: Long)

    @JvmStatic
    @JvmOverloads
    fun convert(
        filePath: String,
        maxDurationUs: Long = Long.MAX_VALUE,
        outputDir: File? = null,
        startUs: Long = 0L,
        autoFindLoudest: Boolean = false
    ): File? {
        val srcFile = File(filePath)
        if (!srcFile.exists() || srcFile.length() == 0L) {
            lastError = "Arquivo de origem não encontrado: $filePath"
            return null
        }

        val targetDir = outputDir?.apply { mkdirs() } ?: srcFile.parentFile
        val outFile = File(targetDir, "voice_note_${System.currentTimeMillis()}.opus")

        // Fast-path: if input is already a valid Ogg/Opus voice note within duration & no trim requested
        if (startUs <= 0L && !autoFindLoudest && isAlreadyShortOggOpus(srcFile, maxDurationUs)) {
            return runCatching {
                srcFile.copyTo(outFile, overwrite = true)
                outFile
            }.getOrNull()
        }

        if (!ensureNativeLoaded()) {
            Log.e(TAG, "Native opus library unavailable: $lastError")
            return null
        }

        val effectiveStartUs = if (autoFindLoudest && startUs <= 0L && maxDurationUs < Long.MAX_VALUE / 2) {
            runCatching { findLoudestWindowStartUs(filePath, maxDurationUs) }.getOrDefault(0L)
        } else {
            startUs.coerceAtLeast(0L)
        }

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoderHandle: Long = 0

        try {
            setDataSourceSafe(extractor, filePath)
            val audioTrack = selectAudioTrack(extractor)
            if (audioTrack < 0) {
                lastError = "Sem faixa de áudio no arquivo"
                return null
            }

            extractor.selectTrack(audioTrack)
            if (effectiveStartUs > 0L) {
                extractor.seekTo(effectiveStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
            val inputFormat = extractor.getTrackFormat(audioTrack)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("MIME de áudio ausente")

            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            encoderHandle =
                nativeInitOpusEncoder(outFile.absolutePath, OPUS_SAMPLE_RATE, OPUS_CHANNEL_COUNT)
            if (encoderHandle == 0L) {
                throw IOException("Falha ao iniciar libopusenc em ${outFile.absolutePath}")
            }

            val writtenBytes = transcode(extractor, decoder, encoderHandle, effectiveStartUs, maxDurationUs)
            nativeCloseOpusEncoder(encoderHandle)
            encoderHandle = 0L

            if (writtenBytes < OPUS_SAMPLE_RATE * 2 / 10 || !outFile.exists() || outFile.length() < 120L) {
                lastError = "Faixa de áudio curta demais ou muda (${writtenBytes}B PCM)"
                if (outFile.exists()) outFile.delete()
                return null
            }
            lastError = null
            return outFile
        } catch (e: Throwable) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Conversion failed", e)
            if (outFile.exists()) outFile.delete()
            return null
        } finally {
            try {
                decoder?.stop()
                decoder?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
            try {
                if (encoderHandle != 0L) nativeCloseOpusEncoder(encoderHandle)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Reads exact duration in seconds of an Ogg/Opus buffer from its final `OggS` granule position
     * minus `OpusHead` pre-skip at 48 kHz (same formula as kuebot's `oggOpusSeconds`).
     */
    @JvmStatic
    fun readOggOpusSeconds(oggBytes: ByteArray): Double {
        if (oggBytes.size < 64) return 0.0
        val headIdx = indexOfAscii(oggBytes, "OpusHead")
        val preSkip = if (headIdx >= 0 && headIdx + 12 <= oggBytes.size) {
            ((oggBytes[headIdx + 11].toInt() and 0xFF) shl 8) or (oggBytes[headIdx + 10].toInt() and 0xFF)
        } else {
            0
        }
        val lastOgg = lastIndexOfAscii(oggBytes, "OggS")
        if (lastOgg < 0 || lastOgg + 14 > oggBytes.size) return 0.0
        val granule = ByteBuffer.wrap(oggBytes, lastOgg + 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
        return ((granule - preSkip).coerceAtLeast(0L)).toDouble() / 48_000.0
    }

    private fun isAlreadyShortOggOpus(file: File, maxDurationUs: Long): Boolean {
        if (file.length() > 240_000L || file.length() < 120L) return false
        return runCatching {
            val bytes = file.readBytes()
            if (indexOfAscii(bytes, "OggS") != 0 || indexOfAscii(bytes, "OpusHead") < 0) {
                return@runCatching false
            }
            val secs = readOggOpusSeconds(bytes)
            secs > 0.15 && (secs * 1_000_000.0) <= maxDurationUs.toDouble() + 150_000.0
        }.getOrDefault(false)
    }

    private fun indexOfAscii(haystack: ByteArray, needle: String): Int {
        val n = needle.toByteArray(Charsets.US_ASCII)
        if (haystack.size < n.size) return -1
        outer@ for (i in 0..(haystack.size - n.size)) {
            for (j in n.indices) {
                if (haystack[i + j] != n[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun lastIndexOfAscii(haystack: ByteArray, needle: String): Int {
        val n = needle.toByteArray(Charsets.US_ASCII)
        if (haystack.size < n.size) return -1
        outer@ for (i in (haystack.size - n.size) downTo 0) {
            for (j in n.indices) {
                if (haystack[i + j] != n[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun setDataSourceSafe(extractor: MediaExtractor, filePath: String) {
        val file = File(filePath)
        if (file.exists()) {
            try {
                FileInputStream(file).use { fis ->
                    extractor.setDataSource(fis.fd)
                }
                return
            } catch (_: Throwable) {
            }
        }
        extractor.setDataSource(filePath)
    }

    private fun findLoudestWindowStartUs(filePath: String, windowDurationUs: Long): Long {
        val extractor = MediaExtractor()
        try {
            setDataSourceSafe(extractor, filePath)
            val track = selectAudioTrack(extractor)
            if (track < 0) return 0L
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val totalDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            if (totalDurationUs <= windowDurationUs || totalDurationUs <= 0L) return 0L

            val sampleBuf = ByteBuffer.allocate(64 * 1024)
            val timesUs = ArrayList<Long>(512)
            val energies = ArrayList<Double>(512)
            val maxScanUs = min(totalDurationUs, 120_000_000L)

            while (true) {
                val size = extractor.readSampleData(sampleBuf, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (pts > maxScanUs) break
                if (pts >= 0L && size > 0) {
                    var sum = 0.0
                    for (i in 0 until size) {
                        val v = sampleBuf.get(i).toInt()
                        sum += (v * v).toDouble()
                    }
                    timesUs.add(pts)
                    energies.add(sum / size)
                }
                if (!extractor.advance()) break
            }

            if (timesUs.size < 4) return 0L
            var bestStartUs = 0L
            var bestScore = -1.0
            for (i in timesUs.indices) {
                val startCandidate = timesUs[i]
                if (startCandidate + windowDurationUs > totalDurationUs) break
                var score = 0.0
                var j = i
                while (j < timesUs.size && timesUs[j] - startCandidate <= windowDurationUs) {
                    score += energies[j]
                    j++
                }
                if (score > bestScore * 1.001) {
                    bestScore = score
                    bestStartUs = startCandidate
                }
            }
            return bestStartUs.coerceAtLeast(0L)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun transcode(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoderHandle: Long,
        startUs: Long,
        maxDurationUs: Long
    ): Int {
        val decoderInfo = MediaCodec.BufferInfo()
        var extractorDone = false
        var decoderDone = false
        var inputSampleRate = OPUS_SAMPLE_RATE
        var inputChannels = 1
        var firstPtsUs = -1L
        val endLimitUs = if (maxDurationUs >= Long.MAX_VALUE / 2) Long.MAX_VALUE else maxDurationUs
        val pcmChunks = ArrayList<ByteArray>()
        var totalPcmBytes = 0

        while (!decoderDone) {
            if (!extractorDone) {
                val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val inBuf = decoder.getInputBuffer(inIdx)
                    val sampleSize = inBuf?.let { extractor.readSampleData(it, 0) } ?: -1
                    val pts = extractor.sampleTime
                    if (firstPtsUs < 0L && pts >= 0L) {
                        firstPtsUs = maxOf(startUs, pts)
                    }
                    val elapsedUs = if (firstPtsUs >= 0L && pts >= firstPtsUs) pts - firstPtsUs else 0L
                    if (sampleSize < 0 || elapsedUs > endLimitUs) {
                        decoder.queueInputBuffer(
                            inIdx,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        extractorDone = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val fmt = decoder.outputFormat
                inputSampleRate =
                    if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else OPUS_SAMPLE_RATE
                inputChannels =
                    if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            } else if (outIdx >= 0) {
                val pcmBuf = decoder.getOutputBuffer(outIdx)
                val eos = (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                if (decoderInfo.size > 0 && pcmBuf != null && decoderInfo.presentationTimeUs >= startUs) {
                    pcmBuf.position(decoderInfo.offset)
                    pcmBuf.limit(decoderInfo.offset + decoderInfo.size)

                    val resampled = resampleAndDownmix(pcmBuf, inputSampleRate, inputChannels)
                    if (resampled.isNotEmpty()) {
                        pcmChunks.add(resampled)
                        totalPcmBytes += resampled.size
                    }
                }

                decoder.releaseOutputBuffer(outIdx, false)
                if (eos) decoderDone = true
            }
        }

        if (totalPcmBytes > 0) {
            val allPcm = ByteArray(totalPcmBytes)
            var pos = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, allPcm, pos, chunk.size)
                pos += chunk.size
            }
            applyEdgeFade15ms(allPcm)
            nativeEncodeOpus(encoderHandle, allPcm, allPcm.size)
        }
        return totalPcmBytes
    }

    private fun applyEdgeFade15ms(pcmBytes: ByteArray) {
        val totalSamples = pcmBytes.size / 2
        val fadeSamples = min(totalSamples / 2, (OPUS_SAMPLE_RATE * 15) / 1000)
        if (fadeSamples <= 1) return
        for (i in 0 until fadeSamples) {
            val factor = i.toFloat() / fadeSamples.toFloat()
            val inOffset = i * 2
            val inSample = ((pcmBytes[inOffset + 1].toInt() shl 8) or (pcmBytes[inOffset].toInt() and 0xFF)).toShort()
            val fadedIn = (inSample * factor).toInt().toShort()
            pcmBytes[inOffset] = (fadedIn.toInt() and 0xFF).toByte()
            pcmBytes[inOffset + 1] = ((fadedIn.toInt() shr 8) and 0xFF).toByte()

            val outIdx = totalSamples - 1 - i
            val outOffset = outIdx * 2
            val outSample = ((pcmBytes[outOffset + 1].toInt() shl 8) or (pcmBytes[outOffset].toInt() and 0xFF)).toShort()
            val fadedOut = (outSample * factor).toInt().toShort()
            pcmBytes[outOffset] = (fadedOut.toInt() and 0xFF).toByte()
            pcmBytes[outOffset + 1] = ((fadedOut.toInt() shr 8) and 0xFF).toByte()
        }
    }

    private fun resampleAndDownmix(
        pcmBuf: ByteBuffer,
        inSampleRate: Int,
        inChannels: Int
    ): ByteArray {
        pcmBuf.order(ByteOrder.LITTLE_ENDIAN)
        val shortBuf = pcmBuf.asShortBuffer()
        val numInputFrames = shortBuf.remaining() / inChannels

        val outSampleRate = OPUS_SAMPLE_RATE
        if (numInputFrames <= 0) return ByteArray(0)

        val numOutputFrames = (numInputFrames.toLong() * outSampleRate / inSampleRate).toInt()
        val outBytes = ByteArray(numOutputFrames * 2)

        for (i in 0 until numOutputFrames) {
            val inPos = i.toFloat() * inSampleRate / outSampleRate
            val inIdx1 = inPos.toInt()
            val inIdx2 = min(inIdx1 + 1, numInputFrames - 1)
            val frac = inPos - inIdx1

            val s1 = getMonoSample(shortBuf, inIdx1, inChannels)
            val s2 = getMonoSample(shortBuf, inIdx2, inChannels)

            var interpolated = s1 + frac * (s2 - s1)

            if (interpolated > 32767.0f) interpolated = 32767.0f
            if (interpolated < -32768.0f) interpolated = -32768.0f

            val outSample = interpolated.toInt().toShort()
            outBytes[i * 2] = (outSample.toInt() and 0xFF).toByte()
            outBytes[i * 2 + 1] = ((outSample.toInt() shr 8) and 0xFF).toByte()
        }
        return outBytes
    }

    private fun getMonoSample(buf: ShortBuffer, frameIndex: Int, channels: Int): Float {
        return if (channels == 1) {
            buf.get(frameIndex).toFloat()
        } else {
            val left = buf.get(frameIndex * channels).toFloat()
            val right = buf.get(frameIndex * channels + 1).toFloat()
            (left + right) / 2.0f
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) return i
        }
        return -1
    }
}
