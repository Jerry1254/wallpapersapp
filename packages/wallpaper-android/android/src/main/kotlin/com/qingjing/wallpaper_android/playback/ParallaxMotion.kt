package com.qingjing.wallpaper_android.playback

import kotlin.math.max
import kotlin.math.abs
import kotlin.math.pow

/** Geometry migrated from PoC TiltSensor/ParallaxScene, independent of Android for boundary tests. */
internal object ParallaxMotion {
    /** At 100% authoring strength a layer travels 15% of the corresponding screen axis. */
    const val MAX_TRAVEL_FRACTION = .15f
    fun angleDelta(angle: Float, base: Float): Float {
        if (!angle.isFinite() || !base.isFinite()) return 0f
        return kotlin.math.atan2(kotlin.math.sin(angle-base),kotlin.math.cos(angle-base))
    }
    fun tilt(roll: Float,pitch: Float,baseRoll: Float,basePitch: Float,maxAngle: Float,rotation: Int): Pair<Float,Float> {
        require(maxAngle.isFinite() && maxAngle>0f && maxAngle<=75f && rotation in 0..3)
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

    /** 0..100 authoring strength with an explicit direction, shared by Web config v2. */
    fun placement(width: Int,height: Int,imageWidth: Int,imageHeight: Int,scale: Float,
                  offsetPercent: Float,direction: String,x: Float,y: Float,background: Boolean): Placement {
        require(width>0 && height>0 && imageWidth>0 && imageHeight>0)
        require(scale in 1f..1.5f && offsetPercent.isFinite() && offsetPercent in 0f..100f)
        require(direction in setOf("follow","reverse","fixed"))
        val sign=when(direction) { "follow" -> 1f; "reverse" -> -1f; else -> 0f }
        val fraction=sign*offsetPercent/100f*MAX_TRAVEL_FRACTION
        val overscan=if(background) 1f+2f*abs(fraction) else 1f
        val cover=max(width.toFloat()/imageWidth,height.toFloat()/imageHeight)*max(scale,overscan)
        val marginX=max(0f,(imageWidth*cover-width)/2f)
        val marginY=max(0f,(imageHeight*cover-height)/2f)
        val requestedX=-x.coerceIn(-1f,1f)*width*fraction
        val requestedY=y.coerceIn(-1f,1f)*height*fraction
        val motionX=if(background) requestedX.coerceIn(-marginX,marginX) else requestedX
        val motionY=if(background) requestedY.coerceIn(-marginY,marginY) else requestedY
        return Placement(cover,-marginX+motionX,-marginY+motionY)
    }

    /** Exact ARGB_8888 allocation required to preserve every source pixel in every layer. */
    fun fullResolutionBytes(width: Int,height: Int,layers: Int): Long {
        require(width in 512..4096 && height in 512..4096 && layers in 2..12)
        return width.toLong()*height*layers*4
    }
}
