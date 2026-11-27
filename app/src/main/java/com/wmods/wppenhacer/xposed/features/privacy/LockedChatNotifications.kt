package com.wmods.wppenhacer.xposed.features.privacy

import android.app.Notification
import android.app.NotificationManager
import android.content.SharedPreferences
import android.os.Build
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.extension.kavaref.hookAll
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.db.MessageStore
import java.util.Locale

/**
 * Suppresses Android message notifications belonging to WhatsApp locked chats.
 * Works independently of LockedChatsEnhancer by inspecting NotificationManager.notify
 * and matching the notification shortcutId (chat JID) against locked chats in msgstore.db.
 */
class LockedChatNotifications(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    @Volatile
    private var cachedLockedIds: Set<String>? = null

    @Volatile
    private var loadedAtMs: Long = Long.MIN_VALUE

    @Volatile
    private var lastLoadFailed: Boolean = false

    private val cacheLock = Any()

    override fun doHook() {
        if (!xprefs.getBoolean(PREF_KEY, false)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        NotificationManager::class.java.resolve().method {
            name = "notify"
        }.hookAll {
            before {
                if (shouldDrop(args)) {
                    result = null
                }
            }
        }
    }

    private fun shouldDrop(args: Array<Any?>?): Boolean {
        if (!xprefs.getBoolean(PREF_KEY, false) || args.isNullOrEmpty()) return false
        return try {
            val notification = args.firstOrNull { it is Notification } as? Notification ?: return false
            val shortcutId = notification.shortcutId?.trim()?.lowercase(Locale.ROOT)
            if (shortcutId.isNullOrEmpty()) return false
            val lockedIds = getCurrentLockedIds() ?: return false
            lockedIds.contains(shortcutId)
        } catch (t: Throwable) {
            logDebug(t)
            false
        }
    }

    private fun getCurrentLockedIds(): Set<String>? {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val age = now - loadedAtMs
            val ttl = if (lastLoadFailed) RETRY_AFTER_FAILURE_MS else FRESH_MS
            if (loadedAtMs != Long.MIN_VALUE && age in 0 until ttl) {
                return cachedLockedIds
            }
            loadedAtMs = now
            val loaded = MessageStore.getInstance().queryLockedChatJids()
            lastLoadFailed = loaded == null
            if (loaded != null && loaded == cachedLockedIds) {
                return cachedLockedIds
            }
            cachedLockedIds = loaded
            return cachedLockedIds
        }
    }

    override fun getPluginName(): String = "Locked Chat Notifications"

    companion object {
        const val PREF_KEY = "hide_locked_chat_notifications"
        private const val FRESH_MS = 1_500L
        private const val RETRY_AFTER_FAILURE_MS = 60_000L
    }
}
