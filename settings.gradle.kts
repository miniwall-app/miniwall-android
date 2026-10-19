pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "miniwall-android"

// Pure Kotlin (JVM): PaywallData model, layout engine, shared logic. Tests run on any JDK.
include(":core")

// Android library (Compose renderer + public API). Needs the Android SDK: enabled when
// ANDROID_HOME / ANDROID_SDK_ROOT is set or local.properties has sdk.dir (Android Studio writes it).
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (hasAndroidSdk) {
    include(":sdk")
    // Sample app and template sweep (sweep-templates.sh).
    include(":example")
}
