package com.qingjing.qingjing_wallpaper

import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Only completed APKs in the private update cache can be shared with the installer. */
class AppUpdateFileProvider : FileProvider()

class AppUpdateBridge(
    private val activity: FlutterActivity,
    messenger: BinaryMessenger,
) {
    private val channel = MethodChannel(messenger, "qingjing/app_updates")
    private val preferences = activity.getSharedPreferences("qingjing_app_updates", 0)
    private val worker = Executors.newSingleThreadExecutor()
    private val verifying = AtomicBoolean(false)
    private var pendingInstall: InstallRequest? = null
    private var destroyed = false

    private data class InstallRequest(val path: String, val versionCode: Long, val sha256: String, val size: Long)

    init {
        channel.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "installedVersion" -> {
                        val info = installedInfo()
                        result.success(mapOf(
                            "versionName" to (info.versionName ?: ""),
                            "versionCode" to versionCode(info),
                            "androidSdk" to Build.VERSION.SDK_INT,
                            "abi" to Build.SUPPORTED_ABIS.firstOrNull(),
                            "packageName" to activity.packageName,
                        ))
                    }
                    "cachedRequirement" -> result.success(preferences.getString(scopeKey(call.arguments as? String), null))
                    "saveRequirement" -> {
                        val key = scopeKey(call.argument<String>("scope"))
                        val value = call.argument<String>("value")
                        require(value == null || value.length <= 65_536) { "Cached update requirement is too large" }
                        if (value != null) JSONObject(value)
                        val editor = preferences.edit()
                        if (value == null) editor.remove(key) else editor.putString(key, value)
                        check(editor.commit()) { "Unable to save update requirement" }
                        result.success(null)
                    }
                    "downloadPath" -> {
                        val key = call.arguments as? String
                        require(key != null && key.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "Invalid update package key" }
                        result.success(File(updateDirectory(), "$key.apk").absolutePath)
                    }
                    "installApk" -> install(parseRequest(call), result)
                    else -> result.notImplemented()
                }
            } catch (error: Exception) {
                result.error("APP_UPDATE_FAILED", error.message ?: "Unable to update application", null)
            }
        }
    }

    private fun scopeKey(scope: String?): String {
        require(!scope.isNullOrBlank() && scope.length <= 2048) { "Update environment is required" }
        return "requirement_${digest(scope.toByteArray(Charsets.UTF_8))}"
    }

    private fun updateDirectory(): File {
        val directory = File(activity.cacheDir, "app-updates")
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create private update cache" }
        val canonical = directory.canonicalFile
        require(canonical.parentFile == activity.cacheDir.canonicalFile) { "Update cache is outside the application cache" }
        return canonical
    }

    private fun parseRequest(call: MethodCall): InstallRequest {
        val path = call.argument<String>("path")
        val code = call.argument<Number>("versionCode")?.toLong()
        val hash = call.argument<String>("sha256")?.lowercase()
        val size = call.argument<Number>("fileSize")?.toLong()
        require(!path.isNullOrBlank() && code != null && code > 0 && size != null && size in 1..MAX_APK_SIZE
            && hash != null && hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid update package descriptor" }
        return InstallRequest(path, code, hash, size)
    }

    private fun install(request: InstallRequest, result: MethodChannel.Result) {
        if (!verifying.compareAndSet(false, true)) {
            result.error("APP_UPDATE_BUSY", "An update package is already being checked", null)
            return
        }
        worker.execute {
            try {
                val file = verifyPackage(request)
                activity.runOnUiThread {
                    verifying.set(false)
                    if (destroyed) {
                        result.error("APP_UPDATE_CANCELLED", "Application is no longer active", null)
                    } else {
                        try {
                            if (!activity.packageManager.canRequestPackageInstalls()) {
                                pendingInstall = request
                                try {
                                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${activity.packageName}")))
                                } catch (error: Exception) {
                                    pendingInstall = null
                                    throw error
                                }
                                result.success("permissionRequested")
                            } else {
                                pendingInstall = null
                                openInstaller(file)
                                result.success("installerOpened")
                            }
                        } catch (error: Exception) {
                            result.error("APP_UPDATE_INSTALL_FAILED", error.message ?: "Unable to open package installer", null)
                        }
                    }
                }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    verifying.set(false)
                    result.error("APP_UPDATE_INVALID_PACKAGE", error.message ?: "Update package validation failed", null)
                }
            }
        }
    }

    /** A settings grant does not bypass the same package checks made for manual installation. */
    fun onResume() {
        val request = pendingInstall ?: return
        pendingInstall = null
        if (!activity.packageManager.canRequestPackageInstalls() || destroyed) return
        if (!verifying.compareAndSet(false, true)) return
        worker.execute {
            try {
                val file = verifyPackage(request)
                activity.runOnUiThread {
                    verifying.set(false)
                    if (!destroyed && activity.packageManager.canRequestPackageInstalls()) {
                        try { openInstaller(file) }
                        catch (_: Exception) { showRetryMessage() }
                    }
                }
            } catch (_: Exception) {
                activity.runOnUiThread {
                    verifying.set(false)
                    if (!destroyed) showRetryMessage()
                }
            }
        }
    }

    private fun showRetryMessage() {
        Toast.makeText(activity, "无法安装此更新，请返回 App 重新下载或重试", Toast.LENGTH_LONG).show()
    }

    private fun verifyPackage(request: InstallRequest): File {
        val file = File(request.path).canonicalFile
        require(file.parentFile == updateDirectory() && file.name.matches(Regex("[A-Za-z0-9_-]{1,160}\\.apk"))
            && file.isFile) { "Package is outside the private update cache" }
        require(file.length() == request.size) { "Update package size does not match" }
        val checksum = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                checksum.update(buffer, 0, count)
            }
        }
        require(checksum.digest().hex() == request.sha256) { "Update package checksum does not match" }
        val flags = signatureFlags()
        val manager = activity.packageManager
        val archive = if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageArchiveInfo(file.path, flags)
        }
        require(archive != null) { "Unable to parse update package" }
        val current = installedInfo()
        require(archive.packageName == activity.packageName) { "Update package name differs from the installed application" }
        require(versionCode(archive) == request.versionCode && request.versionCode > versionCode(current)) {
            "Update version does not match or is not newer"
        }
        val application = archive.applicationInfo
        require(application != null && (application.flags and (ApplicationInfo.FLAG_DEBUGGABLE or ApplicationInfo.FLAG_TEST_ONLY)) == 0) {
            "Debug and test-only packages cannot be installed as updates"
        }
        require(application.minSdkVersion <= Build.VERSION.SDK_INT) { "This update requires a newer Android system" }
        val actualSigners = signerDigests(archive)
        require(actualSigners.isNotEmpty() && actualSigners == signerDigests(current)) {
            "Update signing certificates differ from the installed application"
        }
        return file
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        activity.packageManager.getPackageInfo(activity.packageName,
            PackageManager.PackageInfoFlags.of(signatureFlags().toLong()))
    } else activity.packageManager.getPackageInfo(activity.packageName, signatureFlags())

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.map { digest(it.toByteArray()) }?.toSet() ?: emptySet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else info.versionCode.toLong()

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun openInstaller(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.app_updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("Application update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }

    fun destroy() {
        destroyed = true
        pendingInstall = null
        channel.setMethodCallHandler(null)
        worker.shutdownNow()
    }

    companion object { private const val MAX_APK_SIZE = 260L * 1024 * 1024 }
}
