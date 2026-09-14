package com.qingjing.wallpaper_android.playback

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.ViewTreeObserver
import android.widget.*
import com.qingjing.wallpaper_android.install.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Private app-only trial. There is no system-setting, export or entitlement code in this activity. */
class NativeTrialActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private var id: String? = null
    private var pin: AutoCloseable? = null
    private var bitmap: Bitmap? = null
    private var image: ImageView? = null
    private var surface: SurfaceView? = null
    private var parallax: ParallaxSurfaceRenderer? = null
    private var content: InstalledPackage? = null
    @Volatile private var visible = false
    private var resumed = false
    @Volatile private var dead = false
    private lateinit var message: TextView
    private lateinit var frame: FrameLayout
    private val player = VideoSurfacePlayer(
        ready = { onRendered() },
        failed = { finishWith("unknown","试用视频无法播放，请重试") })
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        id = intent.getStringExtra("trialId")
        val current = id?.let { TrialRuntime.open(this,it) }
        if(current == null) { finishWith("completed","试用已结束"); return }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.BLACK) }
        layout.setOnApplyWindowInsetsListener { view,insets ->
            if(Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left,safe.top,safe.right,safe.bottom)
            } else { @Suppress("DEPRECATION") view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom) }
            insets
        }
        message = TextView(this).apply { text = "正在加载试用画面…"; setTextColor(android.graphics.Color.WHITE); setPadding(24,24,24,24) }
        frame = FrameLayout(this)
        layout.addView(message);layout.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        layout.addView(Button(this).apply { text = "结束试用"; setOnClickListener { finishWith("completed","试用已结束") } })
        setContentView(layout)
        if(current.type != "STATIC_IMAGE") {
            surface = SurfaceView(this).also { view ->
                if(current.type == "LAYER_PARALLAX") parallax = ParallaxSurfaceRenderer(this,view.holder,
                    ready = { _,_ -> runOnUiThread { onRendered() } },
                    failed = { runOnUiThread { finishWith("unknown","4D 试用暂时无法播放，请重试") } },changed = {},
                    source = { TrialRuntime.store(this) to PackageRuntime.verifier(this,PackagePurpose.APP_PREVIEW) },
                    released = { TrialRuntime.cleanup(this) })
                view.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { play() }
                    override fun surfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) { parallax?.redraw() }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { player.close();parallax?.stop() }
                })
                frame.addView(view,FrameLayout.LayoutParams(-1,-1))
            }
        }
        main.post(tick)
    }
    private fun load() {
        val trialId = id ?: return
        val current = TrialRuntime.current(this,trialId) ?: return finishWith("completed","试用已结束")
        val attempt = generation.incrementAndGet()
        worker.execute {
            var lease: AutoCloseable? = null
            var decoded: Bitmap? = null
            try {
                val store = TrialRuntime.store(this);lease = store.pin(current.installed)
                val verified = PackageRuntime.verifier(this,PackagePurpose.APP_PREVIEW).verify(store,current.installed,current.type)
                if(current.type == "STATIC_IMAGE") {
                    val file = verified.content("STATIC_IMAGE")
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath,options)
                    require(options.outWidth in 1..1280 && options.outHeight in 1..1280)
                    val runtime = Runtime.getRuntime()
                    require(runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory()) > options.outWidth.toLong()*options.outHeight*4+16*1024*1024)
                    decoded = BitmapFactory.decodeFile(file.absolutePath) ?: error("Cannot decode trial")
                }
                val retained = lease;lease = null;val readyImage = decoded;decoded = null
                runOnUiThread {
                    if(dead || !visible || generation.get() != attempt || TrialRuntime.current(this,trialId) == null) {
                        retained?.close();readyImage?.recycle();TrialRuntime.cleanup(this)
                    } else {
                        pin = retained;content = verified
                        if(readyImage != null) {
                            bitmap = readyImage
                            image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP;setImageBitmap(readyImage) }
                            val view = image!!
                            val listener = object : ViewTreeObserver.OnDrawListener {
                                override fun onDraw() { view.post { if(view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this);if(visible && generation.get() == attempt) onRendered() } }
                            }
                            view.viewTreeObserver.addOnDrawListener(listener);frame.addView(view,FrameLayout.LayoutParams(-1,-1))
                        } else play()
                    }
                }
            } catch(_: Exception) { lease?.close();decoded?.recycle();runOnUiThread { if(visible && generation.get() == attempt) finishWith("unknown","试用资源校验失败，请重试") };TrialRuntime.cleanup(this) }
            catch(_: OutOfMemoryError) { lease?.close();decoded?.recycle();runOnUiThread { finishWith("unknown","可用内存不足，请关闭其他应用后重试") };TrialRuntime.cleanup(this) }
        }
    }
    private fun play() {
        if(!visible || dead) return
        val holder = surface?.holder ?: return
        if(!holder.surface.isValid) return
        content?.let { if(it.type == "VIDEO") player.open(it.content("VIDEO"),holder) else parallax?.start(it.id) }
    }
    private fun onRendered() {
        if(!visible || dead) return
        val trialId = id ?: return
        if(TrialRuntime.rendered(this,trialId) == null) { finishWith("completed","两分钟试用已到期");return }
        main.removeCallbacks(tick);tick.run()
    }
    private val tick = object : Runnable {
        override fun run() {
            if(dead || isFinishing) return
            val current = id?.let { TrialRuntime.current(this@NativeTrialActivity,it) }
            if(current == null) { finishWith("completed","试用已结束");return }
            val deadline = current.deadline
            if(deadline != null) {
                val remaining = deadline.remainingSeconds(TrialRuntime.boot(this@NativeTrialActivity),SystemClock.elapsedRealtime())
                if(remaining == 0L) { finishWith("completed","两分钟试用已到期");return }
                if(visible) message.text = "试用剩余 ${remaining / 60}:${(remaining % 60).toString().padStart(2,'0')} · 仅在 App 内查看"
            }
            main.postDelayed(this,250)
        }
    }
    override fun onResume() {
        super.onResume();resumed = true;updateVisibility()
    }
    override fun onWindowFocusChanged(focused: Boolean) { super.onWindowFocusChanged(focused);updateVisibility() }
    private fun updateVisibility() {
        if(dead || isFinishing) return
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val shouldShow = resumed && hasWindowFocus() && !keyguard.isKeyguardLocked && power.isInteractive
        if(shouldShow == visible) return
        if(shouldShow) {
            visible = true;main.removeCallbacks(tick);tick.run();if(!isFinishing) load()
        } else releaseVisibleResources()
    }
    private fun releaseVisibleResources() {
        visible = false;generation.incrementAndGet();player.close();parallax?.stop()
        image?.setImageDrawable(null);image?.let { frame.removeView(it) };image = null
        bitmap?.recycle();bitmap = null;content = null;pin?.close();pin = null
        TrialRuntime.cleanup(this)
    }
    override fun onPause() { resumed = false;releaseVisibleResources();super.onPause() }
    @Deprecated("Android Activity callback")
    override fun onBackPressed() { finishWith("completed","试用已结束") }
    private fun finishWith(status: String,text: String) {
        if(dead || isFinishing) return
        id?.let { TrialRuntime.finish(this,it) }
        setResult(RESULT_OK,Intent().putExtra("status",status).putExtra("message",text));finish()
    }
    override fun onDestroy() {
        dead = true;generation.incrementAndGet();main.removeCallbacks(tick);player.close();parallax?.close()
        image?.setImageDrawable(null);bitmap?.recycle();pin?.close();worker.shutdown()
        id?.let { if(isFinishing) TrialRuntime.finish(this,it);TrialRuntime.closed(this,it) }
        super.onDestroy()
    }
}
