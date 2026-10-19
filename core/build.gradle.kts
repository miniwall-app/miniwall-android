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

tasks.test {
    useJUnitPlatform()
    // Shared conformance suite (fixtures + goldens from the web renderer).
    val conformanceDir = rootProject.projectDir.resolve("../../the shared conformance suite")
    systemProperty("conformanceDir", conformanceDir.canonicalPath)
    // Screens shared by the UI tests of every platform (sdks/testdata/ui): checked here that they behave as the UI tests expect.
    val uiTestData = rootProject.projectDir.resolve("../testdata/ui")
    systemProperty("uiTestData", uiTestData.canonicalPath)
    if (uiTestData.exists()) inputs.dir(uiTestData)
    // Re-run when fixtures or goldens change.
    // (The public mirror has no the shared conformance suite those tests stay in the monorepo.)
    if (conformanceDir.exists()) {
        inputs.dir(conformanceDir.resolve("fixtures"))
        inputs.dir(conformanceDir.resolve("golden"))
    }
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
            developers { developer { name.set("Miniwall") } }
            scm {
                url.set("https://github.com/miniwall-app/miniwall-android")
                connection.set("scm:git:https://github.com/miniwall-app/miniwall-android.git")
                developerConnection.set("scm:git:ssh://git@github.com/miniwall-app/miniwall-android.git")
            }
        }
        }
    }
}
