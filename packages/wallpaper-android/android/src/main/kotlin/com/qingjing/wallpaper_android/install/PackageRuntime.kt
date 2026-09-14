package com.qingjing.wallpaper_android.install

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec

/** One store per app process: preview/service creation must not recover an active download's staging. */
internal object PackageRuntime {
    private var instance: AtomicPackageStore? = null
    @Synchronized fun store(context: Context): AtomicPackageStore = instance ?: AtomicPackageStore(File(context.filesDir,"wallpaper-packages-v2")).also { instance = it }
    fun verifier(context: Context): InstalledPackageVerifier {
        fun resource(name: String): String {
            val id = context.resources.getIdentifier(name,"string",context.packageName)
            return if (id == 0) "" else context.getString(id)
        }
        val id = resource("qj_package_signing_key_id"); val der = resource("qj_package_signing_public_key")
        require(id.matches(Regex("[a-z0-9-]{1,64}")) && der.isNotEmpty())
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(der,Base64.NO_WRAP))) as RSAPublicKey
        require(key.modulus.bitLength() == 2048 && key.publicExponent.toInt() == 65537)
        return InstalledPackageVerifier(id,key)
    }
}
