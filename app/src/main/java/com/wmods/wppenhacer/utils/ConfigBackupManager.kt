package com.wmods.wppenhacer.utils

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wmods.wppenhacer.App
import com.wmods.wppenhacer.BuildConfig
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.FeatureLoader
import org.json.JSONArray
import org.json.JSONObject
import rikka.core.util.IOUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles exporting, sharing via WhatsApp, importing (file picker & incoming WhatsApp intent),
 * persistent device default snapshots (surviving app reinstall), and reset confirmation flows.
 */
object ConfigBackupManager {

    const val PREF_ENABLE_WA_SHARE = "enable_whatsapp_config_share"
    const val PREF_AUTO_IMPORT_WA = "auto_import_whatsapp_config"
    private const val PREF_INITIALIZED = "__initialized_prefs"
    private const val PREF_PROMPTED_INITIAL_RESTORE = "__prompted_initial_restore"
    private const val CONFIG_MARKER_KEY = "__wa_ni_ultra_config"

    @Volatile
    private var promptedStorageThisSession = false

    private val INTERNAL_KEYS = setOf(
        PREF_INITIALIZED,
        PREF_PROMPTED_INITIAL_RESTORE,
        CONFIG_MARKER_KEY
    )

    private fun persistentExternalFile(): File {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(File(docs, "WA-NI-ULTRA"), "default_config.json")
    }

    private fun persistentDownloadFallbackFile(): File {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(File(dl, "WaEnhancer"), "default_config.json")
    }

    private fun findExternalPersistentDefaultFile(): File? {
        val candidates = listOf(
            persistentExternalFile(),
            persistentDownloadFallbackFile()
        )
        for (f in candidates) {
            if (runCatching { f.exists() && f.canRead() && f.length() > 2 }.getOrDefault(false)) {
                return f
            }
        }
        // Also check latest WA-NI-ULTRA_config_*.json in Documents/WA-NI-ULTRA or Download/WaEnhancer or Download
        val searchDirs = listOfNotNull(
            runCatching { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "WA-NI-ULTRA") }.getOrNull(),
            runCatching { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WaEnhancer") }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
        )
        for (dir in searchDirs) {
            val latest = runCatching {
                dir.listFiles { file ->
                    file.isFile &&
                        file.name.startsWith("WA-NI-ULTRA_config_", ignoreCase = true) &&
                        file.name.endsWith(".json", ignoreCase = true) &&
                        file.length() > 2
                }?.maxByOrNull { it.lastModified() }
            }.getOrNull()
            if (latest != null) return latest
        }
        return null
    }

    private fun internalFallbackFile(context: Context): File =
        File(context.filesDir, "default_config.json")

    fun hasStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasPersistentDefault(context: Context): Boolean {
        if (findExternalPersistentDefaultFile() != null) return true
        val internal = internalFallbackFile(context)
        return internal.exists() && internal.length() > 2
    }

