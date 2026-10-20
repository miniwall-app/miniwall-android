## 0.1.0

- First preview of `app.miniwall:sdk` (Jetpack Compose) and `app.miniwall:sdk-core` (pure Kotlin).
- `Miniwall.configure`, `setAttributes`, `present` (suspending and callback forms), `preload`, `reportPurchase`, `reset`, `packageIds`, and `MiniwallScreen` for inline screens.
- Screens built in the Miniwall dashboard are drawn natively. Prices and purchases stay in your app.
- A screen waits at most 1.5 s for its custom fonts, then shows with the system font; a font that arrives later is kept for the next screen. `Miniwall.preload` waits up to 10 s.
- Banners and toasts clear the status bar, display cutout and keyboard even when the host Activity consumes the window insets (React Native), and are not drawn under the bars.
- `javadoc` jar is a pointer to the online documentation (the generated one fails on sealed Kotlin classes).
