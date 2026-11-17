# Google Antigravity Desktop Application: Architecture & Reverse-Engineering Investigation

**Project:** Antigravity Mobile Port  
**Phase:** 1 — Application Identification  
**Target:** Google Antigravity Desktop (Windows x86_64)  
**Investigation Tool:** Reverse Engineer Anything (REA) & Static File Artifact Analysis  
**Output File:** `research/architecture/initial-investigation.md`  

---

## Executive Summary

Google Antigravity is a hybrid multi-process desktop application. Its presentation tier is built on **Electron** (Chromium / Node.js runtime) utilizing a custom multi-layered `WebContentsView` architecture with contextual `<webview>` integration. The application's core intelligence, workspace management, generative agent capabilities, and local web serving reside in a standalone native **Go backend daemon** (`language_server.exe`).

Communication between the presentation frontend and the backend daemon occurs via **loopback HTTPS utilizing Connect-RPC / gRPC-Web**, while the frontend interacts with Electron's host platform capabilities via a sandboxed **contextBridge IPC** layer.

---

## 1. Application Framework

### Findings

| Finding | Classification | Source of Evidence | Evidence Details |
| :--- | :--- | :--- | :--- |
| **Electron Presentation Framework** | Confirmed Fact | REA `inspect_desktop_application`, filesystem inspect | Root path contains `LICENSE.electron.txt`, `LICENSES.chromium.html`, `version` (indicating `44.5.1`), and `resources/app.asar`. REA candidate `dsc_672c35db9be7e4ea0d3efc502e60c773a0cd6d41fb4ea5e279d58d29fc7522f6` classified family as `electron`. |
| **Electron Main Orchestrator** | Confirmed Fact | `app.asar/package.json`, `dist/main.js` | `package.json` identifies the product as `Antigravity` (version `2.29.1`) with entry `"main": "dist/main.js"`. `dist/main.js` imports `electron` (`app`, `BrowserWindow`, `BaseWindow`, `WebContentsView`, `ipcMain`). |
| **Go Native Language Server Backend** | Confirmed Fact | REA `inspect_go_binary` (`ev_14163c0153cec1d2c28905f6a1b396812d9d45978be376cc449351e62711c82a`) | `resources/bin/language_server.exe` (187,440,128 bytes, PE x86_64) contains embedded Go build info: `go1.28-20261025-RC00 cl/1006921819 +a87c0aebd5 X:boringcrypto,simd,nogolistexportnewformat`. |
| **Go Native Media Encoder Utility** | Confirmed Fact | REA `inspect_go_binary` (`ev_e8d522ba6112d338caa3738a223a02c5e4fc8b3ace2d738e3e54842e280d302b`) | `resources/bin/webm_encoder.exe` (13,414,400 bytes, PE x86_64) contains identical Go build info: `go1.28-20261025-RC00 cl/1006921819 +a87c0aebd5`. |
| **Connect-RPC & Protocol Buffers Protocol Layer** | Confirmed Fact | `app.asar/package.json`, `dist/hostBridgeServer.js` | Packaged dependencies include `@bufbuild/protobuf` (v2.11.0), `@connectrpc/connect` (v2.1.2), and `@connectrpc/connect-node` (v2.2.0). |

---

## 2. Packaging Format

### Findings

