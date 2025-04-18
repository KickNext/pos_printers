pluginManagement {
    val properties = java.util.Properties().apply {
        file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val flutterSdk = properties.getProperty("flutter.sdk")
        ?: throw GradleException("flutter.sdk not set in local.properties")
    includeBuild("$flutterSdk/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android") version "1.9.10"
}

rootProject.name = "pos_printers"
