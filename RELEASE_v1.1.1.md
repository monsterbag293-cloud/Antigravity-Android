# Antigravity for Android — v1.1.1 (Hotfix)

**Version:** `1.1.1` (`versionCode = 3`)  
**Release Artifact:** `antigravity-v1.1.1-release.apk`  
**Size:** `43,540,016` bytes (~41.52 MB)  
**SHA-256:** `caa7f7103163cbf9017f0ac14f8a686462894c23feb6c9a3e1aa5625e27ab5b0`  

---

## What's Fixed in v1.1.1

- **Mobile-Optimized Popup Menus & Model Usage Fix:** Replaced hardcoded minified symbol names in `JetskiBundleLoader.patchMainJsForMobile` with version-agnostic `indexOf` + bounded slice matching so stacked mobile bottom-sheet menus (`menuMobile=true`, `trigger=true`) stay enabled across Antigravity Web bundle updates (fixing the Model -> View Usage popup glitch).
- **Blank Screen Fix:** Eliminated full-file regex backtracking on `main.js` so WebView asset patching completes in milliseconds without stalling startup.
- **Includes all v1.1.0 features:** ARMv8.0 QEMU Compatibility Mode, cleartext loopback fix, launcher app shortcuts (`New Chat`, `Workspace`, `Settings`), secret 10s Host Settings toggle, Google-themed Host Settings, and cloud file downloads.

---

> **Install Note:** If upgrading from a debug-signed build, uninstall the debug app first before installing `antigravity-v1.1.1-release.apk`.