    /**
     * Checks storage access on app launch/resume:
     * 1) If All Files Access is not granted yet, requests it once per session.
     * 2) Once storage permission is granted, checks once per install if a saved configuration
     *    backup exists on the device and asks the user if they want to restore it.
     */
    fun checkInitialStorageAndBackupRestore(activity: Activity, onRestored: (() -> Unit)? = null) {
        if (!hasStorageAccess(activity)) {
            if (!promptedStorageThisSession) {
                promptedStorageThisSession = true
                App.showRequestStoragePermission(activity)
            }
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        if (prefs.getBoolean(PREF_PROMPTED_INITIAL_RESTORE, false)) return

        prefs.edit {
            putBoolean(PREF_PROMPTED_INITIAL_RESTORE, true)
            putBoolean(PREF_INITIALIZED, true)
        }

        val backupFile = findExternalPersistentDefaultFile()
            ?: internalFallbackFile(activity).takeIf { it.exists() && it.canRead() && it.length() > 2 }
            ?: return

        val jsonText = runCatching { backupFile.readText(Charsets.UTF_8) }.getOrNull() ?: return
        val parsedEntries = runCatching { parseConfigEntries(JSONObject(jsonText)) }.getOrNull()
        if (parsedEntries.isNullOrEmpty()) return

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.restore_found_backup_title)
            .setMessage(R.string.restore_found_backup_message)
            .setPositiveButton(R.string.restore_now) { _, _ ->
                writeEntriesToPrefs(prefs, parsedEntries, preserveAutoImport = null)
                prefs.edit {
                    putBoolean(PREF_PROMPTED_INITIAL_RESTORE, true)
                    putBoolean(PREF_INITIALIZED, true)
                }
                restartWhatsAppApps()
                Toast.makeText(activity, R.string.default_config_restored, Toast.LENGTH_LONG).show()
                onRestored?.invoke()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    fun saveAsPersistentDefault(activity: Activity) {
        if (!hasStorageAccess(activity)) {
            App.showRequestStoragePermission(activity)
            return
        }
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            val jsonText = serializePrefsToJson(prefs).toString(4)

            val extFile = persistentExternalFile()
            extFile.parentFile?.mkdirs()
            extFile.writeText(jsonText, Charsets.UTF_8)
            runCatching {
                val dlFile = persistentDownloadFallbackFile()
                dlFile.parentFile?.mkdirs()
                dlFile.writeText(jsonText, Charsets.UTF_8)
            }
            internalFallbackFile(activity).writeText(jsonText, Charsets.UTF_8)
            prefs.edit {
                putBoolean(PREF_INITIALIZED, true)
                putBoolean(PREF_PROMPTED_INITIAL_RESTORE, true)
            }

            Toast.makeText(activity, R.string.default_config_saved, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(activity, e.message ?: "Error", Toast.LENGTH_SHORT).show()
        }
    }

    fun promptExportConfigs(activity: Activity, saveLauncher: ActivityResultLauncher<String>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val allowWaShare = prefs.getBoolean(PREF_ENABLE_WA_SHARE, true)
        if (!allowWaShare) {
            launchSavePicker(activity, saveLauncher)
            return
        }

        val options = arrayOf(
            activity.getString(R.string.export_save_device),
            activity.getString(R.string.export_share_whatsapp)
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.export_choose_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchSavePicker(activity, saveLauncher)
                    1 -> shareConfigWithWhatsApp(activity)
                }
            }
            .show()
    }

    private fun buildTimestampedFileName(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        return "WA-NI-ULTRA_config_${dateFormat.format(Date())}.json"
    }

    private fun launchSavePicker(activity: Activity, saveLauncher: ActivityResultLauncher<String>) {
        try {
            saveLauncher.launch(buildTimestampedFileName())
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.no_file_picker, Toast.LENGTH_LONG).show()
        }
    }

