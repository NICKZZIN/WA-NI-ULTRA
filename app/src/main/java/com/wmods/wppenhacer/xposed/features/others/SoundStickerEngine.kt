package com.wmods.wppenhacer.xposed.features.others

import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.wmods.wppenhacer.utils.StickerBotConsole
import com.wmods.wppenhacer.utils.WebpStickerEncoder
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener
import com.wmods.wppenhacer.xposed.utils.AudioOpusConverter
import com.wmods.wppenhacer.xposed.utils.HKDF
import com.wmods.wppenhacer.xposed.utils.Utils
import com.wmods.wppenhacer.xposed.utils.setTouchClickAndLongClickListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Native WhatsApp Sound Sticker Engine (134 -> 1 -> 26 -> 26 protobuf wrapper + local tap player).
 *
 * Ported directly from `whatsapp-figurinhas-com-som/src/sound-sticker.ts` and `kuebot/src/soundsticker.js`:
 * 1. Hooks `AbstractMessageLite.toByteArray()` (with zero overhead when idle) to:
 *    - Capture uploaded `AudioMessage` metadata when uploading the `.opus` sound track via WhatsApp MMS
 *    - Wrap outgoing `StickerMessage` + `AudioMessage` (with `streamingSidecar`) into
 *      `Message.134 (FutureProofMessage) -> 1 (Message) -> 26 (StickerMessage) -> 26 (AudioMessage)`
 *      both for direct recipient messages and `31 (DeviceSentMessage)` companion sync.
 * 2. Stores local `.opus` sound tracks in `files/Stickers/sounds/` indexed by `sound-sha256` in the
 *    WebP EXIF and attaches a `🔊` badge + tap-to-play `MediaPlayer` inside WhatsApp conversations
 *    so sound stickers also play on tap on the sender's own phone (including self-chat `wa.me`).
 */
object SoundStickerEngine {

    private const val FIELD_MESSAGE_AUDIO = 8
    private const val FIELD_MESSAGE_STICKER = 26
    private const val FIELD_DEVICE_SENT_MESSAGE = 31
    private const val FIELD_SOUND_WRAPPER = 134
    private const val FIELD_FUTURE_PROOF_INNER = 1
    private const val FIELD_DEVICE_SENT_INNER = 2

    private const val FIELD_STICKER_SHA256 = 2
    private const val FIELD_STICKER_IS_ANIMATED = 13
    private const val FIELD_STICKER_AUDIO = 26

    private const val FIELD_AUDIO_URL = 1
    private const val FIELD_AUDIO_MIMETYPE = 2
    private const val FIELD_AUDIO_FILE_SHA256 = 3
    private const val FIELD_AUDIO_FILE_LENGTH = 4
    private const val FIELD_AUDIO_SECONDS = 5
    private const val FIELD_AUDIO_PTT = 6
    private const val FIELD_AUDIO_MEDIA_KEY = 7
    private const val FIELD_AUDIO_FILE_ENC_SHA256 = 8
    private const val FIELD_AUDIO_DIRECT_PATH = 9
    private const val FIELD_AUDIO_MEDIA_KEY_TS = 10
    private const val FIELD_AUDIO_STREAMING_SIDECAR = 18

    private const val SIDECAR_CHUNK_BYTES = 64 * 1024
    private const val BADGE_TAG = "wae_sound_sticker_badge"
    private const val HOOKED_SOUND_PATH_TAG = "wae_sound_sticker_opus_path"

    private class AudioCaptureSession(
        val expectedPlainSha256: ByteArray,
        val opusBytes: ByteArray,
        val seconds: Int,
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var capturedAudioProto: ByteArray? = null
    )

    private class StickerWrapSession(
        val expectedStickerSha256: ByteArray,
        val audioMessageProto: ByteArray,
        val isAnimated: Boolean,
        val wrappedCount: AtomicInteger = AtomicInteger(0)
    )

    @Volatile
    private var protoHookInstalled = false

    @Volatile
    private var activeAudioCapture: AudioCaptureSession? = null

    @Volatile
    private var activeStickerWrap: StickerWrapSession? = null

    // Cache: absolutePath|lastModified -> soundSha256Base64 ("" if no sound)
    private val exifSoundCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var activePlayer: MediaPlayer? = null

    @Volatile
    private var activePlayingPath: String? = null

