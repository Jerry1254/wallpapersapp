package com.qingjing.wallpaper_android.install

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyProperties
import android.util.Base64
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

internal class AndroidPackageDelivery(private val context: Context,private val purpose: PackagePurpose = PackagePurpose.FORMAL, private val event: (Map<String, Any>) -> Unit) {
    private val trial get() = purpose == PackagePurpose.APP_PREVIEW
    private val store = if(trial) TrialRuntime.store(context) else PackageRuntime.store(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private class Transfer(val id: String) {
        val cancelled = AtomicBoolean(false)
        @Volatile var connection: HttpURLConnection? = null
    }
    private val active = AtomicReference<Transfer?>()
    private class Cancelled : Exception()
    private class NoSpace : Exception()
    private class NoMemory : Exception()
    fun cancel(id: String) {
        active.get()?.takeIf { it.id == id }?.let { it.cancelled.set(true); it.connection?.disconnect() }
    }
    fun close() { active.get()?.let { cancel(it.id) }; executor.shutdown() }
    fun info() = mapOf("osVersion" to Build.VERSION.RELEASE, "sdkVersion" to Build.VERSION.SDK_INT.toString(), "usedBytes" to store.usedBytes(), "availableBytes" to store.root.usableSpace)
    fun current(wallpaper: String, type: String) = store.current("$wallpaper-$type")
    @Synchronized fun clearUnused(): Long { check(active.get() == null); return store.clearUnused() }
    @Synchronized fun start(arguments: Map<*, *>, result: MethodChannel.Result) {
        val id = arguments["requestId"] as? String ?: error("Invalid request ID")
        require(java.util.UUID.fromString(id).toString() == id)
        val transfer = Transfer(id)
        if (!active.compareAndSet(null, transfer)) { result.error("DOWNLOAD_BUSY", "已有下载正在进行，请稍后重试", null); return }
        executor.execute {
            var partial: File? = null; var staging: File? = null; var key: ByteArray? = null
            var phase = "metadata"
            try {
                val descriptor = arguments["descriptor"] as? Map<*, *> ?: error("Missing descriptor")
                require(descriptor["deliveryMode"] == if(trial) "APP_PREVIEW" else "SECURE_PACKAGE")
                if(trial) require(descriptor["purpose"] == "APP_PREVIEW" && descriptor["durationSeconds"] == 120)
                val version = descriptor["resourceVersion"] as? Map<*, *> ?: error("Missing version")
                val metadata = descriptor["package"] as? Map<*, *> ?: error("Missing package metadata")
                fun string(map: Map<*, *>, name: String) = map[name] as? String ?: error("Invalid metadata string")
                fun integer(map: Map<*, *>, name: String): Long {
                    val value = map[name] as? Number ?: error("Invalid metadata number")
                    require(value is Int || value is Long); return value.toLong()
                }
                require(integer(metadata,"formatVersion") == purpose.format && metadata["keyAlgorithm"] == "RSA-OAEP-SHA256-MGF1-SHA1")
                require(version["platform"] in setOf("ANDROID","UNIVERSAL"))
                val number = integer(version,"versionNo"); require(number in 1..Int.MAX_VALUE)
                val expected = PackageExpectation(string(descriptor,"wallpaperId"),string(version,"variantId"),string(version,"id"),number.toInt(),
                    string(version,"resourceType"),integer(metadata,"sizeBytes"),integer(metadata,"plaintextSizeBytes"),string(metadata,"encryptedSha256"),
                    string(metadata,"plaintextSha256"),string(version,"manifestSha256"),string(metadata,"signingKeyId"))
                require(expected.wallpaperId == arguments["wallpaperId"] && expected.type == arguments["resourceType"])
                val path = if(trial) "/api/v1/preview/files" else "/api/v1/delivery/files"
                require(descriptor["downloadUrl"] == path)
                val token = string(descriptor,"ticket"); require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
                val uri = URI(arguments["url"] as? String ?: error("Invalid URL"))
                require(uri.rawPath == path && uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null && !uri.host.isNullOrBlank())
                require(uri.scheme == "https" || (uri.scheme == "http" && context.packageName.endsWith(".local") && uri.host in setOf("127.0.0.1","localhost","10.0.2.2")))
                val trustId = resource("qj_package_signing_key_id")
                val trustDer = resource("qj_package_signing_public_key")
                require(trustId.isNotEmpty() && trustDer.isNotEmpty() && expected.signingKeyId == trustId)
                val trust = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(trustDer,Base64.NO_WRAP))) as RSAPublicKey
                require(trust.modulus.bitLength() == 2048 && trust.publicExponent.toInt() == 65537)
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val publicKey = keys.getCertificate(ENCRYPTION_ALIAS)?.publicKey ?: error("Missing installation decrypt key")
                require(SecurePackageVerifier.hash(publicKey.encoded) == metadata["encryptionKeySha256"])
                val wrapped = string(metadata,"wrappedContentKey"); require(wrapped.matches(Regex("[A-Za-z0-9_-]{342}")))
                val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
                cipher.init(Cipher.DECRYPT_MODE,keys.getKey(ENCRYPTION_ALIAS,null) as PrivateKey,
                    OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT))
                key = cipher.doFinal(Base64.decode(wrapped,Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)); require(key.size == 32)
                if(trial) TrialRuntime.prepare(context)
                if (store.root.usableSpace < expected.size * 3 + 16*1024*1024) throw NoSpace()
                partial = store.partial(); staging = store.staging()
                fun report(status: String, received: Long = 0) {
                    main.post { event(mapOf("requestId" to id,"status" to status,"receivedBytes" to received,"totalBytes" to expected.size)) }
                }
                phase = "download"; report("downloading")
                download(uri,token,partial,expected.size,transfer) { report("downloading",it) }
                if (transfer.cancelled.get()) throw Cancelled()
                phase = "verify"; report("verifying",expected.size)
                // GCM providers may buffer until tag verification. Reserve heap before decoding.
                memory(expected.plaintextSize * 4 + 16*1024*1024)
                SecurePackageVerifier(purpose,::media).verify(partial,staging,expected,key,trustId,trust) { transfer.cancelled.get() }
                if (transfer.cancelled.get()) throw Cancelled()
                report("installing",expected.size)
                val installed = store.commit(staging,expected)
                report("completed",expected.size)
                val response = if(trial) mapOf("trialId" to TrialRuntime.installed(context,installed,expected.type),"resourceType" to expected.type)
                    else mapOf("installedId" to installed,"wallpaperId" to expected.wallpaperId,"versionId" to expected.versionId,"versionNo" to expected.versionNo,"resourceType" to expected.type)
                main.post { result.success(response) }
            } catch (error: Exception) {
                val code = when {
                    transfer.cancelled.get() || error is Cancelled -> "DOWNLOAD_CANCELLED"
                    error is NoSpace || store.root.usableSpace < 1024*1024 -> "INSUFFICIENT_SPACE"
                    error is NoMemory -> "INSUFFICIENT_MEMORY"
                    phase == "download" -> "DOWNLOAD_FAILED"
                    else -> "PACKAGE_INVALID"
                }
                main.post { result.error(code,when(code) {
                    "DOWNLOAD_CANCELLED" -> "下载已取消，可重新下载"
                    "INSUFFICIENT_SPACE" -> "手机空间不足，请清理后重试"
                    "INSUFFICIENT_MEMORY" -> "手机可用内存不足以安装此资源，原有版本已保留"
                    "DOWNLOAD_FAILED" -> "下载中断或票据失效，请重新下载"
                    else -> "资源校验失败，原有版本已保留，请重试或联系客服"
                },null) }
            } catch (_: OutOfMemoryError) {
                main.post { result.error("INSUFFICIENT_MEMORY","手机可用内存不足以安装此资源，原有版本已保留",null) }
            } finally {
                key?.fill(0); transfer.connection?.disconnect()
                try { partial?.let { AtomicPackageStore.remove(it) }; staging?.let { AtomicPackageStore.remove(it) } }
                finally { active.compareAndSet(transfer,null); if(trial) TrialRuntime.cleanup(context) }
            }
        }
    }
    private fun resource(name: String): String {
        val id = context.resources.getIdentifier(name,"string",context.packageName)
        return if(id == 0) "" else context.getString(id)
    }
    private fun download(uri: URI, token: String, target: File, size: Long, transfer: Transfer, progress: (Long) -> Unit) {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        transfer.connection = connection
        if(transfer.cancelled.get()) throw Cancelled()
        connection.connectTimeout=15000; connection.readTimeout=20000; connection.instanceFollowRedirects=false
        connection.setRequestProperty("Authorization","Bearer $token"); connection.setRequestProperty("Accept","application/octet-stream")
        connection.setRequestProperty("Accept-Encoding","identity")
        require(connection.responseCode == 200 && connection.contentLengthLong == size && connection.contentType?.substringBefore(';') == "application/octet-stream")
        val start=System.nanoTime(); var last=start
        connection.inputStream.use { input -> FileOutputStream(target).use { output ->
            val buffer=ByteArray(32768); var count=0L
            while(true) {
                if(transfer.cancelled.get()) throw Cancelled()
                check(System.nanoTime()-start < 180_000_000_000L)
                val read=input.read(buffer); if(read < 0) break
                count+=read; require(count<=size); output.write(buffer,0,read)
                val now=System.nanoTime(); if(now-last >= 100_000_000L || count == size) { progress(count); last=now }
            }
            require(count == size); output.fd.sync()
        } }
    }
    private fun media(file: PackageFile, source: File): MediaInfo {
        if(file.role == "VIDEO") {
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(source.absolutePath); require(extractor.trackCount == 1)
                val format=extractor.getTrackFormat(0)
                require(format.getString(MediaFormat.KEY_MIME) == "video/avc")
                val width=format.getInteger(MediaFormat.KEY_WIDTH); val height=format.getInteger(MediaFormat.KEY_HEIGHT)
                require(width in 1..4096 && height in 1..4096 && format.getLong(MediaFormat.KEY_DURATION) in 1..30_000_000)
                if(format.containsKey(MediaFormat.KEY_FRAME_RATE)) require(format.getInteger(MediaFormat.KEY_FRAME_RATE) in 1..60)
                if(trial) require(width <= 1280 && height <= 1280 && (!format.containsKey(MediaFormat.KEY_FRAME_RATE) || format.getInteger(MediaFormat.KEY_FRAME_RATE) <= 15))
                return MediaInfo(width,height)
            } finally { extractor.release() }
        }
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(source.absolutePath,options)
        require(options.outWidth in 1..4096 && options.outHeight in 1..4096 && options.outMimeType == file.mime)
        memory(options.outWidth.toLong() * options.outHeight * 4 + 16*1024*1024)
        val bitmap=BitmapFactory.decodeFile(source.absolutePath) ?: error("Undecodable image")
        try { return MediaInfo(bitmap.width,bitmap.height,bitmap.hasAlpha()) } finally { bitmap.recycle() }
    }
    private fun memory(required: Long) {
        val runtime = Runtime.getRuntime()
        if(runtime.maxMemory() - (runtime.totalMemory()-runtime.freeMemory()) < required) throw NoMemory()
    }
    companion object { private const val ENCRYPTION_ALIAS="qingjing.installation.decrypt.rsa.v1" }
}
