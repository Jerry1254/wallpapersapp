package com.qingjing.wallpaper_android.install

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Version folders are immutable. A single atomic pointer move makes a verified version current. */
internal class AtomicPackageStore(val root: File) {
    private val pins = mutableMapOf<String, Int>()
    init { require((root.isDirectory || root.mkdirs()) && !Files.isSymbolicLink(root.toPath())); recover() }
    @Synchronized fun staging(): File = File(root, ".stage-${UUID.randomUUID()}").also { check(it.mkdir()) }
    fun partial(): File = File(root, ".part-${UUID.randomUUID()}")
    fun recover() {
        root.listFiles()?.filter { it.name.startsWith(".stage-") || it.name.startsWith(".part-") || it.name.startsWith(".pointer-") }
            ?.forEach { remove(it) }
    }
    @Synchronized fun current(slot: String): String? {
        requireSlot(slot)
        val pointer = File(root, "current-$slot")
        if (!pointer.isFile || pointer.length() > 160) return null
        val id = pointer.readText(Charsets.US_ASCII)
        return if (validId(id) && directory(id).isDirectory && File(directory(id), "manifest.json").isFile) id else null
    }
    @Synchronized fun commit(staging: File, e: PackageExpectation, beforePointer: () -> Unit = {}): String {
        require(staging.parentFile?.canonicalFile == root.canonicalFile && staging.name.startsWith(".stage-"))
        require(File(staging, "manifest.json").isFile && File(staging, "manifest.sig").isFile && File(staging, "payload").isDirectory)
        current(e.slot)?.let { previous ->
            val manifest = File(directory(previous), "manifest.json")
            require(manifest.length() in 1..65536)
            val old = StrictJson.parse(manifest.readBytes()) as? Map<*, *> ?: error("Invalid installed manifest")
            if (old["variantId"] == e.variantId) {
                val version = old["versionNo"] as? Long ?: error("Invalid installed version")
                require(e.versionNo >= version)
                if (e.versionNo.toLong() == version) require(SecurePackageVerifier.hash(manifest) == e.manifestHash)
            }
        }
        val id = e.installedId; val target = directory(id); val existed = target.exists()
        try {
            if (existed) {
                // Reuse only bytes that match the freshly verified directory, never an unchecked cache.
                require(target.list()?.toSet() == staging.list()?.toSet())
                val payload = File(target,"payload"); require(payload.isDirectory && !Files.isSymbolicLink(payload.toPath()))
                require(payload.list()?.toSet() == File(staging,"payload").list()?.toSet())
                val files = listOf(File(staging,"manifest.json"),File(staging,"manifest.sig")) + File(staging,"payload").listFiles()!!.toList()
                for (source in files) {
                    val cached = File(target,source.relativeTo(staging).path)
                    require(cached.isFile && !Files.isSymbolicLink(cached.toPath()) && cached.length() == source.length())
                    require(SecurePackageVerifier.hash(cached) == SecurePackageVerifier.hash(source))
                }
                remove(staging)
            } else Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            beforePointer()
            pointer("current-${e.slot}", id)
            return id
        } catch (error: Exception) {
            if (!existed && current(e.slot) != id && id !in activeIds()) remove(target)
            throw error
        }
    }
    fun directory(id: String): File {
        require(validId(id))
        return File(root, id).also { require(it.canonicalFile.parentFile == root.canonicalFile && !Files.isSymbolicLink(it.toPath())) }
    }
    fun active(target: String = "home"): String? {
        require(target in setOf("home","lock"))
        val file = File(root, "active-$target"); if (!file.isFile || file.length() > 160) return null
        val id = file.readText(Charsets.US_ASCII); return id.takeIf { validId(it) && directory(it).isDirectory }
    }
    fun activeIds() = listOfNotNull(active("home"),active("lock")).toSet()
    @Synchronized fun markActive(id: String, target: String = "home") {
        require(target in setOf("home","lock")); require(directory(id).isDirectory); pointer("active-$target", id)
    }
    @Synchronized fun pin(id: String): AutoCloseable {
        require(directory(id).isDirectory)
        pins[id] = (pins[id] ?: 0) + 1
        var closed = false
        return AutoCloseable { synchronized(this) {
            if (!closed) { closed = true; val count = (pins[id] ?: 1) - 1; if (count == 0) pins.remove(id) else pins[id] = count }
        } }
    }
    @Synchronized fun hold(name: String, id: String?) {
        require(name in setOf("live", "picker"))
        if (id == null) File(root,"hold-$name").delete()
        else { require(directory(id).isDirectory); pointer("hold-$name",id) }
    }
    @Synchronized fun held(name: String): String? {
        require(name in setOf("live", "picker"))
        val file = File(root,"hold-$name")
        if (!file.isFile || file.length() > 160) return null
        return file.readText(Charsets.US_ASCII).takeIf { validId(it) && directory(it).isDirectory }
    }
    @Synchronized fun clearUnused(): Long {
        val held = listOf("live","picker").mapNotNull { name ->
            val file = File(root,"hold-$name")
            if (file.isFile && file.length() <= 160) file.readText(Charsets.US_ASCII).takeIf { validId(it) } else null
        }
        val active = activeIds() + held + pins.keys; var removed = 0L
        root.listFiles()?.filter { validId(it.name) && it.name !in active }?.forEach { removed += size(it); remove(it) }
        root.listFiles()?.filter { it.name.startsWith("current-") && it.isFile }?.forEach { file ->
            if (file.length() > 160 || file.readText(Charsets.US_ASCII) !in active) remove(file)
        }
        return removed
    }
    fun usedBytes() = root.listFiles()?.sumOf { size(it) } ?: 0L
    private fun pointer(name: String, id: String) {
        val temporary = File(root, ".pointer-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { it.write(id.toByteArray(Charsets.US_ASCII)); it.fd.sync() }
            Files.move(temporary.toPath(), File(root, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    companion object {
        private val idPattern = Regex("[1-9][0-9]{0,18}-(STATIC_IMAGE|VIDEO|LAYER_PARALLAX)-[1-9][0-9]{0,18}-[a-f0-9]{64}")
        private fun validId(id: String) = id.length <= 160 && id.matches(idPattern)
        private fun requireSlot(slot: String) { require(slot.matches(Regex("[1-9][0-9]{0,18}-(STATIC_IMAGE|VIDEO|LAYER_PARALLAX)"))) }
        private fun size(file: File): Long {
            if (Files.isSymbolicLink(file.toPath())) return 0
            return if (file.isDirectory) file.listFiles()?.sumOf { size(it) } ?: 0L else file.length()
        }
        fun remove(file: File) {
            if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) file.listFiles()?.forEach { remove(it) }
            if (file.exists() || Files.isSymbolicLink(file.toPath())) check(file.delete())
        }
    }
}
