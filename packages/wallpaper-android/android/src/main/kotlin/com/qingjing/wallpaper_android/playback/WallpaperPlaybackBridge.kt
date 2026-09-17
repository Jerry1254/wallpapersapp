package com.qingjing.wallpaper_android.playback

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.media.MediaCodecList
import android.os.Build
import com.qingjing.wallpaper_android.install.TrialRuntime
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

internal class WallpaperPlaybackBridge(private val context: Context) : PluginRegistry.ActivityResultListener {
    var activity: Activity? = null
    private var pending: MethodChannel.Result? = null
    fun information(): Map<String,Any> {
        val manager = WallpaperManager.getInstance(context)
        val allowed = manager.isWallpaperSupported && manager.isSetWallpaperAllowed
        val video = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { !it.isEncoder && it.supportedTypes.any { mime -> mime == "video/avc" } }
        val picker = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,ComponentName(context,VideoWallpaperService::class.java))
        val setup = LiveWallpaperPolicy.blockedMessage(context)
        val live = allowed && setup == null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_WALLPAPER) && picker.resolveActivity(context.packageManager) != null
        val environment = hostEnvironment()
        return mapOf("osVersion" to Build.VERSION.RELEASE,"sdkInt" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER.ifBlank { "UNKNOWN" },"model" to Build.MODEL.ifBlank { "UNKNOWN" },
            "hostOsFamily" to environment.first,"executionMode" to environment.second,
            "previewEffects" to (listOf("STATIC_IMAGE","LAYER_PARALLAX") + if(video) listOf("VIDEO") else emptyList()),
            "targets" to mapOf("STATIC_IMAGE" to if(allowed) listOf("home","lock","both") else emptyList(),"VIDEO" to if(live && video) listOf("home") else emptyList(),"LAYER_PARALLAX" to if(live) listOf("home") else emptyList()),
            "systemChoosesLiveTarget" to live,"setupMessage" to (setup ?: ""),"parallaxSensorAvailable" to ParallaxTiltSensor.available(context))
    }
    private fun hostEnvironment(): Pair<String,String> {
        val manufacturer = Build.MANUFACTURER.lowercase(java.util.Locale.ROOT)
        val harmony = systemProperty("hw_sc.build.platform.version")
        val emui = systemProperty("ro.build.version.emui")
        return when {
            harmony.isNotBlank() -> "HARMONY_CLASSIC" to "ANDROID_COMPATIBLE"
            manufacturer.contains("huawei") || manufacturer.contains("honor") || emui.isNotBlank() -> "EMUI" to "NATIVE"
            else -> "ANDROID" to "NATIVE"
        }
    }
    private fun systemProperty(name: String): String = try {
        val type = Class.forName("android.os.SystemProperties")
        type.getMethod("get",String::class.java,String::class.java).invoke(null,name,"") as? String ?: ""
    } catch (_: Exception) { "" }
    fun debugState(): Map<String,Any?> {
        require(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val manager = WallpaperManager.getInstance(context); val component = ComponentName(context,VideoWallpaperService::class.java)
        return mapOf("locationQueryable" to (Build.VERSION.SDK_INT >= 34),"homeId" to manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM),"lockId" to manager.getWallpaperId(WallpaperManager.FLAG_LOCK),
            "liveHome" to (manager.wallpaperInfo?.component == component),
            "liveLock" to (Build.VERSION.SDK_INT >= 34 && manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.component == component),
            "engines" to VideoWallpaperService.debugStates(),"parallaxEngines" to ParallaxWallpaperService.debugStates(),
            "parallaxHome" to (manager.wallpaperInfo?.component == ComponentName(context,ParallaxWallpaperService::class.java)),
            "parallaxLock" to (Build.VERSION.SDK_INT >= 34 && manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.component == ComponentName(context,ParallaxWallpaperService::class.java)))
    }
    fun launch(call: MethodCall, result: MethodChannel.Result) {
        if (pending != null) { result.error("PREVIEW_BUSY","已有预览或设置正在进行",null); return }
        val host = activity ?: return result.success(mapOf("status" to "unsupported","message" to "当前页面无法打开原生预览"))
        val type = call.argument<String>("resourceType")
        if (type !in setOf("STATIC_IMAGE","VIDEO","LAYER_PARALLAX")) return result.success(mapOf("status" to "unsupported","message" to "当前资源效果尚不支持"))
        val id = call.argument<String>("installedId") ?: return result.error("PACKAGE_INVALID","请先下载资源",null)
        val intent = Intent(host,NativeWallpaperActivity::class.java).putExtra("installedId",id).putExtra("resourceType",type)
            .putExtra("mode",if(call.method == "applyWallpaper") "apply" else "preview").putExtra("target",call.argument<String>("target"))
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && call.argument<Boolean>("debugForceNoSensor") == true) intent.putExtra("debugForceNoSensor",true)
        try { pending = result; host.startActivityForResult(intent,702) }
        catch (_: Exception) { pending = null; result.error("PREVIEW_UNAVAILABLE","原生预览或设置暂时不可用",null) }
    }
    fun trial(call: MethodCall,result: MethodChannel.Result) {
        if(pending != null) { result.error("PREVIEW_BUSY","已有预览或设置正在进行",null);return }
        val host = activity ?: return result.error("PREVIEW_UNAVAILABLE","当前页面无法打开试用",null)
        val id = call.argument<String>("trialId") ?: return result.error("TRIAL_EXPIRED","试用已结束",null)
        val current = TrialRuntime.current(context,id) ?: return result.success(mapOf("status" to "completed","message" to "试用已结束"))
        require(current.type == call.argument<String>("resourceType"))
        try {
            pending = result
            host.startActivityForResult(Intent(host,NativeTrialActivity::class.java).putExtra("trialId",id),702)
        } catch(_: Exception) { pending = null;result.error("PREVIEW_UNAVAILABLE","试用暂时无法打开，请重试",null) }
    }
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?): Boolean {
        if (requestCode != 702) return false
        val result = pending; pending = null
        result?.success(mapOf("status" to (data?.getStringExtra("status") ?: "cancelled"),"message" to (data?.getStringExtra("message") ?: "已返回，未确认设置结果")))
        return true
    }
    fun close() { activity = null; pending?.success(mapOf("status" to "unknown","message" to "页面已关闭，设置结果待确认")); pending = null }
}
