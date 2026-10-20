plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

// Sample app for trying the SDK on an emulator and for the template sweep (sweep-templates.sh). Not published.
android {
    namespace = "com.miniwall.example"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.miniwall.example"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    // The spec's fixtures (templates/<name>.json, components/<name>.json) become assets: the open spec has a small set of templates;
    // to sweep a bigger library point -PspecDir / MINIWALL_SPEC_DIR at a folder that has more (scripts/fetch-spec.sh fetches the open one).
    val specDir = (findProperty("specDir") as String? ?: System.getenv("MINIWALL_SPEC_DIR"))?.let(::file) ?: rootProject.file(".spec")
    sourceSets.getByName("main").assets.srcDir(specDir.resolve("fixtures"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":sdk"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
}