| Component | Packaging Format | Path / Location | Source & Supporting Evidence |
| :--- | :--- | :--- | :--- |
| **Installer / Distributable** | NSIS (Nullsoft Scriptable Install System) / Electron-Builder | `$env:LOCALAPPDATA\Programs\antigravity\` | Contains `Uninstall Antigravity.exe` (PE32 x86, NSIS stub header verified via REA `inspect_desktop_application`) and standard electron-builder directory structure. |
| **Application JavaScript Bundle** | Electron ASAR Archive | `resources/app.asar` (4,797,365 bytes, SHA-256: `a6bfd4567c...`) | Validated via REA `open_binary` and `inspect_artifact` (`ev_50755a212c...`). Total 1,227 nodes (1,093 files, 164 directories). |
| **Unpacked Node Modules** | File directory | `resources/app.asar.unpacked/` | Contains `chrome-devtools-mcp` module, unpacked to disk so the child process can directly execute standalone scripts (`node_modules/chrome-devtools-mcp/build/src/bin/chrome-devtools-mcp.js`). |
| **Bundled Binaries** | Native PE Executables | `resources/bin/` | Houses `language_server.exe` (187.4 MB) and `webm_encoder.exe` (13.4 MB). |
| **Updater Configuration** | YAML manifest | `resources/app-update.yml` | `provider: generic`, updater URL `https://antigravity-hub-auto-updater-974169037036.us-central1.run.app/manifest/`, cache folder `antigravity-updater`. |
| **Chromium Runtime Assets** | Chromium PAK & Blob files | Root folder | `chrome_100_percent.pak`, `chrome_200_percent.pak`, `resources.pak`, `icudtl.dat`, `snapshot_blob.bin`, `v8_context_snapshot.bin`, 55 language locales in `locales/*.pak`. |

---

## 3. Major Processes

When Google Antigravity is running, the operating system executes the following distinct processes:

```
[ Antigravity.exe (Main Process) ]
       │
       ├── Spawn via child_process
       │     ▼
       ├── [ language_server.exe (Go Core Service) ]
       │         │
       │         ├── Spawns webm_encoder.exe (as needed for media/screen recording)
       │         └── Connects via CDP to Electron DevTools port
       │
       ├── HostBridgeServer (Loopback HTTP on 127.0.0.1:<port>)
       │     ▲
       │     └── language_server connects via Connect-RPC for update control
       │
       ├── Chromium Subprocesses
       │     ├── [ Antigravity.exe --type=gpu-process ]
       │     ├── [ Antigravity.exe --type=utility (Network Service, Audio, Storage) ]
       │     └── [ Antigravity.exe --type=renderer (UI WebContentsView / BrowserWindow) ]
       │
       └── (Optional Windows WSL Subsystem)
             └── [ wsl.exe exec language_server ] (if configured to run in Linux distro)
```

### Detailed Process Breakdown

1. **Electron Main Process (`Antigravity.exe`)**
   - **Classification:** Confirmed Fact.
   - **Source:** `dist/main.js`, `dist/utils.js`.
   - **Role:** Coordinates application lifecycle, deep link handlers (`antigravity://`), single-instance locking (`app.requestSingleInstanceLock()`), native menus, system tray, window creation, and background auto-updates.
   - **Evidence:** `main.js` instantiates `storage_1.StorageManager`, registers IPC handlers, creates local `HostBridgeServer`, spawns the language server, and creates the UI window.

2. **Core Backend Daemon (`language_server.exe`)**
   - **Classification:** Confirmed Fact.
   - **Source:** `dist/languageServer.js`, `C:\Users\NEW0033\AppData\Roaming\Antigravity\logs\language_server.log`.
   - **Role:** Standalone daemon handling workspace indexing, language features, agent trajectories, sidecar plugins, and LLM communication with Google APIs (`https://generativelanguage.googleapis.com` and `https://daily-cloudcode-pa.googleapis.com`). Serves the web UI bundle directly over loopback HTTPS.
   - **Evidence:** Log entries show `server.go:1888] Starting language server process with pid ...`, `server.go:746] Language server listening on random port at ... for HTTPS (gRPC)`, and `server.go:1559] Serving UI bundle from embedded assets`.

3. **Auxiliary Media Process (`webm_encoder.exe`)**
   - **Classification:** Confirmed Fact.
   - **Source:** `dist/main.js`, `dist/paths.js`, `dist/languageServer.js`.
   - **Role:** Encodes video/screen recordings captured during agent web execution.
   - **Evidence:** `languageServer.js` exports environment variable `AGY_BROWSER_ACTIVE_PORT_FILE` pointing to `DevToolsActivePort`, which the recording encoder utilizes to capture session frames.