    fun installHooks(feature: Feature, classLoader: ClassLoader) {
        val toByteArrayMethod = Unobfuscator.loadProtobufToByteArrayMethod(classLoader)
        if (toByteArrayMethod != null) {
            runCatching {
                toByteArrayMethod.isAccessible = true
                with(feature) {
                    toByteArrayMethod.hook {
                        after {
                            val audioCap = activeAudioCapture
                            val stickerWrap = activeStickerWrap
                            if (audioCap == null && stickerWrap == null) return@after
                            val rawBytes = result as? ByteArray ?: return@after
                            if (rawBytes.size < 16) return@after

                            if (audioCap != null && audioCap.capturedAudioProto == null) {
                                tryCaptureAudioFromProto(rawBytes, audioCap)
                            }
                            if (stickerWrap != null) {
                                val rewritten = tryRewriteStickerProto(rawBytes, stickerWrap)
                                if (rewritten != null) {
                                    result = rewritten
                                    stickerWrap.wrappedCount.incrementAndGet()
                                }
                            }
                        }
                    }
                }
                protoHookInstalled = true
            }
        }

        installLocalStickerSoundPlayer()
    }

    fun isProtoHookReady(): Boolean = protoHookInstalled

    /**
     * Saves the `.opus` sound track locally under `files/Stickers/sounds/<safeSha256>.opus`
     * so tapping the sticker in any chat on this device plays the audio immediately.
     */
    fun saveLocalStickerSound(soundSha256Base64: String, opusBytes: ByteArray): File? {
        if (soundSha256Base64.isBlank() || opusBytes.isEmpty()) return null
        return runCatching {
            val soundsDir = File(Utils.getAccountDataDir(), "files/Stickers/sounds").apply { mkdirs() }
            val safeName = soundSha256Base64.replace('/', '-').replace('+', '_').replace("=", "") + ".opus"
            File(soundsDir, safeName).apply { writeBytes(opusBytes) }
        }.getOrNull()
    }

    fun findLocalSoundFileForSha(soundSha256Base64: String): File? {
        if (soundSha256Base64.isBlank()) return null
        val soundsDir = File(Utils.getAccountDataDir(), "files/Stickers/sounds")
        val safeName = soundSha256Base64.replace('/', '-').replace('+', '_').replace("=", "") + ".opus"
        val f = File(soundsDir, safeName)
        return f.takeIf { it.exists() && it.length() > 0L }
    }

    /**
     * Uploads [opusFile] using WhatsApp's own media uploader (via [triggerAudioUpload]), captures
     * the MMS upload credentials (either from `toByteArray()` or from `message_media` in [db]),
     * builds the complete `AudioMessage` protobuf with `streamingSidecar`, and cleans up the
     * temporary upload row in [db].
     */
    fun uploadAndBuildAudioMessageProto(
        opusFile: File,
        opusBytes: ByteArray,
        db: SQLiteDatabase?,
        selfChatRowId: Long,
        triggerAudioUpload: () -> Boolean
    ): ByteArray? {
        val plainSha256 = sha256(opusBytes)
        val durationSec = max(1, ceil(AudioOpusConverter.readOggOpusSeconds(opusBytes)).toInt())
        val maxIdBefore = queryMaxId(db, selfChatRowId)

        val captureSession = AudioCaptureSession(
            expectedPlainSha256 = plainSha256,
            opusBytes = opusBytes,
            seconds = durationSec
        )
        activeAudioCapture = captureSession
        var tempRowId = -1L

        try {
            val triggered = triggerAudioUpload()
            if (!triggered) return null

            // Poll up to 10s for either protobuf capture or message_media upload completion in SQLite
            for (step in 1..25) {
                captureSession.capturedAudioProto?.let { proto ->
                    if (tempRowId <= 0L) {
                        tempRowId = findTempAudioRowId(db, selfChatRowId, maxIdBefore, opusBytes.size)
                    }
                    return proto
                }
                if (captureSession.latch.await(400L, TimeUnit.MILLISECONDS)) {
                    captureSession.capturedAudioProto?.let { return it }
                }
                if (db != null && db.isOpen && selfChatRowId > 0L) {
                    if (tempRowId <= 0L) {
                        tempRowId = findTempAudioRowId(db, selfChatRowId, maxIdBefore, opusBytes.size)
                    }
                    if (tempRowId > 0L) {
                        val fromDb = tryBuildAudioProtoFromDb(db, tempRowId, opusBytes, plainSha256, durationSec)
                        if (fromDb != null) return fromDb
                    }
                }
            }
            return captureSession.capturedAudioProto
        } finally {
            activeAudioCapture = null
            if (tempRowId <= 0L && db != null && selfChatRowId > 0L) {
                tempRowId = findTempAudioRowId(db, selfChatRowId, maxIdBefore, opusBytes.size)
            }
            if (tempRowId > 0L && db != null && db.isOpen) {
                cleanupTempAudioRow(db, tempRowId)
            }
        }
    }

