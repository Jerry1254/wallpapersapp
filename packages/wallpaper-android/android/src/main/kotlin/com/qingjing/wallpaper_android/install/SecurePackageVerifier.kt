package com.qingjing.wallpaper_android.install

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class PackageExpectation(
    val wallpaperId: String, val variantId: String, val versionId: String,
    val versionNo: Int, val type: String, val size: Long, val plaintextSize: Long,
    val encryptedHash: String, val plaintextHash: String, val manifestHash: String,
    val signingKeyId: String
) {
    init {
        for (id in listOf(wallpaperId, variantId, versionId)) require(id.matches(Regex("[1-9][0-9]{0,18}")) && id.toLong() > 0)
        require(versionNo > 0 && type in setOf("STATIC_IMAGE", "VIDEO", "LAYER_PARALLAX"))
        require(size in 37..68157440 && plaintextSize == size - 36)
        for (hash in listOf(encryptedHash, plaintextHash, manifestHash)) require(hash.matches(Regex("[a-f0-9]{64}")))
        require(signingKeyId.matches(Regex("[a-z0-9-]{1,64}")))
    }
    fun aad() = "QJ-PACKAGE-V2\n$wallpaperId\n$variantId\n$versionNo\n$type".toByteArray(Charsets.UTF_8)
    val installedId get() = "$wallpaperId-$type-$versionId-$manifestHash"
    val slot get() = "$wallpaperId-$type"
}
internal data class PackageFile(val path: String, val role: String, val ordinal: Int, val mime: String, val size: Long, val hash: String)
internal data class MediaInfo(val width: Int, val height: Int, val alpha: Boolean = false)

