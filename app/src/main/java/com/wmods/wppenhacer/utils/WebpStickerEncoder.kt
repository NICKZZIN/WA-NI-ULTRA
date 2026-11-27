package com.wmods.wppenhacer.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin + Android SDK WebP static & animated sticker encoder (512x512)
 * with WhatsApp EXIF (0x5741) pack/author/sound-sha256 metadata injection,
 * 4 framing modes (cortar, inteiro/ajustar, esticar, redondo), time range trimming,
 * and meme caption rendering (inspired by kuebot & whatsapp-figurinhas-com-som).
 */
object WebpStickerEncoder {

    const val STICKER_SIZE = 512
    const val MAX_STATIC_BYTES = 98_000
    const val MAX_ANIMATED_BYTES = 490_000
    const val DEFAULT_ANIM_DURATION_MS = 6_000L
    const val MAX_ANIM_DURATION_MS = 10_000L
    const val DEFAULT_SOUND_ANIM_DURATION_MS = 5_000L
    const val MAX_SOUND_STILL_DURATION_MS = 10_000L
    const val MAX_SOUND_LONG_ANIM_DURATION_MS = 10_000L
    const val MAX_SOUND_LONG_STILL_DURATION_MS = 20_000L
    private const val MAX_EXTRACTED_FRAMES = 75

    private data class AnimEncodingStep(
        val maxFps: Int,
        val quality: Int,
        val detailSize: Int
    )

    // Smooth FPS ladder inspired by whatsapp-figurinhas-com-som & kuebot:
    // preserves 12-15 FPS fluidity by reducing internal detail before dropping FPS.
    private val ANIM_LADDER = arrayOf(
        AnimEncodingStep(maxFps = 15, quality = 64, detailSize = 512),
        AnimEncodingStep(maxFps = 15, quality = 46, detailSize = 512),
        AnimEncodingStep(maxFps = 12, quality = 42, detailSize = 432),
        AnimEncodingStep(maxFps = 12, quality = 32, detailSize = 360),
        AnimEncodingStep(maxFps = 10, quality = 26, detailSize = 300),
        AnimEncodingStep(maxFps = 8, quality = 18, detailSize = 256)
    )

    enum class FitMode {
        CROP,     // cortar (padrão): preenche 512x512 cortando sobras
        CONTAIN,  // inteiro / ajustar: mantém imagem toda com bordas transparentes
        STRETCH,  // esticar: estica para 512x512
        CIRCLE    // redondo: recorte circular 512x512 com cantos transparentes
    }

    data class StickerOptions(
        val fitMode: FitMode = FitMode.CROP,
        val startMs: Long = 0L,
        val durationMs: Long = DEFAULT_ANIM_DURATION_MS,
        val keepDuration: Boolean = false,
        val stillFromVideo: Boolean = false,
        val longSound: Boolean = false,
        val separateAudio: Boolean = false,
        val caption: String = "",
        val captionColor: Int = Color.WHITE,
        val soundSha256Base64: String? = null
    )

    data class EncodedSticker(
        val webpBytes: ByteArray,
        val isAnimated: Boolean,
        val durationMs: Long,
        val frameCount: Int
    )

    private data class ExtractedFrames(
        val bitmaps: List<Bitmap>,
        val totalDurationMs: Long
    )

    private val QUALITY_STEPS = intArrayOf(74, 58, 44, 32, 22, 14)

    @JvmStatic
    @JvmOverloads
    fun createStaticSticker(
        imageFile: File,
        fullFrame: Boolean,
        packName: String,
        author: String,
        options: StickerOptions? = null,
        onLog: ((String) -> Unit)? = null
    ): EncodedSticker {
        val opts = options ?: StickerOptions(
            fitMode = if (fullFrame) FitMode.CONTAIN else FitMode.CROP
        )
        onLog?.invoke("Lendo imagem: ${imageFile.name} (${imageFile.length() / 1024} KB)")
        val sourceBitmap = decodeOrientedBitmap(imageFile)
            ?: throw IllegalArgumentException("Não foi possível decodificar a imagem.")

        val framed = frameBitmap512(
            source = sourceBitmap,
            fitMode = opts.fitMode,
            isOpaqueVideo = false,
            caption = opts.caption,
            captionColor = opts.captionColor
        )
        if (framed !== sourceBitmap) sourceBitmap.recycle()

        return encodeSingleBitmapToSticker(framed, packName, author, opts.soundSha256Base64, onLog)
    }

