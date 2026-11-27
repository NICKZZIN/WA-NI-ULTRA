package com.wmods.wppenhacer.xposed.features.general

import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.db.MessageHistoryStore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

class CaptureDevice(classLoader: ClassLoader, xprefs: SharedPreferences) : Feature(
    classLoader,
    xprefs,
) {

    @Volatile
    private var deviceJidClass: Class<*>? = null

    enum class DeviceType(val value: Int) {
        PHONE(0),
        LINKED_DEVICE(1);

        companion object {
            fun fromInt(value: Int): DeviceType {
                return if (value == PHONE.value) PHONE else LINKED_DEVICE
            }
        }
    }

    override fun doHook() {
        if (!xprefs.getBoolean("capture_device", false)) return

        resolveDeviceJidClass()

        val handlePlaintextMethod =
            Unobfuscator.loadSharedMessageProcessorHandlePlaintextMethod(classLoader)
        handlePlaintextMethod.hook {
            after {
                try {
                    captureDeviceInfo(args)
                } catch (t: Throwable) {
                    logDebug(t)
                }
            }
        }

        ConversationItemListener.conversationListeners.add(
            object : ConversationItemListener.OnConversationItemListener() {
                override fun onItemBind(
                    fMessage: FMessageWpp,
                    view: ViewGroup,
                    position: Int,
                    convertView: View?
                ) {
                    updateDeviceIndicator(fMessage, view)
                }
            }
        )
    }

    private fun resolveDeviceJidClass(): Class<*>? {
        deviceJidClass?.let { return it }
        val resolved = runCatching {
            classLoader.loadClass("com.whatsapp.jid.DeviceJid")
        }.getOrNull() ?: runCatching {
            Unobfuscator.findFirstClassUsingName(
                classLoader,
                StringMatchType.EndsWith,
                "jid.DeviceJid"
            )
        }.getOrNull()
        deviceJidClass = resolved
        return resolved
    }

    private fun captureDeviceInfo(args: Array<Any?>?) {
        if (args.isNullOrEmpty()) return

        val fMessage = findFMessage(args) ?: return
        val userjid = fMessage.key.remoteJid.userRawString ?: return
        val messageId = fMessage.key.messageID
        if (messageId.isEmpty()) return

        val device = extractDeviceIdFromArgs(args, fMessage)
        if (device < 0) return

        val deviceType = if (device == DeviceType.PHONE.value) {
            DeviceType.PHONE
        } else {
            DeviceType.LINKED_DEVICE
        }

        MessageHistoryStore.getInstance().insertDeviceInfoAsync(
            userjid = userjid,
            messageId = messageId,
            deviceType = deviceType.value
        )
    }

    private fun findFMessage(args: Array<Any?>): FMessageWpp? {
        for (arg in args) {
            if (arg == null) continue
            val found = findFMessage(arg)
            if (found != null) return found
        }
        return null
    }

    private fun findFMessage(candidate: Any): FMessageWpp? {
        if (FMessageWpp.TYPE.isInstance(candidate)) {
            return FMessageWpp(candidate)
        }

        var currentClass: Class<*>? = candidate.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            val field = ReflectionUtils.getDeclaredFieldsByExtendType(
                currentClass, FMessageWpp.TYPE
            ).firstOrNull()
            if (field != null) {
                val value = runCatching { field.get(candidate) }.getOrNull()
                if (value != null) return FMessageWpp(value)
            }
            currentClass = currentClass.superclass
        }
        return null
    }

    /**
     * Sender device ID from incoming-message arguments: check direct DeviceJid arguments first,
     * then fields on non-message wrapper arguments, and finally the FMessage itself.
     */
    private fun extractDeviceIdFromArgs(args: Array<Any?>, fMessage: FMessageWpp): Int {
        val fMsgObj = fMessage.getObject()
        val devClass = resolveDeviceJidClass()

        for (arg in args) {
            if (arg == null || arg === fMsgObj) continue
            if (devClass != null && devClass.isInstance(arg)) {
                val dev = deviceNumberOf(arg, isDeviceJid = true)
                if (dev >= 0) return dev
            }
        }

        for (arg in args) {
            if (arg == null || arg === fMsgObj || arg is String || arg.javaClass.isPrimitive) continue
            val dev = extractDeviceIdFromObject(arg)
            if (dev >= 0) return dev
        }

        return extractDeviceIdFromFMessage(fMessage)
    }

    private fun extractDeviceIdFromFMessage(fMessage: FMessageWpp): Int {
        fMessage.deviceJid?.let { dj ->
            val dev = deviceNumberOf(dj, isDeviceJid = true)
            if (dev >= 0) return dev
        }
        return extractDeviceIdFromObject(fMessage.getObject())
    }

    private fun extractDeviceIdFromObject(target: Any): Int {
        val devClass = resolveDeviceJidClass()
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull() ?: continue

                if (devClass != null && devClass.isInstance(value)) {
                    val dev = deviceNumberOf(value, isDeviceJid = true)
                    if (dev >= 0) return dev
                }

                val className = value.javaClass.name
                if (className.contains("Jid") || className.contains("jid")) {
                    // Only an explicit ":device" counts on generic JID fields.
                    val dev = deviceNumberOf(value, isDeviceJid = false)
                    if (dev >= 0) return dev
                }
            }
            current = current.superclass
        }
        return -1
    }

    private fun deviceNumberOf(jidObj: Any, isDeviceJid: Boolean): Int {
        if (isDeviceJid) {
            val numeric = runCatching {
                (ReflectionUtils.callMethod(jidObj, "getDevice") as? Number)?.toInt()
            }.getOrNull()
            if (numeric != null && numeric >= 0) return numeric
        }

        val raw = runCatching {
            ReflectionUtils.callMethod(jidObj, "getRawString") as? String
        }.getOrNull()
        val parsedRaw = DeviceJidParser.deviceNumber(raw, isDeviceJid)
        if (parsedRaw >= 0) return parsedRaw

        if (isDeviceJid) {
            val parsedStr = DeviceJidParser.deviceNumber(jidObj.toString(), true)
            if (parsedStr >= 0) return parsedStr
        }
        return -1
    }

    private fun updateDeviceIndicator(fMessage: FMessageWpp, view: ViewGroup) {
        val deviceIndicator = view.findViewWithTag<ImageView>(DEVICE_TYPE_TAG)
        val messageId = fMessage.key.messageID
        val userjid = fMessage.key.remoteJid.userRawString

        var deviceTypeValue = MessageHistoryStore.getInstance().getDeviceType(
            userjid,
            messageId
        ) {
            ConversationItemListener.notifyDataSetChanged()
        }

        // Fallback: check live device JID on the bound FMessage if not yet stored
        if (deviceTypeValue == null && userjid != null && messageId.isNotEmpty()) {
            val liveDevice = extractDeviceIdFromFMessage(fMessage)
            if (liveDevice >= 0) {
                val liveType = if (liveDevice == DeviceType.PHONE.value) {
                    DeviceType.PHONE.value
                } else {
                    DeviceType.LINKED_DEVICE.value
                }
                deviceTypeValue = liveType
                MessageHistoryStore.getInstance().insertDeviceInfoAsync(
                    userjid = userjid,
                    messageId = messageId,
                    deviceType = liveType
                )
            }
        }

        if (deviceTypeValue == null) {
            removeDeviceIndicator(deviceIndicator)
            return
        }

        val dateWrapper = view.findViewById<ViewGroup>(Utils.getID("date_wrapper", "id"))
        if (dateWrapper == null) {
            removeDeviceIndicator(deviceIndicator)
            return
        }

        val target = deviceIndicator ?: ImageView(dateWrapper.context).apply {
            tag = DEVICE_TYPE_TAG
        }
        if (target.parent !== dateWrapper) {
            (target.parent as? ViewGroup)?.removeView(target)
            dateWrapper.addView(target, 0)
        }

        val isPhone = DeviceType.fromInt(deviceTypeValue) == DeviceType.PHONE
        target.setImageResource(
            if (isPhone) R.drawable.device else R.drawable.linked_device
        )
        target.setOnClickListener {
            val msgRes = if (isPhone) {
                R.string.message_sent_via_phone
            } else {
                R.string.message_sent_via_linked_device
            }
            Utils.showToast(Utils.getString(msgRes), Toast.LENGTH_SHORT)
        }
    }

    private fun removeDeviceIndicator(indicator: ImageView?) {
        (indicator?.parent as? ViewGroup)?.removeView(indicator)
    }

    override fun getPluginName(): String = "Capture Device"

    companion object {
        private const val DEVICE_TYPE_TAG = "device_type_text_view"
    }
}
