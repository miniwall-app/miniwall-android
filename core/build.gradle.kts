import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
    `maven-publish`
}

group = "app.miniwall"
version = "0.1.0"

java {
    withSourcesJar()
    withJavadocJar()
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Java 17 bytecode (Android-compatible) without requiring JDK 17 exactly.
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    // Exposed: the public API takes and returns JSON values (screen documents, events).
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

// The Miniwall spec (fixtures, golden frames, vectors, mock-API screens) the tests run against.
// scripts/fetch-spec.sh puts the version pinned in SPEC_VERSION into .spec/; -PspecDir=<dir> or MINIWALL_SPEC_DIR points elsewhere.
val specDir: File = (findProperty("specDir") as String? ?: System.getenv("MINIWALL_SPEC_DIR"))?.let(::file) ?: rootProject.file(".spec")

tasks.test {
    useJUnitPlatform()
    systemProperty("specDir", specDir.absolutePath)
    // Re-run when the spec changes.
    if (specDir.exists()) for (part in listOf("fixtures", "golden", "vectors", "mock-api")) inputs.dir(specDir.resolve(part))
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// app.miniwall:sdk-core, the pure Kotlin core that app.miniwall:sdk depends on.
publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            artifactId = "sdk-core"
        pom {
            name.set("Miniwall SDK core")
            description.set("Layout engine, session logic and screen model shared by the Miniwall mobile SDKs.")
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

apply(from = rootProject.file("gradle/release.gradle.kts"))