    /**
     * Extracts a single frame from a video at [options.startMs] and encodes it as a static 512x512 sticker
     * (used by `/figsom parada`, which allows up to 10s of audio on a video frame).
     */
    @JvmStatic
    fun createVideoFrameSticker(
        videoFile: File,
        packName: String,
        author: String,
        options: StickerOptions,
        onLog: ((String) -> Unit)? = null
    ): EncodedSticker {
        val retriever = MediaMetadataRetriever()
        val rawFrame = try {
            setRetrieverDataSource(retriever, videoFile)
            val timeUs = options.startMs.coerceAtLeast(0L) * 1000L
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L)
        } finally {
            runCatching { retriever.release() }
        } ?: throw IllegalStateException("Não foi possível extrair quadro do vídeo.")

        val framed = frameBitmap512(
            source = rawFrame,
            fitMode = options.fitMode,
            isOpaqueVideo = true,
            caption = options.caption,
            captionColor = options.captionColor
        )
        if (framed !== rawFrame) rawFrame.recycle()

        return encodeSingleBitmapToSticker(framed, packName, author, options.soundSha256Base64, onLog)
    }

    private fun encodeSingleBitmapToSticker(
        framed: Bitmap,
        packName: String,
        author: String,
        soundSha256Base64: String?,
        onLog: ((String) -> Unit)?
    ): EncodedSticker {
        try {
            for (quality in intArrayOf(86, 74, 60, 44, 30, 20)) {
                val singleWebp = compressSingleWebp(framed, quality)
                val withExif = injectWhatsAppExif(singleWebp, packName, author, soundSha256Base64)
                if (withExif.size <= MAX_STATIC_BYTES || quality == 20) {
                    onLog?.invoke("Figurinha estática pronta: ${withExif.size / 1024} KB (qualidade $quality)")
                    return EncodedSticker(
                        webpBytes = withExif,
                        isAnimated = false,
                        durationMs = 0L,
                        frameCount = 1
                    )
                }
            }
            throw IllegalStateException("Não foi possível comprimir a imagem abaixo de 100 KB.")
        } finally {
            runCatching { framed.recycle() }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun createAnimatedSticker(
        videoFile: File,
        fullFrame: Boolean,
        packName: String,
        author: String,
        options: StickerOptions? = null,
        onLog: ((String) -> Unit)? = null
    ): EncodedSticker {
        val opts = options ?: StickerOptions(
            fitMode = if (fullFrame) FitMode.CONTAIN else FitMode.CROP
        )
        if (opts.stillFromVideo) {
            return createVideoFrameSticker(videoFile, packName, author, opts, onLog)
        }

        val extracted = extractAnimatedFrames(videoFile, opts, onLog)
        val bitmaps = extracted.bitmaps
        if (bitmaps.isEmpty()) {
            throw IllegalStateException("Não foi possível extrair quadros do vídeo/GIF.")
        }
        if (bitmaps.size == 1) {
            return encodeSingleBitmapToSticker(
                bitmaps[0],
                packName,
                author,
                opts.soundSha256Base64,
                onLog
            )
        }

        try {
            val totalDurationMs = extracted.totalDurationMs.coerceAtLeast(300L)
            val sourceFps = (bitmaps.size * 1000.0 / totalDurationMs).coerceAtLeast(1.0)

            for (stepIdx in ANIM_LADDER.indices) {
                val step = ANIM_LADDER[stepIdx]
                val effectiveFps = min(step.maxFps.toDouble(), sourceFps)
                val picked = ArrayList<Bitmap>()
                var k = 0
                while (true) {
                    val srcIdx = ((k * sourceFps) / effectiveFps).roundToInt()
                    if (srcIdx >= bitmaps.size) break
                    picked.add(bitmaps[srcIdx])
                    k++
                }
                if (picked.size < 2 && bitmaps.size >= 2) {
                    picked.clear()
                    picked.add(bitmaps.first())
                    picked.add(bitmaps.last())
                }

                val framePayloads = ArrayList<FrameBitstream>(picked.size)
                var anyAlpha = false

                for (bmp in picked) {
                    val workingBmp = if (step.detailSize < STICKER_SIZE) {
                        downscaleAndRestore512(bmp, step.detailSize)
                    } else {
                        bmp
                    }
                    try {
                        val singleWebp = compressSingleWebp(workingBmp, step.quality)
                        val bitstream = extractFrameBitstream(singleWebp) ?: continue
                        if (bitstream.hasAlpha) anyAlpha = true
                        framePayloads.add(bitstream)
                    } finally {
                        if (workingBmp !== bmp) runCatching { workingBmp.recycle() }
                    }
                }

                if (framePayloads.size < 2) {
                    throw IllegalStateException("Falha ao codificar quadros WebP do vídeo/GIF.")
                }

                val muxed = muxAnimatedWebp(
                    frames = framePayloads,
                    totalDurationMs = totalDurationMs,
                    hasAlpha = anyAlpha,
                    packName = packName,
                    author = author,
                    soundSha256Base64 = opts.soundSha256Base64
                )

                val isLastAttempt = stepIdx == ANIM_LADDER.lastIndex
                if (muxed.size <= MAX_ANIMATED_BYTES || isLastAttempt) {
                    onLog?.invoke(
                        "Figurinha animada pronta: ${muxed.size / 1024} KB (${framePayloads.size} quadros @ ${effectiveFps.roundToInt()} fps, q=${step.quality})"
                    )
                    return EncodedSticker(
                        webpBytes = muxed,
                        isAnimated = true,
                        durationMs = totalDurationMs,
                        frameCount = framePayloads.size
                    )
                }
            }
            throw IllegalStateException("Não foi possível comprimir a figurinha animada abaixo de 500 KB.")
        } finally {
            bitmaps.forEach { runCatching { it.recycle() } }
        }
    }

    private fun downscaleAndRestore512(source512: Bitmap, detailSize: Int): Bitmap {
        val small = Bitmap.createScaledBitmap(source512, detailSize, detailSize, true)
        return try {
            val restored = Bitmap.createScaledBitmap(small, STICKER_SIZE, STICKER_SIZE, true)
            restored.setHasAlpha(source512.hasAlpha())
            restored
        } finally {
            if (small !== source512) runCatching { small.recycle() }
        }
    }

    /**
     * Reads the `sound-sha256` tag from a WebP sticker's EXIF block (returns null if absent).
     */
    @JvmStatic
    fun readSoundSha256FromWebp(webpFile: File): String? {
        if (!webpFile.exists() || webpFile.length() < 40L || webpFile.length() > 2_000_000L) return null
        return runCatching {
            val data = webpFile.readBytes()
            if (data.size < 30) return@runCatching null
            var offset = 12
            while (offset + 8 <= data.size) {
                val type = String(data, offset, 4, Charsets.US_ASCII)
                val size = readUInt32LE(data, offset + 4)
                if (size < 0 || offset + 8 + size > data.size) break
                if (type == "EXIF" && size > 24) {
                    val bodyStart = offset + 8
                    val jsonLen = readUInt32LE(data, bodyStart + 14)
                    if (jsonLen in 2..(size - 22)) {
                        val jsonStr = String(data, bodyStart + 22, jsonLen, Charsets.UTF_8)
                        val obj = JSONObject(jsonStr)
                        val sha = obj.optString("sound-sha256", "").trim()
                        if (sha.isNotEmpty()) return@runCatching sha
                    }
                }
                offset += 8 + size + (size and 1)
            }
            null
        }.getOrNull()
    }

    /**
     * Re-tags an existing WebP sticker with the `sound-sha256` hash of the Ogg/Opus audio file
     * and the 🔊 emoji in its EXIF block (same as kuebot's `tagSoundSticker`).
     */
    @JvmStatic
    fun tagSoundSticker(
        webpBytes: ByteArray,
        oggBytes: ByteArray,
        packName: String,
        author: String
    ): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(oggBytes)
        val soundSha = Base64.encodeToString(sha256, Base64.NO_WRAP)
        return injectWhatsAppExif(webpBytes, packName, author, soundSha)
    }

    private fun setRetrieverDataSource(retriever: MediaMetadataRetriever, file: File) {
        if (file.exists()) {
            try {
                FileInputStream(file).use { fis ->
                    retriever.setDataSource(fis.fd)
                }
                return
            } catch (_: Throwable) {
            }
        }
        retriever.setDataSource(file.absolutePath)
    }

    private fun extractAnimatedFrames(
        file: File,
        opts: StickerOptions,
        onLog: ((String) -> Unit)?
    ): ExtractedFrames {
        if (isGifFileHeader(file)) {
            extractGifFrames(file, opts, onLog)?.let { return it }
        }

        val retriever = MediaMetadataRetriever()
        try {
            setRetrieverDataSource(retriever, file)
            val rawDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 3_000L
            val startMs = opts.startMs.coerceIn(0L, max(0L, rawDurationMs - 200L))
            val availableMs = max(300L, rawDurationMs - startMs)
            val requestedMs = opts.durationMs.coerceIn(300L, MAX_ANIM_DURATION_MS)
            val clipDurationMs = min(requestedMs, availableMs)

            val targetFps = when {
                clipDurationMs <= 5_200L -> 15
                clipDurationMs <= 7_500L -> 12
                else -> 10
            }
            val frameIntervalMs = (1000.0 / targetFps).roundToInt().coerceIn(66, 120)
            val totalFrames = (clipDurationMs / frameIntervalMs).toInt().coerceIn(2, MAX_EXTRACTED_FRAMES)

            onLog?.invoke(
                "Extraindo $totalFrames quadros fluidos (${(clipDurationMs / 1000f)}s @ $targetFps fps)..."
            )

            val bitmaps = ArrayList<Bitmap>(totalFrames)

            // Fast & smooth API 28+ single-pass batch frame decoder (avoids keyframe seek stutter!)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    val videoFrameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                        ?.toIntOrNull() ?: 0
                    if (videoFrameCount >= 4 && rawDurationMs > 300L) {
                        val firstIdx = ((startMs.toDouble() / rawDurationMs) * videoFrameCount)
                            .toInt().coerceIn(0, videoFrameCount - 2)
                        val endIdx = (((startMs + clipDurationMs).toDouble() / rawDurationMs) * videoFrameCount)
                            .toInt().coerceIn(firstIdx + 1, videoFrameCount)
                        val spanCount = (endIdx - firstIdx).coerceAtLeast(1)

                        // Decode in small sequential batches of up to 16 frames to keep peak RAM low
                        if (spanCount <= 180) {
                            val wantedIndices = IntArray(totalFrames) { f ->
                                (firstIdx + (f.toLong() * spanCount / totalFrames).toInt())
                                    .coerceIn(firstIdx, endIdx - 1)
                            }.distinct()

                            var cursor = firstIdx
                            var nextWantedPos = 0
                            while (cursor < endIdx && nextWantedPos < wantedIndices.size) {
                                val batchSize = min(16, endIdx - cursor)
                                val batch = retriever.getFramesAtIndex(cursor, batchSize)
                                for (bIdx in batch.indices) {
                                    val absIdx = cursor + bIdx
                                    val rawBmp = batch[bIdx]
                                    if (nextWantedPos < wantedIndices.size && absIdx == wantedIndices[nextWantedPos]) {
                                        val framed = frameBitmap512(
                                            source = rawBmp,
                                            fitMode = opts.fitMode,
                                            isOpaqueVideo = true,
                                            caption = opts.caption,
                                            captionColor = opts.captionColor
                                        )
                                        if (framed !== rawBmp) rawBmp.recycle()
                                        bitmaps.add(framed)
                                        nextWantedPos++
                                    } else {
                                        rawBmp.recycle()
                                    }
                                }
                                cursor += batchSize
                            }
                        }
                    }
                }
            }

            // Fallback: timestamp-based extraction if batch index metadata wasn't available
            if (bitmaps.size < 2) {
                bitmaps.forEach { runCatching { it.recycle() } }
                bitmaps.clear()
                for (f in 0 until totalFrames) {
                    val frameTimeMs = startMs + (f.toLong() * clipDurationMs / totalFrames)
                    val timeUs = frameTimeMs * 1000L
                    val rawFrame = decodeRetrieverFrameAt(retriever, timeUs) ?: continue

                    val framed = frameBitmap512(
                        source = rawFrame,
                        fitMode = opts.fitMode,
                        isOpaqueVideo = true,
                        caption = opts.caption,
                        captionColor = opts.captionColor
                    )
                    if (framed !== rawFrame) rawFrame.recycle()
                    bitmaps.add(framed)
                }
            }

            // Fallback: if timestamp seeking yielded < 2 frames on a short GIF/MP4, extract by frame index (API 28+)
            if (bitmaps.size < 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    val count = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                        ?.toIntOrNull()?.coerceIn(2, MAX_EXTRACTED_FRAMES) ?: 12
                    val step = max(1, count / min(count, 20))
                    var idx = 0
                    while (idx < count && bitmaps.size < 20) {
                        val rawFrame = runCatching { retriever.getFrameAtIndex(idx) }.getOrNull()
                        if (rawFrame != null) {
                            val framed = frameBitmap512(
                                source = rawFrame,
                                fitMode = opts.fitMode,
                                isOpaqueVideo = true,
                                caption = opts.caption,
                                captionColor = opts.captionColor
                            )
                            if (framed !== rawFrame) rawFrame.recycle()
                            bitmaps.add(framed)
                        }
                        idx += step
                    }
                }
            }

            if (bitmaps.isNotEmpty()) {
                return ExtractedFrames(bitmaps, clipDurationMs)
            }
        } catch (t: Throwable) {
            onLog?.invoke("Aviso MediaMetadataRetriever (${t.message}), tentando decodificador GIF...")
        } finally {
            runCatching { retriever.release() }
        }

        extractGifFrames(file, opts, onLog)?.let { return it }
        decodeOrientedBitmap(file)?.let { bmp ->
            val framed = frameBitmap512(
                source = bmp,
                fitMode = opts.fitMode,
                isOpaqueVideo = false,
                caption = opts.caption,
                captionColor = opts.captionColor
            )
            if (framed !== bmp) bmp.recycle()
            return ExtractedFrames(listOf(framed), 1000L)
        }
        throw IllegalStateException("Formato de vídeo ou GIF não suportado.")
    }

    private fun decodeRetrieverFrameAt(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    768,
                    768
                )
            }.getOrNull()?.let { return it }
        }
        return runCatching {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun extractGifFrames(
        file: File,
        opts: StickerOptions,
        onLog: ((String) -> Unit)?
    ): ExtractedFrames? {
        return runCatching {
            val movie = FileInputStream(file).use { Movie.decodeStream(it) } ?: return null
            if (movie.width() <= 0 || movie.height() <= 0) return null
            val rawDuration = movie.duration().takeIf { it > 0 } ?: 2_000
            val clipDuration = min(opts.durationMs.toInt().coerceAtMost(MAX_ANIM_DURATION_MS.toInt()), max(400, rawDuration))
            val intervalMs = 100
            val totalFrames = (clipDuration / intervalMs).coerceIn(2, MAX_EXTRACTED_FRAMES)
            onLog?.invoke("Decodificando GIF: $totalFrames quadros (${clipDuration / 1000f}s)...")

            val bitmaps = ArrayList<Bitmap>(totalFrames)
            for (i in 0 until totalFrames) {
                val rawBmp = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(rawBmp)
                canvas.drawColor(Color.TRANSPARENT)
                movie.setTime(((opts.startMs.toInt() + i * intervalMs) % max(1, rawDuration)))
                movie.draw(canvas, 0f, 0f)
                val framed = frameBitmap512(
                    source = rawBmp,
                    fitMode = opts.fitMode,
                    isOpaqueVideo = false,
                    caption = opts.caption,
                    captionColor = opts.captionColor
                )
                if (framed !== rawBmp) rawBmp.recycle()
                bitmaps.add(framed)
            }
            if (bitmaps.isEmpty()) null else ExtractedFrames(bitmaps, clipDuration.toLong())
        }.getOrNull()
    }

    private fun isGifFileHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 6) return false
        return runCatching {
            val header = ByteArray(6)
            FileInputStream(file).use { it.read(header) }
            val magic = String(header, Charsets.US_ASCII)
            magic.startsWith("GIF87a") || magic.startsWith("GIF89a")
        }.getOrDefault(false)
    }

    private fun decodeOrientedBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > 1536 || bounds.outHeight / sample > 1536) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null

        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return decoded

        val matrix = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private fun frameBitmap512(
        source: Bitmap,
        fitMode: FitMode,
        isOpaqueVideo: Boolean,
        caption: String,
        captionColor: Int
    ): Bitmap {
        val target = Bitmap.createBitmap(STICKER_SIZE, STICKER_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)

        val srcW = source.width.toFloat().coerceAtLeast(1f)
        val srcH = source.height.toFloat().coerceAtLeast(1f)

        val matrix = Matrix()
        when (fitMode) {
            FitMode.STRETCH -> {
                matrix.postScale(STICKER_SIZE / srcW, STICKER_SIZE / srcH)
            }
            FitMode.CONTAIN -> {
                val scale = min(STICKER_SIZE / srcW, STICKER_SIZE / srcH)
                val dx = (STICKER_SIZE - srcW * scale) / 2f
                val dy = (STICKER_SIZE - srcH * scale) / 2f
                matrix.postScale(scale, scale)
                matrix.postTranslate(dx, dy)
            }
            FitMode.CROP, FitMode.CIRCLE -> {
                val scale = max(STICKER_SIZE / srcW, STICKER_SIZE / srcH)
                val dx = (STICKER_SIZE - srcW * scale) / 2f
                val dy = (STICKER_SIZE - srcH * scale) / 2f
                matrix.postScale(scale, scale)
                matrix.postTranslate(dx, dy)
            }
        }

        val needsAlpha = when (fitMode) {
            FitMode.CIRCLE -> true
            FitMode.CONTAIN -> true
            FitMode.CROP, FitMode.STRETCH -> !isOpaqueVideo && source.hasAlpha()
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (fitMode == FitMode.CIRCLE) {
            canvas.drawColor(Color.TRANSPARENT)
            val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            val radius = STICKER_SIZE / 2f
            canvas.drawCircle(radius, radius, radius, paint)
            paint.shader = null
        } else {
            if (needsAlpha) {
                canvas.drawColor(Color.TRANSPARENT)
            } else {
                canvas.drawColor(Color.BLACK)
            }
            canvas.drawBitmap(source, matrix, paint)
        }

        if (caption.isNotBlank()) {
            drawMemeCaption(canvas, caption.trim(), captionColor)
        }

        target.setHasAlpha(needsAlpha)
        return target
    }

    /**
     * Draws a meme-style caption with thick black/white outline at the bottom of the 512x512 canvas
     * (same visual style as kuebot's `captionOverlay`).
     */
    private fun drawMemeCaption(canvas: Canvas, text: String, fillColor: Int) {
        val maxWidth = STICKER_SIZE - 32f
        val maxLines = 3
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        var chosenSize = 56f
        var chosenLines: List<String> = listOf(text)
        var size = 60f
        while (size >= 26f) {
            basePaint.textSize = size
            val wrapped = wrapTextToWidth(text, basePaint, maxWidth)
            if (wrapped.size <= maxLines) {
                chosenSize = size
                chosenLines = wrapped
                break
            }
            size -= 4f
            if (size < 26f) {
                chosenSize = 26f
                chosenLines = wrapped.take(maxLines)
            }
        }

        basePaint.textSize = chosenSize
        val lineHeight = chosenSize * 1.14f
        val bottomY = STICKER_SIZE - 20f
        val strokeColor = if (fillColor == Color.parseColor("#111111") || fillColor == Color.BLACK) {
            Color.WHITE
        } else {
            Color.BLACK
        }

        val strokePaint = Paint(basePaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(5f, chosenSize * 0.18f)
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = strokeColor
        }
        val fillPaint = Paint(basePaint).apply {
            style = Paint.Style.FILL
            color = fillColor
        }

        val centerX = STICKER_SIZE / 2f
        for (i in chosenLines.indices) {
            val line = chosenLines[i]
            val y = bottomY - (chosenLines.size - 1 - i) * lineHeight
            canvas.drawText(line, centerX, y, strokePaint)
            canvas.drawText(line, centerX, y, fillPaint)
        }
    }

    private fun wrapTextToWidth(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotEmpty()) {
                    lines.add(current)
                }
                current = word
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }

    private fun compressSingleWebp(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(32_768)
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, quality, out)
        return out.toByteArray()
    }

    private class FrameBitstream(val bytes: ByteArray, val hasAlpha: Boolean)

    /**
     * Extracts the raw `ALPH` + `VP8 ` / `VP8L` chunk stream from a single-frame WebP file.
     * Carefully handles odd-sized chunks at EOF where `libwebp` omits the trailing pad byte.
     */
    private fun extractFrameBitstream(singleWebp: ByteArray): FrameBitstream? {
        if (singleWebp.size < 20) return null
        val out = ByteArrayOutputStream(singleWebp.size + 4)
        var hasAlpha = false
        var hasImage = false
        var offset = 12
        while (offset + 8 <= singleWebp.size) {
            val fourCc = String(singleWebp, offset, 4, Charsets.US_ASCII)
            val chunkSize = readUInt32LE(singleWebp, offset + 4)
            if (chunkSize < 0) break
            val payloadEnd = offset + 8 + chunkSize
            if (payloadEnd > singleWebp.size) break

            if (fourCc == "ALPH") {
                hasAlpha = true
            } else if (fourCc == "VP8L" && chunkSize >= 5) {
                val flagsByte = singleWebp[offset + 8 + 4].toInt() and 0xFF
                if ((flagsByte and 0x10) != 0) hasAlpha = true
            }
            if (fourCc == "VP8 " || fourCc == "VP8L") hasImage = true
            if (fourCc == "ALPH" || fourCc == "VP8 " || fourCc == "VP8L") {
                out.write(singleWebp, offset, 8 + chunkSize)
                if ((chunkSize and 1) == 1) {
                    out.write(0)
                }
            }
            offset = payloadEnd + (chunkSize and 1)
        }
        if (!hasImage || out.size() == 0) return null
        return FrameBitstream(out.toByteArray(), hasAlpha)
    }

    private fun muxAnimatedWebp(
        frames: List<FrameBitstream>,
        totalDurationMs: Long,
        hasAlpha: Boolean,
        packName: String,
        author: String,
        soundSha256Base64: String?
    ): ByteArray {
        val body = ByteArrayOutputStream(frames.sumOf { it.bytes.size + 32 } + 512)
        body.write("WEBP".toByteArray(Charsets.US_ASCII))

        // VP8X: Animation (0x02) | EXIF (0x08) | optional Alpha (0x10)
        val vp8xPayload = ByteArray(10)
        vp8xPayload[0] = (0x02 or 0x08 or (if (hasAlpha) 0x10 else 0)).toByte()
        writeUInt24LE(vp8xPayload, 4, STICKER_SIZE - 1)
        writeUInt24LE(vp8xPayload, 7, STICKER_SIZE - 1)
        writeChunk(body, "VP8X", vp8xPayload)

        // ANIM: BGRA background color (0x00000000 as in libwebp WebPAnimEncoder) + infinite loop (0)
        val animPayload = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        writeChunk(body, "ANIM", animPayload)

        // ANMF chunks: distribute totalDurationMs across frames so total duration never drifts
        val count = frames.size
        for (idx in frames.indices) {
            val frame = frames[idx]
            val startT = (idx.toLong() * totalDurationMs) / count
            val endT = ((idx + 1).toLong() * totalDurationMs) / count
            val frameDelayMs = (endT - startT).toInt().coerceIn(20, 1000)

            val anmfHeader = ByteArray(16)
            // X=0 (0..2), Y=0 (3..5)
            writeUInt24LE(anmfHeader, 6, STICKER_SIZE - 1)
            writeUInt24LE(anmfHeader, 9, STICKER_SIZE - 1)
            writeUInt24LE(anmfHeader, 12, frameDelayMs)
            // Bit 1 (0x02) = NO_BLEND; Bit 0 (0x01) = DISPOSE_BACKGROUND when transparent, 0 when opaque
            anmfHeader[15] = if (frame.hasAlpha || hasAlpha) 0x03 else 0x02

            val anmfPayload = ByteArray(16 + frame.bytes.size)
            System.arraycopy(anmfHeader, 0, anmfPayload, 0, 16)
            System.arraycopy(frame.bytes, 0, anmfPayload, 16, frame.bytes.size)
            writeChunk(body, "ANMF", anmfPayload)
        }

        // EXIF metadata chunk
        writeChunk(body, "EXIF", buildExifPayload(packName, author, soundSha256Base64))

        return wrapRiff(body.toByteArray())
    }

    @JvmStatic
    @JvmOverloads
    fun injectWhatsAppExif(
        webp: ByteArray,
        packName: String,
        author: String,
        soundSha256Base64: String? = null
    ): ByteArray {
        if (webp.size < 20) return webp
        val chunks = ArrayList<ByteArray>()
        var hasVp8x = false
        var hasAlpha = false
        var offset = 12
        while (offset + 8 <= webp.size) {
            val type = String(webp, offset, 4, Charsets.US_ASCII)
            val size = readUInt32LE(webp, offset + 4)
            if (size < 0) break
            val payloadEnd = offset + 8 + size
            if (payloadEnd > webp.size) break
            if (type == "ALPH") hasAlpha = true
            if (type == "VP8L" && size >= 5) {
                val flagsByte = webp[offset + 8 + 4].toInt() and 0xFF
                if ((flagsByte and 0x10) != 0) hasAlpha = true
            }
            if (type == "VP8X") hasVp8x = true
            if (type != "EXIF") {
                val paddedLen = 8 + size + (size and 1)
                val chunkBytes = ByteArray(paddedLen)
                System.arraycopy(webp, offset, chunkBytes, 0, 8 + size)
                chunks.add(chunkBytes)
            }
            offset = payloadEnd + (size and 1)
        }

        if (chunks.isEmpty()) return webp

        val body = ByteArrayOutputStream(webp.size + 512)
        body.write("WEBP".toByteArray(Charsets.US_ASCII))

        if (!hasVp8x) {
            val vp8x = ByteArray(10)
            vp8x[0] = (0x08 or (if (hasAlpha) 0x10 else 0)).toByte()
            writeUInt24LE(vp8x, 4, STICKER_SIZE - 1)
            writeUInt24LE(vp8x, 7, STICKER_SIZE - 1)
            writeChunk(body, "VP8X", vp8x)
        } else {
            chunks[0][8] = (chunks[0][8].toInt() or 0x08).toByte()
        }

        for (c in chunks) body.write(c)
        writeChunk(body, "EXIF", buildExifPayload(packName, author, soundSha256Base64))

        return wrapRiff(body.toByteArray())
    }

    private fun buildExifPayload(
        packName: String,
        author: String,
        soundSha256Base64: String? = null
    ): ByteArray {
        val emoji = if (!soundSha256Base64.isNullOrBlank()) "🔊" else "✨"
        val json = JSONObject().apply {
            put("sticker-pack-id", UUID.randomUUID().toString())
            put("sticker-pack-name", packName.ifBlank { "WA-NI-ULTRA" })
            put("sticker-pack-publisher", author.ifBlank { "@NickC137" })
            put("emojis", JSONArray().put(emoji))
            if (!soundSha256Base64.isNullOrBlank()) {
                put("sound-sha256", soundSha256Base64)
            }
        }.toString().toByteArray(Charsets.UTF_8)

        val tiff = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x41, 0x57, 0x07, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x16, 0x00, 0x00, 0x00
        )
        ByteBuffer.wrap(tiff, 14, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(json.size)

        val out = ByteArray(tiff.size + json.size)
        System.arraycopy(tiff, 0, out, 0, tiff.size)
        System.arraycopy(json, 0, out, tiff.size, json.size)
        return out
    }

    private fun writeChunk(out: ByteArrayOutputStream, fourCc: String, payload: ByteArray) {
        out.write(fourCc.toByteArray(Charsets.US_ASCII))
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array()
        out.write(sizeBytes)
        out.write(payload)
        if ((payload.size and 1) == 1) out.write(0)
    }

    private fun wrapRiff(webpBody: ByteArray): ByteArray {
        val out = ByteArray(8 + webpBody.size)
        System.arraycopy("RIFF".toByteArray(Charsets.US_ASCII), 0, out, 0, 4)
        ByteBuffer.wrap(out, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(webpBody.size)
        System.arraycopy(webpBody, 0, out, 8, webpBody.size)
        return out
    }

    private fun readUInt32LE(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun writeUInt24LE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }
}
