plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.flutter.flutter-gradle-plugin")
}

import java.util.Properties

val localProps = Properties().apply {
    file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}
val flutterVersionCode = localProps.getProperty("flutter.versionCode")?.toInt() ?: 1
val flutterVersionName = localProps.getProperty("flutter.versionName") ?: "1.0"

android {
    namespace = "com.kicknext.pos_printers_example"
    compileSdk = flutter.compileSdkVersion
    // Use the highest NDK version required by plugins (backward compatible)
    ndkVersion = "28.2.13676358"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.kicknext.pos_printers_example"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    // Здесь можно добавить зависимости для example приложения
}