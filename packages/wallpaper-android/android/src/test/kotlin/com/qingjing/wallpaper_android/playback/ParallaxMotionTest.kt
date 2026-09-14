package com.qingjing.wallpaper_android.playback

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class ParallaxMotionTest {
    @Test fun calibrationAndAngleWrap() {
        assertEquals(.02f,ParallaxMotion.angleDelta((-PI+.01).toFloat(),(PI-.01).toFloat()),.0001f)
        assertEquals(-.02f,ParallaxMotion.angleDelta((PI-.01).toFloat(),(-PI+.01).toFloat()),.0001f)
        assertEquals(0f to 0f,ParallaxMotion.tilt(1f,2f,1f,2f,10f,0))
        assertEquals(0f,ParallaxMotion.angleDelta(Float.NaN,0f),0f)
    }
    @Test fun rotationsAndClampedRange() {
        val angle = Math.toRadians(10.0).toFloat()
        assertEquals(1f to 0f,ParallaxMotion.tilt(angle,0f,0f,0f,10f,0))
        val p = ParallaxMotion.tilt(angle,0f,0f,0f,10f,1); assertEquals(0f,p.first,0f); assertEquals(1f,p.second,0f)
        val q = ParallaxMotion.tilt(angle,0f,0f,0f,10f,2); assertEquals(-1f,q.first,0f); assertEquals(0f,q.second,0f)
        val r = ParallaxMotion.tilt(angle,0f,0f,0f,10f,3); assertEquals(0f,r.first,0f); assertEquals(-1f,r.second,0f)
        assertEquals(1f,ParallaxMotion.tilt(1f,0f,0f,0f,5f,0).first,0f)
    }
    @Test fun extremeMotionNeverExposesUncoveredEdges() {
        for ((w,h) in listOf(1080 to 2400,2400 to 1080,512 to 512))
            for ((iw,ih) in listOf(512 to 512,1080 to 2400,4096 to 512))
                for (scale in listOf(1f,1.1f,1.5f)) for (x in listOf(-1f,1f)) for (y in listOf(-1f,1f)) {
                    val p = ParallaxMotion.placement(w,h,iw,ih,scale,1f,2f,x,y)
                    assertTrue(p.left<=.001f && p.top<=.001f)
                    assertTrue(p.left+iw*p.scale>=w-.001f && p.top+ih*p.scale>=h-.001f)
                }
    }
    @Test fun zeroStrengthAndDepthStayCentered() {
        val a = ParallaxMotion.placement(1080,2400,1080,2400,1.1f,1f,0f,1f,1f)
        val b = ParallaxMotion.placement(1080,2400,1080,2400,1.1f,0f,2f,1f,1f)
        assertEquals(a,b)
    }
    @Test fun smoothingHasSameProgressAcrossFrameRates() {
        for (smoothing in listOf(.05f,.2f,.5f)) {
            val two = ParallaxMotion.smooth(ParallaxMotion.smooth(0f,1f,smoothing,20),1f,smoothing,20)
            assertEquals(two,ParallaxMotion.smooth(0f,1f,smoothing,40),.00001f)
            assertTrue(ParallaxMotion.smooth(0f,1f,smoothing,10000) in 0f..1f)
        }
    }
    @Test fun decodeBudgetIncludesEveryLayer() {
        val budget = 48L*1024*1024
        val sample = ParallaxMotion.sample(4096,4096,12,budget)
        assertEquals(4,sample)
        assertTrue((4096/sample).toLong()*(4096/sample)*12*4<=budget)
        assertEquals(1,ParallaxMotion.sample(1080,2400,2,budget))
        assertThrows(IllegalArgumentException::class.java) { ParallaxMotion.sample(4096,4096,12,-1) }
    }
}