    fun writeConfigsToUri(activity: Activity, uri: Uri) {
        try {
            activity.contentResolver.openOutputStream(uri)?.use { output ->
                val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
                val json = serializePrefsToJson(prefs)
                output.write(json.toString(4).toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(activity, R.string.configs_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareConfigWithWhatsApp(activity: Activity) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            val json = serializePrefsToJson(prefs)
            val shareDir = File(activity.cacheDir, "shared_configs").apply { mkdirs() }
            val outFile = File(shareDir, buildTimestampedFileName())
            outFile.writeText(json.toString(4), Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(
                activity,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                outFile
            )

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val targetPkg = when {
                    isPackageInstalled(activity, FeatureLoader.PACKAGE_WPP) -> FeatureLoader.PACKAGE_WPP
                    isPackageInstalled(activity, FeatureLoader.PACKAGE_BUSINESS) -> FeatureLoader.PACKAGE_BUSINESS
                    else -> null
                }
                if (targetPkg != null) setPackage(targetPkg)
            }

            activity.startActivity(
                Intent.createChooser(sendIntent, activity.getString(R.string.export_share_whatsapp))
            )
        } catch (e: Exception) {
            Toast.makeText(activity, e.message ?: "Error", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchImportPicker(activity: Activity, openLauncher: ActivityResultLauncher<Array<String>>) {
        try {
            openLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.no_file_picker, Toast.LENGTH_LONG).show()
        }
    }

    fun handleIncomingFileIntent(activity: Activity, uri: Uri, onImported: (() -> Unit)? = null) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        if (!prefs.getBoolean(PREF_ENABLE_WA_SHARE, true)) return

        val autoImport = prefs.getBoolean(PREF_AUTO_IMPORT_WA, false)
        importFromUri(activity, uri, askConfirmation = !autoImport, onImported = onImported)
    }

    fun importFromUri(
        activity: Activity,
        uri: Uri,
        askConfirmation: Boolean = false,
        onImported: (() -> Unit)? = null
    ) {
        val jsonText = try {
            activity.contentResolver.openInputStream(uri)?.use { IOUtils.toString(it) }
        } catch (e: Exception) {
            Toast.makeText(activity, e.message, Toast.LENGTH_SHORT).show()
            return
        } ?: return

        // Validate JSON before prompting or importing
        val parsedEntries = runCatching { parseConfigEntries(JSONObject(jsonText)) }.getOrNull()
        if (parsedEntries == null || parsedEntries.isEmpty()) {
            Toast.makeText(activity, R.string.invalid_zip_file, Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        if (!askConfirmation) {
            applyParsedEntries(activity, prefs, parsedEntries)
            onImported?.invoke()
            return
        }

        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val autoCheck = CheckBox(activity).apply {
            setText(R.string.always_auto_import)
            isChecked = prefs.getBoolean(PREF_AUTO_IMPORT_WA, false)
        }
        container.addView(autoCheck)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.import_confirm_title)
            .setMessage(R.string.import_confirm_message)
            .setView(container)
            .setPositiveButton(R.string.import_action) { _, _ ->
                val keepAuto = autoCheck.isChecked
                applyParsedEntries(activity, prefs, parsedEntries, preserveAutoImport = keepAuto)
                onImported?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun promptResetConfigs(activity: Activity, onResetDone: (() -> Unit)? = null) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        if (hasPersistentDefault(activity)) {
            val options = arrayOf(
                activity.getString(R.string.reset_to_saved_default),
                activity.getString(R.string.reset_to_factory)
            )
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.reset_confirm_title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val file = findExternalPersistentDefaultFile() ?: internalFallbackFile(activity)
                            runCatching {
                                val text = file.readText(Charsets.UTF_8)
                                applyJsonStringToPrefs(prefs, text)
                                prefs.edit {
                                    putBoolean(PREF_INITIALIZED, true)
                                    putBoolean(PREF_PROMPTED_INITIAL_RESTORE, true)
                                }
                                restartWhatsAppApps()
                                Toast.makeText(activity, R.string.default_config_restored, Toast.LENGTH_SHORT).show()
                                onResetDone?.invoke()
                            }.onFailure {
                                Toast.makeText(activity, it.message, Toast.LENGTH_SHORT).show()
                            }
                        }

                        1 -> {
                            clearAllToFactory(activity, prefs)
                            onResetDone?.invoke()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.reset_confirm_title)
                .setMessage(R.string.reset_confirm_simple_message)
                .setPositiveButton(R.string.reset_settings) { _, _ ->
                    clearAllToFactory(activity, prefs)
                    onResetDone?.invoke()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun clearAllToFactory(context: Context, prefs: SharedPreferences) {
        prefs.edit {
            prefs.all.keys.toList().forEach { remove(it) }
            putBoolean(PREF_INITIALIZED, true)
            putBoolean(PREF_PROMPTED_INITIAL_RESTORE, true)
        }
        restartWhatsAppApps()
        Toast.makeText(context, R.string.configs_reset, Toast.LENGTH_SHORT).show()
    }

    private fun restartWhatsAppApps() {
        App.instance.restartApp(FeatureLoader.PACKAGE_WPP)
        App.instance.restartApp(FeatureLoader.PACKAGE_BUSINESS)
    }

    private fun serializePrefsToJson(prefs: SharedPreferences): JSONObject {
        val jsonObject = JSONObject()
        jsonObject.put(CONFIG_MARKER_KEY, JSONObject().apply {
            put("type", "Boolean")
            put("value", true)
        })
        for ((key, value) in prefs.all) {
            if (key in INTERNAL_KEYS || value == null) continue
            val entry = JSONObject()
            val serializedValue: Any = if (value is Set<*>) {
                JSONArray(ArrayList(value))
            } else {
                value
            }
            entry.put("type", value.javaClass.simpleName)
            entry.put("value", serializedValue)
            jsonObject.put(key, entry)
        }
        return jsonObject
    }

    private fun applyJsonStringToPrefs(prefs: SharedPreferences, jsonText: String) {
        val entries = parseConfigEntries(JSONObject(jsonText))
        writeEntriesToPrefs(prefs, entries, preserveAutoImport = null)
    }

    private fun applyParsedEntries(
        context: Context,
        prefs: SharedPreferences,
        entries: Map<String, Any>,
        preserveAutoImport: Boolean? = null
    ) {
        writeEntriesToPrefs(prefs, entries, preserveAutoImport)
        restartWhatsAppApps()
        Toast.makeText(context, R.string.configs_imported, Toast.LENGTH_SHORT).show()
    }

    private fun writeEntriesToPrefs(
        prefs: SharedPreferences,
        entries: Map<String, Any>,
        preserveAutoImport: Boolean?
    ) {
        prefs.edit {
            prefs.all.keys.toList().forEach { key ->
                if (key !in INTERNAL_KEYS) remove(key)
            }
            putBoolean(PREF_INITIALIZED, true)
            putBoolean(PREF_PROMPTED_INITIAL_RESTORE, true)
            for ((key, value) in entries) {
                if (key in INTERNAL_KEYS) continue
                when (value) {
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        putStringSet(key, value as Set<String>)
                    }

                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    else -> putString(key, value.toString())
                }
            }
            if (preserveAutoImport != null) {
                putBoolean(PREF_AUTO_IMPORT_WA, preserveAutoImport)
            }
        }
    }

    private fun parseConfigEntries(json: JSONObject): Map<String, Any> {
        val result = LinkedHashMap<String, Any>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in INTERNAL_KEYS) continue
            var raw: Any? = json.opt(key)
            var type: String? = null
            if (raw is JSONObject) {
                type = raw.optString("type").ifEmpty { null }
                raw = raw.opt("value")
            }
            if (raw == null || raw == JSONObject.NULL) continue
            val parsed = runCatching { convertConfigValue(type, raw) }.getOrNull() ?: continue
            result[key] = parsed
        }
        return result
    }

    private fun convertConfigValue(type: String?, raw: Any): Any? {
        if (raw is JSONArray) {
            val set = LinkedHashSet<String>()
            for (i in 0 until raw.length()) set.add(raw.getString(i))
            return set
        }
        val text = raw.toString()
        return when (type?.lowercase(Locale.ROOT)) {
            "boolean" -> raw as? Boolean ?: text.toBooleanStrictOrNull()
            "integer", "int" -> (raw as? Number)?.toInt() ?: text.toIntOrNull()
            "long" -> (raw as? Number)?.toLong() ?: text.toLongOrNull()
            "float", "double" -> (raw as? Number)?.toFloat() ?: text.toFloatOrNull()
            "string" -> text
            else -> when (raw) {
                is Boolean -> raw
                is Int -> raw
                is Long -> raw
                is Number -> raw.toFloat()
                else -> text
            }
        }
    }

    private fun isPackageInstalled(context: Context, pkg: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        }.getOrDefault(false)
    }
}
