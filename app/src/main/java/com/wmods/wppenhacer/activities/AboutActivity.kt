package com.wmods.wppenhacer.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.wmods.wppenhacer.activities.base.BaseActivity
import com.wmods.wppenhacer.databinding.ActivityAboutBinding
import com.wmods.wppenhacer.utils.ConfigBackupManager

class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding

    private val saveDocLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                ConfigBackupManager.writeConfigsToUri(this, uri)
            }
        }

    private val openDocLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                ConfigBackupManager.importFromUri(this, uri, askConfirmation = false) {
                    syncSwitches()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        syncSwitches()

        binding.exportBtn.setOnClickListener {
            ConfigBackupManager.promptExportConfigs(this, saveDocLauncher)
        }

        binding.importBtn.setOnClickListener {
            ConfigBackupManager.launchImportPicker(this, openDocLauncher)
        }

        binding.saveDefaultBtn.setOnClickListener {
            ConfigBackupManager.saveAsPersistentDefault(this)
        }

        binding.resetBtn.setOnClickListener {
            ConfigBackupManager.promptResetConfigs(this) {
                syncSwitches()
            }
        }

        binding.switchWaShare.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(ConfigBackupManager.PREF_ENABLE_WA_SHARE, isChecked) }
            binding.switchAutoImport.isEnabled = isChecked
        }

        binding.switchAutoImport.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(ConfigBackupManager.PREF_AUTO_IMPORT_WA, isChecked) }
        }

        binding.btnTelegram.setOnClickListener { openUrl("https://t.me/NickC137") }
        binding.btnGithub.setOnClickListener { openUrl("https://github.com/NICKZZIN/WA-NI-ULTRA") }
    }

    private fun syncSwitches() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val shareEnabled = prefs.getBoolean(ConfigBackupManager.PREF_ENABLE_WA_SHARE, true)
        val autoImport = prefs.getBoolean(ConfigBackupManager.PREF_AUTO_IMPORT_WA, false)
        binding.switchWaShare.isChecked = shareEnabled
        binding.switchAutoImport.isChecked = autoImport
        binding.switchAutoImport.isEnabled = shareEnabled
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}
