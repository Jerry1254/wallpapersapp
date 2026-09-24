package com.qingjing.wallpaper_android.playback

import org.junit.Assert.*
import org.junit.Test

class ParallaxMotionTest {
    @Test fun physicallyFlatPhoneIsAlwaysNeutral() {
        for(rotation in 0..3) {
            val value=ParallaxMotion.tilt(0f,0f,10f,20f,rotation)
            assertEquals(0f,value.first,0f)
            assertEquals(0f,value.second,0f)
        }
    }
    @Test fun absoluteTiltsRespectScreenRotationAndClampedRange() {
        val angle = Math.toRadians(10.0).toFloat()
        assertEquals(1f to 0f,ParallaxMotion.tilt(angle,0f,10f,20f,0))
        val p = ParallaxMotion.tilt(angle,0f,20f,10f,1); assertEquals(0f,p.first,0f); assertEquals(1f,p.second,0f)
        val q = ParallaxMotion.tilt(angle,0f,10f,20f,2); assertEquals(-1f,q.first,0f); assertEquals(0f,q.second,0f)
        val r = ParallaxMotion.tilt(angle,0f,20f,10f,3); assertEquals(0f,r.first,0f); assertEquals(-1f,r.second,0f)
        assertEquals(1f,ParallaxMotion.tilt(1f,0f,5f,75f,0).first,0f)
    }
    @Test fun respondsFromZeroAndSupportsSeventyFiveDegreeFullScale() {
        val tiny = ParallaxMotion.tilt(Math.toRadians(.1).toFloat(),0f,75f,75f,0).first
        assertTrue(tiny > 0f)
        assertEquals(1f,ParallaxMotion.tilt(Math.toRadians(75.0).toFloat(),0f,75f,75f,0).first,.00001f)
    }
    @Test fun extremeMotionNeverExposesUncoveredEdgesWithoutNeutralCorrection() {
        for ((w,h) in listOf(1080 to 2400,2400 to 1080,512 to 512))
            for ((iw,ih) in listOf(512 to 512,1080 to 2400,4096 to 512))
                for (scale in listOf(1f,1.1f,1.5f)) for (x in listOf(-1f,1f)) for (y in listOf(-1f,1f)) {
                    val p = ParallaxMotion.placement(w,h,iw,ih,scale,300f,150f,0f,0f,"follow",x,y,true)
                    assertTrue(p.left<=.001f && p.top<=.001f)
                    assertTrue(p.left+iw*p.scale>=w-.001f && p.top+ih*p.scale>=h-.001f)
                }
    }
    @Test fun explicitDirectionsMoveLayersInOppositeDirections() {
        val foreground=ParallaxMotion.placement(1000,2000,1000,2000,1f,100f,50f,10f,-5f,"follow",1f,1f,false)
        val background=ParallaxMotion.placement(1000,2000,1000,2000,1f,20f,10f,0f,0f,"reverse",1f,1f,true)
        assertEquals(-900f,foreground.left,.001f)
        assertEquals(900f,foreground.top,.001f)
        assertEquals(0f,background.left,.001f)
        assertEquals(-600f,background.top,.001f)
    }
    @Test fun backgroundOverscanCoversMaximumDirectionalTravel() {
        for(percent in listOf(0f,20f,100f,300f)) for(direction in listOf("follow","reverse","fixed")) for(x in listOf(-1f,1f)) for(y in listOf(-1f,1f)) {
            val p=ParallaxMotion.placement(1080,2400,1080,2400,1f,percent,percent/2f,0f,0f,direction,x,y,true)
            assertTrue(p.left<=.001f && p.top<=.001f)
            assertTrue(p.left+1080*p.scale>=1080-.001f)
            assertTrue(p.top+2400*p.scale>=2400-.001f)
        }
    }
    @Test fun zeroOffsetStaysCentered() {
        val corrected = ParallaxMotion.placement(1080,2400,1080,2400,1.1f,100f,100f,10f,-5f,"fixed",1f,1f,false)
        assertEquals(ParallaxMotion.Placement(1.1f,54f,-240f),corrected)
    }
    @Test fun neutralCorrectionDoesNotChangeBackgroundScaleOrAnimationTravel() {
        val centeredStart=ParallaxMotion.placement(1000,2000,1000,2000,1f,20f,10f,0f,0f,"follow",-1f,-1f,true)
        val centeredEnd=ParallaxMotion.placement(1000,2000,1000,2000,1f,20f,10f,0f,0f,"follow",1f,1f,true)
        val correctedStart=ParallaxMotion.placement(1000,2000,1000,2000,1f,20f,10f,35f,-25f,"follow",-1f,-1f,true)
        val correctedEnd=ParallaxMotion.placement(1000,2000,1000,2000,1f,20f,10f,35f,-25f,"follow",1f,1f,true)
        assertEquals(centeredStart.scale,correctedStart.scale,.001f)
        assertEquals(centeredEnd.left-centeredStart.left,correctedEnd.left-correctedStart.left,.001f)
        assertEquals(centeredEnd.top-centeredStart.top,correctedEnd.top-correctedStart.top,.001f)
    }
    @Test fun smoothingHasSameProgressAcrossFrameRates() {
        for (smoothing in listOf(.05f,.2f,.5f)) {
            val two = ParallaxMotion.smooth(ParallaxMotion.smooth(0f,1f,smoothing,20),1f,smoothing,20)
            assertEquals(two,ParallaxMotion.smooth(0f,1f,smoothing,40),.00001f)
            assertTrue(ParallaxMotion.smooth(0f,1f,smoothing,10000) in 0f..1f)
        }
    }
    @Test fun fullResolutionAllocationIncludesEverySourcePixel() {
        assertEquals(80L*1024*1024,ParallaxMotion.fullResolutionBytes(2048,2048,5))
        assertEquals(768L*1024*1024,ParallaxMotion.fullResolutionBytes(4096,4096,12))
        assertThrows(IllegalArgumentException::class.java) { ParallaxMotion.fullResolutionBytes(4096,4096,1) }
    }
}