4. **UI Renderer Process (`Antigravity.exe --type=renderer`)**
   - **Classification:** Confirmed Fact.
   - **Source:** `dist/utils.js`, `dist/preload.js`.
   - **Role:** Chromium renderer displaying the frontend SPA loaded from `https://127.0.0.1:<port>/`.
   - **Evidence:** `utils.js` creates `WebContentsView` or `BrowserWindow`, setting `preload: path.join(__dirname, 'preload.js')`, `nodeIntegration: false`, `contextIsolation: true`.

5. **Chromium Support Processes (GPU, Utility, Network)**
   - **Classification:** Confirmed Fact.
   - **Source:** Executable layout (`d3dcompiler_47.dll`, `vk_swiftshader.dll`, `vulkan-1.dll`, `chrome_100_percent.pak`). Standard Chromium multi-process isolation.

6. **WSL Bridge Subsystem (Platform Specific)**
   - **Classification:** Confirmed Fact.
   - **Source:** `dist/wsl.js`, `dist/main.js`.
   - **Role:** On Windows, allows executing the backend `language_server` directly inside a local WSL Linux distribution (`wsl.exe exec ...`) while the Electron UI remains native on Windows.

---

## 4. UI Technologies

### Findings

1. **Host-Side Window Architecture: BaseWindow + WebContentsView**
   - **Classification:** Confirmed Fact.
   - **Source:** `dist/utils.js` (`createWindow`, `isWcvBrowserWindowEnabled`).
   - **Evidence:**
     - Rather than using standard single-view `BrowserWindow`, Antigravity uses Electron `BaseWindow` with composited `WebContentsView` children:
       - `curtainView`: An opaque background view (`javascript: false`) preventing visual bleeding from occluded or suspended browser tabs.
       - `hubWcv`: A transparent `WebContentsView` (`transparent: true`, background `#00000000`) hosting the main UI.
     - Supports `<webview>` tag with out-of-process iframes (`webviewTag: true`) for embedding browser sessions controlled by AI subagents.
     - Employs a custom z-order controller (`dist/wcvZOrderController.js`) and browser registry (`dist/wcvBrowserRegistry.js`).

2. **Web UI Serving & Asset Storage**
   - **Classification:** Confirmed Fact.
   - **Source:** `language_server.log` (line 29), `dist/main.js` (line 390).
   - **Evidence:**
     - The web UI (HTML, CSS, JavaScript bundles) is **not** stored as standalone files on disk inside `app.asar`.
     - It is **embedded directly inside `language_server.exe`** as Go embedded assets (`server.go:1559: Serving UI bundle from embedded assets`).
     - Electron navigates directly to `https://127.0.0.1:<port>/` where `port` is dynamically allocated by the Go language server upon startup.

3. **Client-Side Framework**
   - **Classification:** Inferred Hypothesis.
   - **Rationale:** Given the presence of `@bufbuild/protobuf` / Connect-RPC client integration, Google Cloud Code UI heritage, React-style component wrappers, and DOM inspection attributes (`data-theme`), the web frontend is almost certainly a React / TypeScript SPA compiled into the Go binary's embedded filesystem.

4. **Styling and Dynamic Theming**
   - **Classification:** Confirmed Fact.
   - **Source:** `dist/preload.js` (`installHubSurfaceThemeAutoSync`).
   - **Evidence:** A MutationObserver listens for changes to `:root` styling attributes (`style`, `class`, `data-theme`) and extracts the CSS custom property `--background`. It converts this to 6-digit hex and synchronizes it to the Electron main process via IPC channel `wcv:set-theme-color`.

---

## 5. Application Entry Points

