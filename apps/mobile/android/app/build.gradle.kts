import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateInternalTrustResources : DefaultTask() {
    @get:InputFile abstract val certificate: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun generate() {
        val pem = certificate.get().asFile.readText()
        require(pem.contains("-----BEGIN CERTIFICATE-----") && !pem.contains("PRIVATE KEY"))
        val destination = outputDirectory.get().file("raw/qingjing_internal_loopback.pem").asFile
        destination.parentFile.mkdirs()
        destination.writeText(pem)
    }
}

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.qingjing.qingjing_wallpaper"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.qingjing.qingjing_wallpaper"
        minSdk = 26
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    flavorDimensions += "environment"
    productFlavors {
        create("local") {
            dimension = "environment"
            applicationIdSuffix = ".local"
            resValue("string", "app_name", "倾境壁纸·内测")
            resValue("string", "qj_package_signing_key_id", System.getenv("QJ_LOCAL_PACKAGE_SIGNING_KEY_ID") ?: "")
            resValue("string", "qj_package_signing_public_key", System.getenv("QJ_LOCAL_PACKAGE_PUBLIC_KEY_DER") ?: "")
        }
        create("prod") {
            dimension = "environment"
            resValue("string", "app_name", "倾境壁纸")
            resValue("string", "qj_package_signing_key_id", System.getenv("QJ_PROD_PACKAGE_SIGNING_KEY_ID") ?: "")
            resValue("string", "qj_package_signing_public_key", System.getenv("QJ_PROD_PACKAGE_PUBLIC_KEY_DER") ?: "")
        }
        create("internal") {
            dimension = "environment"
            applicationIdSuffix = ".internal"
            resValue("string", "app_name", "倾境壁纸·候选内测")
            resValue("string", "qj_package_signing_key_id", System.getenv("QJ_INTERNAL_PACKAGE_SIGNING_KEY_ID") ?: "")
            resValue("string", "qj_package_signing_public_key", System.getenv("QJ_INTERNAL_PACKAGE_PUBLIC_KEY_DER") ?: "")
        }
        create("lab") {
            dimension = "environment"
            applicationIdSuffix = ".lab"
            resValue("string", "app_name", "倾境壁纸·4D调试")
            resValue("string", "qj_package_signing_key_id", System.getenv("QJ_INTERNAL_PACKAGE_SIGNING_KEY_ID") ?: "")
            resValue("string", "qj_package_signing_public_key", System.getenv("QJ_INTERNAL_PACKAGE_PUBLIC_KEY_DER") ?: "")
        }
    }

    buildTypes {
        release {
            // Internal localRelease only. Production release remains unsigned.
            signingConfig = null
        }
    }
}

flutter {
    source = "../.."
}

// Test signing applies only to local artifacts, never to production.
android.productFlavors.getByName("local").signingConfig = android.signingConfigs.getByName("debug")

// Independent installation and signer for destructive QA; production stays unsigned.
val internalStore = System.getenv("QJ_INTERNAL_KEYSTORE_FILE")
if (!internalStore.isNullOrBlank()) {
    val internalSigning = android.signingConfigs.create("internalTest") {
        storeFile = file(internalStore)
        storePassword = System.getenv("QJ_INTERNAL_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("QJ_INTERNAL_KEY_ALIAS")
        keyPassword = System.getenv("QJ_INTERNAL_KEY_PASSWORD")
    }
    android.productFlavors.getByName("internal").signingConfig = internalSigning
    android.productFlavors.getByName("lab").signingConfig = internalSigning
}

android.sourceSets.getByName("lab").manifest.srcFile("src/internal/AndroidManifest.xml")
android.sourceSets.getByName("lab").res.srcDir("src/internal/res")

val generateInternalTrustResources = tasks.register<GenerateInternalTrustResources>("generateInternalTrustResources") {
    certificate.set(layout.file(providers.environmentVariable("QJ_INTERNAL_TLS_CERT_FILE").map { file(it) }))
    outputDirectory.set(layout.buildDirectory.dir("generated/internalTrust/res"))
}
androidComponents {
    onVariants(selector().withFlavor("environment" to "internal")) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateInternalTrustResources) { it.outputDirectory }
    }
    onVariants(selector().withFlavor("environment" to "lab")) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateInternalTrustResources) { it.outputDirectory }
    }
}
