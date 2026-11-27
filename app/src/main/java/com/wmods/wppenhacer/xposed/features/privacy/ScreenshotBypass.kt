package com.wmods.wppenhacer.xposed.features.privacy

import android.app.Activity
import android.content.SharedPreferences
import android.view.Window
import android.view.WindowManager
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.extension.kavaref.hookAll
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore

/**
 * Prevents WhatsApp from applying FLAG_SECURE to any activity or dialog window,
 * allowing screenshots and screen recording on View Once media, profile photos, and locked chats.
 */
class ScreenshotBypass(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    override fun doHook() {
        if (!xprefs.getBoolean("bypass_screenshot_restriction", false)) return

        val flagSecure = WindowManager.LayoutParams.FLAG_SECURE

        Window::class.java.resolve().method {
            name = "addFlags"
        }.hookAll {
            before {
                val flags = args.getOrNull(0) as? Int ?: return@before
                if ((flags and flagSecure) != 0) {
                    args[0] = flags and flagSecure.inv()
                }
            }
        }

        Window::class.java.resolve().method {
            name = "setFlags"
        }.hookAll {
            before {
                val flags = args.getOrNull(0) as? Int ?: return@before
                val mask = args.getOrNull(1) as? Int ?: return@before
                if ((mask and flagSecure) != 0) {
                    args[0] = flags and flagSecure.inv()
                }
            }
        }

        Activity::class.java.resolve().method {
            name = "onResume"
        }.hookAll {
            after {
                clearSecureFlag(instanceOrNull<Activity>(), flagSecure)
            }
        }

        clearSecureFlag(WppCore.getCurrentActivity(), flagSecure)
    }

    private fun clearSecureFlag(activity: Activity?, flagSecure: Int) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        activity.window?.clearFlags(flagSecure)
    }

    override fun getPluginName(): String = "Screenshot Bypass"
}
