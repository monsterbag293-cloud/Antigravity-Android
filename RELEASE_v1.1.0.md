# Antigravity for Android — v1.1.0

**Version:** `1.1.0` (`versionCode = 2`)  
**Release Artifact:** `antigravity-v1.1.0-release.apk`  
**Size:** `43,539,316` bytes (~41.52 MB)  
**SHA-256:** `c8c79802326ca116cd59f32d821c0c5b22d8866d260a0f87392ce9820a8cd6bb`  

---

## What's Included in v1.1.0

### Compatibility & Networking
- **ARMv8.0 Compatibility Mode (QEMU):** Bundled `libqemu-aarch64.so` and `qemu-aarch64-static.gz` with automatic `SIGILL` fallback and a manual toggle in Host Settings, enabling the on-device `language_server` to run on older ARMv8.0 CPUs lacking LSE atomics.
- **Cleartext Loopback Traffic:** Enabled `android:usesCleartextTraffic="true"` in `app/src/main/AndroidManifest.xml` so Android 9+ devices (including Galaxy Tab A8 / SM-X200) can communicate with the local loopback language server.

### Launcher Shortcuts & Navigation
- **Android Launcher App Shortcuts:** Added 48dp circular adaptive-style icons (`ic_shortcut_new_chat`, `ic_shortcut_workspace`, `ic_shortcut_settings`) and deep-link routing (`antigravity://new_chat`, `antigravity://workspace`, `antigravity://settings`).
- **Cold-Start Deep-Link Dispatch:** Fixed `window.deepLink.getStoredDeepLink()` / `window.__dispatchDeepLink` in `JetskiBundleLoader.kt` so launcher shortcuts bypass the default New Conversation landing screen on both cold and warm starts.

### UI, Files & Host Settings
- **Secret Host Settings Toggle:** Holding the sidebar **Settings** button for 10 seconds (with a countdown pill starting at 5 seconds) toggles the **Host** settings button ON or OFF.
- **Google-Themed Host Settings:** Redesigned `SettingsScreen.kt` to match the dark Google-themed Setup Wizard (`#101010` / `#191919` / `#4285F4` / `GoogleSans`), simplified cards, conditional Desktop Hub / BYOK panels, and a header close button.
- **Cloud File Downloads:** Added download bridge support in `JetskiWebViewHost.kt` and `JetskiBundleLoader.kt`.

### Build & Release
- **Release Signing Support:** Added conditional `signingConfigs.release` in `app/build.gradle.kts` backed by gitignored `keystore.properties` and `release.jks`.
- **Verification:** `testDebugUnitTest` and `:app:assembleRelease` passed (`BUILD SUCCESSFUL`).

---

> **Install Note:** Uninstall any previous debug-signed build before installing `antigravity-v1.1.0-release.apk`, as Android will not overwrite a debug-signed APK with a release-signed one.
