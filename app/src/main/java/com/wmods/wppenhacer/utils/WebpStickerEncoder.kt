package com.wmods.wppenhacer.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
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
    private const val MAX_ANIMATED_BYTES = 495_000
    private const val MAX_DURATION_MS = 10_000L

    data class EncodedSticker(
        val webpBytes: ByteArray,
        val isAnimated: Boolean,
        val durationMs: Long,
        val frameCount: Int
    )

    private data class AnimTier(val fps: Int, val quality: Int)

    private val ANIM_TIERS = listOf(
        AnimTier(10, 55),
        AnimTier(8, 42),
        AnimTier(6, 30),
        AnimTier(5, 20)
    )

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
            for (quality in intArrayOf(85, 70, 55, 40, 25)) {
                val singleWebp = compressSingleWebp(framed, quality)
                val withExif = injectWhatsAppExif(singleWebp, packName, author)
                if (withExif.size <= MAX_STATIC_BYTES || quality == 25) {
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
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val rawDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 3_000L
            val clipDurationMs = min(MAX_DURATION_MS, max(400L, rawDurationMs))
            onLog?.invoke(
                "Vídeo detectado: ${(rawDurationMs / 1000f)}s (usando ${(clipDurationMs / 1000f)}s)"
            )

            val startTier = if (clipDurationMs > 5_000L) 1 else 0
            for (tierIdx in startTier until ANIM_TIERS.size) {
                val tier = ANIM_TIERS[tierIdx]
                val frameIntervalMs = (1000.0 / tier.fps).roundToInt().coerceAtLeast(80)
                val totalFrames = (clipDurationMs / frameIntervalMs).toInt().coerceIn(2, 80)
                onLog?.invoke("Codificando WebP animado: $totalFrames quadros @ ${tier.fps} fps (q=${tier.quality})...")

                val framePayloads = ArrayList<FrameBitstream>(totalFrames)
                var anyAlpha = fullFrame

                for (f in 0 until totalFrames) {
                    val timeUs = (f.toLong() * frameIntervalMs * 1000L)
                    val rawFrame = retriever.getFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    ) ?: retriever.getFrameAtTime(timeUs) ?: continue

                    val framed = frameBitmap512(rawFrame, fullFrame)
                    if (framed !== rawFrame) rawFrame.recycle()

                    val singleWebp = compressSingleWebp(framed, tier.quality)
                    framed.recycle()

                    val extracted = extractFrameBitstream(singleWebp)
                    if (extracted.hasAlpha) anyAlpha = true
                    framePayloads.add(extracted)
                }

                if (framePayloads.size < 2) {
                    throw IllegalStateException("Não foi possível extrair quadros suficientes do vídeo.")
                }

                val muxed = muxAnimatedWebp(
                    frames = framePayloads,
                    frameDurationMs = frameIntervalMs,
                    hasAlpha = anyAlpha,
                    packName = packName,
                    author = author
                )

                if (muxed.size <= MAX_ANIMATED_BYTES || tierIdx == ANIM_TIERS.lastIndex) {
                    onLog?.invoke(
                        "Figurinha animada pronta: ${muxed.size / 1024} KB (${framePayloads.size} quadros)"
                    )
                    return EncodedSticker(
                        webpBytes = muxed,
                        isAnimated = true,
                        durationMs = framePayloads.size.toLong() * frameIntervalMs,
                        frameCount = framePayloads.size
                    )
                } else {
                    onLog?.invoke("Ficou com ${muxed.size / 1024} KB (> 500 KB), otimizando próximo nível...")
                }
            }
            throw IllegalStateException("Não foi possível deixar a figurinha animada abaixo de 500 KB.")
        } finally {
            runCatching { retriever.release() }
        }
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
        canvas.drawColor(Color.TRANSPARENT)

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

    private fun extractFrameBitstream(singleWebp: ByteArray): FrameBitstream {
        val out = ByteArrayOutputStream(singleWebp.size)
        var hasAlpha = false
        var offset = 12
        while (offset + 8 <= singleWebp.size) {
            val fourCc = String(singleWebp, offset, 4, Charsets.US_ASCII)
            val chunkSize = readUInt32LE(singleWebp, offset + 4)
            val paddedSize = chunkSize + (chunkSize and 1)
            val next = offset + 8 + paddedSize
            if (next > singleWebp.size) break

            if (fourCc == "ALPH" || fourCc == "VP8L") hasAlpha = true
            if (fourCc == "ALPH" || fourCc == "VP8 " || fourCc == "VP8L") {
                out.write(singleWebp, offset, 8 + paddedSize)
            }
            offset = next
        }
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

        // ANIM: transparent background (0,0,0,0) + infinite loop (0)
        val animPayload = ByteArray(6)
        writeChunk(body, "ANIM", animPayload)

        // ANMF chunks
        for (frame in frames) {
            val anmfHeader = ByteArray(16)
            // X=0 (0..2), Y=0 (3..5)
            writeUInt24LE(anmfHeader, 6, STICKER_SIZE - 1)
            writeUInt24LE(anmfHeader, 9, STICKER_SIZE - 1)
            writeUInt24LE(anmfHeader, 12, frameDurationMs)
            // Bit 1 = do not blend (overwrite), Bit 0 = dispose to background transparent
            anmfHeader[15] = 0x03

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
            val end = offset + 8 + size + (size and 1)
            if (end > webp.size) break
            if (type == "ALPH" || type == "VP8L") hasAlpha = true
            if (type == "VP8X") hasVp8x = true
            if (type != "EXIF") {
                chunks.add(webp.copyOfRange(offset, end))
            }
            offset = end
        }

        val body = ByteArrayOutputStream(webp.size + 512)
        body.write("WEBP".toByteArray(Charsets.US_ASCII))

        if (!hasVp8x) {
            val vp8x = ByteArray(10)
            vp8x[0] = (0x08 or (if (hasAlpha) 0x10 else 0)).toByte()
            writeUInt24LE(vp8x, 4, STICKER_SIZE - 1)
            writeUInt24LE(vp8x, 7, STICKER_SIZE - 1)
            writeChunk(body, "VP8X", vp8x)
        } else if (chunks.isNotEmpty()) {
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
