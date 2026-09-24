package com.qingjing.wallpaper_android.playback

import kotlin.math.max
import kotlin.math.abs
import kotlin.math.pow

/** Geometry migrated from PoC TiltSensor/ParallaxScene, independent of Android for boundary tests. */
internal object ParallaxMotion {
    fun tilt(roll: Float,pitch: Float,maxAngleX: Float,maxAngleY: Float,rotation: Int): Pair<Float,Float> {
        require(maxAngleX.isFinite() && maxAngleX>0f && maxAngleX<=75f && maxAngleY.isFinite() && maxAngleY>0f && maxAngleY<=75f && rotation in 0..3)
        val (screenX,screenY)=when(rotation) { 1 -> -pitch to roll; 2 -> -roll to -pitch; 3 -> pitch to -roll; else -> roll to pitch }
        val x=(screenX/Math.toRadians(maxAngleX.toDouble()).toFloat()).coerceIn(-1f,1f)
        val y=(screenY/Math.toRadians(maxAngleY.toDouble()).toFloat()).coerceIn(-1f,1f)
        return x to y
    }
    fun smooth(current: Float,target: Float,smoothing: Float,elapsedMs: Long): Float {
        require(smoothing in .05f.. .5f)
        val alpha = 1.0-(1.0-smoothing).pow(elapsedMs.coerceIn(1,100).toDouble()/20.0)
        return (current+(target.coerceIn(-1f,1f)-current)*alpha).toFloat()
    }
    data class Placement(val scale: Float,val left: Float,val top: Float)

    /** Independent screen-axis percentages plus a signed neutral-position correction. */
    fun placement(width: Int,height: Int,imageWidth: Int,imageHeight: Int,scale: Float,
                  offsetXPercent: Float,offsetYPercent: Float,initialOffsetXPercent: Float,initialOffsetYPercent: Float,
                  direction: String,x: Float,y: Float,background: Boolean): Placement {
        require(width>0 && height>0 && imageWidth>0 && imageHeight>0)
        require(scale in 1f..1.5f && offsetXPercent.isFinite() && offsetXPercent>=0f && offsetYPercent.isFinite() && offsetYPercent>=0f)
        require(initialOffsetXPercent.isFinite() && initialOffsetYPercent.isFinite())
        require(direction in setOf("follow","reverse","fixed"))
        val sign=when(direction) { "follow" -> 1f; "reverse" -> -1f; else -> 0f }
        val fractionX=sign*offsetXPercent/100f
        val fractionY=sign*offsetYPercent/100f
        val originX=initialOffsetXPercent/100f
        val originY=initialOffsetYPercent/100f
        // Neutral-position correction is deliberately excluded from protection scaling.
        // It only translates the layer; motion strength alone determines animation scale.
        val overscan=if(background) 1f+2f*max(abs(fractionX),abs(fractionY)) else 1f
        val cover=max(width.toFloat()/imageWidth,height.toFloat()/imageHeight)*max(scale,overscan)
        val marginX=max(0f,(imageWidth*cover-width)/2f)
        val marginY=max(0f,(imageHeight*cover-height)/2f)
        val neutralX=width*originX
        val neutralY=height*originY
        val motionX=-width*x.coerceIn(-1f,1f)*fractionX
        val motionY=height*y.coerceIn(-1f,1f)*fractionY
        return Placement(cover,-marginX+neutralX+motionX,-marginY+neutralY+motionY)
    }

    /** Exact ARGB_8888 allocation required to preserve every source pixel in every layer. */
    fun fullResolutionBytes(width: Int,height: Int,layers: Int): Long {
        require(width in 512..4096 && height in 512..4096 && layers in 2..12)
        return width.toLong()*height*layers*4
    }
}
