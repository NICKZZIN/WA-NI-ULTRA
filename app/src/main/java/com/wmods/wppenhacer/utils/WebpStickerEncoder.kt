package com.wmods.wppenhacer.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Movie
import android.graphics.Paint
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin + Android SDK WebP static & animated sticker encoder (512x512)
 * with WhatsApp EXIF (0x5741) pack/author metadata injection.
 * Requires zero external FFmpeg binaries and consumes zero idle battery.
 */
object WebpStickerEncoder {

    const val STICKER_SIZE = 512
    private const val MAX_STATIC_BYTES = 98_000
    private const val MAX_ANIMATED_BYTES = 490_000
    private const val MAX_DURATION_MS = 8_000L
    private const val MAX_EXTRACTED_FRAMES = 32

    data class EncodedSticker(
        val webpBytes: ByteArray,
        val isAnimated: Boolean,
        val durationMs: Long,
        val frameCount: Int
    )

    private data class ExtractedFrames(
        val bitmaps: List<Bitmap>,
        val frameIntervalMs: Int
    )

    private val QUALITY_STEPS = intArrayOf(72, 56, 42, 30, 20, 12)

    @JvmStatic
    fun createStaticSticker(
        imageFile: File,
        fullFrame: Boolean,
        packName: String,
        author: String,
        onLog: ((String) -> Unit)? = null
    ): EncodedSticker {
        onLog?.invoke("Lendo imagem: ${imageFile.name} (${imageFile.length() / 1024} KB)")
        val sourceBitmap = decodeOrientedBitmap(imageFile)
            ?: throw IllegalArgumentException("Não foi possível decodificar a imagem.")

        val framed = frameBitmap512(sourceBitmap, fullFrame)
        if (framed !== sourceBitmap) sourceBitmap.recycle()

        try {
            for (quality in intArrayOf(85, 72, 58, 42, 28)) {
                val singleWebp = compressSingleWebp(framed, quality)
                val withExif = injectWhatsAppExif(singleWebp, packName, author)
                if (withExif.size <= MAX_STATIC_BYTES || quality == 28) {
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
            framed.recycle()
        }
    }

    @JvmStatic
    fun createAnimatedSticker(
        videoFile: File,
        fullFrame: Boolean,
        packName: String,
        author: String,
        onLog: ((String) -> Unit)? = null
    ): EncodedSticker {
        val extracted = extractAnimatedFrames(videoFile, fullFrame, onLog)
        val bitmaps = extracted.bitmaps
        if (bitmaps.isEmpty()) {
            throw IllegalStateException("Não foi possível extrair quadros do vídeo/GIF.")
        }
        // If only 1 frame could be decoded, encode as static sticker
        if (bitmaps.size == 1) {
            try {
                val single = compressSingleWebp(bitmaps[0], 75)
                val withExif = injectWhatsAppExif(single, packName, author)
                return EncodedSticker(withExif, false, 0L, 1)
            } finally {
                bitmaps.forEach { runCatching { it.recycle() } }
            }
        }

        try {
            // Try encoding all frames across decreasing quality levels; if still > 490 KB, subsample frames by 2x
            for (step in 0..1) {
                val activeFrames = if (step == 0 || bitmaps.size <= 6) {
                    bitmaps
                } else {
                    bitmaps.filterIndexed { index, _ -> index % 2 == 0 }
                }
                val intervalMs = if (step == 0 || bitmaps.size <= 6) {
                    extracted.frameIntervalMs
                } else {
                    (extracted.frameIntervalMs * 2).coerceAtMost(400)
                }

                for (qIdx in QUALITY_STEPS.indices) {
                    val quality = QUALITY_STEPS[qIdx]
                    val framePayloads = ArrayList<FrameBitstream>(activeFrames.size)
                    var anyAlpha = fullFrame

                    for (bmp in activeFrames) {
                        val singleWebp = compressSingleWebp(bmp, quality)
                        val bitstream = extractFrameBitstream(singleWebp) ?: continue
                        if (bitstream.hasAlpha) anyAlpha = true
                        framePayloads.add(bitstream)
                    }

                    if (framePayloads.size < 2) {
                        throw IllegalStateException("Falha ao codificar quadros WebP do vídeo/GIF.")
                    }

                    val muxed = muxAnimatedWebp(
                        frames = framePayloads,
                        frameDurationMs = intervalMs,
                        hasAlpha = anyAlpha,
                        packName = packName,
                        author = author
                    )

                    val isLastAttempt = (step == 1 || bitmaps.size <= 6) && qIdx == QUALITY_STEPS.lastIndex
                    if (muxed.size <= MAX_ANIMATED_BYTES || isLastAttempt) {
                        onLog?.invoke(
                            "Figurinha animada pronta: ${muxed.size / 1024} KB (${framePayloads.size} quadros, q=$quality)"
                        )
                        return EncodedSticker(
                            webpBytes = muxed,
                            isAnimated = true,
                            durationMs = framePayloads.size.toLong() * intervalMs,
                            frameCount = framePayloads.size
                        )
                    }
                }
            }
            throw IllegalStateException("Não foi possível comprimir a figurinha animada abaixo de 500 KB.")
        } finally {
            bitmaps.forEach { runCatching { it.recycle() } }
        }
    }

    private fun extractAnimatedFrames(
        file: File,
        fullFrame: Boolean,
        onLog: ((String) -> Unit)?
    ): ExtractedFrames {
        if (isGifFileHeader(file)) {
            extractGifFrames(file, fullFrame, onLog)?.let { return it }
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val rawDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 3_000L
            val clipDurationMs = min(MAX_DURATION_MS, max(400L, rawDurationMs))
            val targetFps = when {
                clipDurationMs <= 2_500L -> 10
                clipDurationMs <= 5_000L -> 8
                else -> 6
            }
            val frameIntervalMs = (1000.0 / targetFps).roundToInt().coerceIn(90, 220)
            val totalFrames = (clipDurationMs / frameIntervalMs).toInt().coerceIn(2, MAX_EXTRACTED_FRAMES)

            onLog?.invoke(
                "Extraindo $totalFrames quadros (${(clipDurationMs / 1000f)}s @ $targetFps fps)..."
            )

            val bitmaps = ArrayList<Bitmap>(totalFrames)
            for (f in 0 until totalFrames) {
                val timeUs = f.toLong() * frameIntervalMs * 1000L
                val rawFrame = runCatching {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull() ?: continue

                val framed = frameBitmap512(rawFrame, fullFrame)
                if (framed !== rawFrame) rawFrame.recycle()
                bitmaps.add(framed)
            }

            if (bitmaps.isNotEmpty()) {
                return ExtractedFrames(bitmaps, frameIntervalMs)
            }
        } catch (t: Throwable) {
            onLog?.invoke("Aviso MediaMetadataRetriever (${t.message}), tentando decodificador GIF...")
        } finally {
            runCatching { retriever.release() }
        }

        extractGifFrames(file, fullFrame, onLog)?.let { return it }
        decodeOrientedBitmap(file)?.let { bmp ->
            val framed = frameBitmap512(bmp, fullFrame)
            if (framed !== bmp) bmp.recycle()
            return ExtractedFrames(listOf(framed), 100)
        }
        throw IllegalStateException("Formato de vídeo ou GIF não suportado.")
    }

    @Suppress("DEPRECATION")
    private fun extractGifFrames(
        file: File,
        fullFrame: Boolean,
        onLog: ((String) -> Unit)?
    ): ExtractedFrames? {
        return runCatching {
            val movie = FileInputStream(file).use { Movie.decodeStream(it) } ?: return null
            if (movie.width() <= 0 || movie.height() <= 0) return null
            val rawDuration = movie.duration().takeIf { it > 0 } ?: 2_000
            val clipDuration = min(MAX_DURATION_MS.toInt(), max(400, rawDuration))
            val intervalMs = 100
            val totalFrames = (clipDuration / intervalMs).coerceIn(2, MAX_EXTRACTED_FRAMES)
            onLog?.invoke("Decodificando GIF: $totalFrames quadros (${clipDuration / 1000f}s)...")

            val bitmaps = ArrayList<Bitmap>(totalFrames)
            for (i in 0 until totalFrames) {
                val rawBmp = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(rawBmp)
                canvas.drawColor(Color.TRANSPARENT)
                movie.setTime((i * intervalMs) % max(1, rawDuration))
                movie.draw(canvas, 0f, 0f)
                val framed = frameBitmap512(rawBmp, fullFrame)
                if (framed !== rawBmp) rawBmp.recycle()
                bitmaps.add(framed)
            }
            if (bitmaps.isEmpty()) null else ExtractedFrames(bitmaps, intervalMs)
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

    private fun frameBitmap512(source: Bitmap, fullFrame: Boolean): Bitmap {
        val target = Bitmap.createBitmap(STICKER_SIZE, STICKER_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        val sourceHasAlpha = source.hasAlpha()
        val keepAlpha = fullFrame || sourceHasAlpha
        if (keepAlpha) {
            canvas.drawColor(Color.TRANSPARENT)
        } else {
            canvas.drawColor(Color.BLACK)
        }

        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()
        val scale = if (fullFrame) {
            min(STICKER_SIZE / srcW, STICKER_SIZE / srcH)
        } else {
            max(STICKER_SIZE / srcW, STICKER_SIZE / srcH)
        }

        val scaledW = srcW * scale
        val scaledH = srcH * scale
        val dx = (STICKER_SIZE - scaledW) / 2f
        val dy = (STICKER_SIZE - scaledH) / 2f

        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)
        target.setHasAlpha(keepAlpha)
        return target
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

            if (fourCc == "ALPH" || fourCc == "VP8L") hasAlpha = true
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
        frameDurationMs: Int,
        hasAlpha: Boolean,
        packName: String,
        author: String
    ): ByteArray {
        val body = ByteArrayOutputStream(frames.sumOf { it.bytes.size + 32 } + 512)
        body.write("WEBP".toByteArray(Charsets.US_ASCII))

        // VP8X: Animation (0x02) | EXIF (0x08) | optional Alpha (0x10)
        val vp8xPayload = ByteArray(10)
        vp8xPayload[0] = (0x02 or 0x08 or (if (hasAlpha) 0x10 else 0)).toByte()
        writeUInt24LE(vp8xPayload, 4, STICKER_SIZE - 1)
        writeUInt24LE(vp8xPayload, 7, STICKER_SIZE - 1)
        writeChunk(body, "VP8X", vp8xPayload)

        // ANIM: BGRA background color + infinite loop (0)
        val animPayload = if (hasAlpha) {
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        } else {
            byteArrayOf(
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0x00,
                0x00
            )
        }
        writeChunk(body, "ANIM", animPayload)

        // ANMF chunks
        for (frame in frames) {
            val anmfHeader = ByteArray(16)
            // X=0 (0..2), Y=0 (3..5)
            writeUInt24LE(anmfHeader, 6, STICKER_SIZE - 1)
            writeUInt24LE(anmfHeader, 9, STICKER_SIZE - 1)
            writeUInt24LE(anmfHeader, 12, frameDurationMs)
            // Bit 1 (0x02) = NO_BLEND; Bit 0 (0x01) = DISPOSE_BACKGROUND when transparent, 0 when opaque
            anmfHeader[15] = if (frame.hasAlpha || hasAlpha) 0x03 else 0x02

            val anmfPayload = ByteArray(16 + frame.bytes.size)
            System.arraycopy(anmfHeader, 0, anmfPayload, 0, 16)
            System.arraycopy(frame.bytes, 0, anmfPayload, 16, frame.bytes.size)
            writeChunk(body, "ANMF", anmfPayload)
        }

        // EXIF metadata chunk
        writeChunk(body, "EXIF", buildExifPayload(packName, author))

        return wrapRiff(body.toByteArray())
    }

    @JvmStatic
    fun injectWhatsAppExif(webp: ByteArray, packName: String, author: String): ByteArray {
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
            if (type == "ALPH" || type == "VP8L") hasAlpha = true
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
        writeChunk(body, "EXIF", buildExifPayload(packName, author))

        return wrapRiff(body.toByteArray())
    }

    private fun buildExifPayload(packName: String, author: String): ByteArray {
        val json = JSONObject().apply {
            put("sticker-pack-id", UUID.randomUUID().toString())
            put("sticker-pack-name", packName.ifBlank { "WA-NI-ULTRA" })
            put("sticker-pack-publisher", author.ifBlank { "@NickC137" })
            put("emojis", JSONArray().put("✨"))
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
