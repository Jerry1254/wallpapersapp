package com.qingjing.wallpaper_android.playback

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.WindowManager
import com.qingjing.wallpaper_android.install.PackageRuntime
import com.qingjing.wallpaper_android.install.AtomicPackageStore
import com.qingjing.wallpaper_android.install.InstalledPackageVerifier
import java.util.concurrent.atomic.AtomicInteger

/** One thread, sensor, scene and lease per viewer/Engine. Hidden surfaces release all decoded resources. */
internal class ParallaxSurfaceRenderer(private val context: Context,private val holder: SurfaceHolder,
    private val ready: (String,Boolean) -> Unit,private val failed: () -> Unit,private val changed: () -> Unit,
    private val forceNoSensor: Boolean = false,
    private val source: () -> Pair<AtomicPackageStore,InstalledPackageVerifier> = { PackageRuntime.store(context) to PackageRuntime.verifier(context) },
    private val released: () -> Unit = {}) : AutoCloseable {
    private val thread = HandlerThread("qingjing-parallax-render").apply { start() }
    private val handler = Handler(thread.looper)
    private val generation = AtomicInteger()
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile var selectedId: String? = null; private set
    @Volatile private var loading = false
    @Volatile private var rendered = false
    @Volatile private var sensorRunning = false
    @Volatile private var hasFailed = false
    @Volatile private var frames = 0L
    @Volatile private var motionObserved = false
    @Volatile private var decodedBytes = 0L
    private var scene: ParallaxScene? = null
    private var sensor: ParallaxTiltSensor? = null
    private var lease: AutoCloseable? = null
    private var targetX = 0f; private var targetY = 0f; private var x = 0f; private var y = 0f
    private var touchX: Float? = null; private var touchY: Float? = null
    private var previousFrame = 0L
    private var renderGeneration = -1
    private var lastPostTime = 0L
    fun state(): Map<String,Any?> = mapOf("rendering" to (active && rendered),"loading" to loading,"sensorRegistered" to sensorRunning,
        "installedId" to selectedId,"failed" to hasFailed,"frames" to frames,"motionObserved" to motionObserved,"decodedBytes" to decodedBytes)
    fun start(id: String) {
        if (closed) return
        val attempt = generation.incrementAndGet(); active = true; selectedId = id; loading = true; rendered = false; hasFailed = false
        handler.post {
            releaseResources()
            if (!valid(attempt)) return@post
            selectedId = id; loading = true
            try {
                val (store,verifier) = source(); lease = store.pin(id)
                val installed = verifier.verify(store,id,"LAYER_PARALLAX")
                scene = ParallaxScene.load(installed) { !valid(attempt) }
                if (!valid(attempt)) { releaseResources(); return@post }
                decodedBytes = scene!!.decodedBytes
                val configuration = scene!!.configuration
                @Suppress("DEPRECATION")
                val rotation = { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation }
                sensor = ParallaxTiltSensor(context,configuration.maxAngle,rotation) { tx,ty ->
                    if (valid(attempt)) { targetX = tx; targetY = ty; if (kotlin.math.abs(tx)>.03f || kotlin.math.abs(ty)>.03f) motionObserved = true }
                }
                sensorRunning = !forceNoSensor && configuration.layers.any { it.offsetPercent!=0f } && sensor!!.start(handler)
                renderGeneration = attempt; lastPostTime = SystemClock.elapsedRealtime(); loading = false; changed(); handler.post(drawFrame)
            } catch (_: Exception) { error(attempt) }
            catch (_: OutOfMemoryError) { error(attempt) }
        }
    }
    private fun valid(attempt: Int) = !closed && active && generation.get() == attempt
    private fun error(attempt: Int) {
        releaseResources()
        if (valid(attempt)) { loading = false; hasFailed = true; changed(); failed() }
    }
    private val drawFrame = object : Runnable {
        override fun run() {
            if (!valid(renderGeneration) || !holder.surface.isValid) return
            val current = scene ?: return
            val now = SystemClock.elapsedRealtime()
            x = ParallaxMotion.smooth(x,touchX ?: targetX,current.configuration.smoothing,if (previousFrame == 0L) 33 else now-previousFrame)
            y = ParallaxMotion.smooth(y,touchY ?: targetY,current.configuration.smoothing,if (previousFrame == 0L) 33 else now-previousFrame); previousFrame = now
            var canvas: android.graphics.Canvas? = null
            var posted = false
            var drew = false
            var memoryFailure = false
            try {
                // API 26 is our minimum. Full-resolution layered bitmaps use GPU Canvas;
                // software lockCanvas made the 1080x2400 Redmi fixture render at ~5 fps.
                canvas = holder.lockHardwareCanvas()
                if (canvas != null && valid(renderGeneration)) { current.draw(canvas,x,y); drew = true }
            } catch (_: RuntimeException) { }
            catch (_: OutOfMemoryError) { memoryFailure = true }
            finally {
                if (canvas != null) try { holder.unlockCanvasAndPost(canvas); posted = drew } catch (_: RuntimeException) { }
            }
            if (memoryFailure) { error(renderGeneration); return }
            if (posted && valid(renderGeneration)) {
                frames++
                lastPostTime = now
                if (!rendered) { rendered = true; changed(); selectedId?.let { ready(it,sensorRunning) } }
            }
            if (!posted && valid(renderGeneration) && now-lastPostTime>3000) { error(renderGeneration); return }
            // Without a usable sensor the first frame is the complete static fallback; do not poll at 30 fps.
            if (valid(renderGeneration) && (!rendered || sensorRunning || touchX != null)) handler.postDelayed(this,34)
        }
    }
    fun redraw() { if (active && !closed) handler.post { handler.removeCallbacks(drawFrame); handler.post(drawFrame) } }
    fun touch(x: Float?,y: Float?) {
        if (closed) return
        handler.post {
            touchX = x?.coerceIn(-1f,1f); touchY = y?.coerceIn(-1f,1f)
            if (active && !sensorRunning) { handler.removeCallbacks(drawFrame); handler.post(drawFrame) }
        }
    }
    fun stop() {
        active = false; generation.incrementAndGet()
        handler.post { releaseResources(); selectedId = null; loading = false; hasFailed = false; changed() }
    }
    private fun releaseResources() {
        handler.removeCallbacks(drawFrame); sensor?.stop(); sensor = null; sensorRunning = false
        scene?.close(); scene = null; lease?.close(); lease = null; decodedBytes = 0; rendered = false
        targetX = 0f; targetY = 0f; x = 0f; y = 0f; previousFrame = 0; frames = 0; motionObserved = false
        touchX = null; touchY = null
        released()
    }
    override fun close() {
        if (closed) return
        closed = true; active = false; generation.incrementAndGet()
        handler.post { releaseResources(); selectedId = null; loading = false; thread.quitSafely() }
    }
}
