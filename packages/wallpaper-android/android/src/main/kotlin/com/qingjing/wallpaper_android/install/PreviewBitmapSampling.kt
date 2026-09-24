package com.qingjing.wallpaper_android.install

/** Keeps full source files on disk while decoding only the pixels a phone display can show. */
internal object PreviewBitmapSampling {
    fun pixelBudget(displayWidth: Int, displayHeight: Int): Long =
        (displayWidth.coerceAtLeast(1).toLong() * displayHeight.coerceAtLeast(1) * 2)
            .coerceIn(4_000_000L, 6_000_000L)

    fun inSampleSize(width: Int, height: Int, pixelBudget: Long): Int {
        require(width > 0 && height > 0 && pixelBudget > 0)
        var sample = 1
        while ((width / sample).coerceAtLeast(1).toLong() *
            (height / sample).coerceAtLeast(1) > pixelBudget &&
            sample <= 2
        ) {
            sample *= 2
        }
        return sample
    }
}
