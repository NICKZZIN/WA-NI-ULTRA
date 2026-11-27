package com.wmods.wppenhacer.xposed.features.others

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import java.text.Normalizer
import androidx.core.content.ContextCompat
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.highcapable.yukihookapi.extension.kavaref.hookAll
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.utils.StickerBotConsole
import com.wmods.wppenhacer.utils.WebpStickerEncoder
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.features.providers.ContextMenuActionProvider
import com.wmods.wppenhacer.xposed.utils.AudioOpusConverter
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Integrated Xposed Sticker & Sound-Sticker Bot.
 *
 * Runs 100% inside WhatsApp with zero pairing and 0% idle battery drain:
 * - Intercepts !s, !fig, !som, !ajuda in captions, replies, and follow-up messages
 * - Supports self-chat, private chats with other contacts, and optional group chats
 * - Converts images & videos to 512x512 WebP (static or animated) + 48kHz Opus voice note
 * - Saves to Starred Stickers (Favorites) and sends directly in the background without opening
 *   any preview Activity or restarting WhatsApp
 */
class StickerBotFeature(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    private enum class BotCmd { STICKER, SOUND_STICKER, LIMITS }

    private data class ResolvedMediaPair(
        val visualFile: File,
        val visualType: Int,
        val isGif: Boolean,
        val isVideo: Boolean,
        val separateAudioFile: File? = null
    )

    private val completedKeys: MutableSet<String> =
        Collections.synchronizedSet(object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                val added = super.add(element)
                if (size > 400) {
                    val it = iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
                return added
            }
        })

    private val inProgressKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Zero-battery worker pool: 1 thread max, terminates completely after 15s idle
    private val botWorker = ThreadPoolExecutor(
        1,
        1,
        15L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue()
    ) { runnable ->
        Thread(runnable, "WaeStickerBot").apply { isDaemon = true }
    }.apply {
        allowCoreThreadTimeOut(true)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sendingBotVoiceNote = ThreadLocal<Boolean>()

    @Volatile
    private var mediaTypeMethod: Method? = null

    @Volatile
    private var mediaSenderInstance: Any? = null

    @Volatile
    private var originFMessageField: Field? = null

    @Volatile
    private var stickerModelClass: Class<*>? = null

    @Volatile
    private var stickerSenderMethod: Method? = null

    @Volatile
    private var stickerSenderInstance: Any? = null

    @Volatile
    private var stickerFieldMap: Map<String, Field> = emptyMap()

    @Volatile
    private var liveStickersDb: SQLiteDatabase? = null

    @Volatile
    private var liveMsgStoreDb: SQLiteDatabase? = null

    override fun doHook() {
        resolveInternalSenders()
        hookContentResolverForBotFiles()
        hookDatabaseMessageCommands()
        registerContextMenuShortcuts()
        registerStudioBroadcastReceiver()
    }

    private fun isBotEnabled(): Boolean = xprefs.getBoolean(PREF_BOT_ENABLED, true)

    private fun isContextShortcutEnabled(): Boolean =
        xprefs.getBoolean(PREF_BOT_CONTEXT_SHORTCUT, true)

    private fun canProcessCommand(fromMe: Boolean, chatJid: String): Boolean {
        if (fromMe) return true
        if (xprefs.getBoolean(PREF_BOT_SELF_ONLY, false)) return false
        val isGroup = chatJid.endsWith("@g.us")
        return if (isGroup) {
            xprefs.getBoolean(PREF_BOT_ALLOW_GROUPS, false)
        } else {
            xprefs.getBoolean(PREF_BOT_ALLOW_PRIVATE, true)
        }
    }

    private fun getPackName(): String =
        xprefs.getString(PREF_BOT_PACK, "WA-NI-ULTRA")?.takeIf { it.isNotBlank() } ?: "WA-NI-ULTRA"

    private fun getAuthorName(): String =
        xprefs.getString(PREF_BOT_AUTHOR, "@NickC137")?.takeIf { it.isNotBlank() } ?: "@NickC137"

    /**
     * Resolves internal WhatsApp media & sticker sending methods during [doHook] while DexKit is open.
     */
    private fun resolveInternalSenders() {
        runCatching {
            val mMethod = Unobfuscator.loadMediaTypeMethod(classLoader)
            mMethod.isAccessible = true
            mediaTypeMethod = mMethod

            if (!Modifier.isStatic(mMethod.modifiers)) {
                mMethod.declaringClass.resolve().constructor {}.hookAll {
                    after {
                        if (instance != null) mediaSenderInstance = instance
                    }
                }
            }

            mMethod.hook {
                before {
                    if (instance != null) mediaSenderInstance = instance
                    if (sendingBotVoiceNote.get() == true) {
                        val intPositions = ReflectionUtils.findInstancesOfType(
                            args,
                            Int::class.javaObjectType
                        )
                        if (intPositions.size >= 2) {
                            args[intPositions[0].first] = MEDIA_TYPE_AUDIO
                            args[intPositions[1].first] = AUDIO_ORIGIN_VOICE_NOTE
                        }
                    }
                }
            }
        }.onFailure { logDebug("resolveInternalSenders: mediaTypeMethod", it) }

        runCatching {
            originFMessageField = Unobfuscator.loadOriginFMessageField(classLoader).apply {
                isAccessible = true
            }
        }.onFailure { logDebug("resolveInternalSenders: originFMessageField", it) }

        runCatching {
            val sClass = Unobfuscator.loadStickerModelClass(classLoader)
            stickerModelClass = sClass
            if (sClass != null) {
                stickerFieldMap = runCatching { Unobfuscator.getAllMapFields(sClass) }.getOrNull().orEmpty()
            }
            val sMethod = Unobfuscator.loadStickerSenderMethod(classLoader)
            if (sMethod != null) {
                sMethod.isAccessible = true
                stickerSenderMethod = sMethod
                if (!Modifier.isStatic(sMethod.modifiers)) {
                    sMethod.declaringClass.resolve().constructor {}.hookAll {
                        after {
                            if (instance != null) stickerSenderInstance = instance
                        }
                    }
                }
            }
        }.onFailure { logDebug("resolveInternalSenders: stickerSender", it) }
    }

    /**
     * Allows `file://` URIs generated by the bot in cacheDir or files/Stickers to answer
     * [ContentResolver.getType] and [ContentResolver.query] just like `content://` URIs.
     */
    private fun hookContentResolverForBotFiles() {
        runCatching {
            ContentResolver::class.java.resolve().method {
                name = "getType"
                parameters(Uri::class.java)
            }.hookAll {
                before {
                    val uri = args.getOrNull(0) as? Uri ?: return@before
                    val path = uri.path ?: return@before
                    if (path.contains("/sticker_bot/") || path.contains("/Stickers/") || path.contains("voice_note_")) {
                        when {
                            path.endsWith(".webp", ignoreCase = true) -> result = "image/webp"
                            path.endsWith(".opus", ignoreCase = true) || path.endsWith(".ogg", ignoreCase = true) ->
                                result = "audio/ogg; codecs=opus"
                        }
                    }
                }
            }

            ContentResolver::class.java.resolve().method {
                name = "query"
            }.hookAll {
                before {
                    val uri = args.getOrNull(0) as? Uri ?: return@before
                    if (uri.scheme != "file") return@before
                    val path = uri.path ?: return@before
                    if (!path.contains("/sticker_bot/") && !path.contains("/Stickers/") && !path.contains("voice_note_")) {
                        return@before
                    }
                    val file = File(path)
                    if (!file.exists()) return@before
                    val cursor = MatrixCursor(arrayOf("_display_name", "_size", "_data"))
                    cursor.addRow(arrayOf<Any>(file.name, file.length(), file.absolutePath))
                    result = cursor
                }
            }
        }.onFailure { logDebug(it) }
    }

    private fun hookDatabaseMessageCommands() {
        SQLiteDatabase::class.java.resolve().method {
            name = "insertWithOnConflict"
        }.hookAll {
            after {
                val db = instance as? SQLiteDatabase ?: return@after
                val path = db.path ?: return@after
                if (path.endsWith("/stickers.db")) {
                    liveStickersDb = db
                    return@after
                }
                if (path.endsWith("/msgstore.db")) {
                    liveMsgStoreDb = db
                }
                if (!isBotEnabled() || !path.endsWith("/msgstore.db")) return@after
                val rowId = (result as? Number)?.toLong() ?: return@after
                if (rowId <= 0L) return@after
                val table = args.getOrNull(0) as? String ?: return@after
                val values = args.getOrNull(2) as? ContentValues ?: return@after

                when (table) {
                    "message" -> inspectInsertedMessage(db, rowId, values)
                    "message_media" -> {
                        val filePath = values.getAsString("file_path")
                        val msgRowId = values.getAsLong("message_row_id") ?: -1L
                        if (!filePath.isNullOrBlank() && msgRowId > 0L) {
                            inspectMessageById(db, msgRowId)
                        }
                    }
                    "message_text" -> {
                        val text = values.getAsString("text")?.trim().orEmpty()
                        val msgRowId = values.getAsLong("message_row_id") ?: rowId
                        if (isCommandCandidate(text) && msgRowId > 0L) {
                            inspectMessageById(db, msgRowId, text)
                        }
                    }
                }
            }
        }

        SQLiteDatabase::class.java.resolve().method {
            name = "updateWithOnConflict"
        }.hookAll {
            after {
                if (!isBotEnabled()) return@after
                val db = instance as? SQLiteDatabase ?: return@after
                val path = db.path ?: return@after
                if (!path.endsWith("/msgstore.db")) return@after
                val table = args.getOrNull(0) as? String ?: return@after
                val values = args.getOrNull(1) as? ContentValues ?: return@after

                if (table == "message_media") {
                    val filePath = values.getAsString("file_path")
                    if (!filePath.isNullOrBlank()) {
                        var msgRowId = values.getAsLong("message_row_id") ?: -1L
                        if (msgRowId <= 0L) {
                            val whereArgs = args.getOrNull(3) as? Array<*>
                            msgRowId = whereArgs?.firstOrNull()?.toString()?.toLongOrNull() ?: -1L
                        }
                        if (msgRowId > 0L) {
                            inspectMessageById(db, msgRowId)
                        }
                    }
                }
            }
        }
    }

    private fun isCommandCandidate(text: String): Boolean {
        return text.isNotEmpty() && text[0] == '/'
    }

    private fun inspectInsertedMessage(db: SQLiteDatabase, rowId: Long, values: ContentValues) {
        val text = values.getAsString("text_data")?.trim().orEmpty()
        if (!isCommandCandidate(text)) return

        val parsed = parseCommand(text) ?: return
        val fromMe = (values.getAsInteger("from_me") ?: 0) == 1
        val chatRowId = values.getAsLong("chat_row_id") ?: -1L
        val messageType = values.getAsInteger("message_type") ?: 0
        val keyId = values.getAsString("key_id").orEmpty().ifEmpty { "row_$rowId" }

        scheduleCommandExecution(db, rowId, chatRowId, keyId, fromMe, messageType, parsed, text)
    }

    private fun inspectMessageById(
        db: SQLiteDatabase,
        msgRowId: Long,
        overrideText: String? = null
    ) {
        if (!db.isOpen) return
        runCatching {
            db.rawQuery(
                "SELECT text_data, from_me, key_id, chat_row_id, message_type FROM message WHERE _id = ? LIMIT 1",
                arrayOf(msgRowId.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) return
                val text = (overrideText ?: if (cursor.isNull(0)) "" else cursor.getString(0)).trim()
                if (!isCommandCandidate(text)) return
                val parsed = parseCommand(text) ?: return
                val fromMe = cursor.getInt(1) == 1
                val keyId = (if (cursor.isNull(2)) "" else cursor.getString(2)).ifEmpty { "row_$msgRowId" }
                val chatRowId = cursor.getLong(3)
                val messageType = cursor.getInt(4)

                scheduleCommandExecution(db, msgRowId, chatRowId, keyId, fromMe, messageType, parsed, text)
            }
        }
    }

    private fun scheduleCommandExecution(
        db: SQLiteDatabase,
        rowId: Long,
        chatRowId: Long,
        keyId: String,
        fromMe: Boolean,
        messageType: Int,
        parsed: BotCmd,
        rawText: String
    ) {
        if (completedKeys.contains(keyId)) return
        if (!inProgressKeys.add(keyId)) return

        botWorker.execute {
            try {
                Thread.sleep(350L)
                processCommand(db, rowId, chatRowId, keyId, fromMe, messageType, parsed, rawText)
            } finally {
                inProgressKeys.remove(keyId)
            }
        }
    }

    private fun registerContextMenuShortcuts() {
        ContextMenuActionProvider.register { _, _, fMessage ->
            if (!isBotEnabled() || !isContextShortcutEnabled() || !fMessage.isMediaFile) return@register null
            val file = fMessage.mediaFile ?: return@register null
            if (!file.exists()) return@register null
            val isVid = isVideoFile(file) || fMessage.mediaType == 3 || fMessage.mediaType == 13
            ContextMenuActionProvider.ContextMenuAction(
                title = "Figurinha",
                icon = R.drawable.ic_media,
                autoDismiss = true
            ) {
                val chatJid = fMessage.key.remoteJid.userRawString
                    ?: fMessage.key.remoteJid.phoneRawString
                botWorker.execute {
                    createAndDeliverSticker(
                        mediaFile = file,
                        separateAudioFile = null,
                        isVideo = isVid,
                        isGif = fMessage.mediaType == 13 || file.extension.equals("gif", ignoreCase = true),
                        withAudio = false,
                        options = WebpStickerEncoder.StickerOptions(),
                        chatRawJid = chatJid,
                        chatRowId = -1L,
                        db = liveMsgStoreDb,
                        fromMe = true,
                        fMessage = fMessage
                    )
                }
            }
        }
    }

    private fun registerStudioBroadcastReceiver() {
        runCatching {
            val filter = IntentFilter(ACTION_STUDIO_SEND_STICKER)
            ContextCompat.registerReceiver(
                Utils.application,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val webpPath = intent.getStringExtra(EXTRA_WEBP_PATH) ?: return
                        val opusPath = intent.getStringExtra(EXTRA_OPUS_PATH)
                        val targetPhone = intent.getStringExtra(EXTRA_TARGET_PHONE).orEmpty()
                        botWorker.execute {
                            handleStudioStickerBroadcast(webpPath, opusPath, targetPhone)
                        }
                    }
                },
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }.onFailure { logDebug(it) }
    }

    private fun handleStudioStickerBroadcast(webpPath: String, opusPath: String?, targetPhone: String) {
        val app = Utils.application
        try {
            val webpFile = File(webpPath)
            if (!webpFile.exists()) return
            val webpBytes = webpFile.readBytes()
            val (savedFav, favFile, plainHash) = saveToWhatsAppStarredStickers(webpBytes)
            if (savedFav) {
                StickerBotConsole.emitFromXposed(app, "Figurinha do Criador Rápido salva nos Favoritos!")
            }

            val activeJid = WppCore.getCurrentUserJid()?.let {
                it.userRawString ?: it.phoneRawString
            }
            val phoneDigits = targetPhone.filter { it.isDigit() }
            val fallbackSelfJid = if (phoneDigits.length >= 10) {
                "$phoneDigits@s.whatsapp.net"
            } else {
                WppCore.getMyUserJid()?.let { it.phoneRawString ?: it.userRawString }
            }
            val targetJid = activeJid ?: fallbackSelfJid
            val opusFile = opusPath?.let(::File)?.takeIf { it.exists() && it.length() > 0 }
            val isAnimated = isAnimatedWebpBytes(webpBytes)

            if (!targetJid.isNullOrBlank()) {
                deliverMediaToChat(
                    stickerFile = webpFile,
                    webpBytes = webpBytes,
                    plainHash = plainHash,
                    isAnimated = isAnimated,
                    opusFile = opusFile,
                    chatRawJid = targetJid,
                    chatRowId = -1L,
                    db = liveMsgStoreDb
                )
            }
            saveToWhatsAppStarredStickers(webpBytes)
            Utils.showToast("Figurinha salva nos Favoritos e enviada!", Toast.LENGTH_SHORT)
        } catch (t: Throwable) {
            logDebug(t)
            StickerBotConsole.emitFromXposed(app, "Erro ao instalar figurinha: ${t.message}")
        }
    }

    private fun processCommand(
        db: SQLiteDatabase,
        rowId: Long,
        chatRowId: Long,
        keyId: String,
        fromMe: Boolean,
        messageType: Int,
        cmd: BotCmd,
        rawText: String
    ) {
        try {
            val chatJid = resolveChatJid(db, chatRowId)
            if (chatJid.isNullOrEmpty() || chatJid == "status@broadcast") return

            if (!canProcessCommand(fromMe, chatJid)) {
                return
            }

            if (cmd == BotCmd.LIMITS) {
                completedKeys.add(keyId)
                StickerBotConsole.emitFromXposed(Utils.application, "Comando /limites respondido em $chatJid")
                WppCore.sendMessageToJid(chatJid, LIMITS_MESSAGE)
                return
            }

            val mediaPair = resolveMediaPairForCommand(db, rowId, chatRowId, messageType, cmd == BotCmd.SOUND_STICKER)
            if (mediaPair == null || !mediaPair.visualFile.exists()) {
                if (messageType == 0) {
                    completedKeys.add(keyId)
                    val msg = if (cmd == BotCmd.SOUND_STICKER) {
                        "Para */figsom*, envie ou responda a um vídeo, ou envie uma foto/vídeo respondendo a um áudio."
                    } else {
                        "Para */fig*, envie uma foto, vídeo ou GIF com */fig* na legenda ou responda a uma mídia com */fig*."
                    }
                    StickerBotConsole.emitFromXposed(Utils.application, msg)
                    if (fromMe) {
                        Utils.showToast(msg.replace("*", ""), Toast.LENGTH_SHORT)
                    }
                }
                return
            }

            completedKeys.add(keyId)
            val parsedOpts = parseStickerOptions(rawText, cmd == BotCmd.SOUND_STICKER, mediaPair.isVideo)
            val wantAudio = cmd == BotCmd.SOUND_STICKER &&
                (mediaPair.separateAudioFile != null || (mediaPair.isVideo && !mediaPair.isGif))

            if (cmd == BotCmd.SOUND_STICKER && !wantAudio) {
                val warnMsg = if (mediaPair.isGif) {
                    "GIFs do WhatsApp não possuem som 🔇 Enviando figurinha animada sem som."
                } else {
                    "Foto não possui som próprio 🔇 Responda a um áudio com */figsom* para combinar foto + áudio. Enviando figurinha normal."
                }
                StickerBotConsole.emitFromXposed(Utils.application, warnMsg)
                if (fromMe) Utils.showToast(warnMsg, Toast.LENGTH_LONG)
            }

            createAndDeliverSticker(
                mediaFile = mediaPair.visualFile,
                separateAudioFile = mediaPair.separateAudioFile,
                isVideo = mediaPair.isVideo,
                isGif = mediaPair.isGif,
                withAudio = wantAudio,
                options = parsedOpts,
                chatRawJid = chatJid,
                chatRowId = chatRowId,
                db = db,
                fromMe = fromMe,
                fMessage = null
            )
        } catch (t: Throwable) {
            logDebug(t)
            StickerBotConsole.emitFromXposed(Utils.application, "Erro no bot: ${t.message}")
        }
    }

    private fun createAndDeliverSticker(
        mediaFile: File,
        separateAudioFile: File?,
        isVideo: Boolean,
        isGif: Boolean,
        withAudio: Boolean,
        options: WebpStickerEncoder.StickerOptions,
        chatRawJid: String?,
        chatRowId: Long,
        db: SQLiteDatabase?,
        fromMe: Boolean,
        fMessage: FMessageWpp?
    ) {
        val app = Utils.application
        try {
            if (mediaFile.length() > MAX_INPUT_FILE_BYTES) {
                val msg = "Arquivo excede o limite de 16 MB (${mediaFile.length() / (1024 * 1024)} MB). Envie um trecho menor."
                StickerBotConsole.emitFromXposed(app, msg)
                if (fromMe) Utils.showToast(msg, Toast.LENGTH_LONG)
                return
            }

            fMessage?.let { WppCore.sendReaction("⏳", it.getObject()) }
            val modeLabel = when (options.fitMode) {
                WebpStickerEncoder.FitMode.CROP -> "cortar"
                WebpStickerEncoder.FitMode.CONTAIN -> "inteiro"
                WebpStickerEncoder.FitMode.STRETCH -> "esticar"
                WebpStickerEncoder.FitMode.CIRCLE -> "redondo"
            }
            StickerBotConsole.emitFromXposed(
                app,
                "Criando figurinha (${if (isVideo && !options.stillFromVideo) "vídeo/GIF" else "imagem"}, modo=$modeLabel${if (withAudio) " + áudio Opus" else ""}) para ${chatRawJid ?: "chat"}..."
            )

            val workDir = File(app.cacheDir, "sticker_bot").apply { mkdirs() }
            var opusFile: File? = null
            var soundSha256Base64: String? = null

            if (withAudio) {
                val audioSrc = separateAudioFile ?: mediaFile
                val isAnimatedSticker = isVideo && !options.stillFromVideo
                val maxAudioMs = when {
                    options.longSound && isAnimatedSticker -> WebpStickerEncoder.MAX_SOUND_LONG_ANIM_DURATION_MS
                    options.longSound && !isAnimatedSticker -> WebpStickerEncoder.MAX_SOUND_LONG_STILL_DURATION_MS
                    isAnimatedSticker -> WebpStickerEncoder.DEFAULT_SOUND_ANIM_DURATION_MS
                    else -> WebpStickerEncoder.MAX_SOUND_STILL_DURATION_MS
                }
                val targetAudioMs = minOf(options.durationMs.coerceAtLeast(1000L), maxAudioMs)
                val autoLoudest = separateAudioFile != null && options.startMs <= 0L

                StickerBotConsole.emitFromXposed(
                    app,
                    "Extraindo áudio Ogg/Opus 48kHz (${targetAudioMs / 1000f}s${if (autoLoudest) ", buscando trecho mais alto" else ""})..."
                )
                opusFile = AudioOpusConverter.convert(
                    filePath = audioSrc.absolutePath,
                    maxDurationUs = targetAudioMs * 1000L,
                    outputDir = workDir,
                    startUs = options.startMs * 1000L,
                    autoFindLoudest = autoLoudest
                )
                if (opusFile != null && opusFile.exists() && opusFile.length() > 0) {
                    val oggBytes = opusFile.readBytes()
                    val sha = MessageDigest.getInstance("SHA-256").digest(oggBytes)
                    soundSha256Base64 = Base64.encodeToString(sha, Base64.NO_WRAP)
                    StickerBotConsole.emitFromXposed(
                        app,
                        "Áudio Opus pronto: ${oggBytes.size / 1024} KB (SHA-256 anexado ao EXIF)"
                    )
                } else {
                    StickerBotConsole.emitFromXposed(app, "Mídia sem faixa de áudio audível; enviando figurinha sem som.")
                    opusFile = null
                }
            }

            val pack = getPackName()
            val author = getAuthorName()
            val effectiveOpts = options.copy(
                soundSha256Base64 = soundSha256Base64,
                startMs = if (separateAudioFile != null) 0L else options.startMs
            )

            val encoded = if (isVideo) {
                WebpStickerEncoder.createAnimatedSticker(
                    videoFile = mediaFile,
                    fullFrame = effectiveOpts.fitMode == WebpStickerEncoder.FitMode.CONTAIN,
                    packName = pack,
                    author = author,
                    options = effectiveOpts
                ) { msg ->
                    StickerBotConsole.emitFromXposed(app, msg)
                }
            } else {
                WebpStickerEncoder.createStaticSticker(
                    imageFile = mediaFile,
                    fullFrame = effectiveOpts.fitMode == WebpStickerEncoder.FitMode.CONTAIN,
                    packName = pack,
                    author = author,
                    options = effectiveOpts
                ) { msg ->
                    StickerBotConsole.emitFromXposed(app, msg)
                }
            }

            // Use a dedicated temp file in cacheDir so WhatsApp's sticker sender never truncates files/Stickers/<hash>.webp
            val tempStickerFile = File(
                workDir,
                "sticker_${System.currentTimeMillis()}.webp"
            ).apply {
                writeBytes(encoded.webpBytes)
            }
            val sha256 = MessageDigest.getInstance("SHA-256").digest(encoded.webpBytes)
            val plainHash = Base64.encodeToString(sha256, Base64.NO_WRAP)

            // 1. Save into WhatsApp Starred Stickers (Favorites) when triggered by the user
            val (savedFav, _) = if (fromMe) {
                saveToWhatsAppStarredStickers(encoded.webpBytes)
            } else {
                Triple(false, null, plainHash)
            }
            if (savedFav) {
                StickerBotConsole.emitFromXposed(app, "Figurinha salva nos Favoritos do WhatsApp!")
            }

            // 2. Deliver sticker (+ Opus voice note if /figsom) silently in the background
            deliverMediaToChat(
                stickerFile = tempStickerFile,
                webpBytes = encoded.webpBytes,
                plainHash = plainHash,
                isAnimated = encoded.isAnimated,
                opusFile = opusFile,
                chatRawJid = chatRawJid,
                chatRowId = chatRowId,
                db = db ?: liveMsgStoreDb
            )

            // Re-ensure files/Stickers/<hash>.webp is intact after WhatsApp's media sender finishes
            if (fromMe) {
                saveToWhatsAppStarredStickers(encoded.webpBytes)
            }

            fMessage?.let { WppCore.sendReaction(if (withAudio && opusFile != null) "🔊" else "✅", it.getObject()) }
            if (fromMe) {
                Utils.showToast(
                    if (withAudio && opusFile != null) "Figurinha + áudio enviados!"
                    else if (savedFav) "Figurinha enviada e salva nos Favoritos!"
                    else "Figurinha enviada!",
                    Toast.LENGTH_SHORT
                )
            }
        } catch (t: Throwable) {
            logDebug(t)
            fMessage?.let { WppCore.sendReaction("❌", it.getObject()) }
            StickerBotConsole.emitFromXposed(app, "Falha ao criar figurinha: ${t.message}")
            if (fromMe) {
                Utils.showToast("Erro na figurinha: ${t.message}", Toast.LENGTH_LONG)
            }
        }
    }

    /**
     * Saves the WebP sticker into `files/Stickers/<hash>.webp` (replacing only `/` with `-`)
     * and inserts its metadata into `stickers.db` (`starred_stickers`).
     */
    private fun saveToWhatsAppStarredStickers(webpBytes: ByteArray): Triple<Boolean, File?, String> {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(webpBytes)
        val plainHash = Base64.encodeToString(sha256, Base64.NO_WRAP)
        return try {
            val dataDir = Utils.getAccountDataDir()
            val stickersDir = File(dataDir, "files/Stickers").apply { mkdirs() }
            // WhatsApp only replaces '/' with '-' in files/Stickers/<base64>.webp (keeps '+')
            val safeFileName = plainHash.replace('/', '-') + ".webp"
            val destFile = File(stickersDir, safeFileName)
            destFile.writeBytes(webpBytes)

            val stickersDbFile = File(dataDir, "databases/stickers.db")
            if (!stickersDbFile.exists()) return Triple(false, destFile, plainHash)

            val activeDb = liveStickersDb?.takeIf { it.isOpen && !it.isReadOnly }
            if (activeDb != null) {
                insertStarredRow(activeDb, plainHash, webpBytes.size)
            } else {
                SQLiteDatabase.openDatabase(
                    stickersDbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
                ).use { sdb ->
                    insertStarredRow(sdb, plainHash, webpBytes.size)
                }
            }
            Triple(true, destFile, plainHash)
        } catch (t: Throwable) {
            logDebug("Starred sticker DB insert skipped", t)
            Triple(false, null, plainHash)
        }
    }

    private fun insertStarredRow(sdb: SQLiteDatabase, plainHash: String, fileSize: Int) {
        val now = System.currentTimeMillis()
        val columns = HashSet<String>()
        runCatching {
            sdb.rawQuery("PRAGMA table_info(starred_stickers)", null).use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext() && nameIdx >= 0) {
                    cursor.getString(nameIdx)?.let(columns::add)
                }
            }
        }

        if (columns.isNotEmpty()) {
            val cv = ContentValues().apply {
                if ("plaintext_hash" in columns) put("plaintext_hash", plainHash)
                if ("timestamp" in columns) put("timestamp", now)
                if ("mimetype" in columns) put("mimetype", "image/webp")
                if ("file_size" in columns) put("file_size", fileSize)
                if ("width" in columns) put("width", 512)
                if ("height" in columns) put("height", 512)
                if ("is_first_party" in columns) put("is_first_party", 0)
                if ("is_avatar" in columns) put("is_avatar", 0)
                if ("is_fun_sticker" in columns) put("is_fun_sticker", 0)
                if ("is_lottie" in columns) put("is_lottie", 0)
                if ("premium" in columns) put("premium", 0)
            }
            val row = sdb.insertWithOnConflict(
                "starred_stickers",
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            if (row > 0L) return
        }

        val exactSql = "INSERT OR REPLACE INTO starred_stickers " +
            "(plaintext_hash, timestamp, url, enc_hash, direct_path, mimetype, media_key, " +
            "file_size, width, height, is_first_party, is_avatar, is_fun_sticker, is_lottie, premium) " +
            "VALUES (?, ?, NULL, NULL, NULL, 'image/webp', NULL, ?, 512, 512, 0, 0, 0, 0, 0)"
        sdb.execSQL(exactSql, arrayOf<Any>(plainHash, now, fileSize))
    }

    /**
     * Sends the generated WebP sticker and (if present) the 48kHz Opus voice note directly
     * to [chatRawJid] in the background without launching any preview Activity.
     */
    private fun deliverMediaToChat(
        stickerFile: File,
        webpBytes: ByteArray,
        plainHash: String,
        isAnimated: Boolean,
        opusFile: File?,
        chatRawJid: String?,
        chatRowId: Long,
        db: SQLiteDatabase?
    ) {
        val app = Utils.application
        if (chatRawJid.isNullOrBlank()) return
        val targetJid = WppCore.createUserJid(chatRawJid)
        if (targetJid == null) {
            StickerBotConsole.emitFromXposed(app, "JID inválido para envio: $chatRawJid")
            return
        }

        val activeDb = db ?: liveMsgStoreDb
        val effectiveChatRowId = if (chatRowId > 0L) {
            chatRowId
        } else {
            resolveChatRowIdByJid(activeDb, chatRawJid)
        }
        val maxIdBefore = queryMaxMessageId(activeDb, effectiveChatRowId)

        // 1. Try sending sticker via WhatsApp's native MediaType sender (mediaType = 20 = STICKER)
        var stickerSent = invokeMediaTypeSender(
            targetJid = targetJid,
            mediaFile = stickerFile,
            mediaType = MEDIA_TYPE_STICKER,
            sourceType = 0,
            isVoiceNote = false
        )

        if (stickerSent && activeDb != null && effectiveChatRowId > 0L) {
            Thread.sleep(650L)
            if (queryMaxMessageId(activeDb, effectiveChatRowId) <= maxIdBefore) {
                stickerSent = false
            }
        }

        // 2. Fallback to WhatsApp's dedicated Sticker model sender if needed
        if (!stickerSent) {
            stickerSent = invokeNativeStickerSender(
                targetJid = targetJid,
                stickerFile = stickerFile,
                plainHash = plainHash,
                fileSize = webpBytes.size,
                isAnimated = isAnimated
            )
        }

        if (stickerSent) {
            StickerBotConsole.emitFromXposed(app, "Figurinha enviada em segundo plano para $chatRawJid!")
        } else {
            StickerBotConsole.emitFromXposed(
                app,
                "Figurinha pronta nos Favoritos (abra a aba ★ de figurinhas para enviar)."
            )
        }

        // 3. If /figsom was used, send the 48kHz Opus Voice Note right after the sticker
        if (opusFile != null && opusFile.exists() && opusFile.length() > 0) {
            Thread.sleep(450L)
            val audioSent = invokeMediaTypeSender(
                targetJid = targetJid,
                mediaFile = opusFile,
                mediaType = MEDIA_TYPE_AUDIO,
                sourceType = AUDIO_ORIGIN_VOICE_NOTE,
                isVoiceNote = true
            )
            if (audioSent) {
                StickerBotConsole.emitFromXposed(app, "Nota de voz Opus 48kHz enviada em segundo plano!")
            } else {
                StickerBotConsole.emitFromXposed(app, "Não foi possível despachar a nota de voz Opus automaticamente.")
            }
        }
    }

    private fun invokeMediaTypeSender(
        targetJid: Any,
        mediaFile: File,
        mediaType: Int,
        sourceType: Int,
        isVoiceNote: Boolean
    ): Boolean {
        val method = mediaTypeMethod ?: runCatching {
            Unobfuscator.loadMediaTypeMethod(classLoader).also {
                it.isAccessible = true
                mediaTypeMethod = it
            }
        }.getOrNull() ?: return false

        val receiver = if (Modifier.isStatic(method.modifiers)) {
            null
        } else {
            resolveInstanceForClass(method.declaringClass, mediaSenderInstance)?.also {
                mediaSenderInstance = it
            } ?: return false
        }

        val mediaUri = Uri.fromFile(mediaFile)
        val paramTypes = method.parameterTypes
        val callArgs = arrayOfNulls<Any>(paramTypes.size)
        val currentAct = WppCore.getCurrentConversation() ?: WppCore.getCurrentActivity()
        val app = Utils.application
        var intOrder = 0

        for (i in paramTypes.indices) {
            val pType = paramTypes[i]
            callArgs[i] = when {
                List::class.java.isAssignableFrom(pType) ->
                    ArrayList(Collections.singletonList(targetJid))

                FMessageWpp.UserJid.TYPE_JID.isAssignableFrom(pType) ->
                    targetJid

                pType == Uri::class.java ->
                    mediaUri

                pType == File::class.java ->
                    mediaFile

                pType == Int::class.javaPrimitiveType || pType == Int::class.javaObjectType -> {
                    val v = when (intOrder) {
                        0 -> mediaType
                        1 -> sourceType
                        else -> 0
                    }
                    intOrder++
                    v
                }

                pType == Boolean::class.javaPrimitiveType || pType == Boolean::class.javaObjectType ->
                    false

                pType == Long::class.javaPrimitiveType || pType == Long::class.javaObjectType ->
                    0L

                pType == String::class.java ->
                    null

                currentAct != null && pType.isInstance(currentAct) ->
                    currentAct

                pType.isInstance(app) ->
                    app

                !pType.isPrimitive && !pType.isInterface &&
                    !pType.name.startsWith("java.") && !pType.name.startsWith("android.") &&
                    ReflectionUtils.getFieldByExtendType(pType, File::class.java) != null -> {
                    createMediaDataObject(pType, mediaFile, mediaType)
                }

                pType.isInterface -> {
                    Proxy.newProxyInstance(classLoader, arrayOf(pType)) { _, m, _ ->
                        ReflectionUtils.getDefaultValue(m.returnType)
                    }
                }

                else -> ReflectionUtils.getDefaultValue(pType)
            }
        }

        val latch = CountDownLatch(1)
        var success = false
        mainHandler.post {
            try {
                if (isVoiceNote) sendingBotVoiceNote.set(true)
                val res = method.invoke(receiver, *callArgs)
                if (isVoiceNote && res != null) {
                    applyVoiceNoteOrigin(res)
                }
                success = true
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                logDebug("invokeMediaTypeSender failed (${method.name})", cause)
                StickerBotConsole.emitFromXposed(
                    app,
                    "Aviso sender (${if (isVoiceNote) "áudio" else "sticker"}): ${cause.javaClass.simpleName}: ${cause.message}"
                )
            } finally {
                if (isVoiceNote) sendingBotVoiceNote.set(false)
                latch.countDown()
            }
        }
        latch.await(5L, TimeUnit.SECONDS)
        return success
    }

    private fun invokeNativeStickerSender(
        targetJid: Any,
        stickerFile: File,
        plainHash: String,
        fileSize: Int,
        isAnimated: Boolean
    ): Boolean {
        val sClass = stickerModelClass ?: return false
        val sMethod = stickerSenderMethod ?: return false

        return try {
            val stickerObj = instantiateObject(sClass) ?: return false
            populateStickerModel(stickerObj, stickerFile, plainHash, fileSize, isAnimated)

            val receiver = if (Modifier.isStatic(sMethod.modifiers)) {
                null
            } else {
                resolveInstanceForClass(sMethod.declaringClass, stickerSenderInstance)?.also {
                    stickerSenderInstance = it
                } ?: return false
            }

            val paramTypes = sMethod.parameterTypes
            val callArgs = arrayOfNulls<Any>(paramTypes.size)
            for (i in paramTypes.indices) {
                val pType = paramTypes[i]
                callArgs[i] = when {
                    pType == sClass || pType.isAssignableFrom(sClass) -> stickerObj
                    List::class.java.isAssignableFrom(pType) ->
                        ArrayList(Collections.singletonList(targetJid))
                    FMessageWpp.UserJid.TYPE_JID.isAssignableFrom(pType) -> targetJid
                    pType == Int::class.javaPrimitiveType || pType == Int::class.javaObjectType -> 0
                    pType == Boolean::class.javaPrimitiveType || pType == Boolean::class.javaObjectType -> false
                    else -> ReflectionUtils.getDefaultValue(pType)
                }
            }

            val latch = CountDownLatch(1)
            var sent = false
            mainHandler.post {
                try {
                    sMethod.invoke(receiver, *callArgs)
                    sent = true
                } catch (t: Throwable) {
                    logDebug("invokeNativeStickerSender failed", t.cause ?: t)
                } finally {
                    latch.countDown()
                }
            }
            latch.await(4L, TimeUnit.SECONDS)
            sent
        } catch (t: Throwable) {
            logDebug(t)
            false
        }
    }

    private fun populateStickerModel(
        stickerObj: Any,
        stickerFile: File,
        plainHash: String,
        fileSize: Int,
        isAnimated: Boolean
    ) {
        val clazz = stickerObj.javaClass
        if (stickerFieldMap.isNotEmpty()) {
            for ((name, field) in stickerFieldMap) {
                field.isAccessible = true
                val lower = name.lowercase(Locale.ROOT)
                runCatching {
                    when {
                        field.type == String::class.java && (lower.contains("path") || lower.contains("file") && !lower.contains("hash")) ->
                            field.set(stickerObj, stickerFile.absolutePath)
                        field.type == String::class.java && lower.contains("hash") ->
                            field.set(stickerObj, plainHash)
                        field.type == String::class.java && lower.contains("mime") ->
                            field.set(stickerObj, "image/webp")
                        field.type == Int::class.javaPrimitiveType && (lower.contains("width") || lower.contains("height")) ->
                            field.setInt(stickerObj, 512)
                        field.type == Int::class.javaPrimitiveType && lower.contains("size") ->
                            field.setInt(stickerObj, fileSize)
                        field.type == Boolean::class.javaPrimitiveType && lower.contains("anim") ->
                            field.setBoolean(stickerObj, isAnimated)
                    }
                }
            }
        }
        // Also ensure File field if any
        ReflectionUtils.getFieldByExtendType(clazz, File::class.java)?.apply {
            isAccessible = true
            runCatching { set(stickerObj, stickerFile) }
        }
    }

    private fun createMediaDataObject(
        mediaDataClass: Class<*>,
        mediaFile: File,
        mediaType: Int
    ): Any? {
        val obj = instantiateObject(mediaDataClass) ?: return null
        ReflectionUtils.getFieldByExtendType(mediaDataClass, File::class.java)?.apply {
            isAccessible = true
            runCatching { set(obj, mediaFile) }
        }
        runCatching {
            val fields = Unobfuscator.getAllMapFields(mediaDataClass)
            fields["fileSize"]?.apply {
                isAccessible = true
                if (type == Long::class.javaPrimitiveType) setLong(obj, mediaFile.length())
            }
            if (mediaType == MEDIA_TYPE_STICKER) {
                fields["width"]?.apply {
                    isAccessible = true
                    if (type == Int::class.javaPrimitiveType) setInt(obj, 512)
                }
                fields["height"]?.apply {
                    isAccessible = true
                    if (type == Int::class.javaPrimitiveType) setInt(obj, 512)
                }
            }
        }
        return obj
    }

    private fun applyVoiceNoteOrigin(res: Any) {
        val originField = originFMessageField ?: return
        runCatching {
            if (res is List<*>) {
                for (item in res) {
                    if (item != null && originField.declaringClass.isInstance(item)) {
                        originField.setInt(item, AUDIO_ORIGIN_VOICE_NOTE)
                    }
                }
            } else if (originField.declaringClass.isInstance(res)) {
                originField.setInt(res, AUDIO_ORIGIN_VOICE_NOTE)
            }
        }
    }

    private fun resolveInstanceForClass(targetClass: Class<*>, cached: Any?): Any? {
        if (cached != null && targetClass.isInstance(cached)) return cached

        val actionUser = WppCore.getActionUser()
        if (actionUser != null && targetClass.isInstance(actionUser)) return actionUser

        // Check fields of live WhatsApp objects (ActionUser, CurrentConversation, CurrentActivity)
        val roots = listOfNotNull(
            actionUser,
            WppCore.getCurrentConversation(),
            WppCore.getCurrentActivity()
        )
        for (root in roots) {
            findFieldInstance(root, targetClass)?.let { return it }
        }

        // Finally instantiate via constructor
        return instantiateObject(targetClass)
    }

    private fun findFieldInstance(holder: Any, targetClass: Class<*>): Any? {
        var curr: Class<*>? = holder.javaClass
        while (curr != null && curr != Any::class.java) {
            for (f in curr.declaredFields) {
                if (f.type.isPrimitive) continue
                if (targetClass.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    val v = runCatching { f.get(holder) }.getOrNull()
                    if (v != null) return v
                }
            }
            curr = curr.superclass
        }
        return null
    }

    private fun instantiateObject(clazz: Class<*>): Any? {
        return runCatching {
            val zeroArg = clazz.declaredConstructors.firstOrNull { it.parameterCount == 0 }
            if (zeroArg != null) {
                zeroArg.isAccessible = true
                return@runCatching zeroArg.newInstance()
            }
            val constructor = clazz.declaredConstructors.minByOrNull { it.parameterCount }
                ?: return@runCatching null
            constructor.isAccessible = true
            val args = Array(constructor.parameterCount) { idx ->
                ReflectionUtils.getDefaultValue(constructor.parameterTypes[idx])
            }
            constructor.newInstance(*args)
        }.getOrNull()
    }

    private fun queryMaxMessageId(db: SQLiteDatabase?, chatRowId: Long): Long {
        if (db == null || !db.isOpen || chatRowId <= 0L) return -1L
        return runCatching {
            db.rawQuery(
                "SELECT COALESCE(MAX(_id), 0) FROM message WHERE chat_row_id = ?",
                arrayOf(chatRowId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            }
        }.getOrDefault(-1L)
    }

    private fun resolveChatRowIdByJid(db: SQLiteDatabase?, rawJid: String): Long {
        if (db == null || !db.isOpen || rawJid.isBlank()) return -1L
        return runCatching {
            db.rawQuery(
                "SELECT c._id FROM chat c JOIN jid j ON j._id = c.jid_row_id WHERE j.raw_string = ? LIMIT 1",
                arrayOf(rawJid)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            }
        }.getOrDefault(-1L)
    }

    private fun resolveChatJid(db: SQLiteDatabase, chatRowId: Long): String? {
        if (chatRowId <= 0L || !db.isOpen) return null
        return runCatching {
            db.rawQuery(
                "SELECT j.raw_string FROM chat c JOIN jid j ON j._id = c.jid_row_id WHERE c._id = ? LIMIT 1",
                arrayOf(chatRowId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun isAnimatedWebpBytes(webp: ByteArray): Boolean {
        if (webp.size < 24) return false
        val fourCc = String(webp, 12, 4, Charsets.US_ASCII)
        return fourCc == "VP8X" && ((webp[20].toInt() and 0x02) != 0)
    }

    /**
     * Resolves the visual media (image/video/GIF) and optional separate audio file (for `/figsom`
     * when combining a photo/video with a quoted or recent voice note/MP3, just like kuebot).
     */
    private fun resolveMediaPairForCommand(
        db: SQLiteDatabase,
        messageRowId: Long,
        chatRowId: Long,
        messageType: Int,
        wantSound: Boolean
    ): ResolvedMediaPair? {
        // 1. Own message media (caption command on an image or video)
        var ownFile: File? = queryMediaPathByRowId(db, messageRowId)
        if (ownFile == null && messageType != 0) {
            for (attempt in 1..35) {
                Thread.sleep(400L)
                ownFile = queryMediaPathByRowId(db, messageRowId)
                if (ownFile != null) break
            }
        }

        // 2. Quoted / marked message in message_quoted
        var quotedFile: File? = null
        var quotedType = -1
        val quotedKeyId = runCatching {
            db.rawQuery(
                "SELECT key_id FROM message_quoted WHERE message_row_id = ? LIMIT 1",
                arrayOf(messageRowId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

        if (!quotedKeyId.isNullOrBlank()) {
            var originalRowId = -1L
            runCatching {
                db.rawQuery(
                    "SELECT _id, message_type FROM message WHERE key_id = ? ORDER BY _id DESC LIMIT 1",
                    arrayOf(quotedKeyId)
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        originalRowId = cursor.getLong(0)
                        quotedType = cursor.getInt(1)
                    }
                }
            }
            if (originalRowId > 0L) {
                quotedFile = queryMediaPathByRowId(db, originalRowId)
                if (quotedFile == null) {
                    for (attempt in 1..12) {
                        Thread.sleep(400L)
                        quotedFile = queryMediaPathByRowId(db, originalRowId)
                        if (quotedFile != null) break
                    }
                }
            }
        }

        // Case A: Own message has visual media (photo/video with caption /fig or /figsom)
        if (ownFile != null && !isAudioOnly(ownFile, messageType)) {
            val separateAudio = if (wantSound && quotedFile != null && isAudioCapable(quotedFile, quotedType)) {
                quotedFile
            } else {
                null
            }
            return buildMediaPair(ownFile, messageType, separateAudio)
        }

        // Case B: Quoted message is visual media (replying to a photo/video/GIF with /fig or /figsom)
        if (quotedFile != null && !isAudioOnly(quotedFile, quotedType)) {
            val separateAudio = if (wantSound && ownFile != null && isAudioCapable(ownFile, messageType)) {
                ownFile
            } else {
                null
            }
            return buildMediaPair(quotedFile, quotedType, separateAudio)
        }

        // Case C: Quoted message is an audio/voice note (e.g. replying to an audio with /figsom after sending an image)
        if (wantSound && quotedFile != null && isAudioOnly(quotedFile, quotedType)) {
            val recentVisual = findRecentVisualInChat(db, chatRowId, messageRowId)
            if (recentVisual != null) {
                return buildMediaPair(recentVisual.first, recentVisual.second, quotedFile)
            }
        }

        // Case D: Follow-up command sent right after an image/video/GIF in the same chat (within 3 minutes)
        val recentVisual = findRecentVisualInChat(db, chatRowId, messageRowId)
        if (recentVisual != null) {
            return buildMediaPair(recentVisual.first, recentVisual.second, null)
        }

        return null
    }

    private fun findRecentVisualInChat(
        db: SQLiteDatabase,
        chatRowId: Long,
        beforeRowId: Long
    ): Pair<File, Int>? {
        if (chatRowId <= 0L) return null
        val minTimestamp = System.currentTimeMillis() - 180_000L
        var recentRowId = -1L
        var recentType = 0
        runCatching {
            db.rawQuery(
                "SELECT _id, message_type FROM message WHERE chat_row_id = ? AND _id < ? " +
                    "AND message_type IN (1, 3, 13) AND timestamp >= ? " +
                    "ORDER BY _id DESC LIMIT 1",
                arrayOf(chatRowId.toString(), beforeRowId.toString(), minTimestamp.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    recentRowId = cursor.getLong(0)
                    recentType = cursor.getInt(1)
                }
            }
        }
        if (recentRowId <= 0L) return null
        var file = queryMediaPathByRowId(db, recentRowId)
        if (file == null) {
            for (attempt in 1..10) {
                Thread.sleep(400L)
                file = queryMediaPathByRowId(db, recentRowId)
                if (file != null) break
            }
        }
        return if (file != null) Pair(file, recentType) else null
    }

    private fun buildMediaPair(
        visualFile: File,
        visualType: Int,
        separateAudioFile: File?
    ): ResolvedMediaPair {
        val isGif = visualType == 13 ||
            visualFile.extension.equals("gif", ignoreCase = true) ||
            visualFile.parentFile?.name?.contains("Gif", ignoreCase = true) == true
        val isVideo = isVideoFile(visualFile) || visualType == 3 || isGif
        return ResolvedMediaPair(
            visualFile = visualFile,
            visualType = visualType,
            isGif = isGif,
            isVideo = isVideo,
            separateAudioFile = separateAudioFile
        )
    }

    private fun isAudioOnly(file: File, messageType: Int): Boolean {
        if (messageType == 2) return true
        val ext = file.extension.lowercase(Locale.ROOT)
        return ext in setOf("opus", "ogg", "mp3", "m4a", "aac", "wav")
    }

    private fun isAudioCapable(file: File, messageType: Int): Boolean {
        return isAudioOnly(file, messageType) || isVideoFile(file) || messageType == 3
    }

    private fun queryMediaPathByRowId(db: SQLiteDatabase, rowId: Long): File? {
        if (!db.isOpen) return null
        val relPath = runCatching {
            db.rawQuery(
                "SELECT file_path FROM message_media WHERE message_row_id = ? LIMIT 1",
                arrayOf(rowId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        return resolveMediaPathOnDisk(relPath)
    }

    private fun resolveMediaPathOnDisk(relPath: String): File? {
        val app = Utils.application
        val extStorage = Environment.getExternalStorageDirectory()
        val pkg = app.packageName
        val appLabel = runCatching {
            app.packageManager.getApplicationLabel(app.applicationInfo).toString()
        }.getOrDefault("WhatsApp")

        val candidates = listOf(
            File(relPath),
            File(WppCore.getRootWhatsAppDir(), relPath),
            File(extStorage, "Android/media/$pkg/$appLabel/$relPath"),
            File(extStorage, "Android/media/$pkg/WhatsApp/$relPath"),
            File(extStorage, "Android/media/$pkg/WhatsApp Business/$relPath"),
            File(extStorage, "$appLabel/$relPath"),
            File(extStorage, "WhatsApp/$relPath"),
            File(extStorage, relPath),
            File(Utils.getAccountDataDir(), relPath)
        )
        return candidates.firstOrNull { it.exists() && it.isFile && it.length() > 0L }
    }

    /**
     * Parses commands strictly prefixed with `/` (as requested by the user):
     * 1) `/fig` — Image, Video, or GIF sticker (without audio)
     * 2) `/figsom` — Sticker with Audio 🔊
     * 3) `/limites` — Shows video, image, and audio limits
     */
    private fun parseCommand(text: String): BotCmd? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return null
        val firstToken = trimmed.split(Regex("\\s+"), limit = 2).firstOrNull()
            ?.removePrefix("/")
            ?.let(::normalizeToken)
            ?: return null
        return when (firstToken) {
            "fig", "figurinha", "s", "sticker" -> BotCmd.STICKER
            "figsom", "som", "fs", "figaudio" -> BotCmd.SOUND_STICKER
            "limites", "limite", "ajuda", "menu", "comandos" -> BotCmd.LIMITS
            else -> null
        }
    }

    /**
     * Parses kuebot-style options after `/fig` or `/figsom`:
     * - Mode: `cortar` (default), `inteiro`/`ajustar`, `esticar`, `redondo`
     * - Time range: `3-8` (from 3s to 8s), `1:05-1:10`, or `5s` (first 5 seconds)
     * - `/figsom` flags: `parada` (still frame from video with up to 10s sound), `longo` (up to 10s anim / 20s still)
     * - Caption color: `branco`, `preto`, `vermelho`, `azul`, `verde`, `amarelo`, `rosa`, `roxo`, `laranja`, `ciano`, `dourado`
     * - Remaining words become the meme caption at the bottom of the sticker.
     */
    private fun parseStickerOptions(
        rawText: String,
        isSoundCmd: Boolean,
        isVideo: Boolean
    ): WebpStickerEncoder.StickerOptions {
        val tokens = rawText.trim().split(Regex("\\s+")).drop(1).filter { it.isNotEmpty() }
        var fitMode: WebpStickerEncoder.FitMode? = null
        var startMs: Long? = null
        var durationMs: Long? = null
        var stillFromVideo = false
        var longSound = false
        var captionColor: Int? = null

        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            val norm = normalizeToken(tok)
            val dotTok = tok.replace(',', '.')
            val rangeMatch = TIME_RANGE_REGEX.matchEntire(dotTok)
            val durMatch = DURATION_REGEX.matchEntire(dotTok)

            if (fitMode == null && MODE_ALIASES.containsKey(norm)) {
                fitMode = MODE_ALIASES[norm]
            } else if (captionColor == null && CAPTION_COLORS.containsKey(norm)) {
                captionColor = CAPTION_COLORS[norm]
            } else if (isSoundCmd && norm in STILL_FLAGS && !stillFromVideo) {
                stillFromVideo = true
            } else if (isSoundCmd && norm in LONG_FLAGS && !longSound) {
                longSound = true
            } else if (rangeMatch != null && startMs == null) {
                val aSec = parseTimeSeconds(rangeMatch.groupValues[1])
                val bSec = parseTimeSeconds(rangeMatch.groupValues[2])
                if (bSec <= aSec) break
                startMs = (aSec * 1000.0).toLong()
                durationMs = ((bSec - aSec) * 1000.0).toLong()
            } else if (durMatch != null && durationMs == null) {
                val dSec = durMatch.groupValues[1].toDoubleOrNull() ?: break
                if (dSec <= 0.0) break
                durationMs = (dSec * 1000.0).toLong()
            } else {
                break
            }
            i++
        }

        val caption = tokens.subList(i, tokens.size).joinToString(" ")
        val animated = isVideo && !stillFromVideo
        val defaultDur = when {
            !isSoundCmd -> WebpStickerEncoder.DEFAULT_ANIM_DURATION_MS
            animated && longSound -> WebpStickerEncoder.MAX_SOUND_LONG_ANIM_DURATION_MS
            !animated && longSound -> WebpStickerEncoder.MAX_SOUND_LONG_STILL_DURATION_MS
            animated -> WebpStickerEncoder.DEFAULT_SOUND_ANIM_DURATION_MS
            else -> WebpStickerEncoder.MAX_SOUND_STILL_DURATION_MS
        }
        val maxDur = when {
            !isSoundCmd -> WebpStickerEncoder.MAX_ANIM_DURATION_MS
            animated && longSound -> WebpStickerEncoder.MAX_SOUND_LONG_ANIM_DURATION_MS
            !animated && longSound -> WebpStickerEncoder.MAX_SOUND_LONG_STILL_DURATION_MS
            animated -> WebpStickerEncoder.DEFAULT_SOUND_ANIM_DURATION_MS
            else -> WebpStickerEncoder.MAX_SOUND_STILL_DURATION_MS
        }

        return WebpStickerEncoder.StickerOptions(
            fitMode = fitMode ?: WebpStickerEncoder.FitMode.CROP,
            startMs = (startMs ?: 0L).coerceAtLeast(0L),
            durationMs = minOf(durationMs ?: defaultDur, maxDur),
            keepDuration = isSoundCmd,
            stillFromVideo = stillFromVideo,
            longSound = longSound,
            caption = caption,
            captionColor = captionColor ?: Color.WHITE
        )
    }

    private fun parseTimeSeconds(raw: String): Double {
        val clean = raw.removeSuffix("s").removeSuffix("S")
        return if (clean.contains(':')) {
            val parts = clean.split(':')
            val m = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val s = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            m * 60.0 + s
        } else {
            clean.toDoubleOrNull() ?: 0.0
        }
    }

    private fun normalizeToken(input: String): String {
        val nfd = Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace("-", "")
            .replace("_", "")
    }

    private fun isVideoFile(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.ROOT)
        return ext in setOf("mp4", "mkv", "webm", "3gp", "mov", "gif")
    }

    override fun getPluginName(): String = "Sticker Bot"

    companion object {
        const val PREF_BOT_ENABLED = "sticker_bot_enabled"
        const val PREF_BOT_SELF_ONLY = "sticker_bot_self_only"
        const val PREF_BOT_ALLOW_PRIVATE = "sticker_bot_allow_private"
        const val PREF_BOT_ALLOW_GROUPS = "sticker_bot_allow_groups"
        const val PREF_BOT_CONTEXT_SHORTCUT = "sticker_bot_context_shortcut"
        const val PREF_BOT_PACK = "sticker_bot_pack"
        const val PREF_BOT_AUTHOR = "sticker_bot_author"

        const val ACTION_STUDIO_SEND_STICKER = "com.wmods.wppenhacer.BOT_STUDIO_SEND"
        const val EXTRA_WEBP_PATH = "webp_path"
        const val EXTRA_OPUS_PATH = "opus_path"
        const val EXTRA_TARGET_PHONE = "target_phone"

        private const val MEDIA_TYPE_AUDIO = 2
        private const val MEDIA_TYPE_STICKER = 20
        private const val AUDIO_ORIGIN_VOICE_NOTE = 1
        private const val MAX_INPUT_FILE_BYTES = 16L * 1024L * 1024L

        private val TIME_RANGE_REGEX =
            Regex("^(\\d+(?::\\d{1,2})?(?:\\.\\d+)?)s?-(\\d+(?::\\d{1,2})?(?:\\.\\d+)?)s?$", RegexOption.IGNORE_CASE)
        private val DURATION_REGEX =
            Regex("^(\\d+(?:\\.\\d+)?)s$", RegexOption.IGNORE_CASE)

        private val MODE_ALIASES = mapOf(
            "cortar" to WebpStickerEncoder.FitMode.CROP,
            "cortado" to WebpStickerEncoder.FitMode.CROP,
            "quadrado" to WebpStickerEncoder.FitMode.CROP,
            "crop" to WebpStickerEncoder.FitMode.CROP,
            "inteiro" to WebpStickerEncoder.FitMode.CONTAIN,
            "inteira" to WebpStickerEncoder.FitMode.CONTAIN,
            "ajustar" to WebpStickerEncoder.FitMode.CONTAIN,
            "original" to WebpStickerEncoder.FitMode.CONTAIN,
            "bordas" to WebpStickerEncoder.FitMode.CONTAIN,
            "esticar" to WebpStickerEncoder.FitMode.STRETCH,
            "esticado" to WebpStickerEncoder.FitMode.STRETCH,
            "esticada" to WebpStickerEncoder.FitMode.STRETCH,
            "redondo" to WebpStickerEncoder.FitMode.CIRCLE,
            "redonda" to WebpStickerEncoder.FitMode.CIRCLE,
            "circulo" to WebpStickerEncoder.FitMode.CIRCLE,
            "bola" to WebpStickerEncoder.FitMode.CIRCLE
        )

        private val STILL_FLAGS = setOf("parada", "parado", "estatica")
        private val LONG_FLAGS = setOf("longo", "longa", "estendido", "estendida")

        private val CAPTION_COLORS = mapOf(
            "branco" to Color.parseColor("#FFFFFF"),
            "preto" to Color.parseColor("#111111"),
            "vermelho" to Color.parseColor("#FF2D2D"),
            "azul" to Color.parseColor("#2D8CFF"),
            "verde" to Color.parseColor("#2EE66B"),
            "amarelo" to Color.parseColor("#FFE11A"),
            "rosa" to Color.parseColor("#FF5EC4"),
            "roxo" to Color.parseColor("#9B5CFF"),
            "laranja" to Color.parseColor("#FF8C1A"),
            "ciano" to Color.parseColor("#1AE5FF"),
            "dourado" to Color.parseColor("#FFC83D")
        )

        private val LIMITS_MESSAGE = """
            🤖 *WA-NI-ULTRA — Comandos e Limites*
            
            🖼️ */fig* — Figurinha de Foto, Vídeo ou GIF (sem som)
            • Envie na legenda da mídia, respondendo a ela ou logo após enviar.
            • Modos opcionais: _cortar_ (padrão), _inteiro_ (sem cortar), _esticar_, _redondo_
            • Trecho opcional: _/fig 2-7_ (do 2s ao 7s) ou _/fig 5s_
            • Legenda opcional: _/fig redondo amarelo bom dia_
            
            🔊 */figsom* — Figurinha com Áudio
            • Envie ou responda a um *vídeo* com */figsom* (usa o som do vídeo).
            • Ou envie uma *foto/vídeo* com */figsom* respondendo a um *áudio*.
            • Opções: _/figsom 3-8_, _/figsom parada_ (vídeo vira fig parada com até 10s de som), _/figsom longo_ (até 10s animada).
            
            📏 */limites* — Limites de Imagem, Vídeo e Áudio:
            • *Foto (estática):* 512×512 px · máx. *100 KB*
            • *Vídeo / GIF (/fig):* duração padrão *6s* (máx. *10s*) · WebP até *500 KB* · arquivo de entrada até *16 MB*
            • *Áudio na figurinha (/figsom):*
              - Animada (vídeo): até *5s* de som (ou *10s* com _/figsom longo_)
              - Parada (foto ou _/figsom parada_): até *10s* de som (ou *20s* com _/figsom parada longo_)
              - Formato: Ogg/Opus mono 48 kHz (máx. *256 KB*)
              - _GIFs do WhatsApp não possuem faixa de áudio._
        """.trimIndent()
    }
}
