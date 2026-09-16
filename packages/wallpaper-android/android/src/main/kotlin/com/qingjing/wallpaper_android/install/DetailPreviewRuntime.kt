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
    fun inspect(context: Context,id: String): Map<String,Any> {
        require(id.length<=160 && id.matches(Regex("[1-9][0-9]{0,18}-LAYER_PARALLAX-[1-9][0-9]{0,18}-[a-f0-9]{64}")))
        val store=store(context)
        store.pin(id).use {
            val installed=PackageRuntime.verifier(context,PackagePurpose.APP_PREVIEW).verify(store,id,"LAYER_PARALLAX")
            val config=installed.content("PARALLAX_CONFIG").readBytes()
            require(config.size in 1..65536)
            ParallaxConfigurationBridge.validate(config)
            val root=StrictJson.parse(java.io.File(installed.directory,"manifest.json").readBytes()) as? Map<*,*> ?: error("Invalid manifest")
            val version=root["versionNo"] as? Long ?: error("Invalid version")
            return mapOf("config" to config.toString(Charsets.UTF_8),"resourceVersionId" to id.split('-')[2],"versionNo" to version)
        }
    }
}

/** Keeps the install package independent from playback internals while validating current client support. */
private object ParallaxConfigurationBridge {
    fun validate(bytes: ByteArray) { com.qingjing.wallpaper_android.playback.ParallaxConfiguration.parse(bytes) }
}
