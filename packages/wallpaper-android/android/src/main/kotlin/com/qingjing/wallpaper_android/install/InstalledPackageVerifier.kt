package com.qingjing.wallpaper_android.install

import java.io.File
import java.nio.file.Files
import java.security.PublicKey
import java.security.Signature

internal data class InstalledPackage(val id: String, val type: String, val files: List<PackageFile>, val directory: File) {
    fun content(role: String): File = File(directory, files.single { it.role == role }.path)
}

/** Recheck persisted bytes before using them. An installation pointer alone is not proof of integrity. */
internal class InstalledPackageVerifier(private val keyId: String, private val key: PublicKey,private val purpose: PackagePurpose = PackagePurpose.FORMAL) {
    fun verify(store: AtomicPackageStore, id: String, type: String): InstalledPackage {
        val directory = store.directory(id)
        require(directory.isDirectory && directory.list()?.toSet() == setOf("manifest.json","manifest.sig","payload"))
        fun ordinary(file: File, max: Long) {
            require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..max)
        }
        val manifest = File(directory,"manifest.json"); ordinary(manifest,65536)
        val signature = File(directory,"manifest.sig"); ordinary(signature,256); require(signature.length() == 256L)
        val bytes = manifest.readBytes(); val parts = id.split('-'); require(parts.size == 4 && parts[1] == type)
        require(SecurePackageVerifier.hash(bytes) == parts[3])
        require(Signature.getInstance("SHA256withRSA").run { initVerify(key); update(bytes); verify(signature.readBytes()) })
        val root = StrictJson.parse(bytes) as? Map<*, *> ?: error("Invalid manifest")
        val version = root["versionNo"] as? Long ?: error("Invalid version"); require(version in 1..Int.MAX_VALUE)
        val expected = PackageExpectation(parts[0],root["variantId"] as? String ?: error("Invalid variant"),parts[2],version.toInt(),type,
            37,1,"0".repeat(64),"0".repeat(64),parts[3],keyId)
        val files = SecurePackageVerifier(purpose) { _,_ -> error("Unused decoder") }.manifest(bytes,expected)
        val payload = File(directory,"payload")
        require(payload.isDirectory && !Files.isSymbolicLink(payload.toPath()))
        require(payload.list()?.toSet() == files.map { File(it.path).name }.toSet())
        for (entry in files) {
            val file = File(directory,entry.path); ordinary(file,67108864)
            require(file.canonicalFile.parentFile == payload.canonicalFile && file.length() == entry.size && SecurePackageVerifier.hash(file) == entry.hash)
        }
        return InstalledPackage(id,type,files,directory)
    }
}