    /**
     * Activates `134 -> 1 -> 26 -> 26` protobuf wrapping for the sticker matching [webpBytes]
     * while [sendStickerAction] executes and uploads the sticker.
     * Returns `true` if at least one outgoing `E2E$Message` packet was wrapped as a Sound Sticker.
     */
    fun sendWrappedSoundSticker(
        webpBytes: ByteArray,
        audioMessageProto: ByteArray,
        isAnimated: Boolean,
        sendStickerAction: () -> Boolean
    ): Boolean {
        val stickerSha = sha256(webpBytes)
        val session = StickerWrapSession(
            expectedStickerSha256 = stickerSha,
            audioMessageProto = audioMessageProto,
            isAnimated = isAnimated
        )
        activeStickerWrap = session
        try {
            val dispatched = sendStickerAction()
            if (!dispatched) return false
            // Wait up to 8s for WhatsApp's async Sticker MediaUpload + E2E serialization to fire
            for (i in 1..20) {
                if (session.wrappedCount.get() > 0) {
                    // Brief extra window so companion DeviceSentMessage also gets wrapped
                    Thread.sleep(350L)
                    return true
                }
                Thread.sleep(400L)
            }
            return session.wrappedCount.get() > 0
        } finally {
            activeStickerWrap = null
        }
    }

    /**
     * Exact Kotlin port of `createStreamingSidecar` from `whatsapp-figurinhas-com-som/src/sound-sticker.ts`:
     * Derives `(iv, cipherKey, macKey)` via HKDFv3("WhatsApp Audio Keys"), encrypts [plainAudio] with
     * AES-256-CBC, optionally verifies `SHA256(encrypted + mac10) == expectedEncSha256`, and emits
     * 10-byte HMAC-SHA256 chunks for each 64 KiB ciphertext block chained with the preceding 16 bytes.
     */
    fun createStreamingSidecar(
        plainAudio: ByteArray,
        mediaKey: ByteArray,
        expectedEncSha256: ByteArray? = null
    ): ByteArray {
        val derived = HKDF.createFor(3).deriveSecrets(
            mediaKey,
            "WhatsApp Audio Keys".toByteArray(Charsets.UTF_8),
            112
        )
        val iv = derived.copyOfRange(0, 16)
        val cipherKey = derived.copyOfRange(16, 48)
        val macKey = derived.copyOfRange(48, 80)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plainAudio)

