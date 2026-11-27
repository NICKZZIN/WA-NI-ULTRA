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
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
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

    private enum class BotCmd { STICKER, SOUND_STICKER, HELP }

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

    override fun doHook() {
        resolveInternalSenders()
        hookContentResolverForBotFiles()
        hookDatabaseMessageCommands()
        registerContextMenuShortcuts()
        registerStudioBroadcastReceiver()
    }

    private fun isBotEnabled(): Boolean = xprefs.getBoolean(PREF_BOT_ENABLED, true)

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
        if (text.isEmpty()) return false
        val first = text[0]
        return first == '!' || first == '/'
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
        val fullFrame = hasFullFrameFlag(rawText)

        botWorker.execute {
            try {
                Thread.sleep(350L)
                processCommand(db, rowId, chatRowId, keyId, fromMe, messageType, parsed, fullFrame)
            } finally {
                inProgressKeys.remove(keyId)
            }
        }
    }

    private fun registerContextMenuShortcuts() {
        ContextMenuActionProvider.register { _, _, fMessage ->
            if (!isBotEnabled() || !fMessage.isMediaFile) return@register null
            val file = fMessage.mediaFile ?: return@register null
            if (!file.exists()) return@register null
            val isVid = isVideoFile(file)
            val label = if (isVid) "Fig + Som" else "Figurinha"
            ContextMenuActionProvider.ContextMenuAction(
                title = label,
                icon = R.drawable.ic_media,
                autoDismiss = true
            ) {
                val chatJid = fMessage.key.remoteJid.userRawString
                    ?: fMessage.key.remoteJid.phoneRawString
                botWorker.execute {
                    createAndDeliverSticker(
                        mediaFile = file,
                        isVideo = isVid,
                        withAudio = isVid,
                        fullFrame = false,
                        chatRawJid = chatJid,
                        chatRowId = -1L,
                        db = null,
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

            if (!targetJid.isNullOrBlank()) {
                deliverMediaToChat(
                    stickerFile = favFile ?: webpFile,
                    webpBytes = webpBytes,
                    plainHash = plainHash,
                    opusFile = opusFile,
                    chatRawJid = targetJid,
                    chatRowId = -1L,
                    db = null
                )
            }
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
        fullFrame: Boolean
    ) {
        try {
            val chatJid = resolveChatJid(db, chatRowId)
            if (chatJid.isNullOrEmpty() || chatJid == "status@broadcast") return

            if (!canProcessCommand(fromMe, chatJid)) {
                return
            }

            if (cmd == BotCmd.HELP) {
                completedKeys.add(keyId)
                StickerBotConsole.emitFromXposed(Utils.application, "Comando !ajuda respondido em $chatJid")
                WppCore.sendMessageToJid(chatJid, HELP_MESSAGE)
                return
            }

            val mediaPath = resolveMediaFileForCommand(db, rowId, chatRowId, messageType)
            if (mediaPath == null || !mediaPath.exists()) {
                if (messageType == 0) {
                    completedKeys.add(keyId)
                    StickerBotConsole.emitFromXposed(
                        Utils.application,
                        "Nenhuma foto ou vídeo encontrada na mensagem, resposta ou histórico recente do chat."
                    )
                    if (fromMe) {
                        Utils.showToast("Envie ou marque uma foto/vídeo com o comando!", Toast.LENGTH_SHORT)
                    }
                }
                return
            }

            completedKeys.add(keyId)
            val isVideo = isVideoFile(mediaPath) || messageType == 3 || messageType == 13
            val wantAudio = (cmd == BotCmd.SOUND_STICKER) && isVideo
            createAndDeliverSticker(
                mediaFile = mediaPath,
                isVideo = isVideo,
                withAudio = wantAudio,
                fullFrame = fullFrame,
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
        isVideo: Boolean,
        withAudio: Boolean,
        fullFrame: Boolean,
        chatRawJid: String?,
        chatRowId: Long,
        db: SQLiteDatabase?,
        fromMe: Boolean,
        fMessage: FMessageWpp?
    ) {
        val app = Utils.application
        try {
            fMessage?.let { WppCore.sendReaction("⏳", it.getObject()) }
            StickerBotConsole.emitFromXposed(
                app,
                "Criando figurinha (${if (isVideo) "vídeo" else "imagem"}${if (withAudio) " + áudio Opus" else ""}) para ${chatRawJid ?: "chat"}..."
            )

            val pack = getPackName()
            val author = getAuthorName()
            val encoded = if (isVideo) {
                WebpStickerEncoder.createAnimatedSticker(mediaFile, fullFrame, pack, author) { msg ->
                    StickerBotConsole.emitFromXposed(app, msg)
                }
            } else {
                WebpStickerEncoder.createStaticSticker(mediaFile, fullFrame, pack, author) { msg ->
                    StickerBotConsole.emitFromXposed(app, msg)
                }
            }

            var opusFile: File? = null
            if (withAudio) {
                StickerBotConsole.emitFromXposed(app, "Convertendo áudio para Nota de Voz Opus 48kHz (máx 10s)...")
                opusFile = AudioOpusConverter.convert(mediaFile.absolutePath, 10_000_000L)
                if (opusFile != null && opusFile.exists() && opusFile.length() > 0) {
                    StickerBotConsole.emitFromXposed(
                        app,
                        "Áudio Opus pronto: ${opusFile.length() / 1024} KB"
                    )
                } else {
                    StickerBotConsole.emitFromXposed(app, "Vídeo sem faixa de áudio; enviando figurinha animada.")
                    opusFile = null
                }
            }

            // 1. Save into WhatsApp Starred Stickers (Favorites) when triggered by the user
            val (savedFav, favStickerFile, plainHash) = if (fromMe) {
                saveToWhatsAppStarredStickers(encoded.webpBytes)
            } else {
                val sha256 = MessageDigest.getInstance("SHA-256").digest(encoded.webpBytes)
                val hash = Base64.encodeToString(sha256, Base64.NO_WRAP)
                Triple(false, null, hash)
            }
            if (savedFav) {
                StickerBotConsole.emitFromXposed(app, "Figurinha salva nos Favoritos do WhatsApp!")
            }

            val workDir = File(app.cacheDir, "sticker_bot").apply { mkdirs() }
            val tempStickerFile = favStickerFile ?: File(
                workDir,
                "sticker_${System.currentTimeMillis()}.webp"
            ).apply {
                writeBytes(encoded.webpBytes)
            }

            // 2. Deliver sticker (+ Opus voice note if !som) silently in the background
            deliverMediaToChat(
                stickerFile = tempStickerFile,
                webpBytes = encoded.webpBytes,
                plainHash = plainHash,
                opusFile = opusFile,
                chatRawJid = chatRawJid,
                chatRowId = chatRowId,
                db = db
            )

            fMessage?.let { WppCore.sendReaction(if (withAudio) "🔊" else "✅", it.getObject()) }
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

        val maxIdBefore = queryMaxMessageId(db, chatRowId)

        // 1. Try sending sticker via WhatsApp's native MediaType sender (mediaType = 20 = STICKER)
        var stickerSent = invokeMediaTypeSender(
            targetJid = targetJid,
            mediaFile = stickerFile,
            mediaType = MEDIA_TYPE_STICKER,
            sourceType = 0,
            isVoiceNote = false
        )

        if (stickerSent && db != null && chatRowId > 0L) {
            Thread.sleep(650L)
            if (queryMaxMessageId(db, chatRowId) <= maxIdBefore) {
                stickerSent = false
            }
        }

        // 2. Fallback to WhatsApp's dedicated Sticker model sender if needed
        if (!stickerSent) {
            stickerSent = invokeNativeStickerSender(
                targetJid = targetJid,
                stickerFile = stickerFile,
                plainHash = plainHash,
                fileSize = webpBytes.size
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

        // 3. If !som was used, send the 48kHz Opus Voice Note right after the sticker
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
        fileSize: Int
    ): Boolean {
        val sClass = stickerModelClass ?: return false
        val sMethod = stickerSenderMethod ?: return false

        return try {
            val stickerObj = instantiateObject(sClass) ?: return false
            populateStickerModel(stickerObj, stickerFile, plainHash, fileSize)

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
        fileSize: Int
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

    /**
     * Resolves the image/video file for a command:
     * 1. Direct caption command on an image/video message (polls up to 14s while WhatsApp transcodes/saves the file)
     * 2. Reply/marked command quoting an image/video message in `message_quoted`
     * 3. Follow-up command sent right after an image/video in the same chat within the last 3 minutes
     */
    private fun resolveMediaFileForCommand(
        db: SQLiteDatabase,
        messageRowId: Long,
        chatRowId: Long,
        messageType: Int
    ): File? {
        // 1. Direct media attached to this message (caption command like sending an image/video with !s or !som)
        queryMediaPathByRowId(db, messageRowId)?.let { return it }
        if (messageType != 0) {
            for (attempt in 1..35) {
                Thread.sleep(400L)
                queryMediaPathByRowId(db, messageRowId)?.let { return it }
            }
        }

        // 2. Quoted / marked message in message_quoted
        val quotedKeyId = runCatching {
            db.rawQuery(
                "SELECT key_id FROM message_quoted WHERE message_row_id = ? LIMIT 1",
                arrayOf(messageRowId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

        if (!quotedKeyId.isNullOrBlank()) {
            val originalRowId = runCatching {
                db.rawQuery(
                    "SELECT _id FROM message WHERE key_id = ? ORDER BY _id DESC LIMIT 1",
                    arrayOf(quotedKeyId)
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else -1L
                }
            }.getOrDefault(-1L)

            if (originalRowId > 0L) {
                queryMediaPathByRowId(db, originalRowId)?.let { return it }
                for (attempt in 1..12) {
                    Thread.sleep(400L)
                    queryMediaPathByRowId(db, originalRowId)?.let { return it }
                }
            }
        }

        // 3. Follow-up command: check most recent image/video/gif in the same chat within the last 3 minutes
        if (chatRowId > 0L) {
            val minTimestamp = System.currentTimeMillis() - 180_000L
            val recentMediaRowId = runCatching {
                db.rawQuery(
                    "SELECT _id FROM message WHERE chat_row_id = ? AND _id < ? " +
                        "AND message_type IN (1, 3, 13) AND timestamp >= ? " +
                        "ORDER BY _id DESC LIMIT 1",
                    arrayOf(chatRowId.toString(), messageRowId.toString(), minTimestamp.toString())
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else -1L
                }
            }.getOrDefault(-1L)

            if (recentMediaRowId > 0L) {
                queryMediaPathByRowId(db, recentMediaRowId)?.let { return it }
                for (attempt in 1..10) {
                    Thread.sleep(400L)
                    queryMediaPathByRowId(db, recentMediaRowId)?.let { return it }
                }
            }
        }

        return null
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

    private fun parseCommand(text: String): BotCmd? {
        val token = text.split(Regex("\\s+"), limit = 2).firstOrNull()
            ?.lowercase(Locale.ROOT)?.removePrefix("/")?.let { if (it.startsWith("!")) it else "!$it" }
            ?: return null
        return when (token) {
            "!s", "!f", "!fig", "!figurinha", "!sticker", "!figu" -> BotCmd.STICKER
            "!som", "!fs", "!figsom" -> BotCmd.SOUND_STICKER
            "!ajuda", "!help", "!comandos" -> BotCmd.HELP
            else -> null
        }
    }

    private fun hasFullFrameFlag(text: String): Boolean {
        return text.lowercase(Locale.ROOT).split(Regex("\\s+")).drop(1)
            .any { it == "inteiro" || it == "!inteiro" }
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
        const val PREF_BOT_PACK = "sticker_bot_pack"
        const val PREF_BOT_AUTHOR = "sticker_bot_author"

        const val ACTION_STUDIO_SEND_STICKER = "com.wmods.wppenhacer.BOT_STUDIO_SEND"
        const val EXTRA_WEBP_PATH = "webp_path"
        const val EXTRA_OPUS_PATH = "opus_path"
        const val EXTRA_TARGET_PHONE = "target_phone"

        private const val MEDIA_TYPE_AUDIO = 2
        private const val MEDIA_TYPE_STICKER = 20
        private const val AUDIO_ORIGIN_VOICE_NOTE = 1

        private val HELP_MESSAGE = """
            *WA-NI-ULTRA — Bot de Figurinhas* ✨
            
            • *!s* ou *!fig* — cria figurinha de foto ou vídeo (na legenda, respondendo ou logo após enviar a mídia).
            • *!fig inteiro* — mantém a imagem/vídeo inteira sem cortar.
            • *!som* — cria figurinha animada + nota de voz Opus 48kHz sincronizada.
            • *!ajuda* — mostra esta lista de comandos.
            
            💡 _Dica: você também pode segurar o dedo em qualquer foto/vídeo e tocar em "Figurinha" / "Fig + Som"!_
        """.trimIndent()
    }
}
