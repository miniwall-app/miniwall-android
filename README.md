# Miniwall Android SDK (`app.miniwall:sdk`)

Show screens built in the Miniwall dashboard natively with Jetpack Compose. Miniwall renders; your
app sells: prices come from your app, purchases run in your code (Play Billing or your library).

Status: `0.1.0`. Tested on Android 15 and Android 7 (API 24) emulators: unit and instrumented tests, 77-template sweep in light, dark and large text. minSdk 24, Kotlin 2.0, Compose. The AAR is built with AGP 8.7.3 / Kotlin 2.0 and works in apps on newer AGP and Kotlin (a Flutter 3.47 app on AGP 8.11 and Kotlin 2.2 builds and runs against it).

| Module | What |
|---|---|
| `core` | Pure Kotlin (JVM): screen model, layout engine, shared logic, render tree, sessions. Tested on any JDK |
| `sdk` | Android library: Compose renderer, networking, `Miniwall` API, `MiniwallActivity`. Enabled when the Android SDK is present |

## Install

The artifacts are served from `https://maven.miniwall.app`, a standard Maven repository (the rest of the dependencies come from Maven Central and Google as usual).

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.miniwall.app")
            content { includeGroup("app.miniwall") }
        }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("app.miniwall:sdk:0.1.0")
}
```

Projects that declare repositories in the root `build.gradle` (`allprojects { repositories { ... } }`) add the same `maven { ... }` block there.

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

## Selling with Google Play Billing

Miniwall draws the screen; the purchase is yours. A sketch with Play Billing Library 7 (`com.android.billingclient:billing-ktx`; not part of this SDK and not run by its tests;
adapt it to your product ids, and verify purchases on your server):

```kotlin
class Billing(private val activity: Activity) {
    private val purchases = Channel<Pair<BillingResult, List<Purchase>?>>(Channel.CONFLATED)
    private val client = BillingClient.newBuilder(activity)
        .setListener { result, list -> purchases.trySend(result to list) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    /** The package ids of the screen are your product ids: "monthly", "annual". Returns true when bought, false when cancelled. */
    suspend fun buy(productId: String): Boolean {
        connect()
        val details = client.queryProductDetails(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(BillingClient.ProductType.SUBS).build(),
            )).build(),
        ).productDetailsList?.firstOrNull() ?: return false
        val offer = details.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return false
        client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).setOfferToken(offer).build(),
        )).build())
        val (result, list) = purchases.receive()
        val purchase = list?.firstOrNull()
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchase == null) return false
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build())
        return true
    }

    /** "Restore purchases": true when the user owns a subscription. */
    suspend fun restore(): Boolean {
        connect()
        val owned = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        return owned.purchasesList.isNotEmpty()
    }

    private suspend fun connect() {
        if (client.isReady) return
        suspendCancellableCoroutine<Unit> { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) { if (cont.isActive) cont.resume(Unit) }
                override fun onBillingServiceDisconnected() {}
            })
        }
    }
}
```

Pass the real prices to `PresentOptions.products` from `ProductDetails` (`formattedPrice`, `priceAmountMicros`) so the screen shows what the store charges.
After a purchase call `Miniwall.reportPurchase(packageId = "monthly")` so holdouts and conversion are counted.

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

`example/` is a sample app. It bundles the spec's templates, so with the open spec it shows the ten example screens:

```bash
./gradlew :example:installDebug
adb shell am start -n com.miniwall.example/.MainActivity --es tpl free-trial   # present one (the display follows the file name: -bar, -toast, -slide-in...)
./sweep-templates.sh [dark|large]   # present every bundled template, check the app survives, screenshots in build/sweep/
```

To sweep a bigger library, build with `-PspecDir=<folder>` (and `MINIWALL_SPEC_DIR=<folder> ./sweep-templates.sh`) pointing at a spec folder that has more `fixtures/templates`.

If your own Activity can be recreated (rotation), declare `configChanges` for it or call `present` from a scope that survives: cancelling the caller's coroutine closes the screen.

Use from another Gradle build before publishing:

```kotlin
// settings.gradle.kts of the app
includeBuild("<repo>") {
    dependencySubstitution { substitute(module("app.miniwall:sdk")).using(project(":sdk")) }
}
```
