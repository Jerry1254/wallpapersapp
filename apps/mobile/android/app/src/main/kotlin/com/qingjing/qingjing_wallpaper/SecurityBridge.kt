package com.qingjing.qingjing_wallpaper

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File

/** Local signals only, not hardware attestation. Checks are lazy and run only for enabled rules. */
class SecurityBridge(context: Context, messenger: BinaryMessenger) {
    private val preferences = context.getSharedPreferences("qingjing_security", Context.MODE_PRIVATE)
    private val environment = SecurityEnvironmentChecks(
        developerMode = { Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, -1) },
        usbDebugging = { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, -1) },
        rooted = { listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/adb/magisk").any { File(it).exists() } },
        emulator = { emulator() },
        // AOSP's Wi-Fi debugging setting is not a public constant. Read its key without hidden-API reflection;
        // inaccessible or redacted settings remain UNKNOWN in SecurityEnvironmentChecks.
        wirelessDebugging = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", -1) else -1 },
    )
    init {
        MethodChannel(messenger, "qingjing/security").setMethodCallHandler { call, result ->
            when (call.method) {
                "cachedBlock" -> {
                    val scope = call.arguments as? String
                    if (scope == null || scope.length > 1024) result.error("INVALID_SCOPE", "Invalid scope", null)
                    else result.success(preferences.getBoolean("blocked_$scope", false))
                }
                "saveBlock" -> {
                    val scope = call.argument<String>("scope")
                    val blocked = call.argument<Boolean>("blocked")
                    if (scope == null || scope.length > 1024 || blocked == null) result.error("INVALID_STATE", "Invalid state", null)
                    else if (preferences.edit().putBoolean("blocked_$scope", blocked).commit()) result.success(null)
                    else result.error("SAVE_FAILED", "Unable to save state", null)
                }
                "checkEnvironment" -> {
                    val checks = (call.arguments as? List<*>)?.filterIsInstance<String>().orEmpty()
                    result.success(environment.check(checks))
                }
                else -> result.notImplemented()
            }
        }
    }
    private fun emulator(): Boolean = Build.FINGERPRINT.startsWith("generic/") ||
        Build.FINGERPRINT.startsWith("generic_x86/") || Build.HARDWARE in setOf("goldfish", "ranchu") ||
        Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
        Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
        Build.MODEL == "google_sdk" || Build.MANUFACTURER.equals("Genymotion", ignoreCase = true)
}
