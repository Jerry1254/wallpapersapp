package com.qingjing.support_agent

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "qingjing/support-agent").setMethodCallHandler { call, result ->
            if (call.method == "beep") {
                try {
                    val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
                    Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 250)
                    result.success(null)
                } catch (_: Exception) { result.success(null) }
            } else result.notImplemented()
        }
    }
}
