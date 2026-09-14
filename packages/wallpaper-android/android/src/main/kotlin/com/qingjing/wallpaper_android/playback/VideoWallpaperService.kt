package com.qingjing.wallpaper_android.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.pm.ApplicationInfo
import android.util.Log
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.qingjing.wallpaper_android.install.PackageRuntime
import java.util.concurrent.Executors

class VideoWallpaperService : WallpaperService() {
    companion object {
        private val states = mutableMapOf<Int,Map<String,Any?>>()
        @Synchronized fun debugStates() = states.values.toList()
        @Synchronized private fun state(engine: Int,value: Map<String,Any?>?): Boolean {
            val changed = states[engine] != value
            if(value == null) states.remove(engine) else states[engine] = value
            return changed
        }
    }
    override fun onCreateEngine(): Engine = VideoEngine()
    inner class VideoEngine : Engine() {
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor()
        private var generation = 0
        private var pin: AutoCloseable? = null
        private var loadedId: String? = null
        private var loadingId: String? = null
        private var failedId: String? = null
        private var previewToken: String? = null
        private var destroyed = false
        private var surfaceReady = false
        private val player = VideoSurfacePlayer(
            ready = { previewToken?.let { LiveSelection.ready(this@VideoWallpaperService,it) }; snapshot() },
            failed = { failedId = loadedId; release() },
        )
        private val checkSelection = object : Runnable {
            override fun run() {
                if (!destroyed && isVisible && surfaceReady) {
                    val id = selection()?.first
                    if (id != loadedId && id != loadingId && id != failedId) load()
                    snapshot()
                    main.postDelayed(this,1000)
                }
            }
        }
        private fun selection(): Pair<String,String?>? = if (isPreview) LiveSelection.pending(this@VideoWallpaperService,"VIDEO")
            else LiveSelection.current(this@VideoWallpaperService)?.let { it to null }
        override fun onSurfaceCreated(holder: SurfaceHolder) { super.onSurfaceCreated(holder); surfaceReady = true; if (isVisible) load() }
        override fun onVisibilityChanged(visible: Boolean) {
            failedId = null
            main.removeCallbacks(checkSelection)
            if (visible && surfaceReady) { load(); main.postDelayed(checkSelection,1000) } else release()
        }
        override fun onSurfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; release(); super.onSurfaceDestroyed(holder) }
        private fun load() {
            release()
            val selection = selection() ?: return
            val id = selection.first; previewToken = selection.second
            loadingId = id; failedId = null
            val attempt = generation
            worker.execute {
                var lease: AutoCloseable? = null
                try {
                    val store = PackageRuntime.store(this@VideoWallpaperService); lease = store.pin(id)
                    val verified = PackageRuntime.verifier(this@VideoWallpaperService).verify(store,id,"VIDEO")
                    val retained = lease; lease = null
                    main.post {
                        if (destroyed || attempt != generation || !isVisible || !surfaceReady) retained?.close()
                        else { pin = retained; loadedId = id; loadingId = null; player.open(verified.content("VIDEO"),surfaceHolder); snapshot() }
                    }
                } catch (_: Exception) { lease?.close(); main.post { if(attempt == generation) { loadingId = null; failedId = id; snapshot() } } }
            }
        }
        private fun snapshot() {
            if (destroyed) return
            val value = mapOf("preview" to isPreview,"visible" to isVisible,"playing" to player.playing,"installedId" to loadedId,"failed" to (failedId != null))
            if (state(hashCode(),value) && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
                Log.d("QJPlayback","elapsed=${SystemClock.elapsedRealtime()} engine=${hashCode()} $value")
        }
        private fun release() { generation++; player.close(); pin?.close(); pin = null; loadedId = null; loadingId = null; previewToken = null; snapshot() }
        override fun onDestroy() { destroyed = true; main.removeCallbacks(checkSelection); release(); state(hashCode(),null); worker.shutdown(); super.onDestroy() }
    }
}
