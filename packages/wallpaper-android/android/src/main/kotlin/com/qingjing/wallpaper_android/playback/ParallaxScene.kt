package com.qingjing.wallpaper_android.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.qingjing.wallpaper_android.install.InstalledPackage
import java.io.File

/** PoC centered cover/depth rendering. Only verified private files; total scene allocation is bounded. */
internal class ParallaxScene private constructor(val configuration: ParallaxConfiguration,private val bitmaps: List<Bitmap>) : AutoCloseable {
    val decodedBytes get() = bitmaps.sumOf { it.allocationByteCount.toLong() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val modes = mapOf("screen" to PorterDuffXfermode(PorterDuff.Mode.SCREEN),"add" to PorterDuffXfermode(PorterDuff.Mode.ADD))
    fun draw(canvas: Canvas,x: Float,y: Float) {
        canvas.drawColor(Color.BLACK)
        for ((index,layer) in configuration.layers.withIndex()) {
            val bitmap = bitmaps[index]
            val p = ParallaxMotion.placement(canvas.width,canvas.height,bitmap.width,bitmap.height,layer.scale,layer.depth,configuration.strength,x,y)
            matrix.reset(); matrix.setScale(p.scale,p.scale); matrix.postTranslate(p.left,p.top)
            paint.alpha = (255*layer.opacity).toInt(); paint.xfermode = modes[layer.blend]
            canvas.drawBitmap(bitmap,matrix,paint)
        }
        paint.xfermode = null
    }
    override fun close() { bitmaps.forEach { if (!it.isRecycled) it.recycle() } }
    companion object {
        fun load(installed: InstalledPackage,cancelled: () -> Boolean): ParallaxScene {
            require(installed.type == "LAYER_PARALLAX")
            val config = ParallaxConfiguration.parse(installed.content("PARALLAX_CONFIG").readBytes())
            require(installed.files.filter { it.role != "PARALLAX_CONFIG" }.map { it.role to it.ordinal }.toSet() == config.layers.map { it.role to it.ordinal }.toSet())
            val runtime = Runtime.getRuntime()
            val spare = runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory())
            val budget = minOf(48L*1024*1024,spare-16L*1024*1024)
            val sample = ParallaxMotion.sample(config.width,config.height,config.layers.size,budget)
            val images = mutableListOf<Bitmap>()
            try {
                for (layer in config.layers) {
                    check(!cancelled())
                    val entry = installed.files.single { it.role == layer.role && it.ordinal == layer.ordinal }
                    val file = File(installed.directory,entry.path)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath,bounds)
                    require(bounds.outWidth == config.width && bounds.outHeight == config.height)
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: error("Undecodable layer")
                    images.add(bitmap)
                    require(layer.role != "FOREGROUND" || bitmap.hasAlpha())
                    require(images.sumOf { it.allocationByteCount.toLong() }<=budget)
                }
                check(!cancelled()); return ParallaxScene(config,images)
            } catch (error: Exception) { images.forEach { it.recycle() }; throw error }
            catch (error: OutOfMemoryError) { images.forEach { it.recycle() }; throw error }
        }
    }
}
