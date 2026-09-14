package com.qingjing.wallpaper_android.playback

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.qingjing.wallpaper_android.install.InstalledPackage
import com.qingjing.wallpaper_android.install.PackageRuntime
import java.util.concurrent.Executors

/** Private viewer/coordinator. Only an opaque installed ID crosses Flutter; files never leave private storage. */
class NativeWallpaperActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var pin: AutoCloseable? = null
    private var bitmap: Bitmap? = null
    private var content: InstalledPackage? = null
    private var surface: SurfaceView? = null
    private var resumed = false
    private var dead = false
    private var rendered = false
    private var settingStatic = false
    private var selectionToken: String? = null
    private lateinit var message: TextView
    private val player = VideoSurfacePlayer(
        ready = { rendered = true; message.text = "视频预览 · 仅在 App 内播放" },
        failed = { finishWith(WallpaperOutcome("unknown","视频无法播放，请重新下载或联系客服")) },
    )
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.BLACK) }
        layout.setOnApplyWindowInsetsListener { view,insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left,safe.top,safe.right,safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            }
            insets
        }
        message = TextView(this).apply { text = "正在校验本地资源…"; setTextColor(android.graphics.Color.WHITE); setPadding(24,24,24,24) }
        val frame = FrameLayout(this)
        val close = Button(this).apply { text = "结束预览"; setOnClickListener { exitPreview() } }
        layout.addView(message); layout.addView(frame,LinearLayout.LayoutParams(-1,0,1f)); layout.addView(close); setContentView(layout)
        selectionToken = state?.getString("selectionToken")
        if (selectionToken != null) { message.text = "等待系统壁纸设置结果…"; close.text = "返回"; return }
        val id = intent.getStringExtra("installedId") ?: return finishWith(WallpaperOutcome("unknown","本地资源不可用，请先下载"))
        val type = intent.getStringExtra("resourceType") ?: return finishWith(WallpaperOutcome("unknown","资源类型不可用"))
        if (type !in setOf("STATIC_IMAGE","VIDEO")) return finishWith(WallpaperOutcome("unsupported","4D 原生能力尚未接入"))
        worker.execute {
            var lease: AutoCloseable? = null
            try {
                val store = PackageRuntime.store(this); lease = store.pin(id)
                val verified = PackageRuntime.verifier(this).verify(store,id,type)
                val retained = lease; lease = null
                runOnUiThread {
                    if (dead) retained?.close()
                    else {
                        pin = retained; content = verified
                        if (intent.getStringExtra("mode") == "apply") {
                            close.isEnabled = false
                            if (type == "STATIC_IMAGE") applyStatic(verified) else openSystemPicker(id)
                        } else if (type == "STATIC_IMAGE") showImage(verified,frame) else showVideo(frame)
                    }
                }
            } catch (_: Exception) { lease?.close(); runOnUiThread { finishWith(WallpaperOutcome("unknown","本地资源校验失败，请重新下载")) } }
        }
    }
    private fun showImage(verified: InstalledPackage, frame: FrameLayout) {
        worker.execute {
            var decoded: Bitmap? = null
            try {
                val file = verified.content("STATIC_IMAGE")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath,bounds)
                require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
                var sample = 1
                val pixels = resources.displayMetrics
                while (bounds.outWidth / (sample*2) >= pixels.widthPixels && bounds.outHeight / (sample*2) >= pixels.heightPixels) sample *= 2
                val required = (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) * 4 + 16*1024*1024
                val runtime = Runtime.getRuntime(); require(runtime.maxMemory() - (runtime.totalMemory()-runtime.freeMemory()) > required)
                decoded = BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Undecodable image")
                val image = decoded; decoded = null
                runOnUiThread {
                    if (dead) image?.recycle()
                    else { bitmap = image; frame.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(image) },FrameLayout.LayoutParams(-1,-1)); rendered = true; message.text = "静态预览 · 仅在 App 内显示" }
                }
            } catch (_: Exception) { decoded?.recycle(); runOnUiThread { finishWith(WallpaperOutcome("unknown","图片无法预览，请重新下载")) } }
            catch (_: OutOfMemoryError) { decoded?.recycle(); runOnUiThread { finishWith(WallpaperOutcome("unknown","可用内存不足，请关闭其他应用后重试")) } }
        }
    }
    private fun showVideo(frame: FrameLayout) {
        surface = SurfaceView(this).also { view ->
            view.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { if (resumed) play() }
                override fun surfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) { }
                override fun surfaceDestroyed(holder: SurfaceHolder) { player.close() }
            }); frame.addView(view,FrameLayout.LayoutParams(-1,-1))
        }
    }
    private fun play() {
        val holder = surface?.holder ?: return
        if (holder.surface.isValid) content?.let { player.open(it.content("VIDEO"),holder) }
    }
    private fun applyStatic(verified: InstalledPackage) {
        settingStatic = true
        val settingPin = PackageRuntime.store(this).pin(verified.id)
        message.text = "正在设置系统壁纸…"
        worker.execute {
            val outcome = try {
                val manager = WallpaperManager.getInstance(this)
                if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) WallpaperOutcome("unsupported","此系统暂不允许设置壁纸")
                else {
                    val target = intent.getStringExtra("target") ?: error("Missing target")
                    val flags = when(target) { "home" -> WallpaperManager.FLAG_SYSTEM; "lock" -> WallpaperManager.FLAG_LOCK; "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK; else -> error("Invalid target") }
                    val id = verified.content("STATIC_IMAGE").inputStream().use { manager.setStream(it,null,false,flags) }
                    val result = WallpaperResults.static(id,manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM),manager.getWallpaperId(WallpaperManager.FLAG_LOCK),target)
                    // Even if an OEM cannot confirm IDs, retain the requested resource until the user checks.
                    if (id > 0) {
                        val store = PackageRuntime.store(this)
                        if (target != "lock") store.markActive(verified.id,"home")
                        if (target != "home") store.markActive(verified.id,"lock")
                    }
                    result
                }
            } catch (_: Exception) { WallpaperOutcome("unknown","系统设置失败或结果未知，请到桌面或锁屏检查后重试") }
            settingPin.close()
            runOnUiThread { settingStatic = false; finishWith(outcome) }
        }
    }
    private fun openSystemPicker(id: String) {
        try {
            LiveWallpaperPolicy.blockedMessage(this)?.let { return finishWith(WallpaperOutcome("unsupported",it)) }
            val manager = WallpaperManager.getInstance(this)
            if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) return finishWith(WallpaperOutcome("unsupported","此系统暂不允许设置动态壁纸"))
            require(intent.getStringExtra("target") == "home") // This entry delegates location selection to the system picker.
            val picker = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,ComponentName(this,VideoWallpaperService::class.java))
            if (picker.resolveActivity(packageManager) == null) return finishWith(WallpaperOutcome("unsupported","此手机没有可用的动态壁纸设置入口"))
            selectionToken = LiveSelection.begin(this,id)
            message.text = "位置由系统选择；桌面和锁屏使用此服务时共用同一视频"
            startActivityForResult(picker,701)
        } catch (_: Exception) {
            selectionToken?.let { LiveSelection.finish(this,it,false) }; selectionToken = null
            finishWith(WallpaperOutcome("unsupported","无法打开系统动态壁纸设置，请重试或查看教程"))
        }
    }
    @Deprecated("Android Activity callback")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode != 701) return
        val token = selectionToken ?: return finishWith(WallpaperOutcome("unknown","设置请求已失效，请重试"))
        val accepted = resultCode == RESULT_OK
        val ready = LiveSelection.isReady(this,token)
        val outcome = try {
            val manager = WallpaperManager.getInstance(this); val component = ComponentName(this,VideoWallpaperService::class.java)
            val home = manager.wallpaperInfo?.component == component
            val lock = Build.VERSION.SDK_INT >= 34 && manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.component == component
            val result = WallpaperResults.live(accepted,ready,home,lock,Build.VERSION.SDK_INT >= 34)
            LiveSelection.finish(this,token,result.retainLiveContent,home,lock); result
        } catch (_: Exception) {
            LiveSelection.finish(this,token,accepted && ready && Build.VERSION.SDK_INT < 34)
            WallpaperOutcome("unknown","系统设置结果不可确认，请检查桌面或锁屏")
        }
        selectionToken = null; finishWith(outcome)
    }
    override fun onSaveInstanceState(state: Bundle) { state.putString("selectionToken",selectionToken); super.onSaveInstanceState(state) }
    override fun onResume() { super.onResume(); resumed = true; play() }
    override fun onPause() { resumed = false; player.close(); super.onPause() }
    @Deprecated("Android Activity callback")
    override fun onBackPressed() { exitPreview() }
    private fun exitPreview() {
        if (settingStatic) return
        if (intent.getStringExtra("mode") == "apply") return finishWith(WallpaperOutcome("unknown","设置结果待确认，请检查系统壁纸"))
        finishWith(if (rendered) WallpaperOutcome("completed","预览已结束") else WallpaperOutcome("cancelled","预览已取消"))
    }
    private fun finishWith(outcome: WallpaperOutcome) {
        if (dead || isFinishing) return
        setResult(RESULT_OK,Intent().putExtra("status",outcome.status).putExtra("message",outcome.message)); finish()
    }
    override fun onDestroy() {
        dead = true; player.close(); bitmap?.recycle(); pin?.close(); worker.shutdown()
        if (isFinishing) selectionToken?.let { LiveSelection.finish(this,it,false) }
        super.onDestroy()
    }
}
