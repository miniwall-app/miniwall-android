package com.miniwall.core

import java.io.File

/**
 * The Miniwall spec (github.com/miniwall-app/miniwall-spec): fixtures, golden frames, vectors and the screens the UI tests drive.
 * The version this SDK is tested against is pinned in SPEC_VERSION; `scripts/fetch-spec.sh` puts it in `.spec/`.
 * `build.gradle.kts` passes the folder as the `specDir` system property (override with -PspecDir=... or MINIWALL_SPEC_DIR).
 */
object Spec {
    val dir: File = File(System.getProperty("specDir") ?: error("specDir is not set: run scripts/fetch-spec.sh")).also {
        require(it.isDirectory) { "The Miniwall spec is not at $it: run scripts/fetch-spec.sh" }
    }

    /** Screens shared by the UI tests of every platform. */
    val ui: File get() = File(dir, "mock-api/ui")
}
