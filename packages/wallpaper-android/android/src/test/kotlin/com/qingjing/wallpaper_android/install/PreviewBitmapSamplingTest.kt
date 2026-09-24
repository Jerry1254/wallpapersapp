package com.qingjing.wallpaper_android.install

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewBitmapSamplingTest {
    @Test fun keepsScreenSizedImagesAtFullDecodeResolution() {
        assertEquals(1,PreviewBitmapSampling.inSampleSize(1080,2400,5_200_000))
    }

    @Test fun samplesLargeSourcesToADeviceAppropriateDecodeSize() {
        assertEquals(2,PreviewBitmapSampling.inSampleSize(4096,4096,5_200_000))
        assertEquals(2,PreviewBitmapSampling.inSampleSize(2160,3840,5_200_000))
    }

    @Test fun derivesAStableBudgetFromThePhysicalDisplay() {
        assertEquals(5_184_000,PreviewBitmapSampling.pixelBudget(1080,2400))
        assertEquals(4_000_000,PreviewBitmapSampling.pixelBudget(720,1280))
        assertEquals(6_000_000,PreviewBitmapSampling.pixelBudget(1440,3200))
    }
}
