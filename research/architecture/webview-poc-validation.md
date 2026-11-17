# Antigravity Android Port — Option C WebView Proof-of-Concept Validation & Implementation Plan

- **Date:** 2026-11-10
- **Phase:** P0 Fidelity Correction — Option C Empirical PoC Validation
- **Status:** PoC Verified (`0` uncaught exceptions, `0` console errors, `26` Connect-RPC methods exercised, `364` client methods verified)
- **Constraint Compliance:** Read-only extraction in memory from local installation; zero proprietary files copied into workspace; existing P0 modules preserved intact.

---

## 1. Executive Summary & PoC Verdict

Before modifying ADRs or existing Android project code, we executed a live end-to-end runtime Proof of Concept (`poc_runner.js`) booting the actual `Jetski Web` frontend bundle directly in memory from the local Antigravity installation (`language_server.exe`, byte offset `163294824`, length `5,628,057` bytes) inside a headless Chromium instance configured as an Android 14 WebView (`390x844` viewport, `deviceScaleFactor: 3`, touch emulation enabled, Android WebView `wv` User-Agent).

### Empirical Results Summary

| Validation Area | Target / Mechanism | Empirical Result | Status |
| :--- | :--- | :--- | :--- |
| **Bundle Extraction** | In-memory ZIP reader at offset `163294824` (`5,628,057` bytes) | Loaded all 99 files (`540` zip entries including directories) in memory without writing proprietary assets to disk | **PASS (`[V]`)** |
| **React 19 Initialization** | `#root` mount in `index.html` + `main.js` (`11.18 MB`) | Mounted cleanly (`rootChildCount: 2`, `33,310` HTML bytes on `/`, `40,147` HTML bytes on `/c/cascade-poc-1`, `0` uncaught exceptions) | **PASS (`[V]`)** |
| **Mobile Layout Activation** | `Yka() === "web.mobile"` + `window.innerWidth === 390 < 640` (`HE() === true`) | Activated mobile home layout, floating bottom Composer pill, mobile conversation header (`<-`, `...`), and mobile bottom-sheet Settings modal | **PASS (`[V]`)** |
| **SPA Route Navigation** | `/`, `/c/cascade-poc-1`, `/customizations`, `?settingsOpen=true` | Navigated cleanly across all routes via History API (`pushState` + `PopStateEvent`) with zero console errors | **PASS (`[V]`)** |
| **CSS & Design Tokens** | `jetbox.css` (`95 KB`) + `compiled_tailwind.css` (`294 KB`) | Computed styles verified: `--background: #101010`, `--foreground: #cccccc`, `--primary: #007acc`, `--sidebar: color-mix(in srgb, #ffffff 2.5%, #101010)` | **PASS (`[V]`)** |
| **Web Workers** | `/diff_worker.js` (classic) & `/workers/shiki_worker.js` (ES module) | `diff_worker.js` computed `{ additions: 2, deletions: 1 }`; `shiki_worker.js` emitted `{ type: "ready" }` and tokenized TypeScript/Kotlin AST spans | **PASS (`[V]`)** |
| **Browser API Surface** | 14 DOM, Observer, Worker, Crypto, Storage & CSS APIs | `14 / 14` returned `true` inside the mobile WebView runtime environment | **PASS (`[V]`)** |
| **Connect-RPC Decorator** | `globalThis.__JETSKI_LS_CLIENT_DECORATOR__` | Attached before `main.js` boot; exposed all **364** camelCase RPC methods on `lsClient` and intercepted every unary and streaming call | **PASS (`[V]`)** |
| **Connect-RPC Wire Protocol** | `POST /exa.language_server_pb.LanguageServerService/<Method>` (`application/grpc-web+json`) | Verified 5-byte gRPC-Web data framing (`0x00`) + trailer framing (`0x80`, `grpc-status: 0\r\n`) + `x-codeium-csrf-token` header across 26 active RPC endpoints | **PASS (`[V]`)** |

---

## 2. Empirical Findings from the WebView Proof of Concept

### 2.1 React 19 Boot & Mobile Layout Activation (`HE() === true`)

When booted with an Android WebView User-Agent (`Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A.240205.004; wv) ...`) and a narrow mobile viewport (`390x844`, matching `(max-width: 639px)`):

