# Android: Compose UI tests

`kotlin/com/miniwall/sdk/MiniwallUiTest.kt` drives a real presentation (`MiniwallActivity`) through the Compose semantics tree:
open, choose a plan, fake purchase (cancelled and successful), Restore, Close, system Back on the first screen, a multi-screen flow
with a required question and the screen's own Back button, system Back inside a flow, the offline cache (MockWebServer), holdout skip.

The screens are the spec's `mock-api/ui/*.json` (added as test assets by `sdk/build.gradle.kts`; run `scripts/fetch-spec.sh` first); the iOS, React Native and Flutter suites use the
same files. `core`'s `UiFixturesTest` checks, at cloud and without an emulator, that those screens behave as these tests expect.

```bash
export ANDROID_HOME=~/Library/Android/sdk        # or open the folder in Android Studio (writes local.properties)
# an emulator (API 30+) or a device must be running: adb devices
./gradlew :sdk:connectedDebugAndroidTest
# one test:
./gradlew :sdk:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.miniwall.sdk.MiniwallUiTest#choosingAPlanThenSubscribeBuysThatPlan
```

Report: `sdk/build/reports/androidTests/connected/index.html`. The `:sdk` module only exists when an Android SDK is found
(`settings.gradle.kts`), so `gradle :core:test` keeps working without one.

Notes
- The test turns animations off for its run (`settings put global animator_duration_scale 0`, restored after); the SDK then draws no spinner animation.
- `systemBackInsideAFlowGoesToThePreviousScreen` encodes MOBILE-SDK-PLAN 5.1 ("inside a flow, Back is `screen:back`"). `MiniwallActivity` currently
  calls `close()` for system Back on every screen, so that one is expected to fail until the Activity routes Back to the session.
- Status: written without an Android SDK (cloud has none); not compiled or run yet. Expect small API fixes on the first run.
