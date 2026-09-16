package com.qingjing.wallpaper_android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ParallaxConfigurationTest {
    private val valid = """{"formatVersion":2,"canvas":{"width":1080,"height":2400},"motion":{"maxAngleX":75,"maxAngleY":50},"layers":[{"index":1,"offsetXPercent":300,"offsetYPercent":150,"initialOffsetXPercent":-25,"initialOffsetYPercent":10,"direction":"follow","scale":1.18,"opacity":0.5,"blendMode":"screen"},{"index":2,"offsetXPercent":0,"offsetYPercent":0,"initialOffsetXPercent":200,"initialOffsetYPercent":-300,"direction":"fixed","scale":1.1,"opacity":1,"blendMode":"add"},{"index":3,"offsetXPercent":20,"offsetYPercent":10,"initialOffsetXPercent":0,"initialOffsetYPercent":0,"direction":"reverse","scale":1,"opacity":1,"blendMode":"normal"}]}"""

    @Test fun parsesIndependentAxesAndReversesSourceOrderForDrawing() {
        val config = ParallaxConfiguration.parse(valid.toByteArray())
        assertEquals(1080, config.width)
        assertEquals(2400, config.height)
        assertEquals(75f, config.maxAngleX, 0f)
        assertEquals(50f, config.maxAngleY, 0f)
        assertEquals(.2f, config.smoothing, 0f)
        assertEquals(listOf("BACKGROUND", "FOREGROUND", "FOREGROUND"), config.layers.map { it.role })
        assertEquals(listOf(0, 1, 0), config.layers.map { it.ordinal })
        assertEquals(listOf(20f, 0f, 300f), config.layers.map { it.offsetXPercent })
        assertEquals(listOf(10f, 0f, 150f), config.layers.map { it.offsetYPercent })
        assertEquals(listOf(0f, 200f, -25f), config.layers.map { it.initialOffsetXPercent })
        assertEquals(listOf(0f, -300f, 10f), config.layers.map { it.initialOffsetYPercent })
        assertEquals(listOf("reverse", "fixed", "follow"), config.layers.map { it.direction })
        assertEquals(listOf("normal", "add", "screen"), config.layers.map { it.blend })
    }

    @Test fun rejectsLegacyAndInvalidConfiguration() {
        val legacy = """{"formatVersion":1,"canvas":{"width":1080,"height":2400},"sensor":{"maxAngle":10,"strength":1},"layers":[]}"""
        val broken = listOf(
            legacy,
            valid.replace("\"formatVersion\":2", "\"formatVersion\":1"),
            valid.replace("\"width\":1080", "\"width\":4097"),
            valid.replace("\"width\":1080", "\"width\":1080.1"),
            valid.replace("\"maxAngleX\":75", "\"maxAngleX\":0"),
            valid.replace("\"maxAngleY\":50", "\"maxAngleY\":76"),
            valid.replace("\"index\":1", "\"index\":2"),
            valid.replace("\"offsetXPercent\":300", "\"offsetXPercent\":-1"),
            valid.replace("\"offsetYPercent\":150", "\"offsetYPercent\":-1"),
            valid.replace("\"initialOffsetXPercent\":-25", "\"initialOffsetXPercent\":\"bad\""),
            valid.replace("\"direction\":\"follow\"", "\"direction\":\"sideways\""),
            valid.replace("\"scale\":1.18", "\"scale\":2"),
            valid.replace("\"opacity\":0.5", "\"opacity\":2"),
            valid.replace("screen", "multiply"),
            valid.replace("\"maxAngleX\":75", "\"maxAngleX\":75,\"maxAngleX\":10")
        )
        for (value in broken) assertThrows(RuntimeException::class.java) { ParallaxConfiguration.parse(value.toByteArray()) }
    }
}