| Stage / Component | Entry Point | Source / Evidence |
| :--- | :--- | :--- |
| **Native Binary Entry** | `Antigravity.exe` | Main executable at `$env:LOCALAPPDATA\Programs\antigravity\Antigravity.exe`. |
| **Electron Main Process Entry** | `resources/app.asar/dist/main.js` | Defined in `package.json` (`"main": "dist/main.js"`). Triggered on `electron.app.whenReady()`. |
| **Renderer Preload Hook** | `resources/app.asar/dist/preload.js` | Specified in `webPreferences.preload` within `dist/utils.js`. Injected before any web content executes. |
| **Core Backend Server Entry** | `resources/bin/language_server.exe` | Invoked in `dist/languageServer.js` via `child_process.spawn(LS_BINARY, args)`. |
| **Host Bridge Server Entry** | `dist/hostBridgeServer.js` | Started within `dist/main.js` via `startHostBridgeServer()`. Listens on loopback HTTP (`127.0.0.1:<ephemeral-port>`). |
| **Protocol / Deep Link Entry** | `antigravity://` URL handler | Registered via `app.setAsDefaultProtocolClient('antigravity')` in `dist/main.js`. Handled in `second-instance` and `open-url` events. |

---

## 6. Frontend to Backend Communication

Communication in Antigravity spans three distinct boundaries:

```
┌────────────────────────────────────────────────────────┐
│               Electron Renderer Window                 │
│              (Web SPA from 127.0.0.1)                  │
└───────────────┬────────────────────────┬───────────────┘
                │                        │
       Connect-RPC / HTTPS               │ Electron contextBridge
       (gRPC-Web / HTTP/2)               │ (ipcRenderer.invoke)
                │                        │
                ▼                        ▼
┌────────────────────────────┐    ┌────────────────────────────┐
│    language_server.exe     │    │   Antigravity.exe (Main)   │
│   (Go Service Port 63096)  │    │      (Node.js Runtime)     │
└───────────────┬────────────┘    └──────────────┬─────────────┘
                │                                │
                └────── Connect-RPC (HTTP) ──────┘
                        HostBridgeService
                     (Token-authenticated)
```

### 1. Frontend ↔ Language Server Backend (Data & Intelligence Layer)
- **Classification:** Confirmed Fact.
- **Source:** `dist/constants.js`, `dist/languageServer.js`, `language_server.log`.
- **Mechanism:** Direct network communication over loopback **HTTPS (`https://127.0.0.1:<port>/`)** using **Connect-RPC / gRPC-Web**.
- **Security & Validation:**
  - `language_server.exe` is launched with `--csrf_token <UUID>`.
  - Electron injects certificate trust via `setupLocalCertTrust()` so Chromium accepts the backend's local self-signed TLS certificates.
  - The language server serves RPC endpoints and the web assets over the same trusted loopback origin.

### 2. Frontend ↔ Electron Main Process (Desktop Integration Layer)
- **Classification:** Confirmed Fact.
- **Source:** `dist/preload.js`, `dist/ipcHandlers.js`.
- **Mechanism:** Asynchronous IPC messages over `electron.contextBridge` and `ipcRenderer.invoke` / `ipcMain.handle`.
- **Exposed APIs:**
  - `window.electronNative`: Window operations (minimize, maximize, close, custom titlebar overlay, zoom factor).
  - `window.nativeStorage`: Persistent key-value storage backed by `app_storage.json`.
  - `window.dialog`: Workspace directory pickers (`dialog:open-workspace`).
  - `window.nativeNotifications`: System notifications dispatch.
  - `window.electronUpdater`: Status and controls for application updates.
  - `window.electronNative.wcvBrowser`: Commands controlling the embedded `<webview>` browser surfaces (navigation, mouse/key inputs, screen capture).
  - `window.wsl`: WSL state querying and distribution switching.

### 3. Language Server Backend ↔ Electron Main Process (Host Bridge Layer)
- **Classification:** Confirmed Fact.
- **Source:** `dist/hostBridgeServer.js`, `dist/proto/host_bridge_pb.js`.
- **Mechanism:** Loopback Connect-RPC server (`HostBridgeServer`) instantiated in the Electron main process on an ephemeral port.
- **Protocol:** Defined in Protocol Buffer contract `HostBridgeService` (`getUpdateStatus`, `applyUpdate`).
- **Security:**
  - Restricted strictly to loopback addresses (`127.0.0.1`, `::1`).
  - Strict `Host` header check to thwart DNS rebinding attacks.
  - Constant-time validation of a 32-byte hex Bearer token passed to `language_server.exe` via `--host_bridge_token`.

