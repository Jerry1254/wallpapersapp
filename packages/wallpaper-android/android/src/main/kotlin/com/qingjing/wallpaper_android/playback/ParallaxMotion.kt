package com.qingjing.wallpaper_android.playback

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Geometry migrated from PoC TiltSensor/ParallaxScene, independent of Android for boundary tests. */
internal object ParallaxMotion {
    fun angleDelta(angle: Float, base: Float): Float {
        if (!angle.isFinite() || !base.isFinite()) return 0f
        return kotlin.math.atan2(kotlin.math.sin(angle-base),kotlin.math.cos(angle-base))
    }
    fun tilt(roll: Float,pitch: Float,baseRoll: Float,basePitch: Float,maxAngle: Float,rotation: Int): Pair<Float,Float> {
        require(maxAngle.isFinite() && maxAngle in 5f..25f && rotation in 0..3)
        val radians = Math.toRadians(maxAngle.toDouble()).toFloat()
        val x = (angleDelta(roll,baseRoll)/radians).coerceIn(-1f,1f)
        val y = (angleDelta(pitch,basePitch)/radians).coerceIn(-1f,1f)
        return when(rotation) { 1 -> -y to x; 2 -> -x to -y; 3 -> y to -x; else -> x to y }
    }
    fun smooth(current: Float,target: Float,smoothing: Float,elapsedMs: Long): Float {
        require(smoothing in .05f.. .5f)
        val alpha = 1.0-(1.0-smoothing).pow(elapsedMs.coerceIn(1,100).toDouble()/20.0)
        return (current+(target.coerceIn(-1f,1f)-current)*alpha).toFloat()
    }
    data class Placement(val scale: Float,val left: Float,val top: Float)
    fun placement(width: Int,height: Int,imageWidth: Int,imageHeight: Int,scale: Float,depth: Float,strength: Float,x: Float,y: Float): Placement {
        require(width>0 && height>0 && imageWidth>0 && imageHeight>0)
        require(scale in 1f..1.5f && depth in 0f..1f && strength in 0f..2f)
        val cover = max(width.toFloat()/imageWidth,height.toFloat()/imageHeight)*max(1.18f,scale)
        val marginX = max(0f,(imageWidth*cover-width)/2f)
        val marginY = max(0f,(imageHeight*cover-height)/2f)
        val motionX = -x.coerceIn(-1f,1f)*min(width*.09f*depth*strength,marginX)
        val motionY = y.coerceIn(-1f,1f)*min(height*.05f*depth*strength,marginY)
        return Placement(cover,-marginX+motionX,-marginY+motionY)
    }
    /** Bound all layers together; downsample preserves alpha and never allocates a full oversized scene first. */
    fun sample(width: Int,height: Int,layers: Int,budget: Long): Int {
        require(width in 512..4096 && height in 512..4096 && layers in 2..12 && budget>=65536)
        var sample = 1
        while (((width+sample-1)/sample).toLong()*((height+sample-1)/sample)*layers*4 > budget) sample *= 2
        return sample
    }
}
