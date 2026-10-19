// The Android Gradle plugin goes on the root build's classpath (only when an Android SDK exists, so
// `gradle :core:test` still runs on a machine without one). Every plugin of the sdk module then loads
// in one classloader: Kotlin's Android plugin and AGP must see each other.
buildscript {
    val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
    if (hasAndroidSdk) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies { classpath("com.android.tools.build:gradle:8.7.3") }
    }
}

plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("plugin.compose") version "2.0.21" apply false
}
