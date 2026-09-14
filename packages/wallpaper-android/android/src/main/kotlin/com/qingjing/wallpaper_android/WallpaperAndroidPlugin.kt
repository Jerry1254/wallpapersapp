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
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.concurrent.Executors

class WallpaperAndroidPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    companion object {
        private const val ALIAS = "qingjing.installation.rsa.v1"
        private val keyLock = Any()
    }
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "qingjing/wallpaper_android")
        channel.setMethodCallHandler(this)
    }
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        worker.shutdown()
    }
    private fun keyStore(): KeyStore = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build())
            generator.generateKeyPair()
            // A newly generated installation key must never reuse a stale credential identifier.
            context.getSharedPreferences("qingjing.identity", Context.MODE_PRIVATE).edit().clear().commit()
        }
        store
    }
    private fun url64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        worker.execute {
            try {
                val response: Any? = when (call.method) {
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
}
