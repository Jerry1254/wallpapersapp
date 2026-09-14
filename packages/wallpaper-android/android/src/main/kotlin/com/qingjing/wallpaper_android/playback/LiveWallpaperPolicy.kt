package com.qingjing.wallpaper_android.playback

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.util.Locale

/** Read-only OEM preflight. Never grants permissions or changes AppOps. */
internal object LiveWallpaperPolicy {
    private const val MIUI_LIVE_WALLPAPER_OP = 10045
    const val MIUI_HINT = "请在手机设置→应用管理→倾境壁纸→权限管理→其他权限中，允许动态壁纸服务，再重新检测手机能力"
    fun blockedMessage(context: Context): String? {
        val vendor = Build.MANUFACTURER.lowercase(Locale.ROOT)
        if (vendor !in setOf("xiaomi","redmi","poco")) return null
        // This ROM's system picker checks this same operation. Non-SDK lookup may be
        // unavailable on another ROM: fall back to the system picker, never invent denial.
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val query = AppOpsManager::class.java.getMethod("checkOpNoThrow",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,String::class.java)
            val mode = query.invoke(ops,MIUI_LIVE_WALLPAPER_OP,Process.myUid(),context.packageName) as Int
            if (mode == AppOpsManager.MODE_ALLOWED) null else MIUI_HINT
        } catch (_: Exception) { null }
    }
}