1. **Host & Cohort Detection (`[V]`):**
   - `window.electronNative` is `undefined`, and `qka()` (`/android|iphone|ipad|ipod/i.test(navigator.userAgent)`) evaluates to `true`, setting host type `Yka()` to `"web.mobile"` (`Xi.Web.Mobile.Value`).
   - `window.innerWidth === 390` (`< 640`), setting viewport cohort `bla()` to `"narrow"`.
   - `HE()` (`mobileLayout.enabled`) evaluates to `true`, `touchInput.enabled` evaluates to `true`, and `settingsNavigation.modalFullScreen` evaluates to `true`.
2. **Actual Mobile UI Structure Rendered by `Jetski Web` (`[V]`):**
   - **Mobile Home Screen (`/`):** Instead of the desktop split-pane layout, `/` renders a dedicated mobile **Conversation History & Projects** view (`Conversation History`, `Projects`, `Antigravity Port`, `+`), a top bar with the Antigravity logo, Search button (`Search Conversations`), and Settings gear (`Settings`), and a bottom-docked floating Composer pill (`Ask anything, @ to mention, / for actions`, `+` context button, `No Model Selected ^` model selector, and `->` send button).
   - **Mobile Cascade Conversation Screen (`/c/cascade-poc-1`):** Renders a mobile top navigation bar (`<-` back arrow, conversation title, `...` overflow menu, and right panel toggle), the scrollable turn list with user message bubbles, agent Markdown prose, inline code pills, syntax-highlighted code blocks with `@` mention and `Copy` buttons, turn action buttons (`Copy`, `ThumbsUp`, `ThumbsDown`), and the bottom-docked Composer pill.
   - **Mobile Settings Bottom Sheet (`?settingsOpen=true`):** Renders Settings as a mobile bottom sheet with a top drag handle pill, touch-friendly drill-down rows with right chevrons (`General >`, `Appearance >`, `Shortcuts >`, `Models >`, `Customizations >`, `Give Feedback >`), and the authenticated user profile card (`AA Antigravity Android Port / developer@android.local >`) pinned at the bottom.

### 2.2 Web Worker Verification (`diff_worker.js` & `workers/shiki_worker.js`)

Both background workers bundled inside `language_server.exe` were tested directly inside the WebView runtime:

1. **`/diff_worker.js` (Classic Web Worker) (`[V]`):**
   - Accepts `{ id, type: "diffStats", original, modified }` and `{ id, type: "diffArrays", oldArr, newArr, ignoreWhitespace }`.
   - Empirically returned `{ id: 1, type: "diffStats", additions: 2, deletions: 1 }` in `< 5 ms`.
2. **`/workers/shiki_worker.js` (ES Module Web Worker, `type: "module"`) (`[V]`):**
   - Immediately posts `{ type: "ready" }` upon initialization (`attachShikiWorkerScope(globalThis)`).
   - Accepts `{ requestId, type: "tokenize", content, language, theme }` and `{ requestId, type: "cancel" }`.
   - Empirically returned `{ type: "chunk", startLine: 1, lines: [{ lineNumber: 1, tokens: [{ text: "const", style: "color: var(--syntax-keyword)" }, ...] }], done: true, requestId: 1 }`.

### 2.3 Browser API & CSS Compatibility Audit

All 14 browser platform features required by `main.js`, `jetbox.css`, and `compiled_tailwind.css` were verified at runtime:

```json
{
  "customElements": true,
  "resizeObserver": true,
  "intersectionObserver": true,
  "mutationObserver": true,
  "worker": true,
  "broadcastChannel": true,
  "indexedDB": true,
  "cryptoSubtle": true,
  "cryptoRandomUUID": true,
  "clipboard": true,
  "visualViewport": true,
  "matchMediaNarrow": true,
  "cssColorMix": true,
  "cssDvh": true
}
```

---

## 3. Verified Connect-RPC Decorator & Protobuf Schemas

By decoding all **96 embedded `FileDescriptorProto` descriptors (`cd("...")`)** inside `main.js` and intercepting live traffic through both `globalThis.__JETSKI_LS_CLIENT_DECORATOR__` and the HTTP gRPC-Web wire handler, we established the exact RPC contract required by `Jetski Web`.

### 3.1 Dual Interception Architecture (`[V]`)

`main.js` constructs its `LanguageServerService` (`Fi`) client in `zYc` (`main.js:10430690`):

