package com.wmods.wppenhacer.activities

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
        binding.switchSelfOnly.isChecked =
            prefs.getBoolean(StickerBotFeature.PREF_BOT_SELF_ONLY, true)
        binding.editPackName.setText(
            prefs.getString(StickerBotFeature.PREF_BOT_PACK, "WA-NI-ULTRA")
        )
        binding.editAuthorName.setText(
            prefs.getString(StickerBotFeature.PREF_BOT_AUTHOR, "@NickC137")
        )
        binding.editSelfPhone.setText(prefs.getString(PREF_SAVED_SELF_PHONE, "+55"))

        binding.switchBotEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean(StickerBotFeature.PREF_BOT_ENABLED, checked) }
            StickerBotConsole.append(
                if (checked) "Bot integrado ATIVADO (0% bateria em espera)."
                else "Bot integrado DESATIVADO."
            )
            notifyPrefsUpdated()
        }

        binding.switchSelfOnly.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean(StickerBotFeature.PREF_BOT_SELF_ONLY, checked) }
            StickerBotConsole.append(
                if (checked) "Modo Anti-Ban ativo: respondendo apenas suas próprias mensagens."
                else "Modo aberto ativo: respondendo em qualquer conversa."
            )
            notifyPrefsUpdated()
        }

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
            StickerBotConsole.append("Use !s, !fig ou !som no WhatsApp, ou escolha uma mídia acima.")
        }
        StickerBotConsole.addListener(logListener)
        renderLogs(StickerBotConsole.getAll())
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
                val isVideo = mime.startsWith("video/") || mime == "image/gif"
                val ext = if (isVideo) "mp4" else "jpg"
                val workDir = File(cacheDir, "sticker_studio").apply { mkdirs() }
                tempInput = File(workDir, "input_${System.currentTimeMillis()}.$ext")

                contentResolver.openInputStream(uri)?.use { input ->
                    tempInput.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Não foi possível ler o arquivo selecionado.")

                StickerBotConsole.append("Mídia carregada (${tempInput.length() / 1024} KB) — tipo: ${if (isVideo) "Vídeo/GIF" else "Imagem"}")

                val encoded = if (isVideo) {
                    WebpStickerEncoder.createAnimatedSticker(tempInput, fullFrame, pack, author) {
                        StickerBotConsole.append(it)
                    }
                } else {
                    WebpStickerEncoder.createStaticSticker(tempInput, fullFrame, pack, author) {
                        StickerBotConsole.append(it)
                    }
                }

                val outWebp = File(workDir, "figurinha_${System.currentTimeMillis()}.webp")
                outWebp.writeBytes(encoded.webpBytes)

                if (isVideo && withSound) {
                    StickerBotConsole.append("Convertendo áudio para Ogg/Opus 48kHz (máx 10s)...")
                    opusOut = AudioOpusConverter.convert(tempInput.absolutePath, 10_000_000L)
                    if (opusOut != null && opusOut.exists() && opusOut.length() > 0) {
                        StickerBotConsole.append("Áudio Opus gerado: ${opusOut.length() / 1024} KB")
                    } else {
                        StickerBotConsole.append("Vídeo sem áudio; enviando figurinha animada.")
                        opusOut = null
                    }
                }

                val stickerUri = FileProvider.getUriForFile(
                    this@StickerBotActivity,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    outWebp
                )
                val audioUri = opusOut?.let {
                    FileProvider.getUriForFile(
                        this@StickerBotActivity,
                        "${BuildConfig.APPLICATION_ID}.fileprovider",
                        it
                    )
                }

                withContext(Dispatchers.Main) {
                    shareGeneratedToWhatsApp(stickerUri, audioUri)
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

    private fun shareGeneratedToWhatsApp(stickerUri: Uri, audioUri: Uri?) {
        val sendIntent = if (audioUri != null) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(stickerUri, audioUri))
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/webp"
                putExtra(Intent.EXTRA_STREAM, stickerUri)
            }
        }
        sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            packageManager.getPackageInfo(FeatureLoader.PACKAGE_WPP, 0)
            sendIntent.setPackage(FeatureLoader.PACKAGE_WPP)
        }
        StickerBotConsole.append("Abrindo envio para o WhatsApp...")
        startActivity(Intent.createChooser(sendIntent, getString(R.string.sticker_bot_title)))
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
