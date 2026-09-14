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
        }
        create("prod") {
            dimension = "environment"
            resValue("string", "app_name", "倾境壁纸")
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