```javascript
this.lsClient = rYc(nfa(Fi, a));
```

where `rYc(a)` invokes `globalThis.__JETSKI_LS_CLIENT_DECORATOR__ ? globalThis.__JETSKI_LS_CLIENT_DECORATOR__(a) : a`.

This gives our Android host two complementary, documented integration layers:
1. **Wire-Level gRPC-Web+JSON Interception (`WebViewClient.shouldInterceptRequest` or Local Loopback):**
   - `xYc` (`createGrpcWebTransport`) sends `POST /exa.language_server_pb.LanguageServerService/<MethodName>` with `Content-Type: application/grpc-web+json`, `X-Grpc-Web: 1`, and `x-codeium-csrf-token: <csrfToken>`.
   - Request body: 5-byte gRPC-Web header (`0x00` + 4-byte big-endian length) + UTF-8 JSON bytes.
   - Response body: One or more 5-byte framed JSON messages (`0x00` + 4-byte length + UTF-8 JSON), terminated by a 5-byte trailer frame (`0x80` + 4-byte length + `grpc-status: 0\r\n`).
   - Because `xYc` uses `Bfa` (`jf(a.output, jsonText, { ignoreUnknownFields: true, registry: h6 })`), `main.js` automatically deserializes standard Protobuf JSON into full `@bufbuild/protobuf` `Message` instances (handling `BigInt`, default fields, `oneof` cases, and nested types automatically).
2. **Client-Decorator Interception (`globalThis.__JETSKI_LS_CLIENT_DECORATOR__`):**
   - Wraps the 364 camelCase methods on `lsClient` in JavaScript before React mounts, allowing telemetry, offline caching, or direct JS-to-Kotlin `@JavascriptInterface` short-circuiting without HTTP connection pool overhead.

### 3.2 Critical Empirical Discoveries During PoC Testing

Our live PoC uncovered five critical behavioral requirements that static analysis alone did not reveal:

1. **Boot Auth Gate (`HasAuthToken` vs `GetAuthStatus`) (`[V]`):**
   - During boot, `Jetski Web` calls `HasAuthToken` (`hasAuthToken`) before `GetAuthStatus`. If `HasAuthTokenResponse` does not include `{ "hasToken": true }`, the SPA router immediately redirects `/` to `/onboarding?login=true` (`Welcome to Antigravity / Continue with Google`).
   - Returning `{ "hasToken": true, "isGcpTos": false, "projectId": "...", "location": "us-central1" }` passes the auth gate and boots directly into `/`.
2. **`ListProjects` vs `ProjectUpdatesStream` Schema Asymmetry (`[V]`):**
   - `ListProjectsResponse` (`XMc`) reads top-level `response.projectIds` (`string[]`).
   - `ProjectUpdatesStreamResponse` (`WMc`) uses a `oneof update` field: `{ "projectList": { "projectIds": ["..."] } }` or `{ "projectUpdatedId": "..." }`.
3. **Project-to-Conversation Linking (`ReadProjects`) (`[V]`):**
   - `ReadProjectsResponse` returns `{ "projects": [Project], "notFoundIds": [] }` where `.exa.project_pb.Project` links conversations via field `#8` (`projectConversations`):
     ```json
     {
       "id": "proj-1",
       "name": "Antigravity Port",
       "projectConversations": {
         "conversations": [{ "conversationId": "cascade-poc-1" }]
       }
     }
     ```
4. **Live Conversation State Reduction (`StreamAgentStateUpdates` -> `sca` / `rca`) (`[V]`):**
   - When opening `/c/<cascadeId>`, `Jetski Web` calls `StreamAgentStateUpdates` (`uca`) and reduces each `AgentStateUpdate` via `sca()` and `rca()`.
   - `mainTrajectoryUpdate.stepsUpdate` (`.exa.jetski_cortex_pb.StepsUpdate`) requires:
     - `indices`: `uint32[]` (e.g. `[0, 1]`)
     - `steps`: direct array of `.gemini_coder.Step` objects (NOT wrapped in `{ step: ... }`)
     - `totalLength`: `uint32`
     - `pageBounds`: `{ "startIndex": 0, "endIndexExclusive": 2 }`
   - Each `.gemini_coder.Step` uses field `#1` (`type`: `"CORTEX_STEP_TYPE_USER_INPUT"` | `"CORTEX_STEP_TYPE_PLANNER_RESPONSE"` | `"CORTEX_STEP_TYPE_RUN_COMMAND"` | `"CORTEX_STEP_TYPE_CODE_ACTION"`), field `#4` (`status`: `"CORTEX_STEP_STATUS_DONE"`), field `#5` (`metadata`), field `#19` (`userInput`), and field `#20` (`plannerResponse`).
