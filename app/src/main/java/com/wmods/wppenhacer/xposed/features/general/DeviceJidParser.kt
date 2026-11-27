package com.wmods.wppenhacer.xposed.features.general

/** Parses the sender device number from a WhatsApp JID raw string. */
internal object DeviceJidParser {

    /**
     * Device number from a WhatsApp JID string, `user[.agent][:device]@server`, e.g.
     * `421900123456:2@s.whatsapp.net` or `123456789012345:3@lid`.
     * Device 0 is the primary phone. Without `:device`, a DeviceJid means the phone (0);
     * any other JID says nothing (-1).
     */
    fun deviceNumber(raw: String?, isDeviceJid: Boolean): Int {
        if (raw == null) return -1
        val at = raw.indexOf('@')
        val user = if (at >= 0) raw.substring(0, at) else raw
        val colon = user.lastIndexOf(':')
        if (colon < 0) return if (isDeviceJid && user.isNotEmpty()) 0 else -1
        val device = user.substring(colon + 1)
        if (device.isEmpty() || device.length > 4) return -1
        for (i in 0 until device.length) {
            if (!device[i].isDigit()) return -1
        }
        return device.toIntOrNull() ?: -1
    }
}
