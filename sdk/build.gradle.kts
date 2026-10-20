plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.compose")
    `maven-publish`
}

// Maven coordinates app.miniwall:sdk (React Native / Flutter substitute this project for it locally).
group = "app.miniwall"
version = "0.1.0"

android {
    namespace = "com.miniwall.sdk"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The test APK targets the current SDK: an old target makes Android 15 show an "app built for an older version" dialog
    // that takes the first Back press.
    testOptions.targetSdk = 35
    // UI tests (src/androidTest): the screens shared by every platform's UI tests (the spec's mock-api/ui) become test assets.
    val specDir = (findProperty("specDir") as String? ?: System.getenv("MINIWALL_SPEC_DIR"))?.let(::file) ?: rootProject.file(".spec")
    sourceSets.getByName("androidTest").assets.srcDir(specDir.resolve("mock-api/ui"))
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } }
}

dependencies {
    api(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.foundation:foundation")
    // Public API types (Modifier in MiniwallScreen, ComponentActivity in MiniwallActivity): consumers need them.
    api("androidx.compose.ui:ui")
    api("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Compose UI tests: ./gradlew :sdk:connectedDebugAndroidTest (emulator or device; see src/androidTest/README.md).
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// app.miniwall:sdk. Publish with: ./gradlew publishToMavenLocal (check), then your repository (signing and credentials are not in the repo).
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "sdk"
        pom {
            name.set("Miniwall Android SDK")
            description.set("Show screens built in the Miniwall dashboard natively with Jetpack Compose.")
            url.set("https://docs.miniwall.app")
            licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT") } }
            developers { developer { id.set("miniwall"); name.set("Miniwall"); url.set("https://miniwall.app") } }
            scm {
                url.set("https://github.com/miniwall-app/miniwall-android")
                connection.set("scm:git:https://github.com/miniwall-app/miniwall-android.git")
                developerConnection.set("scm:git:ssh://git@github.com/miniwall-app/miniwall-android.git")
            }
        }
            }
        }
    }
}

apply(from = rootProject.file("gradle/release.gradle.kts"))