5. **gRPC-Web Streaming Trailer & HTTP/1.1 Connection Pool Limit (`[V]`):**
   - On startup, `Jetski Web` opens 6+ server-streaming RPCs (`JetboxSubscribeToState`, `JetboxSubscribeToSummaries`, `ProjectUpdatesStream`, `SubscribeToMcpServerStates`, `SubscribeToPlugins`, `StreamAgentStateUpdates`).
   - Over standard HTTP/1.1, holding 6 streams open indefinitely exhausts Chromium's 6-connection-per-origin limit, and closing any stream without a `0x80` trailer frame (`grpc-status: 0\r\n`) triggers `ConnectError: [unknown] missing trailer`.
   - Both `bj` (background stream helper) and `uca` (`consumeAgentStateStream`) cleanly handle streams that emit a `0x00` state frame followed by a `0x80` `grpc-status: 0\r\n` trailer frame, or streams intercepted via `WebViewClient.shouldInterceptRequest` (which bypasses the TCP connection pool entirely!).

### 3.3 Verified 26 Startup & Navigation RPC Ledger

The following 26 RPC methods were empirically invoked by `Jetski Web` during our PoC boot, Home rendering, Cascade conversation view, Customizations view, and Settings view:

| # | Method Name (`LanguageServerService`) | Kind | Request Fields Observed | Verified Response Schema Summary |
| :--- | :--- | :--- | :--- | :--- |
| 1 | `GetLocalUserInfo` | Unary | `{}` | `{ username, homeDirUri, machineType }` |
| 2 | `GetServerConfiguration` | Unary | `{}` | `{ config: { isJetski: true, standalone: true, antigravityHub: true, subclientType: "web", appDataDir, configDir } }` |
| 3 | `GetMendelFlags` | Unary | `{}` | `{ experimentConfig: { experiments: [] }, webrtcMeshReady: false }` |
| 4 | `HasAuthToken` | Unary | `{}` | `{ hasToken: true, isGcpTos: false, projectId, location }` |
| 5 | `GetAuthStatus` | Unary | `{}` | `{ authResult: { hasValidAuth: true, uiMessage, projectId, location, authMode: 1 } }` |
| 6 | `GetUserStatus` | Unary | `{}` | `{ userStatus: { pro: true, name, email, hasUsedAntigravity: true, acceptedLatestTermsOfService: true } }` |
| 7 | `FetchAdminControls` | Unary | `{}` | `{ adminControlsApplicable: false }` |
| 8 | `JetboxSubscribeToState` | Server Stream | `{}` | `{ appState: { agentOnboardingCompleted: 2, migrateConvosIntoProjects: 2, sidebarWorkspacesMigrated: true, userConfigMigrated: true }, userConfig: { userSettings: { themeMode: 1 } } }` |
| 9 | `JetboxSubscribeToSummaries` | Server Stream | `{}` | `{ updates: { "<cascadeId>": { summary, stepCount, lastModifiedTime, trajectoryId, status, createdTime } } }` |
| 10 | `ListProjects` | Unary | `{}` | `{ projectIds: ["<projectId>"] }` |
| 11 | `ProjectUpdatesStream` | Server Stream | `{}` | `{ projectList: { projectIds: ["<projectId>"] } }` |
| 12 | `ReadProjects` | Unary | `{ ids: ["..."] }` | `{ projects: [{ id, name, updatedAt, projectConversations: { conversations: [{ conversationId }] } }], notFoundIds: [] }` |
| 13 | `SubscribeToMcpServerStates` | Server Stream | `{}` | `{ states: [], isLoading: false }` |
| 14 | `SubscribeToPlugins` | Server Stream | `{}` | `{ plugins: [] }` |
| 15 | `GetAllSkills` | Unary | `{ includeShadowed }` | `{ skills: [{ name, description }] }` |
| 16 | `GetAllWorkflows` | Unary | `{}` | `{ workflows: [] }` |
| 17 | `GetCustomizationStates` | Unary | `{}` | `{}` |
| 18 | `ListRemotePlugins` | Unary | `{ backend }` | `{ plugins: [] }` |
| 19 | `ListMcpPrompts` | Unary | `{}` | `{ prompts: [] }` |
| 20 | `GetCascadeNuxes` | Unary | `{}` | `{ nuxes: [] }` |
| 21 | `GetRemoteControlInfo` | Unary | `{}` | `{ enabled: false, instanceId: "", companionOrigin: "" }` |
| 22 | `RecordAnalyticsEvent` | Unary | `{ metadata, events }` | `{}` (No-op locally) |
| 23 | `RecordError` | Unary | `{ errorTrace }` | `{}` (Zero calls once trailer framing fixed) |
| 24 | `StreamAgentStateUpdates` | Server Stream | `{ conversationId, subscriberId, trajectoryVerbosity }` | `{ update: { conversationId, trajectoryId, status, fullyIdle, turnsUpdate, mainTrajectoryUpdate: { stepsUpdate } } }` |
| 25 | `GetTurnDiff` | Unary | `{ conversationId, ... }` | `{}` |
| 26 | `UpdateConversationAnnotations` | Unary | `{ cascadeId, ... }` | `{}` |

