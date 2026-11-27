package com.wmods.wppenhacer.utils

import android.content.Context
import android.content.Intent
import com.wmods.wppenhacer.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared live log console for the Integrated Sticker Bot.
 * Receives logs both from local studio runs and via broadcast from the Xposed hook inside WhatsApp.
 */
object StickerBotConsole {

    const val ACTION_BOT_LOG = "${BuildConfig.APPLICATION_ID}.STICKER_BOT_LOG"
    const val EXTRA_LOG_LINE = "log_line"
    private const val MAX_LINES = 150

    private val lines = ArrayList<String>()
    private val listeners = CopyOnWriteArrayList<(List<String>) -> Unit>()

    @Synchronized
    fun append(rawMessage: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formatted = "[$time] $rawMessage"
        lines.add(formatted)
        while (lines.size > MAX_LINES) {
            lines.removeAt(0)
        }
        val snapshot = lines.toList()
        for (listener in listeners) {
            listener(snapshot)
        }
    }

    @Synchronized
    fun appendRaw(formattedLine: String) {
        lines.add(formattedLine)
        while (lines.size > MAX_LINES) {
            lines.removeAt(0)
        }
        val snapshot = lines.toList()
        for (listener in listeners) {
            listener(snapshot)
        }
    }

    fun emitFromXposed(context: Context, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formatted = "[$time] $message"
        appendRaw(formatted)
        runCatching {
            val intent = Intent(ACTION_BOT_LOG).apply {
                setPackage(BuildConfig.APPLICATION_ID)
                putExtra(EXTRA_LOG_LINE, formatted)
            }
            context.sendBroadcast(intent)
        }
    }

    @Synchronized
    fun getAll(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        val snapshot = emptyList<String>()
        for (listener in listeners) {
            listener(snapshot)
        }
    }

    fun addListener(listener: (List<String>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (List<String>) -> Unit) {
        listeners.remove(listener)
    }
}
