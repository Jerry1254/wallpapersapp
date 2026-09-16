package com.qingjing.wallpaper_android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ParallaxConfigurationTest {
    private val valid = """{"formatVersion":2,"canvas":{"width":1080,"height":2400},"motion":{"maxAngle":75},"layers":[{"index":1,"offsetPercent":300,"direction":"follow","scale":1.18,"opacity":0.5,"blendMode":"screen"},{"index":2,"offsetPercent":0,"direction":"fixed","scale":1.1,"opacity":1,"blendMode":"add"},{"index":3,"offsetPercent":20,"direction":"reverse","scale":1,"opacity":1,"blendMode":"normal"}]}"""

    @Test fun parsesDirectionalStrengthAndReversesSourceOrderForDrawing() {
        val config = ParallaxConfiguration.parse(valid.toByteArray())
        assertEquals(1080, config.width)
        assertEquals(2400, config.height)
        assertEquals(75f, config.maxAngle, 0f)
        assertEquals(.2f, config.smoothing, 0f)
        assertEquals(listOf("BACKGROUND", "FOREGROUND", "FOREGROUND"), config.layers.map { it.role })
        assertEquals(listOf(0, 1, 0), config.layers.map { it.ordinal })
        assertEquals(listOf(20f, 0f, 300f), config.layers.map { it.offsetPercent })
        assertEquals(listOf("reverse", "fixed", "follow"), config.layers.map { it.direction })
        assertEquals(listOf("normal", "add", "screen"), config.layers.map { it.blend })
    }

    @Test fun rejectsLegacyAndInvalidConfiguration() {
        val legacy = """{"canvas":{"width":1080,"height":2400},"sensor":{"maxAngle":10,"smoothing":0.2,"strength":1},"layers":[{"role":"BACKGROUND","ordinal":0,"depth":0,"scale":1.1,"opacity":1,"blendMode":"normal"},{"role":"FOREGROUND","ordinal":0,"depth":1,"scale":1.2,"opacity":1,"blendMode":"normal"}]}"""
        val broken = listOf(
            legacy,
            valid.replace("\"formatVersion\":2", "\"formatVersion\":1"),
            valid.replace("\"width\":1080", "\"width\":4097"),
            valid.replace("\"width\":1080", "\"width\":1080.1"),
            valid.replace("\"maxAngle\":75", "\"maxAngle\":0"),
            valid.replace("\"maxAngle\":75", "\"maxAngle\":76"),
            valid.replace("\"index\":1", "\"index\":2"),
            valid.replace("\"offsetPercent\":300", "\"offsetPercent\":-1"),
            valid.replace("\"direction\":\"follow\"", "\"direction\":\"sideways\""),
            valid.replace("\"scale\":1.18", "\"scale\":2"),
            valid.replace("\"opacity\":0.5", "\"opacity\":2"),
            valid.replace("screen", "multiply"),
            valid.replace("\"maxAngle\":75", "\"maxAngle\":75,\"maxAngle\":10")
        )
        for (value in broken) {
            assertThrows(RuntimeException::class.java) { ParallaxConfiguration.parse(value.toByteArray()) }
        }
    }
}
