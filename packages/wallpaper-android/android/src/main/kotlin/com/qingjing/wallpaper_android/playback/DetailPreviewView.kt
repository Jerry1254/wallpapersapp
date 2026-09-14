package com.qingjing.wallpaper_android.playback

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.qingjing.wallpaper_android.install.DetailPreviewRuntime
import com.qingjing.wallpaper_android.install.InstalledPackage
import com.qingjing.wallpaper_android.install.PackagePurpose
import com.qingjing.wallpaper_android.install.PackageRuntime
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.util.concurrent.Executors

internal class DetailPreviewFactory(private val messenger: BinaryMessenger) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context,id: Int,args: Any?): PlatformView = DetailPreviewView(context,id,args as? Map<*,*> ?: emptyMap<Any,Any>(),messenger)
}

/** Same verified renderer/player as system wallpapers, with an independent Surface and lease. */
private class DetailPreviewView(context: Context,id: Int,args: Map<*,*>,messenger: BinaryMessenger) : PlatformView {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val channel = MethodChannel(messenger,"qingjing/detail_preview/$id")
    private val surface = SurfaceView(context)
    private val frame = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK); addView(surface,FrameLayout.LayoutParams(-1,-1)) }
    private val type = args["resourceType"] as? String
    private var active = args["visible"] == true
    private var resumed = true
    private var dead = false
    private var playing = false
    private var pin: AutoCloseable? = null
    private var content: InstalledPackage? = null
    private val player = VideoSurfacePlayer(ready = { status("ready") },failed = { status("failed") })
    private var renderer: ParallaxSurfaceRenderer? = null
    private fun activity(context: Context): Activity? {
        var value = context
        while(value is ContextWrapper) { if(value is Activity) return value; val next = value.baseContext; if(next === value) break; value = next }
        return value as? Activity
    }
    private val host = activity(context)
    private val application = context.applicationContext as Application
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) { if(activity === host) { resumed = true; play() } }
        override fun onActivityPaused(activity: Activity) { if(activity === host) { resumed = false; stop() } }
        override fun onActivityDestroyed(activity: Activity) { if(activity === host) dispose() }
        override fun onActivityCreated(activity: Activity,state: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity,state: Bundle) {}
    }
    init {
        application.registerActivityLifecycleCallbacks(lifecycle)
        channel.setMethodCallHandler { call,result ->
            when(call.method) {
                "visible" -> { active = call.arguments == true; if(active) play() else stop(); result.success(null) }
                "state" -> result.success(mapOf("resourceType" to type,"playing" to player.playing,"rendering" to (player.rendered || renderer?.state()?.get("rendering") == true)))
                else -> result.notImplemented()
            }
        }
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { play() }
            override fun surfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) { renderer?.redraw() }
            override fun surfaceDestroyed(holder: SurfaceHolder) { stop() }
        })
        if(type == "LAYER_PARALLAX") surface.setOnTouchListener { view,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    renderer?.touch((event.x / view.width.coerceAtLeast(1)-.5f)*2f,(event.y / view.height.coerceAtLeast(1)-.5f)*2f)
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { renderer?.touch(null,null); view.parent?.requestDisallowInterceptTouchEvent(false); view.performClick() }
            }
            true
        }
        worker.execute {
            var lease: AutoCloseable? = null
            try {
                require(type in setOf("VIDEO","LAYER_PARALLAX"))
                val installed = args["installedId"] as? String ?: error("Missing preview")
                val restricted = args["restricted"] == true
                val store = if(restricted) DetailPreviewRuntime.store(context) else PackageRuntime.store(context)
                val purpose = if(restricted) PackagePurpose.APP_PREVIEW else PackagePurpose.FORMAL
                lease = store.pin(installed)
                val verified = PackageRuntime.verifier(context,purpose).verify(store,installed,type!!)
                val retained = lease; lease = null
                main.post {
                    if(dead) retained?.close()
                    else {
                        pin = retained; content = verified
                        if(type == "LAYER_PARALLAX") renderer = ParallaxSurfaceRenderer(context,surface.holder,
                            ready = { _,sensor -> main.post { status(if(sensor) "ready" else "touch") } },
                            failed = { main.post { status("failed") } },changed = {},
                            source = { store to PackageRuntime.verifier(context,purpose) })
                        play()
                    }
                }
            } catch(_: Exception) { lease?.close(); main.post { status("failed") } }
        }
    }
    private fun status(value: String) { if(!dead) channel.invokeMethod("status",value) }
    private fun play() {
        val value = content ?: return
        if(dead || !active || !resumed || !surface.holder.surface.isValid || playing) return
        playing = true
        if(type == "VIDEO") player.open(value.content("VIDEO"),surface.holder) else renderer?.start(value.id)
    }
    private fun stop() { playing = false; player.close(); renderer?.stop() }
    override fun getView(): View = frame
    override fun dispose() {
        if(dead) return
        dead = true; stop(); renderer?.close(); pin?.close(); pin = null
        channel.setMethodCallHandler(null); application.unregisterActivityLifecycleCallbacks(lifecycle); worker.shutdown()
    }
}
