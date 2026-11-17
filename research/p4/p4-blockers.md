# Phase P4: Platform Blockers, Constraints & Technical Limitations

## Overview
This document registers the legitimate technical limitations, upstream platform constraints, and Google Cloud infrastructure boundaries identified during Phase 4. Rather than inventing undocumented workarounds or assuming internal desktop-only APIs are publicly supported, each limitation is formally documented with its technical cause, impact, and mitigation.

---

## 1. Registered Technical Limitations & Constraints

### LIMITATION-01: Google CloudCode PA File-Hosting Boundaries for Local NTFS Folders
- **Category:** Account Synchronization & Cloud Storage.
- **Root Cause:** 
  The Google Antigravity backend (`language_server`) synchronizes project definitions (`<id>.json`), workspace settings, and cloud-hosted remote workspaces (`daily-cloudcode-pa.googleapis.com`) under the user's Google account (`monsterbag293@gmail.com`). However, the original Google Language Server does NOT upload arbitrary desktop-local NTFS folder contents to Google cloud servers unless the project is explicitly created as a Cloud Code remote workspace.
- **Impact:** 
  Account synchronization discovers project metadata (project ID, project name, settings) across all devices. However, raw project files stored only on a user's Windows hard drive (e.g. `C:\Users\...\ChaosAI`) cannot be downloaded from Google Cloud if they were never pushed to a remote repository or cloud workspace.
- **Mitigation & Handling:**
  The `AccountSyncManager` maps the project into the Android workspace directory (`/tmp/gemini_app/workspaces/<name>/`), generates SHA-256 manifests, and synchronizes files when remote storage or cloud workspace metadata is present, while distinguishing project metadata synchronisation from file-content synchronisation.

---

### LIMITATION-02: Electron Multi-Process `<webview>` Architecture vs Android Single WebView
- **Category:** Browser Integration & UI Architecture.
- **Root Cause:**
  Desktop Antigravity leverages Electron's `wcvBrowserRegistry.js` and Chromium multi-process guest `<webview>` architecture to host auxiliary browser tabs within the main Electron window. Android `WebView` is single-process embedded per Activity.
- **Impact:**
  Opening an arbitrary nested Electron `<webview>` tab inside an Android WebView is not supported by the Android framework.
- **Mitigation & Handling:**
  Implemented a full JavaScript polyfill for `window.electronNative.wcvBrowser` in `JetskiBundleLoader.kt`. URL navigation requests are safely routed to the Android system browser via `Intent(Intent.ACTION_VIEW)` with zero crashes or unhandled rejections.

---

### LIMITATION-03: Operating System Filesystem Path Disparity
- **Category:** Workspace Portability.
- **Root Cause:**
  Desktop paths use Windows drive letters and backslashes (`file:///c%3A/Users/NEW0033/Downloads/...`) or macOS posix paths. Android uses sandboxed Linux paths (`/data/data/dev.agentic.workspace/files/rootfs/tmp/gemini_app/workspaces/...`).
- **Impact:**
  A direct literal copy of a desktop project's `folderUri` will fail to resolve on Android, and vice versa.
- **Mitigation & Handling:**
  `AccountSyncManager.mapToLocalWorkspaceUri()` inspects the project name, sanitizes it, and constructs a platform-appropriate local URI (`file:///tmp/gemini_app/workspaces/<sanitized_name>`), ensuring full compatibility across platforms while preserving the project ID and configuration.

---

### LIMITATION-04: Non-Root Linux Execution on Android (PRoot Ptrace Overhead)
- **Category:** Process Virtualization.
- **Root Cause:**
  Android's Bionic libc and SELinux policies prohibit standard glibc ELF binary execution and system-level root access.
- **Impact:**
  The ARM64 `language_server` binary requires glibc and Linux file structures. PRoot emulates these system calls via `ptrace`.
- **Mitigation & Handling:**
  The PRoot userspace environment is tuned with architecture-specific loader flags (`PROOT_LOADER`, `PROOT_TMP_DIR`), ensuring stable backend execution and daemon lifecycle management within Android application sandbox limits without requiring root permissions.
