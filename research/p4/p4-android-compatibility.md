# Phase P4: Android Compatibility Specification & Electron Bridge Analysis

## Overview
This document specifies how all desktop-specific Electron, Windows, and Node.js APIs used by the original Google Antigravity application have been mapped to native Android architecture without loss of functionality.

---

## 1. Electron & Windows API Bridge Matrix

| Desktop API / Electron Module | Desktop Implementation | Android Compatible Architecture | Bridge Mechanism |
|---|---|---|---|
| `window.nativeStorage` | Electron `StorageManager` writing to `app_storage.json` | Android Internal App Storage (`context.filesDir/app_storage.json`) | `@JavascriptInterface fun nativeStorageGetItems()` & `nativeStorageUpdateItems()` |
| `window.nativeNotifications` | Electron `Notification` class + macOS/Windows notification center | Android `NotificationManager` on channel `antigravity_notifications` | `@JavascriptInterface fun sendNotification()` & `openNotificationSettings()` |
| `window.dialog` | Electron `dialog.showOpenDialog` (folder selection) | Android custom single-step mobile modal + `listWorkspaceFolders()` | Injected JS overlay + `@JavascriptInterface` directory manager |
| `window.electronNative.openExternal` | Electron `shell.openExternal` | Android `Intent(Intent.ACTION_VIEW, Uri.parse(url))` | `@JavascriptInterface fun openExternal(url: String)` |
| `window.electronNative.revealInFilePicker` | Electron `shell.showItemInFolder` | Android File Provider / Toast notification | `@JavascriptInterface fun revealInFilePicker(path: String)` |
| `window.electronNative.getPathForFile` | Electron `webUtils.getPathForFile(file)` | HTML5 File object `.name` or `.path` | Injected JS polyfill |
| `window.electronNative.getZoomLevel` | Electron `webFrame.getZoomFactor()` | Android WebView viewport scale (1.0 default) | Injected JS polyfill returning 1.0 |
| `window.electronNative.wcvBrowser` | Electron `<webview>` guest tabs & `wcvBrowserRegistry.js` | Virtual Browser Manager + system browser intent dispatch | Polyfilled JS object implementing all 20 `wcvBrowser` methods |
| `window.deepLink` | Electron custom protocol handler `antigravity://` | Android Intent Filter with `<data android:scheme="antigravity" />` | `MainActivity` intent capture + `@JavascriptInterface fun getStoredDeepLink()` |
| `window.agent` | Electron tray icon active agent counter | Android logging / foreground service notification | `@JavascriptInterface fun updateActiveAgentCount(count: Int)` |
| `window.ide` | Check for IDE executable install path | Embedded standalone mode (always true) | JS Promise resolving `true` |
| `window.logs` | Electron log file reader (`logs:electron`) | Android Logcat / internal diagnostics buffer | `@JavascriptInterface fun getLogs()` |
| File Input (`<input type="file">`) | Electron file dialog | Android `WebChromeClient.onShowFileChooser` (`PickActivity`) | `ActivityResultContracts.StartActivityForResult()` with `ACTION_GET_CONTENT` |
| Audio Input (Microphone) | Chromium audio recording | Android `RECORD_AUDIO` + `MODIFY_AUDIO_SETTINGS` | `WebChromeClient.onPermissionRequest` granting `RESOURCE_AUDIO_CAPTURE` |
| Terminal / Command Execution | Windows PowerShell / `node-pty` | Genuine Debian Linux PRoot sandbox with `/bin/bash` | `SandboxedCommandExecutor.kt` inside PRoot userspace |

---

## 2. In-Depth Architectural Adaptations

### 2.1 Viewport & Responsive Mobile Layout
- **Desktop Behavior:** Desktop Antigravity renders a multi-column desktop layout with sidebars and editor panes intended for wide monitors (> 1200px).
- **Android Adaptation:** 
  - Android WebView settings configure `useWideViewPort = false` and `loadWithOverviewMode = false`.
  - This ensures `window.innerWidth` matches the phone's physical CSS pixel width (e.g. 412px on Pixel 10 Pro).
  - Jetski Web contains built-in responsive checks (`HE() === true` when `window.innerWidth < 640`). This activates the mobile bottom sheet, stacked conversation view, and compact controls automatically.

### 2.2 Storage Persistence Across Lifecycles
- **Desktop Behavior:** When the user changes projects, toggles aux-pane tabs, or collapses sections, `window.nativeStorage.updateItems` writes immediately to `app_storage.json`.
- **Android Adaptation:**
  - Changes are serialized to JSON and persisted atomically to `app_storage.json` inside the application's private sandbox directory (`/data/data/dev.agentic.workspace/files/app_storage.json`).
  - Upon WebView reload or app restart, `window.nativeStorage.getItems()` reads this file, restoring user preferences and session layout seamlessly.

### 2.3 Audio Permissions for Voice Memos
- **Desktop Behavior:** Desktop Electron requests system microphone permissions directly through OS APIs.
- **Android Adaptation:**
  - Modern Chromium WebView requires both `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS` declared in `AndroidManifest.xml` and granted at runtime.
  - When the user taps the microphone button in Jetski Web, `WebChromeClient.onPermissionRequest` intercepts the request for `RESOURCE_AUDIO_CAPTURE` and launches the Android runtime permission dialog if not yet granted.
  - Once granted, the audio stream is passed directly to the React audio recorder.

### 2.4 Terminal Execution & Shell Tools
- **Desktop Behavior:** Desktop Antigravity executes shell commands using host PowerShell or CMD processes.
- **Android Adaptation:**
  - The Android OS does not permit arbitrary process execution on standard non-rooted devices due to SELinux and `W^X` memory restrictions.
  - Antigravity uses PRoot (userspace `ptrace` system call emulation) to host a full Debian GNU/Linux environment.
  - Commands executed by the Cascade agent run through `/bin/bash` inside the rootfs with a full Linux PATH (`/usr/bin:/bin`) and root privileges, supporting standard command-line tools without requiring device rooting.
