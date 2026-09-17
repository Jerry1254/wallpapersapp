package com.qingjing.wallpaper_android.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import java.io.PrintWriter
import org.json.JSONObject

/** Read-only ADB service dump for QA. No tickets, installation IDs, keys or file paths. */
internal object PlaybackDiagnostics {
    fun enabled(context: Context): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return true
        return context.packageName in setOf("com.qingjing.bizhi.internal", "com.qingjing.bizhi.lab") &&
            context.packageManager.getApplicationInfo(context.packageName,PackageManager.GET_META_DATA)
                .metaData?.getBoolean("qingjing.internalDiagnostics",false) == true
    }
    fun dump(context: Context,writer: PrintWriter,states: List<Map<String,Any?>>) {
        if (!enabled(context)) return
        val safe = states.map { state ->
            state.filterKeys { it in setOf("type","preview","visible","rendering","playing","sensorRegistered","decodedBytes","frames","droppedFrames","decodeErrors","failed") } +
                mapOf("resourceLoaded" to (state["installedId"] != null))
        }
        writer.println("QJ_INTERNAL_PLAYBACK "+JSONObject(mapOf("elapsed" to SystemClock.elapsedRealtime(),"engines" to safe)).toString())
    }
}
