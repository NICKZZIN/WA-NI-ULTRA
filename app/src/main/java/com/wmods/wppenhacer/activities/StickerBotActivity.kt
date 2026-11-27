package com.wmods.wppenhacer.activities

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.wmods.wppenhacer.App
import com.wmods.wppenhacer.BuildConfig
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.activities.base.BaseActivity
import com.wmods.wppenhacer.databinding.ActivityStickerBotBinding
import com.wmods.wppenhacer.utils.StickerBotConsole
import com.wmods.wppenhacer.utils.WebpStickerEncoder
import com.wmods.wppenhacer.xposed.core.FeatureLoader
import com.wmods.wppenhacer.xposed.features.others.StickerBotFeature
import com.wmods.wppenhacer.xposed.utils.AudioOpusConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class StickerBotActivity : BaseActivity() {

    private lateinit var binding: ActivityStickerBotBinding

    private val logListener: (List<String>) -> Unit = { lines ->
        runOnUiThread {
            renderLogs(lines)
        }
    }

    private val xposedLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val line = intent.getStringExtra(StickerBotConsole.EXTRA_LOG_LINE) ?: return
            StickerBotConsole.appendRaw(line)
        }
    }

    private val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                convertPickedMediaAndShare(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStickerBotBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        binding.switchBotEnabled.isChecked =
            prefs.getBoolean(StickerBotFeature.PREF_BOT_ENABLED, true)
        val selfOnly = prefs.getBoolean(StickerBotFeature.PREF_BOT_SELF_ONLY, false)
        val allowPrivate = prefs.getBoolean(StickerBotFeature.PREF_BOT_ALLOW_PRIVATE, true)
        val allowGroups = prefs.getBoolean(StickerBotFeature.PREF_BOT_ALLOW_GROUPS, false)
        binding.switchSelfOnly.isChecked = selfOnly
        binding.switchAllowPrivate.isChecked = !selfOnly && allowPrivate
        binding.switchAllowGroups.isChecked = !selfOnly && allowGroups
        binding.switchContextMenuShortcut.isChecked =
            prefs.getBoolean(StickerBotFeature.PREF_BOT_CONTEXT_SHORTCUT, true)
        binding.editPackName.setText(
            prefs.getString(StickerBotFeature.PREF_BOT_PACK, "WA-NI-ULTRA")
        )
        binding.editAuthorName.setText(
            prefs.getString(StickerBotFeature.PREF_BOT_AUTHOR, "@NickC137")
        )
        binding.editSelfPhone.setText(prefs.getString(PREF_SAVED_SELF_PHONE, "+55"))

        var updatingSwitches = false

        binding.switchBotEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean(StickerBotFeature.PREF_BOT_ENABLED, checked) }
            StickerBotConsole.append(
                if (checked) "Bot integrado ATIVADO (0% bateria em espera)."
                else "Bot integrado DESATIVADO."
            )
            notifyPrefsUpdated()
        }

        binding.switchSelfOnly.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            updatingSwitches = true
            if (checked) {
                binding.switchAllowPrivate.isChecked = false
                binding.switchAllowGroups.isChecked = false
                prefs.edit {
                    putBoolean(StickerBotFeature.PREF_BOT_SELF_ONLY, true)
                    putBoolean(StickerBotFeature.PREF_BOT_ALLOW_PRIVATE, false)
                    putBoolean(StickerBotFeature.PREF_BOT_ALLOW_GROUPS, false)
                }
                StickerBotConsole.append("Modo Só Eu ativado: respondendo apenas aos seus próprios comandos.")
            } else {
                binding.switchAllowPrivate.isChecked = true
                prefs.edit {
                    putBoolean(StickerBotFeature.PREF_BOT_SELF_ONLY, false)
                    putBoolean(StickerBotFeature.PREF_BOT_ALLOW_PRIVATE, true)
                }
                StickerBotConsole.append("Modo Privado ativado: contatos podem usar comandos no seu chat privado.")
            }
            updatingSwitches = false
            notifyPrefsUpdated()
        }

        binding.switchAllowPrivate.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            updatingSwitches = true
            if (checked) {
                binding.switchSelfOnly.isChecked = false
                prefs.edit {
                    putBoolean(StickerBotFeature.PREF_BOT_SELF_ONLY, false)
                    putBoolean(StickerBotFeature.PREF_BOT_ALLOW_PRIVATE, true)
                }
                StickerBotConsole.append("Outras pessoas agora podem usar /fig, /figsom e /limites no seu chat privado!")
            } else {
                val groupsActive = binding.switchAllowGroups.isChecked
                binding.switchSelfOnly.isChecked = !groupsActive
                prefs.edit {
                    putBoolean(StickerBotFeature.PREF_BOT_ALLOW_PRIVATE, false)
                    putBoolean(StickerBotFeature.PREF_BOT_SELF_ONLY, !groupsActive)
                }
                StickerBotConsole.append("Comandos de outras pessoas no privado desativados.")
            }
            updatingSwitches = false
            notifyPrefsUpdated()
        }

        binding.switchAllowGroups.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            updatingSwitches = true
            if (checked) {
                binding.switchSelfOnly.isChecked = false
                prefs.edit {
                    putBoolean(StickerBotFeature.PREF_BOT_SELF_ONLY, false)
                    putBoolean(StickerBotFeature.PREF_BOT_ALLOW_GROUPS, true)
                }
                StickerBotConsole.append("Comandos de outras pessoas em grupos ATIVADOS.")
            } else {
                val privActive = binding.switchAllowPrivate.isChecked
                binding.switchSelfOnly.isChecked = !privActive
                prefs.edit {
                    putBoolean(StickerBotFeature.PREF_BOT_ALLOW_GROUPS, false)
                    putBoolean(StickerBotFeature.PREF_BOT_SELF_ONLY, !privActive)
                }
                StickerBotConsole.append("Comandos de outras pessoas em grupos desativados.")
            }
            updatingSwitches = false
            notifyPrefsUpdated()
        }

        binding.switchContextMenuShortcut.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean(StickerBotFeature.PREF_BOT_CONTEXT_SHORTCUT, checked) }
            StickerBotConsole.append(
                if (checked) "Botão 'Figurinha' ao segurar foto/vídeo no WhatsApp ATIVADO."
                else "Botão 'Figurinha' ao segurar foto/vídeo no WhatsApp DESATIVADO (apenas comandos ativos)."
            )
            notifyPrefsUpdated()
        }

        setupTerminalScrollTouch()

        binding.editPackName.addTextChangedListener(SimpleTextWatcher { value ->
            prefs.edit { putString(StickerBotFeature.PREF_BOT_PACK, value) }
        })

        binding.editAuthorName.addTextChangedListener(SimpleTextWatcher { value ->
            prefs.edit { putString(StickerBotFeature.PREF_BOT_AUTHOR, value) }
        })

        binding.editSelfPhone.addTextChangedListener(SimpleTextWatcher { value ->
            prefs.edit { putString(PREF_SAVED_SELF_PHONE, value) }
        })

        binding.btnOpenWaMe.setOnClickListener {
            val digits = binding.editSelfPhone.text?.toString().orEmpty()
                .filter { it.isDigit() }
            if (digits.length < 10) {
                Toast.makeText(this, R.string.sticker_bot_phone_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            StickerBotConsole.append("Abrindo chat seguro wa.me/$digits...")
            val waIntent = Intent(Intent.ACTION_VIEW, "https://wa.me/$digits".toUri())
            startActivity(waIntent)
        }

        binding.btnPickMediaCreate.setOnClickListener {
            try {
                pickMediaLauncher.launch("*/*")
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnClearTerminal.setOnClickListener {
            StickerBotConsole.clear()
            StickerBotConsole.append("Console limpo. Pronto.")
        }

        binding.btnCopyTerminal.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("WA-NI-ULTRA Bot Logs", binding.tvTerminalOutput.text)
            )
            Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        ContextCompat.registerReceiver(
            this,
            xposedLogReceiver,
            IntentFilter(StickerBotConsole.ACTION_BOT_LOG),
            ContextCompat.RECEIVER_EXPORTED
        )

        if (StickerBotConsole.getAll().isEmpty()) {
            StickerBotConsole.append("WA-NI-ULTRA Bot pronto — integrado via Xposed (sem parear, 0% bateria parado).")
            StickerBotConsole.append("Comandos no WhatsApp: /fig (foto/vídeo/GIF), /figsom (fig com áudio) e /limites.")
        }
        StickerBotConsole.addListener(logListener)
        renderLogs(StickerBotConsole.getAll())
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTerminalScrollTouch() {
        val disallowParentIntercept = View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
        binding.terminalScroll.setOnTouchListener(disallowParentIntercept)
        binding.tvTerminalOutput.setOnTouchListener(disallowParentIntercept)
    }

    private fun renderLogs(lines: List<String>) {
        binding.tvTerminalOutput.text = lines.joinToString("\n")
        binding.terminalScroll.post {
            binding.terminalScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun convertPickedMediaAndShare(uri: Uri) {
        val withSound = binding.switchWithSound.isChecked
        val fullFrame = binding.switchFullFrame.isChecked
        val pack = binding.editPackName.text?.toString().orEmpty().ifBlank { "WA-NI-ULTRA" }
        val author = binding.editAuthorName.text?.toString().orEmpty().ifBlank { "@NickC137" }

        binding.btnPickMediaCreate.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            var tempInput: File? = null
            var opusOut: File? = null
            try {
                val mime = contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
                val isGif = mime == "image/gif"
                val isVideo = mime.startsWith("video/") || isGif
                val ext = when {
                    isGif -> "gif"
                    isVideo -> "mp4"
                    else -> "jpg"
                }
                val workDir = File(cacheDir, "sticker_studio").apply { mkdirs() }
                tempInput = File(workDir, "input_${System.currentTimeMillis()}.$ext")

                contentResolver.openInputStream(uri)?.use { input ->
                    tempInput.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Não foi possível ler o arquivo selecionado.")

                StickerBotConsole.append("Mídia carregada (${tempInput.length() / 1024} KB) — tipo: ${if (isGif) "GIF" else if (isVideo) "Vídeo" else "Imagem"}")

                val encoded = if (isVideo) {
                    WebpStickerEncoder.createAnimatedSticker(tempInput, fullFrame, pack, author) {
                        StickerBotConsole.append(it)
                    }
                } else {
                    WebpStickerEncoder.createStaticSticker(tempInput, fullFrame, pack, author) {
                        StickerBotConsole.append(it)
                    }
                }

                val sharedDir = runCatching {
                    File(App.waEnhancerFolder, "Stickers").apply { mkdirs() }
                }.getOrNull()?.takeIf { it.exists() && it.canWrite() } ?: workDir

                val ts = System.currentTimeMillis()
                var finalWebpBytes = encoded.webpBytes

                if (isVideo && !isGif && withSound) {
                    StickerBotConsole.append("Convertendo áudio para Ogg/Opus 48kHz (máx 5s animada)...")
                    val converted = AudioOpusConverter.convert(
                        tempInput.absolutePath,
                        WebpStickerEncoder.DEFAULT_SOUND_ANIM_DURATION_MS * 1000L,
                        workDir
                    )
                    if (converted != null && converted.exists() && converted.length() > 0) {
                        val sharedOpus = File(sharedDir, "figurinha_som_$ts.opus")
                        converted.copyTo(sharedOpus, overwrite = true)
                        converted.delete()
                        opusOut = sharedOpus
                        finalWebpBytes = WebpStickerEncoder.tagSoundSticker(
                            finalWebpBytes,
                            sharedOpus.readBytes(),
                            pack,
                            author
                        )
                        StickerBotConsole.append("Áudio Opus gerado: ${sharedOpus.length() / 1024} KB (SHA-256 anexado ao EXIF)")
                    } else {
                        StickerBotConsole.append("Vídeo sem áudio; enviando figurinha animada.")
                        opusOut = null
                    }
                }

                val outWebp = File(sharedDir, "figurinha_$ts.webp")
                outWebp.writeBytes(finalWebpBytes)

                val targetPhone = withContext(Dispatchers.Main) {
                    binding.editSelfPhone.text?.toString().orEmpty()
                }

                withContext(Dispatchers.Main) {
                    sendToWhatsAppInBackground(outWebp, opusOut, targetPhone)
                }
            } catch (e: Throwable) {
                StickerBotConsole.append("Erro: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StickerBotActivity, e.message, Toast.LENGTH_LONG).show()
                }
            } finally {
                tempInput?.delete()
                withContext(Dispatchers.Main) {
                    binding.btnPickMediaCreate.isEnabled = true
                }
            }
        }
    }

    private fun sendToWhatsAppInBackground(webpFile: File, opusFile: File?, targetPhone: String) {
        val intent = Intent(StickerBotFeature.ACTION_STUDIO_SEND_STICKER).apply {
            putExtra(StickerBotFeature.EXTRA_WEBP_PATH, webpFile.absolutePath)
            if (opusFile != null && opusFile.exists()) {
                putExtra(StickerBotFeature.EXTRA_OPUS_PATH, opusFile.absolutePath)
            }
            putExtra(StickerBotFeature.EXTRA_TARGET_PHONE, targetPhone)
        }
        sendBroadcast(intent)
        StickerBotConsole.append(
            "Enviado via Xposed para o WhatsApp (salvo nos Favoritos${if (opusFile != null) " + áudio Opus" else ""})!"
        )
        Toast.makeText(
            this,
            "Figurinha enviada para os Favoritos e conversa no WhatsApp!",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun notifyPrefsUpdated() {
        App.instance.sendBroadcast(Intent("${BuildConfig.APPLICATION_ID}.PREFS_CHANGED"))
    }

    override fun onDestroy() {
        StickerBotConsole.removeListener(logListener)
        runCatching { unregisterReceiver(xposedLogReceiver) }
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private class SimpleTextWatcher(private val onChanged: (String) -> Unit) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            onChanged(s?.toString().orEmpty())
        }
    }

    companion object {
        private const val PREF_SAVED_SELF_PHONE = "sticker_bot_self_phone"
    }
}
