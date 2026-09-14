package com.qingjing.wallpaper_android.install

import android.content.Context
import java.io.File

/** Restricted catalog bytes never enter the formal wallpaper or timed trial stores. */
internal object DetailPreviewRuntime {
    private var instance: AtomicPackageStore? = null
    @Synchronized fun store(context: Context): AtomicPackageStore = instance ?: AtomicPackageStore(
        File(context.filesDir,"wallpaper-detail-preview-v1")
    ).also { it.clearUnused(); instance = it }
    fun prepare(context: Context) { store(context).clearUnused() }
}
