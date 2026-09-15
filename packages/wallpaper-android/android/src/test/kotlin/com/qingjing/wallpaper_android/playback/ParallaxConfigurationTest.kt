package com.qingjing.wallpaper_android.playback

import org.junit.Assert.*
import org.junit.Test

class ParallaxConfigurationTest {
    private val valid = """{"canvas":{"width":1080,"height":2400},"sensor":{"maxAngle":10,"smoothing":0.2,"strength":1},"layers":[{"role":"BACKGROUND","ordinal":0,"depth":0,"scale":1.1,"opacity":1,"blendMode":"normal"},{"role":"FOREGROUND","ordinal":0,"depth":1,"scale":1.2,"opacity":0.5,"blendMode":"screen"}]}"""
    private val offsets = """{"formatVersion":2,"canvas":{"width":1080,"height":2400},"motion":{"maxAngle":75},"layers":[{"index":1,"offsetPercent":8,"scale":1.18,"opacity":0.5,"blendMode":"screen"},{"index":2,"offsetPercent":-3,"scale":1,"opacity":1,"blendMode":"normal"}]}"""
    @Test fun retainsSignedRenderingParameters() {
        val config = ParallaxConfiguration.parse(valid.toByteArray())
        assertEquals(1080,config.width); assertEquals(2400,config.height); assertEquals(.2f,config.smoothing,0f)
        assertEquals(.5f,config.layers[1].opacity,0f); assertEquals("screen",config.layers[1].blend)
        assertEquals("add",ParallaxConfiguration.parse(valid.replace("screen","add").toByteArray()).layers[1].blend)
    }
    @Test fun parsesSignedOffsetFormatAndReversesSourceOrderForDrawing() {
        val config=ParallaxConfiguration.parse(offsets.toByteArray())
        assertEquals(75f,config.maxAngle,0f)
        assertEquals(listOf("BACKGROUND","FOREGROUND"),config.layers.map { it.role })
        assertEquals(-3f,config.layers[0].offsetPercent!!,0f)
        assertEquals(8f,config.layers[1].offsetPercent!!,0f)
        assertEquals(0,config.layers[1].ordinal)
    }
    @Test fun rejectsInvalidOrAmbiguousConfiguration() {
        val broken = listOf(
            valid.replace("\"width\":1080","\"width\":4097"), valid.replace("\"width\":1080","\"width\":1080.1"),
            valid.replace("\"maxAngle\":10","\"maxAngle\":0"),valid.replace("\"maxAngle\":10","\"maxAngle\":25.1"),valid.replace("\"smoothing\":0.2","\"smoothing\":1"),
            valid.replace("\"strength\":1","\"strength\":3"),valid.replace("\"depth\":1","\"depth\":-1"),
            valid.replace("\"scale\":1.2","\"scale\":2"),valid.replace("\"opacity\":0.5","\"opacity\":2"),
            valid.replace("screen","multiply"),valid.replace("FOREGROUND","BACKGROUND"),
            valid.replace("\"maxAngle\":10","\"maxAngle\":10,\"maxAngle\":15"))
        for (value in broken) assertThrows(RuntimeException::class.java) { ParallaxConfiguration.parse(value.toByteArray()) }
        for(value in listOf(
            offsets.replace("\"index\":1","\"index\":2"),
            offsets.replace("\"offsetPercent\":8","\"offsetPercent\":26"),
            offsets.replace("\"maxAngle\":75","\"maxAngle\":0")
        )) assertThrows(RuntimeException::class.java) { ParallaxConfiguration.parse(value.toByteArray()) }
    }
}
