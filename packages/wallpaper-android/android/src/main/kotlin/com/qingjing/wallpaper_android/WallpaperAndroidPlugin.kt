package com.qingjing.wallpaper_android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import com.qingjing.wallpaper_android.install.AndroidPackageDelivery
import com.qingjing.wallpaper_android.playback.WallpaperPlaybackBridge
import com.qingjing.wallpaper_android.playback.LiveSelection
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.concurrent.Executors

class WallpaperAndroidPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private lateinit var delivery: AndroidPackageDelivery
    private lateinit var events: EventChannel
    private lateinit var playback: WallpaperPlaybackBridge
    private var activityBinding: ActivityPluginBinding? = null
    private var eventSink: EventChannel.EventSink? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    companion object {
        private const val ALIAS = "qingjing.installation.rsa.v1"
        private const val ENCRYPTION_ALIAS = "qingjing.installation.decrypt.rsa.v1"
        private val keyLock = Any()
    }
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "qingjing/wallpaper_android")
        channel.setMethodCallHandler(this)
        events = EventChannel(binding.binaryMessenger, "qingjing/wallpaper_downloads")
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) { eventSink = sink }
            override fun onCancel(arguments: Any?) { eventSink = null }
        })
        delivery = AndroidPackageDelivery(context) { eventSink?.success(it) }
        playback = WallpaperPlaybackBridge(context)
    }
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        events.setStreamHandler(null); eventSink = null; delivery.close()
        playback.close(); worker.shutdown()
    }
    private fun keyStore(): KeyStore = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build())
            generator.generateKeyPair()
            if (store.containsAlias(ENCRYPTION_ALIAS)) store.deleteEntry(ENCRYPTION_ALIAS)
            // A newly generated installation key must never reuse a stale credential identifier.
            context.getSharedPreferences("qingjing.identity", Context.MODE_PRIVATE).edit().clear().commit()
        }
        store
    }
    private fun encryptionPublicKey(): Map<String, String> = synchronized(keyLock) {
        val store = keyStore()
        if (!store.containsAlias(ENCRYPTION_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(ENCRYPTION_ALIAS, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
            generator.generateKeyPair()
        }
        val encoded = store.getCertificate(ENCRYPTION_ALIAS).publicKey.encoded
        val pem = Base64.encodeToString(encoded, Base64.NO_WRAP).chunked(64).joinToString("\n")
        mapOf("publicKeyPem" to "-----BEGIN PUBLIC KEY-----\n$pem\n-----END PUBLIC KEY-----",
            "fingerprint" to MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) },
            "keyAlgorithm" to "RSA-OAEP-SHA256-MGF1-SHA1")
    }
    private fun url64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "previewPackage" || call.method == "applyWallpaper") { playback.launch(call,result); return }
        if (call.method == "debugPlaybackState") { try { result.success(playback.debugState()) } catch (_: Exception) { result.error("UNAVAILABLE","调试状态不可用",null) }; return }
        if (call.method == "installPackage" || call.method == "cancelDownload") {
            try {
                if (call.method == "installPackage") delivery.start(call.arguments as Map<*, *>, result)
                else { delivery.cancel(call.argument<String>("requestId") ?: throw IllegalArgumentException()); result.success(null) }
            } catch (_: Exception) { result.error("PACKAGE_INVALID", "资源信息不可用，请重试", null) }
            return
        }
        worker.execute {
            try {
                val response: Any? = when (call.method) {
                    "deliveryInfo" -> delivery.info()
                    "playbackCapabilities" -> playback.information()
                    "installedPackage" -> delivery.current(call.argument<String>("wallpaperId") ?: throw IllegalArgumentException(),call.argument<String>("resourceType") ?: throw IllegalArgumentException())
                    "clearPackageCache" -> { LiveSelection.pending(context); delivery.clearUnused() }
                    "encryptionPublicKey" -> encryptionPublicKey()
                    "identity" -> {
                        val key = keyStore().getCertificate(ALIAS).publicKey.encoded
                        val encoded = Base64.encodeToString(key, Base64.NO_WRAP).chunked(64).joinToString("\n")
                        mapOf("publicKeyPem" to "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----",
                            "fingerprint" to MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) },
                            "scope" to context.packageName,
                            "credentialKeyId" to context.getSharedPreferences("qingjing.identity", Context.MODE_PRIVATE).getString("credentialKeyId", null))
                    }
                    "sign" -> {
                        val payload = call.argument<String>("payload") ?: throw IllegalArgumentException()
                        require(payload.toByteArray(Charsets.UTF_8).size <= 16384)
                        val privateKey = keyStore().getKey(ALIAS, null) as PrivateKey
                        val signature = Signature.getInstance("SHA256withRSA")
                        signature.initSign(privateKey); signature.update(payload.toByteArray(Charsets.UTF_8))
                        url64(signature.sign())
                    }
                    "readPendingRedemption" -> {
                        keyStore()
                        context.getSharedPreferences("qingjing.identity", Context.MODE_PRIVATE).getString("pendingRedemption", null)
                    }
                    "writePendingRedemption" -> {
                        keyStore()
                        val value = call.argument<String>("value")
                        if (value != null) {
                            require(value.length <= 256)
                            val json = org.json.JSONObject(value)
                            require(json.length() == 3)
                            require(java.util.UUID.fromString(json.getString("key")).toString() == json.getString("key"))
                            require(json.getString("wallpaperId").matches(Regex("[1-9][0-9]*")))
                            require(json.getString("bodyHash").matches(Regex("[0-9a-f]{64}")))
                        }
                        check(context.getSharedPreferences("qingjing.identity", Context.MODE_PRIVATE).edit().putString("pendingRedemption", value).commit())
                        null
                    }
                    "rememberCredential" -> {
                        val id = call.argument<String>("credentialKeyId") ?: throw IllegalArgumentException()
                        require(java.util.UUID.fromString(id).toString() == id)
                        keyStore()
                        check(context.getSharedPreferences("qingjing.identity", Context.MODE_PRIVATE).edit().putString("credentialKeyId", id).commit())
                        null
                    }
                    else -> { main.post { result.notImplemented() }; return@execute }
                }
                main.post { result.success(response) }
            } catch (_: Exception) {
                // Never return exception text: it can contain key aliases or request payloads.
                main.post { result.error("IDENTITY_UNAVAILABLE", "安装凭据暂时不可用，请重试或联系客服", null) }
            }
        }
    }
    override fun onAttachedToActivity(binding: ActivityPluginBinding) { activityBinding = binding; playback.activity = binding.activity; binding.addActivityResultListener(playback) }
    override fun onDetachedFromActivityForConfigChanges() { detachActivity() }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) { onAttachedToActivity(binding) }
    override fun onDetachedFromActivity() { detachActivity(); playback.close() }
    private fun detachActivity() { activityBinding?.removeActivityResultListener(playback); activityBinding = null; playback.activity = null }
}
