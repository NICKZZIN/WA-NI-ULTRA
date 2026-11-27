package com.wmods.wppenhacer.xposed.features.others

import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.extension.kavaref.hookAll
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.utils.StickerBotConsole
import com.wmods.wppenhacer.utils.WebpStickerEncoder
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.features.providers.ContextMenuActionProvider
import com.wmods.wppenhacer.xposed.utils.AudioOpusConverter
import com.wmods.wppenhacer.xposed.utils.Utils
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Integrated Xposed Sticker & Sound-Sticker Bot.
 *
 * Runs 100% inside WhatsApp with zero pairing and 0% idle battery drain:
 * - Intercepts !s, !fig, !som, !ajuda commands on msgstore.db inserts
 * - Adds 1-tap "Figurinha" / "Fig + Som" actions to WhatsApp's message context menu
 * - Converts images & videos to 512x512 WebP (static or animated) + 48kHz Opus audio
 * - Injects custom EXIF pack/author metadata, saves to Starred Stickers, and delivers to chat
 */
class StickerBotFeature(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    private enum class BotCmd { STICKER, SOUND_STICKER, HELP }

    private val handledKeys: MutableSet<String> =
        Collections.synchronizedSet(object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                val added = super.add(element)
                if (size > 300) {
                    val it = iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
                return added
            }
        })

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

    override fun doHook() {
        hookDatabaseMessageCommands()
        registerContextMenuShortcuts()
    }

    private fun isBotEnabled(): Boolean = xprefs.getBoolean(PREF_BOT_ENABLED, true)

    private fun isSelfOnlyMode(): Boolean = xprefs.getBoolean(PREF_BOT_SELF_ONLY, true)

    private fun getPackName(): String =
        xprefs.getString(PREF_BOT_PACK, "WA-NI-ULTRA")?.takeIf { it.isNotBlank() } ?: "WA-NI-ULTRA"

    private fun getAuthorName(): String =
        xprefs.getString(PREF_BOT_AUTHOR, "@NickC137")?.takeIf { it.isNotBlank() } ?: "@NickC137"

    private fun hookDatabaseMessageCommands() {
        SQLiteDatabase::class.java.resolve().method {
            name = "insertWithOnConflict"
        }.hookAll {
            after {
                if (!isBotEnabled()) return@after
                val rowId = (result as? Number)?.toLong() ?: return@after
                if (rowId <= 0L) return@after
                val db = instance as? SQLiteDatabase ?: return@after
                val path = db.path ?: return@after
                if (!path.endsWith("/msgstore.db")) return@after
                val table = args.getOrNull(0) as? String ?: return@after
                if (table != "message") return@after
                val values = args.getOrNull(2) as? ContentValues ?: return@after

                inspectInsertedMessage(db, rowId, values)
            }
        }
    }

    private fun inspectInsertedMessage(db: SQLiteDatabase, rowId: Long, values: ContentValues) {
        val text = values.getAsString("text_data")?.trim().orEmpty()
        if (text.isEmpty()) return
        val firstChar = text[0]
        if (firstChar != '!' && firstChar != '/') return

        val parsed = parseCommand(text) ?: return
        val fromMe = (values.getAsInteger("from_me") ?: 0) == 1
        if (isSelfOnlyMode() && !fromMe) return

        val keyId = values.getAsString("key_id").orEmpty().ifEmpty { "row_$rowId" }
        if (!handledKeys.add(keyId)) return

        val chatRowId = values.getAsLong("chat_row_id") ?: -1L
        val fullFrame = hasFullFrameFlag(text)

        botWorker.execute {
            // Brief delay so WhatsApp finishes committing message_media / message_quoted rows
            Thread.sleep(450L)
            processCommand(db, rowId, chatRowId, parsed, fullFrame)
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
                        fMessage = fMessage
                    )
                }
            }
        }
    }

    private fun processCommand(
        db: SQLiteDatabase,
        rowId: Long,
        chatRowId: Long,
        cmd: BotCmd,
        fullFrame: Boolean
    ) {
        try {
            val chatJid = resolveChatJid(db, chatRowId)
            if (chatJid.isNullOrEmpty() || chatJid == "status@broadcast") return

            if (cmd == BotCmd.HELP) {
                StickerBotConsole.emitFromXposed(Utils.application, "Comando !ajuda recebido em $chatJid")
                WppCore.sendMessageToJid(chatJid, HELP_MESSAGE)
                return
            }

            val mediaPath = resolveMediaFileForCommand(db, rowId)
            if (mediaPath == null || !mediaPath.exists()) {
                StickerBotConsole.emitFromXposed(
                    Utils.application,
                    "Nenhuma foto ou vídeo encontrada na mensagem ou citação."
                )
                Utils.showToast("Envie ou responda uma foto/vídeo com o comando!", Toast.LENGTH_SHORT)
                return
            }

            val isVideo = isVideoFile(mediaPath)
            val wantAudio = (cmd == BotCmd.SOUND_STICKER) && isVideo
            createAndDeliverSticker(
                mediaFile = mediaPath,
                isVideo = isVideo,
                withAudio = wantAudio,
                fullFrame = fullFrame,
                chatRawJid = chatJid,
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
        fMessage: FMessageWpp?
    ) {
        val app = Utils.application
        try {
            fMessage?.let { WppCore.sendReaction("⏳", it.getObject()) }
            StickerBotConsole.emitFromXposed(
                app,
                "Iniciando figurinha (${if (isVideo) "vídeo" else "imagem"}${if (withAudio) " + som" else ""})..."
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
                StickerBotConsole.emitFromXposed(app, "Extraindo áudio Opus 48kHz (máx 10s)...")
                opusFile = AudioOpusConverter.convert(mediaFile.absolutePath, 10_000_000L)
                if (opusFile != null && opusFile.exists() && opusFile.length() > 0) {
                    StickerBotConsole.emitFromXposed(
                        app,
                        "Áudio Opus pronto: ${opusFile.length() / 1024} KB"
                    )
                } else {
                    StickerBotConsole.emitFromXposed(app, "Vídeo sem faixa de áudio audível; enviando só figurinha.")
                    opusFile = null
                }
            }

            // 1. Save into WhatsApp Starred Stickers so it immediately appears in Favorites
            val savedFav = saveToWhatsAppStarredStickers(encoded.webpBytes)
            if (savedFav) {
                StickerBotConsole.emitFromXposed(app, "Figurinha adicionada aos Favoritos do WhatsApp!")
            }

            // 2. Deliver sticker (+ Opus audio if !som) to the target chat
            deliverMediaToChat(encoded.webpBytes, opusFile, chatRawJid)
            opusFile?.delete()

            fMessage?.let { WppCore.sendReaction(if (withAudio) "🔊" else "✅", it.getObject()) }
            Utils.showToast(
                if (savedFav) "Figurinha criada e salva nos Favoritos!" else "Figurinha criada!",
                Toast.LENGTH_SHORT
            )
        } catch (t: Throwable) {
            logDebug(t)
            fMessage?.let { WppCore.sendReaction("❌", it.getObject()) }
            StickerBotConsole.emitFromXposed(app, "Falha ao criar figurinha: ${t.message}")
            Utils.showToast("Erro na figurinha: ${t.message}", Toast.LENGTH_LONG)
        }
    }

    private fun saveToWhatsAppStarredStickers(webpBytes: ByteArray): Boolean {
        return try {
            val dataDir = Utils.getAccountDataDir()
            val stickersDir = File(dataDir, "files/Stickers").apply { mkdirs() }
            val sha256 = MessageDigest.getInstance("SHA-256").digest(webpBytes)
            val plainHash = Base64.encodeToString(sha256, Base64.NO_WRAP)
            val safeFileName = plainHash.replace('/', '-').replace('+', '-') + ".webp"
            val destFile = File(stickersDir, safeFileName)
            destFile.writeBytes(webpBytes)

            val stickersDbFile = File(dataDir, "databases/stickers.db")
            if (!stickersDbFile.exists()) return false

            SQLiteDatabase.openDatabase(
                stickersDbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { sdb ->
                val sql = "INSERT OR REPLACE INTO starred_stickers " +
                    "(plaintext_hash, timestamp, hash_of_image_part, url, enc_hash, direct_path, " +
                    "mimetype, media_key, file_size, width, height, emojis, is_first_party, " +
                    "is_avatar, avatar_template_id, is_fun_sticker, is_lottie) " +
                    "VALUES (?, ?, NULL, NULL, NULL, ?, 'image/webp', NULL, ?, 512, 512, '✨', 0, 0, NULL, 0, 0)"
                runCatching {
                    sdb.execSQL(
                        sql,
                        arrayOf<Any>(
                            plainHash,
                            System.currentTimeMillis(),
                            destFile.absolutePath,
                            webpBytes.size
                        )
                    )
                }.onFailure {
                    // Fallback minimal schema for newer/older stickers.db variants
                    val fallbackSql = "INSERT OR IGNORE INTO starred_stickers " +
                        "(plaintext_hash, timestamp, mimetype, file_size, width, height, is_first_party) " +
                        "VALUES (?, ?, 'image/webp', ?, 512, 512, 0)"
                    sdb.execSQL(
                        fallbackSql,
                        arrayOf<Any>(plainHash, System.currentTimeMillis(), webpBytes.size)
                    )
                }
            }
            true
        } catch (t: Throwable) {
            logDebug("Starred sticker DB insert skipped", t)
            false
        }
    }

    private fun deliverMediaToChat(webpBytes: ByteArray, opusFile: File?, chatRawJid: String?) {
        val app = Utils.application
        val ts = System.currentTimeMillis()
        val stickerUri = exportToMediaStoreDownloads(
            fileName = "sticker_$ts.webp",
            mimeType = "image/webp",
            bytes = webpBytes
        ) ?: return

        val audioUri = if (opusFile != null && opusFile.exists()) {
            exportToMediaStoreDownloads(
                fileName = "sticker_sound_$ts.ogg",
                mimeType = "audio/ogg",
                bytes = opusFile.readBytes()
            )
        } else {
            null
        }

        mainHandler.post {
            try {
                val intent = if (audioUri != null) {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(
                            Intent.EXTRA_STREAM,
                            arrayListOf(stickerUri, audioUri)
                        )
                    }
                } else {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/webp"
                        putExtra(Intent.EXTRA_STREAM, stickerUri)
                    }
                }
                intent.setPackage(app.packageName)
                if (!chatRawJid.isNullOrEmpty()) {
                    intent.putExtra("jid", chatRawJid)
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                app.startActivity(intent)
            } catch (t: Throwable) {
                logDebug(t)
            }
        }
    }

    private fun exportToMediaStoreDownloads(
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Uri? {
        val app = Utils.application
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/WA-NI-ULTRA/Stickers"
                    )
                }
                val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                uri
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "WA-NI-ULTRA/Stickers"
                ).apply { mkdirs() }
                val f = File(dir, fileName)
                f.writeBytes(bytes)
                Uri.fromFile(f)
            }
        } catch (t: Throwable) {
            logDebug(t)
            null
        }
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

    private fun resolveMediaFileForCommand(db: SQLiteDatabase, messageRowId: Long): File? {
        // 1. Check if media is attached directly to this message (caption command)
        queryMediaPathByRowId(db, messageRowId)?.let { return it }

        // 2. Otherwise check quoted message in message_quoted
        val quotedKeyId = runCatching {
            db.rawQuery(
                "SELECT key_id FROM message_quoted WHERE message_row_id = ? LIMIT 1",
                arrayOf(messageRowId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: return null

        val originalRowId = runCatching {
            db.rawQuery(
                "SELECT _id FROM message WHERE key_id = ? ORDER BY _id DESC LIMIT 1",
                arrayOf(quotedKeyId)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            }
        }.getOrDefault(-1L)

        return if (originalRowId > 0L) queryMediaPathByRowId(db, originalRowId) else null
    }

    private fun queryMediaPathByRowId(db: SQLiteDatabase, rowId: Long): File? {
        val relPath = runCatching {
            db.rawQuery(
                "SELECT file_path FROM message_media WHERE message_row_id = ? LIMIT 1",
                arrayOf(rowId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        val direct = File(relPath)
        if (direct.exists()) return direct
        val inRoot = File(WppCore.getRootWhatsAppDir(), relPath)
        if (inRoot.exists()) return inRoot
        return null
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
        const val PREF_BOT_PACK = "sticker_bot_pack"
        const val PREF_BOT_AUTHOR = "sticker_bot_author"

        private val HELP_MESSAGE = """
            *WA-NI-ULTRA — Bot de Figurinhas* ✨
            
            • *!s* ou *!fig* — cria figurinha de foto ou vídeo (até 10s).
            • *!fig inteiro* — mantém a imagem/vídeo inteira sem cortar.
            • *!som* — cria figurinha animada + áudio Opus 48kHz sincronizado.
            • *!ajuda* — mostra esta lista de comandos.
            
            💡 _Dica: você também pode segurar o dedo em qualquer foto/vídeo e tocar em "Figurinha"!_
        """.trimIndent()
    }
}
