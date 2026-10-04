package com.qingjing.qingjing_wallpaper

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var appUpdates: AppUpdateBridge? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        appUpdates?.destroy()
        appUpdates = AppUpdateBridge(this, flutterEngine.dartExecutor.binaryMessenger)
        val preferences = getSharedPreferences("qingjing_privacy_state", MODE_PRIVATE)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "qingjing/privacy_consent")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "acceptedVersion" -> result.success(
                        preferences.getString("accepted_policy_version", null)
                    )
                    "acceptVersion" -> {
                        val version = call.arguments as? String
                        if (version.isNullOrBlank()) {
                            result.error("INVALID_VERSION", "Policy version is required", null)
                        } else {
                            val saved = preferences.edit()
                                .putString("accepted_policy_version", version)
                                .commit()
                            if (saved) {
                                result.success(null)
                            } else {
                                result.error("SAVE_FAILED", "Unable to save consent", null)
                            }
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    override fun onResume() {
        super.onResume()
        appUpdates?.onResume()
    }

    override fun onDestroy() {
        appUpdates?.destroy()
        appUpdates = null
        super.onDestroy()
    }
}
