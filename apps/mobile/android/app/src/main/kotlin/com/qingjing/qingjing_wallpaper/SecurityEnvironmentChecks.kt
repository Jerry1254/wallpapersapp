package com.qingjing.qingjing_wallpaper

/** Requested rules alone read the environment; one unavailable signal must not hide other signals. */
internal class SecurityEnvironmentChecks(
    private val developerMode: () -> Int,
    private val usbDebugging: () -> Int,
    private val rooted: () -> Boolean,
    private val emulator: () -> Boolean,
) {
    fun check(keys: List<String>): Map<String, String> = keys.distinct().take(4).associateWith { key ->
        try {
            when (key) {
                // Third-party reads may be redacted to zero. Only an explicit 1 establishes risk.
                "DEVELOPER_MODE" -> enabledSignal(developerMode())
                "USB_DEBUGGING" -> enabledSignal(usbDebugging())
                "ROOT_JAILBREAK" -> if (rooted()) "RISK" else "NORMAL"
                "EMULATOR" -> if (emulator()) "RISK" else "NORMAL"
                else -> "UNKNOWN"
            }
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }

    private fun enabledSignal(value: Int): String = if (value == 1) "RISK" else "UNKNOWN"
}