---

## 7. Implications for Mobile Port (Phase 1 Takeaways)

1. **Decoupled Architecture Advantage:**
   The presentation UI is completely separated from the core logic: the Electron app is primarily a native shell around a local web client and a Go backend service.
2. **Backend Portability:**
   The backend (`language_server.exe`) is written in Go (`go1.28`). Go natively compiles to mobile targets:
   - **Android:** `arm64-v8a` (Linux/Android ELF)
   - **iOS:** `aarch64-apple-ios`
3. **Frontend Reusability:**
   Since the desktop UI is rendered as a web SPA communicating via standard Connect-RPC / HTTPS, a mobile port can either:
   - Run the Go language server directly on-device (via Android NDK / iOS Framework / Gomobile), serving the web UI inside an Android `WebView` or iOS `WKWebView`.
   - Run in client-server mode, connecting a native mobile UI to a remote or cloud-hosted instance of the language server via Connect-RPC.
4. **Desktop-Specific Subsystems to Re-implement or Stub:**
   - Electron IPC bridge (`preload.js` APIs: `windowState`, `wcvBrowser`, native window decorations, WSL integration).
   - Chrome DevTools Protocol (CDP) browser recording (`chrome-devtools-mcp` and `webm_encoder.exe`).


---

## Errata (added in Phase 2, 2026-11-09)

A re-check during Phase 2 found several Phase 1 statements that were stronger than the evidence. The sections above are kept unchanged. These corrections take precedence.

| # | Phase 1 statement | Correction | Evidence |
| :--- | :--- | :--- | :--- |
| E1 | The frontend ↔ language server link is "Connect-RPC / gRPC-Web" (§6.1, marked Confirmed) | **Hypothesis.** Verified: the language server opens an HTTPS port labelled "(gRPC)" and a separate HTTP port, and serves the UI. Its binary contains the `LanguageServerService` RPC name 5,414 times. The protocol the embedded UI actually uses was **not observed**. | `language_server.log` lines 5–6, 29; bounded string scan |
| E2 | "Electron injects certificate trust … to accept the backend's self-signed certificates" | `setupLocalCertTrust()` accepts **any** certificate whose hostname is `127.0.0.1` or `localhost` (`callback(0)`). It does not pin a specific certificate. | `dist/languageServer.js` |
| E3 | The installer is NSIS (presented as a finding) | **Hypothesis.** REA observed only a PE32 i386 header on `Uninstall Antigravity.exe`. NSIS is inferred from the electron-builder layout. | REA `inspect_desktop_application` |
| E4 | Chromium GPU/utility/renderer subprocesses (§3, Confirmed) | **Expected, not observed.** No Antigravity processes were running during inspection. This is standard Chromium behaviour, inferred from the bundled DLLs and PAKs. | `Get-Process` returned no matches |
| E5 | The process diagram shows "Go Service Port 63096" | Ports are **dynamic** (`DYNAMIC_PORT = 0`). 63096 was one observed run. | `dist/constants.js`, log |
| E6 | The web UI is "almost certainly" React/TypeScript | **Unconfirmed.** `react-dom` was not found as plain ASCII in the binary. The assets may be compressed, so this is not evidence of absence either. | Bounded string scan |
| E7 | §7 "Backend portability": the Go backend can be compiled for Android/iOS | **Incorrect as stated.** Go cross-compilation needs source code. `language_server.exe` is a closed-source Windows binary, and nothing found licenses it for redistribution. A mobile port needs independent backend components. See [backend-requirements.md](backend-requirements.md). | — |
| E8 | §7 suggests running the embedded web UI in an Android WebView against the language server | **Not recommended.** That would mean redistributing proprietary assets and relying on undocumented RPCs. Superseded by [mobile-architecture.md](mobile-architecture.md). | — |