        if (expectedEncSha256 != null && expectedEncSha256.size == 32) {
            val fullMac = hmacSha256(macKey, iv, encrypted).copyOfRange(0, 10)
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(encrypted)
                update(fullMac)
            }.digest()
            if (!digest.contentEquals(expectedEncSha256)) {
                throw IllegalStateException("Hash do áudio cifrado diverge do upload MMS")
            }
        }

        val sidecar = ByteArrayOutputStream(((encrypted.size / SIDECAR_CHUNK_BYTES) + 2) * 10)
        var start = 0
        while (start < encrypted.size) {
            val previous = if (start == 0) iv else encrypted.copyOfRange(start - 16, start)
            val end = min(start + SIDECAR_CHUNK_BYTES, encrypted.size)
            val chunk = encrypted.copyOfRange(start, end)
            val tag10 = hmacSha256(macKey, previous, chunk).copyOfRange(0, 10)
            sidecar.write(tag10)
            start += SIDECAR_CHUNK_BYTES
        }
        return sidecar.toByteArray()
    }

    private fun tryCaptureAudioFromProto(rawMessageBytes: ByteArray, session: AudioCaptureSession) {
        val topFields = ProtoWire.parseFields(rawMessageBytes) ?: return
        val candidateAudioBytes = findAudioMessageMatchingSha(topFields, session.expectedPlainSha256) ?: return
        val normalized = normalizeCapturedAudioMessage(candidateAudioBytes, session.opusBytes, session.seconds)
        if (normalized != null) {
            session.capturedAudioProto = normalized
            session.latch.countDown()
        }
    }

    private fun findAudioMessageMatchingSha(
        fields: List<ProtoWire.ProtoField>,
        expectedSha256: ByteArray
    ): ByteArray? {
        for (f in fields) {
            if (f.wireType != 2) continue
            if (f.fieldNumber == FIELD_MESSAGE_AUDIO) {
                val sub = ProtoWire.parseFields(f.bytes) ?: continue
                val sha = sub.firstOrNull { it.fieldNumber == FIELD_AUDIO_FILE_SHA256 && it.wireType == 2 }?.bytes
                if (sha != null && sha.contentEquals(expectedSha256)) {
                    return f.bytes
                }
            } else if (f.fieldNumber == FIELD_DEVICE_SENT_MESSAGE) {
                val dsm = ProtoWire.parseFields(f.bytes) ?: continue
                val inner = dsm.firstOrNull { it.fieldNumber == FIELD_DEVICE_SENT_INNER && it.wireType == 2 }?.bytes ?: continue
                val innerFields = ProtoWire.parseFields(inner) ?: continue
                findAudioMessageMatchingSha(innerFields, expectedSha256)?.let { return it }
            }
        }
        return null
    }

    private fun normalizeCapturedAudioMessage(
        rawAudioProto: ByteArray,
        plainOpusBytes: ByteArray,
        seconds: Int
    ): ByteArray? {
        val fields = ProtoWire.parseFields(rawAudioProto) ?: return null
        val mediaKey = fields.firstOrNull { it.fieldNumber == FIELD_AUDIO_MEDIA_KEY && it.wireType == 2 }?.bytes ?: return null
        val encSha = fields.firstOrNull { it.fieldNumber == FIELD_AUDIO_FILE_ENC_SHA256 && it.wireType == 2 }?.bytes
        val hasSidecar = fields.any { it.fieldNumber == FIELD_AUDIO_STREAMING_SIDECAR && it.wireType == 2 && it.bytes.isNotEmpty() }

        val sidecar = if (hasSidecar) {
            null
        } else {
            runCatching { createStreamingSidecar(plainOpusBytes, mediaKey, encSha) }.getOrNull()
        }

        val out = ByteArrayOutputStream(rawAudioProto.size + 64)
        var wroteMime = false
        var wroteSeconds = false
        for (f in fields) {
            // Omit ptt (field 6) so StickerAudio has ptt=false
            if (f.fieldNumber == FIELD_AUDIO_PTT) continue
            if (f.fieldNumber == FIELD_AUDIO_MIMETYPE) {
                ProtoWire.writeLengthDelimited(out, FIELD_AUDIO_MIMETYPE, "audio/ogg; codecs=opus".toByteArray(Charsets.UTF_8))
                wroteMime = true
                continue
            }
            if (f.fieldNumber == FIELD_AUDIO_SECONDS) {
                ProtoWire.writeVarintField(out, FIELD_AUDIO_SECONDS, seconds.toLong())
                wroteSeconds = true
                continue
            }
            ProtoWire.writeRawField(out, f)
        }
        if (!wroteMime) {
            ProtoWire.writeLengthDelimited(out, FIELD_AUDIO_MIMETYPE, "audio/ogg; codecs=opus".toByteArray(Charsets.UTF_8))
        }
        if (!wroteSeconds) {
            ProtoWire.writeVarintField(out, FIELD_AUDIO_SECONDS, seconds.toLong())
        }
        if (sidecar != null && sidecar.isNotEmpty()) {
            ProtoWire.writeLengthDelimited(out, FIELD_AUDIO_STREAMING_SIDECAR, sidecar)
        }
        return out.toByteArray()
    }

    private fun tryBuildAudioProtoFromDb(
        db: SQLiteDatabase,
        rowId: Long,
        opusBytes: ByteArray,
        plainSha256: ByteArray,
        seconds: Int
    ): ByteArray? {
        return runCatching {
            db.rawQuery(
                "SELECT message_url, direct_path, media_key, file_hash, enc_file_hash, media_key_timestamp " +
                    "FROM message_media WHERE message_row_id = ? LIMIT 1",
                arrayOf(rowId.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val directPath = if (cursor.isNull(1)) null else cursor.getString(1)
                val mediaKey = if (cursor.isNull(2)) null else cursor.getBlob(2)
                if (directPath.isNullOrBlank() || mediaKey == null || mediaKey.size != 32) {
                    return@use null
                }
                val url = (if (cursor.isNull(0)) null else cursor.getString(0))
                    ?.takeIf { it.isNotBlank() }
                    ?: "https://mmg.whatsapp.net$directPath"
                val encHashStr = if (cursor.isNull(4)) null else cursor.getString(4)
                val encShaBytes = encHashStr?.takeIf { it.isNotBlank() }?.let {
                    runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
                }
                val mediaKeyTs = if (cursor.isNull(5)) (System.currentTimeMillis() / 1000L) else cursor.getLong(5)
                val sidecar = createStreamingSidecar(opusBytes, mediaKey, encShaBytes)
                val finalEncSha = encShaBytes ?: computeEncSha256(opusBytes, mediaKey)

                val out = ByteArrayOutputStream(256 + sidecar.size)
                ProtoWire.writeLengthDelimited(out, FIELD_AUDIO_URL, url.toByteArray(Charsets.UTF_8))
                ProtoWire.writeLengthDelimited(out, FIELD_AUDIO_MIMETYPE, "audio/ogg; codecs=opus".toByteArray(Charsets.UTF_8))
                ProtoWire.writeLengthDelimited(out, FIELD_AUDIO_FILE_SHA256, plainSha256)
                ProtoWire.writeVarintField(out, FIELD_AUDIO_FILE_LENGTH, opusBytes.size.toLong())
                ProtoWire.writeVarintField(out, FIELD_AUDIO_SECONDS, seconds.toLong())
                ProtoWire.writeLengthDelimited(out, FIELD_AUDIO_MEDIA_KEY, mediaKey)
                ProtoWire.writeLengthDelimited(out, FIELD_AUDIO_FILE_ENC_SHA256, finalEncSha)
                ProtoWire.writeLengthDelimited(out, FIELD_AUDIO_DIRECT_PATH, directPath.toByteArray(Charsets.UTF_8))
                if (mediaKeyTs > 0L) {
                    ProtoWire.writeVarintField(out, FIELD_AUDIO_MEDIA_KEY_TS, mediaKeyTs)
                }
                ProtoWire.writeLengthDelimited(out, FIELD_AUDIO_STREAMING_SIDECAR, sidecar)
                out.toByteArray()
            }
        }.getOrNull()
    }

    private fun computeEncSha256(plainAudio: ByteArray, mediaKey: ByteArray): ByteArray {
        val derived = HKDF.createFor(3).deriveSecrets(
            mediaKey,
            "WhatsApp Audio Keys".toByteArray(Charsets.UTF_8),
            112
        )
        val iv = derived.copyOfRange(0, 16)
        val cipherKey = derived.copyOfRange(16, 48)
        val macKey = derived.copyOfRange(48, 80)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plainAudio)
        val mac10 = hmacSha256(macKey, iv, encrypted).copyOfRange(0, 10)
        return MessageDigest.getInstance("SHA-256").apply {
            update(encrypted)
            update(mac10)
        }.digest()
    }

    private fun tryRewriteStickerProto(rawMessageBytes: ByteArray, session: StickerWrapSession): ByteArray? {
        val fields = ProtoWire.parseFields(rawMessageBytes) ?: return null

        // Case 1: Direct E2E$Message with field 26 (StickerMessage)
        val stickerField = fields.firstOrNull { it.fieldNumber == FIELD_MESSAGE_STICKER && it.wireType == 2 }
        if (stickerField != null && stickerMatchesSha(stickerField.bytes, session.expectedStickerSha256)) {
            val wrapper134 = buildSoundWrapper134(stickerField.bytes, session.audioMessageProto, session.isAnimated)
            val out = ByteArrayOutputStream(rawMessageBytes.size + session.audioMessageProto.size + 32)
            for (f in fields) {
                if (f.fieldNumber == FIELD_MESSAGE_STICKER) continue
                ProtoWire.writeRawField(out, f)
            }
            ProtoWire.writeLengthDelimited(out, FIELD_SOUND_WRAPPER, wrapper134)
            return out.toByteArray()
        }

        // Case 2: DeviceSentMessage (field 31 -> field 2: inner E2E$Message)
        val dsmField = fields.firstOrNull { it.fieldNumber == FIELD_DEVICE_SENT_MESSAGE && it.wireType == 2 }
        if (dsmField != null) {
            val dsmSub = ProtoWire.parseFields(dsmField.bytes) ?: return null
            val innerField = dsmSub.firstOrNull { it.fieldNumber == FIELD_DEVICE_SENT_INNER && it.wireType == 2 } ?: return null
            val rewrittenInner = tryRewriteStickerProto(innerField.bytes, session) ?: return null

            val newDsm = ByteArrayOutputStream(dsmField.bytes.size + session.audioMessageProto.size + 32)
            for (sub in dsmSub) {
                if (sub.fieldNumber == FIELD_DEVICE_SENT_INNER) {
                    ProtoWire.writeLengthDelimited(newDsm, FIELD_DEVICE_SENT_INNER, rewrittenInner)
                } else {
                    ProtoWire.writeRawField(newDsm, sub)
                }
            }

            val out = ByteArrayOutputStream(rawMessageBytes.size + session.audioMessageProto.size + 32)
            for (f in fields) {
                if (f.fieldNumber == FIELD_DEVICE_SENT_MESSAGE) {
                    ProtoWire.writeLengthDelimited(out, FIELD_DEVICE_SENT_MESSAGE, newDsm.toByteArray())
                } else {
                    ProtoWire.writeRawField(out, f)
                }
            }
            return out.toByteArray()
        }

        return null
    }

    private fun stickerMatchesSha(stickerBytes: ByteArray, expectedSha256: ByteArray): Boolean {
        val sub = ProtoWire.parseFields(stickerBytes) ?: return false
        val sha = sub.firstOrNull { it.fieldNumber == FIELD_STICKER_SHA256 && it.wireType == 2 }?.bytes ?: return false
        return sha.contentEquals(expectedSha256)
    }

    /**
     * Builds FutureProofMessage bytes for `Message.134`:
     * `1 (Message) -> 26 (StickerMessage + 26: AudioMessage)`
     */
    private fun buildSoundWrapper134(
        stickerBytes: ByteArray,
        audioMessageBytes: ByteArray,
        isAnimated: Boolean
    ): ByteArray {
        val subFields = ProtoWire.parseFields(stickerBytes).orEmpty()
        val combinedSticker = ByteArrayOutputStream(stickerBytes.size + audioMessageBytes.size + 16)
        for (f in subFields) {
            if (f.fieldNumber == FIELD_STICKER_AUDIO) continue
            if (isAnimated && f.fieldNumber == FIELD_STICKER_IS_ANIMATED) continue
            ProtoWire.writeRawField(combinedSticker, f)
        }
        if (isAnimated) {
            ProtoWire.writeVarintField(combinedSticker, FIELD_STICKER_IS_ANIMATED, 1L)
        }
        ProtoWire.writeLengthDelimited(combinedSticker, FIELD_STICKER_AUDIO, audioMessageBytes)

        val innerMsg = ByteArrayOutputStream(combinedSticker.size() + 8)
        ProtoWire.writeLengthDelimited(innerMsg, FIELD_MESSAGE_STICKER, combinedSticker.toByteArray())

        val futureProof = ByteArrayOutputStream(innerMsg.size() + 8)
        ProtoWire.writeLengthDelimited(futureProof, FIELD_FUTURE_PROOF_INNER, innerMsg.toByteArray())
        return futureProof.toByteArray()
    }

    private fun queryMaxId(db: SQLiteDatabase?, chatRowId: Long): Long {
        if (db == null || !db.isOpen || chatRowId <= 0L) return -1L
        return runCatching {
            db.rawQuery(
                "SELECT COALESCE(MAX(_id), 0) FROM message WHERE chat_row_id = ?",
                arrayOf(chatRowId.toString())
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
        }.getOrDefault(-1L)
    }

    private fun findTempAudioRowId(
        db: SQLiteDatabase?,
        chatRowId: Long,
        afterRowId: Long,
        expectedSize: Int
    ): Long {
        if (db == null || !db.isOpen || chatRowId <= 0L) return -1L
        return runCatching {
            db.rawQuery(
                "SELECT m._id FROM message m LEFT JOIN message_media mm ON mm.message_row_id = m._id " +
                    "WHERE m.chat_row_id = ? AND m._id > ? AND m.from_me = 1 AND m.message_type = 2 " +
                    "ORDER BY m._id DESC LIMIT 1",
                arrayOf(chatRowId.toString(), afterRowId.toString())
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
        }.getOrDefault(-1L)
    }

    private fun cleanupTempAudioRow(db: SQLiteDatabase, rowId: Long) {
        runCatching {
            db.execSQL("DELETE FROM message_media WHERE message_row_id = ?", arrayOf<Any>(rowId))
            db.execSQL("DELETE FROM message WHERE _id = ?", arrayOf<Any>(rowId))
            ConversationItemListener.notifyDataSetChanged()
        }
    }

    /**
     * Adds a `🔊` badge and tap-to-play audio playback on any sticker bubble in WhatsApp chats
     * whose WebP EXIF contains `sound-sha256` matching a saved `.opus` file in `files/Stickers/sounds/`.
     */
    @Volatile
    var liveDbProvider: (() -> SQLiteDatabase?)? = null

    private fun installLocalStickerSoundPlayer() {
        val stickerContainerId = runCatching { Utils.getID("stickerContainer", "id") }.getOrDefault(0)
        if (stickerContainerId <= 0) return

        ConversationItemListener.conversationListeners.add(object :
            ConversationItemListener.OnConversationItemListener() {
            override fun onItemBind(
                fMessage: com.wmods.wppenhacer.xposed.core.components.FMessageWpp,
                view: ViewGroup,
                position: Int,
                convertView: View?
            ) {
                val container = view.findViewById<ViewGroup>(stickerContainerId) ?: return
                val existingBadge = container.findViewWithTag<View>(BADGE_TAG)

                val webpFile = resolveWebpFileForMessage(fMessage)
                val opusFile = if (webpFile != null) {
                    resolveLocalOpusForSticker(webpFile)
                } else {
                    null
                }

                if (opusFile == null) {
                    existingBadge?.visibility = View.GONE
                    if (container.getTag(stickerContainerId) != null) {
                        container.setTag(stickerContainerId, null)
                        container.setOnTouchListener(null)
                    }
                    return
                }

                val badge = existingBadge ?: createSoundBadge(container).also { container.addView(it) }
                badge.visibility = View.VISIBLE
                badge.setOnClickListener {
                    togglePlayOpus(opusFile)
                }

                container.setTag(stickerContainerId, opusFile.absolutePath)
                container.setTouchClickAndLongClickListener(
                    onClick = {
                        togglePlayOpus(opusFile)
                    },
                    onLongClick = {
                        container.performLongClick()
                    }
                )
            }
        })
    }

    private fun resolveWebpFileForMessage(
        fMessage: com.wmods.wppenhacer.xposed.core.components.FMessageWpp
    ): File? {
        val direct = fMessage.mediaFile
        if (direct != null && direct.exists() && direct.name.endsWith(".webp", ignoreCase = true)) {
            return direct
        }
        val rowId = fMessage.rowId
        if (rowId <= 0L) return null
        val db = liveDbProvider?.invoke()?.takeIf { it.isOpen } ?: return null
        return runCatching {
            db.rawQuery(
                "SELECT file_path, file_hash FROM message_media WHERE message_row_id = ? LIMIT 1",
                arrayOf(rowId.toString())
            ).use { c ->
                if (!c.moveToFirst()) return@use null
                val relPath = if (c.isNull(0)) null else c.getString(0)
                if (!relPath.isNullOrBlank()) {
                    val f1 = File(relPath)
                    if (f1.exists()) return@use f1
                    val f2 = File(com.wmods.wppenhacer.xposed.core.WppCore.getRootWhatsAppDir(), relPath)
                    if (f2.exists()) return@use f2
                }
                val hash = if (c.isNull(1)) null else c.getString(1)
                if (!hash.isNullOrBlank()) {
                    val starred = File(
                        Utils.getAccountDataDir(),
                        "files/Stickers/${hash.replace('/', '-')}.webp"
                    )
                    if (starred.exists()) return@use starred
                }
                null
            }
        }.getOrNull()
    }

    private fun resolveLocalOpusForSticker(webpFile: File): File? {
        if (!webpFile.exists()) return null
        val cacheKey = "${webpFile.absolutePath}|${webpFile.lastModified()}"
        val sha = exifSoundCache.computeIfAbsent(cacheKey) {
            WebpStickerEncoder.readSoundSha256FromWebp(webpFile).orEmpty()
        }
        if (sha.isEmpty()) return null
        return findLocalSoundFileForSha(sha)
    }

    private fun createSoundBadge(parent: ViewGroup): TextView {
        val ctx = parent.context
        val padH = Utils.dipToPixels(6)
        val padV = Utils.dipToPixels(3)
        val margin = Utils.dipToPixels(6)
        return TextView(ctx).apply {
            tag = BADGE_TAG
            text = "🔊"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Utils.dipToPixels(12).toFloat()
                setColor(Color.parseColor("#B3000000"))
            }
            elevation = Utils.dipToPixels(2).toFloat()
            layoutParams = when (parent) {
                is FrameLayout -> FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ).apply { setMargins(margin, margin, margin, margin) }

                is RelativeLayout -> RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                    setMargins(margin, margin, margin, margin)
                }

                else -> ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }
    }

    @Synchronized
    private fun togglePlayOpus(opusFile: File) {
        try {
            val current = activePlayer
            if (current != null) {
                val wasSame = activePlayingPath == opusFile.absolutePath && current.isPlaying
                runCatching { current.stop() }
                runCatching { current.release() }
                activePlayer = null
                activePlayingPath = null
                if (wasSame) return
            }

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(opusFile.absolutePath)
                setOnCompletionListener { mp ->
                    runCatching { mp.release() }
                    synchronized(SoundStickerEngine) {
                        if (activePlayer === mp) {
                            activePlayer = null
                            activePlayingPath = null
                        }
                    }
                }
                setOnErrorListener { mp, _, _ ->
                    runCatching { mp.release() }
                    synchronized(SoundStickerEngine) {
                        if (activePlayer === mp) {
                            activePlayer = null
                            activePlayingPath = null
                        }
                    }
                    true
                }
                prepare()
                start()
            }
            activePlayer = player
            activePlayingPath = opusFile.absolutePath
        } catch (t: Throwable) {
            StickerBotConsole.emitFromXposed(Utils.application, "Erro ao tocar som da figurinha: ${t.message}")
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hmacSha256(key: ByteArray, part1: ByteArray, part2: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(part1)
        return mac.doFinal(part2)
    }

    /**
     * Minimal, zero-dependency Protobuf wire-format parser & writer.
     */
    object ProtoWire {
        class ProtoField(
            val fieldNumber: Int,
            val wireType: Int,
            val varintValue: Long = 0L,
            val bytes: ByteArray = EMPTY_BYTES
        )

        private val EMPTY_BYTES = ByteArray(0)

        fun parseFields(data: ByteArray): List<ProtoField>? {
            val out = ArrayList<ProtoField>()
            var pos = 0
            while (pos < data.size) {
                val tagPair = readVarint(data, pos) ?: return null
                val tag = tagPair.first
                pos = tagPair.second
                val fieldNumber = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()
                if (fieldNumber <= 0) return null

                when (wireType) {
                    0 -> {
                        val v = readVarint(data, pos) ?: return null
                        pos = v.second
                        out.add(ProtoField(fieldNumber, 0, varintValue = v.first))
                    }
                    1 -> {
                        if (pos + 8 > data.size) return null
                        out.add(ProtoField(fieldNumber, 1, bytes = data.copyOfRange(pos, pos + 8)))
                        pos += 8
                    }
                    2 -> {
                        val lenPair = readVarint(data, pos) ?: return null
                        val len = lenPair.first
                        pos = lenPair.second
                        if (len < 0L || len > (data.size - pos).toLong()) return null
                        val end = pos + len.toInt()
                        out.add(ProtoField(fieldNumber, 2, bytes = data.copyOfRange(pos, end)))
                        pos = end
                    }
                    5 -> {
                        if (pos + 4 > data.size) return null
                        out.add(ProtoField(fieldNumber, 5, bytes = data.copyOfRange(pos, pos + 4)))
                        pos += 4
                    }
                    else -> return null
                }
            }
            return out
        }

        private fun readVarint(data: ByteArray, startPos: Int): Pair<Long, Int>? {
            var result = 0L
            var shift = 0
            var pos = startPos
            while (pos < data.size && shift < 64) {
                val b = data[pos].toInt() and 0xFF
                pos++
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) {
                    return Pair(result, pos)
                }
                shift += 7
            }
            return null
        }

        fun writeVarint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while (true) {
                if ((v and 0x7FL.inv()) == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v.toInt() and 0x7F) or 0x80))
                v = v ushr 7
            }
        }

        fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
            writeVarint(out, ((fieldNumber.toLong() shl 3) or 0L))
            writeVarint(out, value)
        }

        fun writeLengthDelimited(out: ByteArrayOutputStream, fieldNumber: Int, payload: ByteArray) {
            writeVarint(out, ((fieldNumber.toLong() shl 3) or 2L))
            writeVarint(out, payload.size.toLong())
            out.write(payload)
        }

        fun writeRawField(out: ByteArrayOutputStream, field: ProtoField) {
            when (field.wireType) {
                0 -> writeVarintField(out, field.fieldNumber, field.varintValue)
                1, 5 -> {
                    writeVarint(out, ((field.fieldNumber.toLong() shl 3) or field.wireType.toLong()))
                    out.write(field.bytes)
                }
                2 -> writeLengthDelimited(out, field.fieldNumber, field.bytes)
            }
        }
    }
}