---

## 4. Licensing & Usage Permissions Confirmation

We reviewed the embedded license notices in `main.js` and the desktop installation directory (`resources/app/LICENSE.txt`, `ThirdPartyNotices.txt`):

1. **Open-Source Third-Party Runtime Libraries inside `main.js` (`[V]`):**
   - Closure Library (`Apache-2.0`), `@bufbuild/protobuf` (`Apache-2.0`), `@connectrpc/connect` (`Apache-2.0`), React 19 (`MIT`), Lexical (`MIT`), Shiki (`MIT`), Lucide (`ISC`), Tailwind CSS (`MIT`), PDF.js (`Apache-2.0`).
2. **Proprietary Google Application Code & Assets (`[V]`):**
   - The compiled `Jetski Web` application logic (`google3/third_party/gemini_coder/framework/TS/jetbox/...`), `jetbox.css`, `compiled_tailwind.css`, and `language_server.exe` are proprietary Google artifacts distributed under the Google Antigravity Terms of Service.
   - **No-Redistribution Boundary:** Proprietary assets (`main.js`, `jetbox.css`, `compiled_tailwind.css`, `language_server.exe`) **must not** be committed to our source repository or pre-bundled inside a redistributable APK.
3. **Authorised Runtime Frontend Provisioning Modes (`[V]`):**
   - **Mode 1 — Remote Desktop Host Connection (LAN / Tailscale):** The Android WebView connects to the user's own licensed desktop Antigravity `language_server` instance, which serves its own embedded `Jetski Web` UI bundle directly over HTTPS/WSS. Zero proprietary assets are stored on the Android device.
   - **Mode 2 — User-Supplied Local Bundle Import (BYO-Bundle via Android SAF):** For standalone on-device execution, the user selects their own legally installed desktop `language_server.exe` (or extracted `Jetski Web` bundle zip) via the Android Storage Access Framework (`ActivityResultContracts.OpenDocument`). The Android app locates the ZIP header (`PK\x03\x04` at offset `163294824` or by scanning for the `index.html` + `main.js` central directory) and extracts the web assets into device-private storage (`context.filesDir/jetski-web/`) at runtime.
   - **Mode 3 — Clean-Room Jetbox Compatibility Interface (Built-in Fallback):** When no desktop host is connected and no user bundle has been imported yet, the app presents our clean-room native Compose UI styled to match the verified `jetbox.css` dark theme (`#101010` background, `#007acc` primary, `#cccccc` foreground) alongside a one-tap **"Import Antigravity Desktop UI Bundle"** / **"Connect to Desktop Host"** setup card.

---

## 5. Components Genuinely Requiring Native Android Replacements

Because `Jetski Web` already contains complete web fallbacks for all 11 Electron `preload.js` globals (`OWc` `localStorage`, `vXc` Web Notifications, `cXc` RPC folder modal, `bSa` DOM context menus) and activates its mobile layout (`HE() === true`) automatically on `< 640px` viewports, **zero UI components inside `Jetski Web` need to be rewritten**.