/** Only writes inside a new private staging directory. Nothing is installed until every check passes. */
internal class SecurePackageVerifier(private val media: (PackageFile, File) -> MediaInfo) {
    fun verify(encrypted: File, staging: File, expected: PackageExpectation, key: ByteArray,
               trustKeyId: String, trustKey: PublicKey, cancelled: () -> Boolean = { false }) {
        require(expected.signingKeyId == trustKeyId && key.size == 32)
        require(encrypted.length() == expected.size && hash(encrypted) == expected.encryptedHash)
        require(staging.isDirectory && staging.listFiles()?.isEmpty() == true)
        val plaintext = File(staging, "package.zip")
        try {
            decrypt(encrypted, plaintext, expected, key, cancelled)
            require(plaintext.length() == expected.plaintextSize && hash(plaintext) == expected.plaintextHash)
            val entries = inspectZip(plaintext)
            ZipFile(plaintext).use { zip ->
                fun bytes(name: String, limit: Int): ByteArray {
                    val entry = zip.getEntry(name) ?: error("Missing package entry")
                    require(entry.size in 1..limit.toLong())
                    val value = zip.getInputStream(entry).use { readBounded(it, limit + 1) }
                    require(value.size.toLong() == entry.size && value.size <= limit)
                    require(CRC32().apply { update(value) }.value == entry.crc)
                    return value
                }
                val manifest = bytes("manifest.json", 65536)
                require(hash(manifest) == expected.manifestHash)
                val signature = bytes("manifest.sig", 256)
                require(signature.size == 256)
                require(Signature.getInstance("SHA256withRSA").run {
                    initVerify(trustKey); update(manifest); verify(signature)
                })
                val files = manifest(manifest, expected)
                require(entries == files.map { it.path }.toSet() + setOf("manifest.json", "manifest.sig"))
                require(File(staging, "payload").mkdir())
                val images = mutableMapOf<String, MediaInfo>()
                for (file in files) {
                    check(!cancelled())
                    val entry = zip.getEntry(file.path) ?: error("Missing payload")
                    require(entry.size == file.size)
                    val target = File(staging, file.path)
                    require(target.canonicalFile.parentFile == File(staging, "payload").canonicalFile)
                    val digest = MessageDigest.getInstance("SHA-256"); val crc = CRC32()
                    zip.getInputStream(entry).use { input -> FileOutputStream(target).use { output ->
                        val buffer = ByteArray(32768); var count = 0L
                        while (true) {
                            check(!cancelled()); val read = input.read(buffer); if (read < 0) break
                            count += read; require(count <= file.size)
                            digest.update(buffer, 0, read); crc.update(buffer, 0, read); output.write(buffer, 0, read)
                        }
                        require(count == file.size && hex(digest.digest()) == file.hash && crc.value == entry.crc)
                        output.fd.sync()
                    } }
                    if (file.role != "PARALLAX_CONFIG") images["${file.role}:${file.ordinal}"] = media(file, target)
                }
                if (expected.type == "LAYER_PARALLAX") {
                    val config = files.single { it.role == "PARALLAX_CONFIG" }
                    require(config.size <= 65536)
                    parallax(File(staging, config.path).readBytes(), images)
                }
                FileOutputStream(File(staging, "manifest.json")).use { it.write(manifest); it.fd.sync() }
                FileOutputStream(File(staging, "manifest.sig")).use { it.write(signature); it.fd.sync() }
            }
        } finally { plaintext.delete() }
    }
    private fun decrypt(source: File, target: File, expected: PackageExpectation, key: ByteArray, cancelled: () -> Boolean) {
        source.inputStream().use { input ->
            require(readBounded(input, 8).contentEquals("QJWP0002".toByteArray(Charsets.US_ASCII)))
            val nonce = readBounded(input, 12); require(nonce.size == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(expected.aad())
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(32768); var length = 0L
                fun write(bytes: ByteArray?) { if (bytes != null) { length += bytes.size; require(length <= expected.plaintextSize); output.write(bytes) } }
                while (true) { check(!cancelled()); val read = input.read(buffer); if (read < 0) break; write(cipher.update(buffer, 0, read)) }
                write(cipher.doFinal()); require(length == expected.plaintextSize); output.fd.sync()
            }
        }
    }
    internal fun manifest(bytes: ByteArray, e: PackageExpectation): List<PackageFile> {
        val root = fields(StrictJson.parse(bytes), setOf("formatVersion", "wallpaperId", "variantId", "versionNo", "resourceType", "signingKeyId", "files"))
        require(root["formatVersion"] == 2L && root["wallpaperId"] == e.wallpaperId && root["variantId"] == e.variantId &&
            root["versionNo"] == e.versionNo.toLong() && root["resourceType"] == e.type && root["signingKeyId"] == e.signingKeyId)
        val values = root["files"] as? List<*> ?: error("Invalid files"); require(values.size in 1..16)
        val extensions = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "video/mp4" to "mp4", "application/json" to "json")
        val allowed = when(e.type) { "STATIC_IMAGE" -> setOf("STATIC_IMAGE"); "VIDEO" -> setOf("VIDEO"); else -> setOf("BACKGROUND", "FOREGROUND", "PARALLAX_CONFIG") }
        val seen = mutableSetOf<String>(); var total = 0L
        val result = values.map { value ->
            val item = fields(value, setOf("path", "role", "ordinal", "mimeType", "sizeBytes", "sha256"))
            val role = item["role"] as? String ?: error("Invalid role")
            val ordinal = item["ordinal"] as? Long ?: error("Invalid ordinal")
            val mime = item["mimeType"] as? String ?: error("Invalid MIME")
            val path = item["path"] as? String ?: error("Invalid path")
            val size = item["sizeBytes"] as? Long ?: error("Invalid length")
            val hash = item["sha256"] as? String ?: error("Invalid digest")
            require(role in allowed && ordinal in 0..15 && seen.add("$role:$ordinal"))
            val extension = extensions[mime] ?: error("Invalid extension")
            require(path == "payload/${role.lowercase(java.util.Locale.ROOT)}-$ordinal.$extension")
            require(size in 1..67108864 && hash.matches(Regex("[a-f0-9]{64}")))
            require(when(role) { "VIDEO" -> mime == "video/mp4"; "PARALLAX_CONFIG" -> mime == "application/json" && size <= 65536; else -> mime.startsWith("image/") })
            total += size; require(total <= 67108864)
            PackageFile(path, role, ordinal.toInt(), mime, size, hash)
        }
        require(result.map { it.role }.toSet() == allowed)
        if (e.type != "LAYER_PARALLAX") require(result.size == 1)
        else require(result.count { it.role == "PARALLAX_CONFIG" } == 1)
        return result
    }
    private fun parallax(bytes: ByteArray, images: Map<String, MediaInfo>) {
        val root = fields(StrictJson.parse(bytes), setOf("canvas", "sensor", "layers"))
        val canvas = fields(root["canvas"], setOf("width", "height"))
        val width = canvas["width"] as? Long ?: error("Invalid canvas")
        val height = canvas["height"] as? Long ?: error("Invalid canvas")
        require(width in 512..4096 && height in 512..4096)
        val sensor = fields(root["sensor"], setOf("maxAngle", "smoothing", "strength"))
        range(sensor["maxAngle"], 5.0, 25.0); range(sensor["smoothing"], .05, .5); range(sensor["strength"], 0.0, 2.0)
        val layers = root["layers"] as? List<*> ?: error("Invalid layers")
        require(layers.size in 2..12 && layers.size == images.size)
        val seen = mutableSetOf<String>(); var previous = -1.0
        for (value in layers) {
            val layer = fields(value, setOf("role", "ordinal", "depth", "scale", "opacity", "blendMode"))
            val role = layer["role"] as? String ?: error("Invalid role")
            val ordinal = layer["ordinal"] as? Long ?: error("Invalid ordinal")
            require(role in setOf("BACKGROUND", "FOREGROUND") && ordinal in 0..15)
            val image = images["$role:$ordinal"] ?: error("Missing layer")
            require(seen.add("$role:$ordinal") && image.width.toLong() == width && image.height.toLong() == height && (role != "FOREGROUND" || image.alpha))
            val depth = range(layer["depth"], 0.0, 1.0); require(depth >= previous); previous = depth
            range(layer["scale"], 1.0, 1.5); range(layer["opacity"], 0.0, 1.0)
            require(layer["blendMode"] in setOf("normal", "screen", "add"))
        }
    }
    private fun range(value: Any?, min: Double, max: Double): Double {
        val number = (value as? Number)?.toDouble() ?: error("Invalid number")
        require(number.isFinite() && number in min..max); return number
    }
    @Suppress("UNCHECKED_CAST")
    private fun fields(value: Any?, expected: Set<String>): Map<String, Any?> {
        val fields = value as? Map<String, Any?> ?: error("Invalid object")
        require(fields.keys == expected); return fields
    }
    companion object {
        /** Android 26-compatible equivalent; InputStream.readNBytes requires API 33. */
        private fun readBounded(input: InputStream, limit: Int): ByteArray {
            require(limit in 1..65537)
            val bytes = ByteArray(limit); var count = 0
            while(count < limit) {
                val read = input.read(bytes, count, limit - count)
                if(read < 0) break
                require(read > 0); count += read
            }
            return bytes.copyOf(count)
        }
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(32768); while(true) { val count = input.read(buffer); if(count < 0) break; digest.update(buffer, 0, count) } }
            return hex(digest.digest())
        }
        fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        /** Reject ZIP64, links, directories, duplicates, encryption, stubs and trailing bytes before extraction. */
        private fun inspectZip(file: File): Set<String> = RandomAccessFile(file, "r").use { input ->
            fun u16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
            require(file.length() >= 22); input.seek(file.length() - 22)
            require(u32() == 0x06054b50L && u16() == 0 && u16() == 0)
            val count = u16(); require(count in 3..18 && u16() == count)
            val centralSize = u32(); val offset = u32(); require(u16() == 0 && offset + centralSize == file.length() - 22)
            input.seek(offset); val names = mutableSetOf<String>(); val localOffsets = mutableSetOf<Long>(); val ranges = mutableListOf<Pair<Long,Long>>()
            repeat(count) {
                require(u32() == 0x02014b50L); u16(); require(u16() <= 20)
                val flags = u16(); val method = u16(); require(flags and 1 == 0 && flags and 0x40 == 0 && method in setOf(0, 8))
                u16(); u16(); u32(); val compressed = u32(); val size = u32()
                require(size in 1..67108864 && compressed in 1..68157440)
                val nameLength = u16(); val extra = u16(); val comment = u16(); require(nameLength in 1..128 && comment == 0 && u16() == 0)
                u16(); val attributes = u32(); val local = u32(); require(local < offset && localOffsets.add(local))
                val mode = ((attributes ushr 16).toInt() and 0xf000)
                require(mode == 0 || mode == 0x8000); require(attributes and 0x10 == 0L)
                val nameBytes = ByteArray(nameLength); input.readFully(nameBytes)
                val name = String(nameBytes, Charsets.US_ASCII)
                require(name.toByteArray(Charsets.US_ASCII).contentEquals(nameBytes) && names.add(name))
                require(name in setOf("manifest.json", "manifest.sig") || name.matches(Regex("payload/(static_image|video|background|foreground|parallax_config)-([0-9]|1[0-5])\\.(jpg|png|webp|mp4|json)")))
                val next = input.filePointer + extra; require(next <= offset + centralSize)
                input.seek(local); require(u32() == 0x04034b50L); u16(); require(u16() == flags && u16() == method)
                input.skipBytes(16); val localNameLength = u16(); val localExtra = u16(); require(localNameLength == nameLength)
                val localName = ByteArray(localNameLength); input.readFully(localName); require(localName.contentEquals(nameBytes))
                require(input.filePointer + localExtra + compressed <= offset)
                ranges.add(local to input.filePointer + localExtra + compressed)
                input.seek(next)
            }
            require(input.filePointer == offset + centralSize && localOffsets.minOrNull() == 0L)
            val ordered = ranges.sortedBy { it.first }
            require(ordered.zipWithNext().all { (a,b) -> a.second <= b.first })
            names
        }
    }
}
