# Miniwall Android SDK (`app.miniwall:sdk`)

Show screens built in the Miniwall dashboard natively with Jetpack Compose. Miniwall renders; your
app sells: prices come from your app, purchases run in your code (Play Billing or your library).

Status: preview, not yet on Maven Central (`./gradlew publishToMavenLocal` works). Tested on an Android 15 emulator: unit, instrumented, 77-template sweep in light, dark and large text. minSdk 24, Kotlin 2.0, Compose.

| Module | What |
|---|---|
| `core` | Pure Kotlin (JVM): screen model, layout engine, shared logic, render tree, sessions. Tested on any JDK |
| `sdk` | Android library: Compose renderer, networking, `Miniwall` API, `MiniwallActivity`. Enabled when the Android SDK is present |

## Use

```kotlin
// Application.onCreate
Miniwall.configure(this, "pk_...")

// From an Activity
lifecycleScope.launch {
    val result = Miniwall.present(this@MainActivity, ScreenSource.Placement("upgrade"), PresentOptions(
        products = mapOf("monthly" to ProductInfo(9.99, "USD", Period.Every(Period.Unit.MONTH))),
        onPurchase = { request -> billing.buy(request.packageId) }, // true = bought, false = cancelled
        onRestore = { billing.restore() },
    ))
}
```

Java and callback code: `Miniwall.present(activity, source, options) { result -> ... }`.
Inline in Compose: `MiniwallScreen(ScreenSource.Placement("onboarding"), options) { result -> ... }`.

Same API as iOS: `configure`, `setAttributes`, `present`, `preload`, `reportPurchase`, `reset`,
`packageIds`. Display mapping: fullscreen → full-screen activity, modal → full-height sheet,
slide-in → sheet as tall as the content, banner/toast → overlay that does not block the app.
Back closes the screen (`Closed`).

## Develop

The tests run against the [Miniwall spec](https://github.com/miniwall-app/miniwall-spec) (fixtures, golden frames, logic vectors, mock-API screens). The version this SDK is tested against is pinned in `SPEC_VERSION`:

```bash
scripts/fetch-spec.sh          # puts it in .spec/ (or point -PspecDir=<dir> / MINIWALL_SPEC_DIR at your own checkout)
./gradlew :core:test           # layout goldens, logic vectors, components, sessions
```

On an emulator or device (JDK 17 and the Android SDK, `ANDROID_HOME` or `local.properties`):

```bash
./gradlew :sdk:connectedDebugAndroidTest   # the SDK's UI tests (purchase, restore, flow, offline, Back)
```

If your own Activity can be recreated (rotation), declare `configChanges` for it or call `present` from a scope that survives: cancelling the caller's coroutine closes the screen.

Use from another Gradle build before publishing:

```kotlin
// settings.gradle.kts of the app
includeBuild("<repo>") {
    dependencySubstitution { substitute(module("app.miniwall:sdk")).using(project(":sdk")) }
}
```