Only the backend/platform capabilities that reside outside the browser sandbox require native Android replacements:

| Original Desktop Component | Why It Cannot Run Directly on Android | Native Android Replacement (Clean-Room Kotlin) |
| :--- | :--- | :--- |
| **`language_server_windows_x64.exe` (Go x86_64 Binary)** | Compiled for Windows x86_64 (`PE32+`); cannot execute on Android `arm64-v8a` | **`LanguageServerGrpcWebHandler` (`core:network` / `core:agent`):** In-process Kotlin Connect-RPC (`application/grpc-web+json`) dispatcher implementing the 26+ core `LanguageServerService` RPCs backed by `core:storage` (Room), `core:workspace`, and `core:agent` (Gemini API / remote host). |
| **Embedded UI Static Asset Server (Go HTTP Server)** | Embedded inside `language_server.exe` | **`JetskiBundleLoader` & `JetskiWebViewHost` (`app` / `core:network`):** Runtime ZIP extractor (capable of extracting the `Jetski Web` bundle directly from a user-supplied `language_server.exe` or `.zip` into `filesDir/jetski-web/`) + `WebViewAssetLoader` serving `https://appassets.androidplatform.net/` with `window.__APP_CONFIG__` injection. |
| **Electron `webContentsView` (`wcvBrowser`) Subagent** | Uses Electron main-process `BrowserView` / CDP for agent browser automation | **`AndroidBrowserSubagent` (`feature:browser`):** Headless/tabbed Android `WebView` fulfilling `SubscribeToAgentBrowserRequests` and `FulfillAgentBrowserRequest` (`openBrowserUrl`, `readBrowserPage`, `captureBrowserScreenshot`, `executeBrowserJavaScript`). |
| **Node.js `child_process` PTY Shell (`RunCommand`)** | Uses desktop Node PTY / PowerShell | **`AndroidShellExecutor` (`feature:terminal` / `core:workspace`):** Executes commands via Android `ProcessBuilder("/system/bin/sh", "-c", ...)` scoped to the active workspace directory (or delegates to the optional Go Workspace Host when paired). |
| **Electron Native Folder Picker (`window.dialog.showOpenDialog`)** | Desktop OS file dialog (`dialog:showOpenDialog` IPC) | **Two supported paths:** (1) `Jetski Web`'s built-in `cXc` RPC folder browser modal backed by our `ReadDir` / `StatUri` RPCs, plus (2) an optional `@JavascriptInterface` bridge to Android SAF (`OpenDocumentTree`). |

---

## 6. Updated Staged Implementation Plan (Option C)

All existing P0 modules (`core/model`, `core/storage`, `core/agent`, `core/workspace`, `core/network`, `feature/chat`, `feature/editor`, `feature/explorer`, `feature/terminal`, `feature/browser`, `feature/manager`, `feature/settings`) are preserved intact and serve as the backing engine for `LanguageServerService`.

### Stage P0.5-A — Architecture Decision Records Update
- Update `research/architecture/architecture-decisions.md` (`ADR-005`: Option C Hybrid Antigravity Port Architecture) and align `mobile-architecture.md`, `feature-compatibility.md`, and `backend-requirements.md` with the verified PoC findings.

### Stage P0.5-B — Runtime `JetskiBundleLoader` & In-Process `LanguageServerGrpcWebHandler`
1. **`JetskiBundleLoader`:**
   - Clean-room utility that accepts a user-supplied URI (`language_server.exe` or `jetski-web.zip` via Android SAF) and scans for the embedded `Jetski Web` ZIP archive (verifying `index.html` and `main.js` entries), extracting them to `context.filesDir/jetski-web/`.
2. **`GrpcWebFrameCodec` & `LanguageServerGrpcWebHandler`:**
   - Implements the exact 5-byte gRPC-Web framing (`0x00` data frame + `0x80` `grpc-status: 0\r\n` trailer frame) verified in our PoC.
   - Implements the 26 startup/navigation RPCs (`GetLocalUserInfo`, `GetServerConfiguration`, `GetMendelFlags`, `HasAuthToken`, `GetAuthStatus`, `GetUserStatus`, `FetchAdminControls`, `JetboxSubscribeToState`, `JetboxSubscribeToSummaries`, `ListProjects`, `ProjectUpdatesStream`, `ReadProjects`, `StreamAgentStateUpdates`, `StartCascade`, `SendUserCascadeMessage`, `ReadDir`, `ReadFile`, `WriteFile`, `RunCommand`, etc.) bridged to our existing P0 `core:storage`, `core:workspace`, and `core:agent` repositories.

