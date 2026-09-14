package com.qingjing.wallpaper_android.playback

import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder

class ParallaxWallpaperService : WallpaperService() {
    companion object {
        private val engines = mutableMapOf<Int,() -> Map<String,Any?>>()
        @Synchronized fun debugStates() = engines.values.map { it() }
        @Synchronized private fun register(id: Int,state: (() -> Map<String,Any?>)?) { if (state == null) engines.remove(id) else engines[id] = state }
    }
    override fun onCreateEngine(): Engine = ParallaxEngine()
    inner class ParallaxEngine : Engine() {
        private val main = Handler(Looper.getMainLooper())
        private var surfaceReady = false; private var dead = false; private var token: String? = null
        private var lastSelection: Pair<String,String?>? = null
        private var lastState: Map<String,Any?>? = null
        private lateinit var renderer: ParallaxSurfaceRenderer
        private fun state() = renderer.state()+mapOf("type" to "LAYER_PARALLAX","preview" to isPreview,"visible" to isVisible)
        private fun snapshot() {
            if (dead) return
            val value = state().filterKeys { it !in setOf("frames","motionObserved") }
            if (lastState != value && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d("QJPlayback","elapsed=${SystemClock.elapsedRealtime()} engine=${hashCode()} $value")
            lastState = value
        }
        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder); setOffsetNotificationsEnabled(false)
            renderer = ParallaxSurfaceRenderer(this@ParallaxWallpaperService,holder,
                ready = { id,_ -> main.post { if (!dead && isVisible && surfaceReady && lastSelection?.first == id) { token?.let { LiveSelection.ready(this@ParallaxWallpaperService,it) }; snapshot() } } },
                failed = { main.post { snapshot() } },changed = { main.post { snapshot() } })
            register(hashCode()) { state() }
        }
        private val checkSelection = object : Runnable {
            override fun run() { if (!dead && isVisible && surfaceReady) { load(); main.postDelayed(this,1000) } }
        }
        private fun selection(): Pair<String,String?>? = if (isPreview) LiveSelection.pending(this@ParallaxWallpaperService,"LAYER_PARALLAX")
            else LiveSelection.current(this@ParallaxWallpaperService,"LAYER_PARALLAX")?.let { it to null }
        private fun load() {
            val selected = selection()
            if (selected == lastSelection) return
            lastSelection = selected; token = selected?.second
            if (selected == null) renderer.stop() else renderer.start(selected.first)
        }
        override fun onVisibilityChanged(visible: Boolean) {
            main.removeCallbacks(checkSelection); lastSelection = null
            if (visible && surfaceReady) { load(); main.postDelayed(checkSelection,1000) }
            else { token = null; renderer.stop() }
        }
        override fun onSurfaceCreated(holder: SurfaceHolder) { super.onSurfaceCreated(holder); surfaceReady = true; if (isVisible) { load(); main.removeCallbacks(checkSelection); main.postDelayed(checkSelection,1000) } }
        override fun onSurfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) { renderer.redraw() }
        override fun onSurfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; lastSelection = null; token = null; renderer.stop(); main.removeCallbacks(checkSelection); super.onSurfaceDestroyed(holder) }
        override fun onDestroy() { dead = true; main.removeCallbacks(checkSelection); renderer.close(); register(hashCode(),null); super.onDestroy() }
    }
}