### Stage P0.5-C — `JetskiWebViewHost` Android Component
1. Configures an Android `WebView` (`domStorageEnabled = true`, `javaScriptEnabled = true`, dark color-scheme media query, viewport fit).
2. Uses `WebViewClient.shouldInterceptRequest` to:
   - Serve extracted `Jetski Web` assets (`index.html` with injected `window.__APP_CONFIG__` and `globalThis.__JETSKI_LS_CLIENT_DECORATOR__`, `main.js`, `jetbox.css`, `compiled_tailwind.css`, `diff_worker.js`, `workers/shiki_worker.js`, `symbols-icons/*`) from `https://appassets.androidplatform.net/`.
   - Route `POST https://appassets.androidplatform.net/exa.language_server_pb.LanguageServerService/*` directly to `LanguageServerGrpcWebHandler` in-process (bypassing HTTP/1.1 connection pool limits).

### Stage P0.5-D — Clean-Room Jetbox Theme Alignment & Bundle Import UI
1. Update the native Compose fallback theme (`AgenticTheme`) to match the verified `jetbox.css` dark tokens (`#101010` background, `#007acc` primary, `#cccccc` foreground) so the native fallback and bundle-import screen match Antigravity's visual identity.
2. Add a runtime mode switcher (`JetskiFrontendModeManager`) in Settings / Home allowing the user to:
   - **Import `Jetski Web` bundle** from their desktop `language_server.exe` / `.zip` and launch `JetskiWebViewHost` as the primary UI.
   - **Connect to a remote desktop `language_server` or Go Workspace Host** over LAN/Tailscale.
   - **Use the built-in Clean-Room Jetbox Fallback UI** when no proprietary bundle is imported.
3. Verify compilation and unit tests via `./gradlew assembleDebug testDebugUnitTest`.

---

## 7. P0.5 Implementation Verification Summary

All four sequential stages (`P0.5-A` through `P0.5-D`) have been implemented and verified:

| Stage | Key Deliverables | Verification |
|---|---|---|
| **P0.5-A** | `ADR-05` (`ADR-005`) in `architecture-decisions.md`, plus updated `mobile-architecture.md`, `feature-compatibility.md`, and `backend-requirements.md`. | Architecture boundaries, Connect-RPC contracts, and licensing/redistribution constraints documented. |
| **P0.5-B** | `JetskiBundleLoader.kt`, `GrpcWebFrameCodec.kt`, `LanguageServerGrpcWebHandler.kt`, real HTTPS SSE streaming in `GeminiModelProvider.kt`, and `StageP05BBundleAndRpcTest.kt`. | 5 unit/integration tests pass (standalone ZIP, embedded binary slice, desktop `language_server.exe` validation, invalid/incomplete/incompatible bundle rejection, gRPC-Web framing, CSRF/auth gating, and live AI prompt streaming). |
| **P0.5-C** | `JetskiWebViewRequestInterceptor.kt`, `JetskiWebViewHost.kt`, `window.fetch` `x-antigravity-post-body-b64` POST-body bridge in `JetskiBundleLoader.kt`, and `StageP05CWebViewInterceptorTest.kt`. | Verified in-process serving of `index.html`, `main.js`, `jetbox.css`, `compiled_tailwind.css`, `diff_worker.js`, `workers/shiki_worker.js`, and header-bridged Connect-RPC `POST` calls (`StartCascade`, `SendUserCascadeMessage`, `StreamAgentStateUpdates`). |
| **P0.5-D** | `JetskiFrontendModeManager.kt`, Jetbox dark palette in `Color.kt` (`#101010`, `#161616`, `#007acc`, `#cccccc`) and `Theme.kt`, runtime SAF bundle picker & mode controls in `AppNavigation.kt`, `MainActivity.kt`, `SettingsScreen.kt`, `ConversationsScreen.kt`, and `StageP05DFrontendModeManagerTest.kt`. | Full project compilation (`assembleDebug`) and unit test suites (`testDebugUnitTest`) pass with zero redistributed proprietary assets. |
