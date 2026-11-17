# Feature Compatibility Matrix — Desktop → Android

**Project:** Antigravity Mobile Port · **Phase:** 2 — Mobile Architecture Planning
**Inputs:** [initial-investigation.md](initial-investigation.md) (Phase 1, incl. errata), read-only re-inspection of the extracted `app.asar` shell, a bounded string scan of `language_server.exe`, and public product documentation.
**Companion documents:** [mobile-architecture.md](mobile-architecture.md) · [backend-requirements.md](backend-requirements.md) · [architecture-decisions.md](architecture-decisions.md) · [port-fidelity-audit.md](port-fidelity-audit.md) · [webview-port-feasibility.md](webview-port-feasibility.md) · [webview-poc-validation.md](webview-poc-validation.md)

> [!IMPORTANT]
> **P0.5 Option C Update (Verified Embedded `Jetski Web` Bundle & Protobuf Schemas):** During the P0 Port-Fidelity Audit and Feasibility Study, we verified (**V**) that `language_server.exe` embeds a 99-file ZIP archive (`Jetski Web`, React 19 + Tailwind + Shiki + Diff Workers) at byte offset `163294824` (`5,628,057` bytes), and that `main.js` embeds all 96 compiled Protobuf `FileDescriptorProto` definitions (`cd("...")`) for `exa.language_server_pb.LanguageServerService` (364 methods). Under **ADR-05 (Option C)**, the original `Jetski Web` frontend is hosted inside `JetskiWebViewHost` via user-supplied runtime bundle import (`JetskiBundleLoader`) or remote host connection, backed by an in-process Kotlin `LanguageServerGrpcWebHandler` implementation. All earlier Phase 1/Phase 2 evidence grades below are preserved for historical trace integrity.

---

## 1. Evidence grades

Every row has an evidence grade. Only **V** counts as a verified finding.

| Grade | Meaning |
| :--- | :--- |
| **V** | **Verified.** Seen directly in a shipped artifact (shell JS source, REA Evidence, binary metadata). |
| **S** | **String marker.** A literal string is present in the closed-source `language_server.exe`. It shows the code or data is in the binary. It does **not** show how or whether that code runs. |
| **D** | **Public documentation.** Product docs or public descriptions found by web search. Not checked against the binary. |
| **H** | **Hypothesis.** Inferred from architecture. Not confirmed. |

### Key structural finding (V)

The Electron shell (`app.asar/dist/*.js`) is a **thin host**. It contains no conversation, agent, file-explorer, terminal, or project logic:

- A keyword survey of `dist/` found `workspace` only in the folder-picker IPC (`ipcHandlers.js`, `preload.js`). `conversation` appears once, in `webviewBrowser.js`. `terminal` appears only in incidental comments.
- The shell spawns `language_server.exe` and loads `https://127.0.0.1:<port>/`, which the language server serves "from embedded assets" (`language_server.log`, `server.go:1559`).

**So almost every feature on the investigation list lives in the closed-source Go binary and its embedded web UI.** We can see what the shell does in detail. Everything else we can describe only through string markers, documentation and hypotheses.

### Bounded string-marker scan of `language_server.exe` (S)

| Marker | Count | Interpretation |
| :--- | ---: | :--- |
| `LanguageServerService` | 5,414 | Name of an RPC service. Consistent with the gRPC listener in the log. The wire schema is **undocumented**. |
| `ExtensionServerService` | 275 | A second RPC service (purpose unknown). |
| `CreatePseudoConsole` | 3 | Windows ConPTY API. Suggests the backend hosts terminals/PTYs. |
| `xterm` | 5 | Could be xterm.js or just a `TERM=xterm` value. Ambiguous. |
| `react-dom`, `monaco`, `node-pty` | 0 | Not found as plain ASCII. The embedded UI may be compressed, so a zero count is **not** evidence of absence. |

---

## 2. Compatibility categories & operational modes

| Category | Meaning for the Android build |
| :--- | :--- |
| 🟢 **Independent (On-Device)** | Operates 100% standalone on-device using public Android/Kotlin APIs. Works without any host. |
| 🟡 **Independent + Documented Cloud API** | Operates standalone on-device, calling a **public, documented** API (e.g. Gemini API, Vertex AI, Anthropic, OpenAI) with user-supplied credentials. |
| 🟠 **Optional Host Extension** | Connects to an independent Go-based Workspace Host for remote execution (terminal, builds, headless browser). Fully optional. |
| 🔴 **Mobile alternative required** | Desktop-specific convention. Must be replaced, redesigned, or dropped for mobile UX. |
| ⛔ **Do not replicate** | Would depend on undocumented/proprietary interfaces, bypass security, or violate ecosystem boundaries. |

### Operational modes: Standalone vs. Extended

- **Standalone Mode (Device-Only MVP):** The application runs self-contained on the Android device. No server daemon or desktop host is required. It provides agent conversations, code reading, editing, diff generation/review, JGit operations, local file search, and in-app web preview.
- **Extended Mode (Paired Host):** The application connects over an authenticated, encrypted channel to an optional user-run Go Workspace Host daemon (`workspace-host`) to unlock remote PTY terminals, arbitrary shell command execution, host git repositories, and automated headless browser interaction.

---

## 3. Requested systems

### 3.1 Agent conversations

| Aspect | Detail |
| :--- | :--- |
| **Desktop implementation** | Runs in the language server. The log shows `SEND_USER_CASCADE_MESSAGE_LATENCY`, `cascade_id`, `trajectory_id` and a "trajectory store manager with proto store and SQLite store" (**V**, `language_server.log`). Model calls go to `daily-cloudcode-pa.googleapis.com/v1internal:*` (**V**, launch args and log). |
| **What is undocumented** | The `v1internal` Cloud Code endpoints, the trajectory format and the `LanguageServerService` RPC schema. |
| **Mobile approach** | Native chat UI (Jetpack Compose). Model interaction is abstracted behind a provider-agnostic `ModelProvider` SPI. Initial implementation uses the **public Gemini API** with **user-supplied credentials (BYOK)** stored securely in Android Keystore / EncryptedDataStore. Zero credentials are baked into the distributable APK. The interface allows adding OpenAI, Anthropic, or local Ollama providers later without changing agent runtime code. Conversations are persisted locally in Room. Streaming responses render progressively. |
| **Category** | 🟡 Independent + documented API (100% functional standalone) |
| **Confidence** | High that this is feasible. The desktop *behaviour* (prompting, context assembly) is **H**. We design our own prompt and context architecture. |

### 3.2 Agent task execution (tool use, subagents)

| Aspect | Detail |
| :--- | :--- |
| **Desktop implementation** | Runs in the language server. Public docs describe tool calls, `invoke_subagent`, built-in `research`/`browser` subagents, and command approval policies (**D**). Shell evidence (**V**): the language server receives `--enable_sidecars` and `CHROME_DEVTOOLS_MCP_JS`. The log mentions an MCP manager and plugin migrations. |
| **Mobile approach** | A decoupled agent loop in `:agent-runtime` (Kotlin Multiplatform): `ModelProvider` → function calling → tool execution → context reflection. The runtime interacts with a pluggable `ToolRegistry`.<br>• **Standalone tools (On-Device):** Pure Kotlin file read/write, patch application, JGit status/commit, and HTTP fetch.<br>• **Optional host tools (Extended):** Shell execution, build/test runners, and browser automation delegated over the authenticated host API. When no host is paired, host-specific tools are cleanly disabled and the agent is instructed to operate in file-only editing mode. |
| **Constraint** | Android enforces W^X memory and execution restrictions on app-writable storage (targetSdk ≥ 29). Running compilers or arbitrary binaries requires the optional Workspace Host. |
| **Category** | 🟡 Standalone on-device tools · 🟠 Optional remote execution |

### 3.3 Workspace management

| Aspect | Detail |
| :--- | :--- |
| **Desktop implementation** | The shell provides only the folder picker: `dialog:open-workspace` and `dialog:open-workspaces` with WSL path mapping (**V**, `ipcHandlers.js`). Everything else lives in the language server. Docs describe Local vs. new git-worktree modes (**D**). |
| **Mobile approach** | Dual workspace abstraction (`WorkspaceFileSystem`):<br>1. **Device Workspaces (Standalone):** Storage Access Framework (SAF) trees or app-private storage directories with JGit for clone, commit, branch, and push.<br>2. **Remote Workspaces (Optional Extended):** Folders on a paired Go Workspace Host. Host manages Git worktrees for isolated subagent execution. |
| **Category** | 🟢 Device (Standalone) · 🟠 Remote (Optional Extension) |

### 3.4 File explorer

| Aspect | Detail |
| :--- | :--- |
| **Desktop implementation** | Not in the shell (**V**, by absence). Assumed to be in the embedded UI and backed by language-server RPCs (**H**). The only file-related shell APIs are `getPathForFile` (drag and drop) and `shell:reveal-in-file-picker` (**V**, `preload.js`). |
| **Mobile approach** | Native Compose tree and list navigation over `WorkspaceFileSystem`. Features a syntax-highlighted code viewer, touch-friendly light editor, and a dual-pane/inline diff review interface where users inspect and accept/reject agent file edits per hunk. Operates 100% locally on device workspaces. |
| **Category** | 🟢 Device (Standalone) · 🟠 Remote (Optional Extension) |

### 3.5 Terminal

| Aspect | Detail |
| :--- | :--- |
| **Desktop implementation** | No terminal code in the shell (**V**). The language server contains the `CreatePseudoConsole` marker (**S**), which suggests it hosts PTYs. The shell loads the user's login-shell environment (`shell-env`) before spawning the language server so commands can find user tools (**V**, `languageServer.js`). |
| **Mobile approach** | **Optional extension only.** When paired with the Go Workspace Host, renders an interactive PTY over an authenticated, encrypted WebSocket connection using an Android terminal emulator view. In standalone mode (no host), the terminal tab is gracefully hidden or displays a "Connect a Workspace Host to enable interactive terminals" notice. |
| **Category** | 🔴 Replaced by 🟠 Optional Host Extension (Non-blocking for MVP) |

### 3.6 Browser integration

| Aspect | Detail |
| :--- | :--- |
| **Desktop implementation** | **V.** Electron `<webview>` / `WebContentsView` tabs managed by `wcvBrowserRegistry.js` and `wcvZOrderController.js`. Agent input injection (`wcv:send-mouse`, `wcv:send-key`, `wcv:insert-text`) and `wcv:capture-page`. An isolated "Script Jail" for model-generated scripts (`scriptJail.js`). Chromium remote-debugging port forced on (`remote-debugging-port=0`), plus `disable-blink-features=AutomationControlled`. The language server finds the CDP endpoint (`[CDP Discovery]` in the log). Bundled `chrome-devtools-mcp`. `webm_encoder.exe` for recordings. WebAuthn/caBLE passkey support and BeyondCorp PAC proxying for webviews. |
| **Mobile approach** | **Tiered implementation:**<br>1. **In-App Preview (Standalone):** Android `WebView` in `:feature:browser` allowing the user to browse documentation or test web endpoints, with DOM inspection and screenshot capture via `PixelCopy`.<br>2. **Autonomous Agent Browser (Optional Extended):** Headless Chrome supervised by the Go Workspace Host via `chrome-devtools-mcp`, streaming frames and action records back to the phone.<br>In standalone mode, agent browser actions fall back to simple HTTP/REST fetches. |
| **Do not replicate** ⛔ | Hiding automation signals (`AutomationControlled`) or attempting anti-bot evasion. |
| **Category** | 🟢 In-App Preview (Standalone) · 🟠 Agent Automation (Optional Extension) |

### 3.7 Agent Manager

| Aspect | Detail |
| :--- | :--- |
| **Desktop implementation** | The UI lives in the embedded web app (**H**). Shell evidence (**V**): the `agent:update-active-count` IPC drives a tray item ("No agents running"). The `runInBackground` and `keepComputerAwake` settings keep agents alive when windows close. Docs describe a dashboard for running many agents in parallel (**D**). |
| **Mobile approach** | Native dashboard listing active and historical agent runs with step timelines, tool calls, approval gates, and generated artifacts.<br>• **Standalone Runs:** Executed on-device within an Android foreground service with active notification.<br>• **Remote Extended Runs:** Submitted to the Go Workspace Host, which survives device disconnection and sends push notifications (FCM) when human intervention is required. |
| **Category** | 🟢 UI & Local Runs (Standalone) · 🟠 Remote Background Runs (Optional) |

### 3.8 Project navigation

| Aspect | Detail |
| :--- | :--- |
| **Desktop implementation** | Language server and UI (**H**). The log shows a "projects migration" and a summary store reconciling conversations (**V**). Docs describe grouping conversations into projects (**D**). Deep links use the `antigravity://` scheme (**V**). |
| **Mobile approach** | Complete local project management hierarchy: `Project` → `Workspaces` → `Runs/Conversations` backed by SQLite/Room. Indexed full-text search across projects. Deep linking uses verified Android App Links under an independent domain. |
| **Category** | 🟢 Independent (100% Standalone) |

### 3.9 Application settings

| Aspect | Detail |
| :--- | :--- |
| **Desktop implementation** | **V.** There are two settings layers. The shell's `SettingsService` holds three keys (`runInBackground`, `keepComputerAwake`, `autoCheckForUpdates`) in `app_storage.json`. Theme comes from `~/.gemini/config/config.json` (`userSettings.themeMode`). Agent and model settings are assumed to be held by the language server (**H**). |
| **Mobile approach** | Jetpack DataStore preferences with sensitive secrets encrypted via Android Keystore. Configurable settings:<br>• **Model Settings:** Provider selection (Gemini, Vertex AI, Custom/OpenAI-compatible), user-entered API keys or endpoint URLs, model ID.<br>• **Host Settings:** Paired Workspace Hosts, pinned TLS certificates, pairing revocation.<br>• **Agent Policies:** Autonomy thresholds, file edit auto-approval, command confirmation rules.<br>• **App Settings:** Theme (System, Light, Dark), notification preferences. |
| **Category** | 🟢 Independent (100% Standalone) |

---

## 4. Other desktop subsystems found in the shell

| Subsystem (V, source file) | Purpose | Android disposition |
| :--- | :--- | :--- |
| Window chrome, zoom, title-bar overlay (`ipcHandlers.js`) | Desktop windowing | 🔴 Drop. Replace with adaptive Material 3 layouts (phone, foldable, tablet). |
| System tray and run-in-background (`tray.js`, `settingsService.js`) | Keep agents running after windows close | 🔴 Replace with Android Foreground Service or delegate to Workspace Host. |
| Keep computer awake (`SleepBlocker`, `utils.js`) | Prevent sleep during runs | 🔴 Drop on device. Workspace Host handles long-running execution. |
| Auto-updater (`updater.js`, `app-update.yml`) | Self-update from a Google endpoint | 🔴 Replace with standard Google Play Store updates. ⛔ Never contact Google updater endpoint. |
| Host Bridge server (`hostBridgeServer.js`) | Lets language server query shell for updates | 🔴 Not needed. (Security pattern of loopback check + constant-time token is adopted for local IPC). |
| WSL integration (`wsl.js`) | Run language server inside Windows WSL | 🔴 Drop. Replaced by the optional Go Workspace Host architecture. |
| Native notifications (`notification:*`) | System alerts | 🟢 Native Android notification channels and FCM. |
| Deep links (`main.js`) | `antigravity://` routing | 🟢 Android App Links using an independent custom scheme/domain. |
| WebAuthn / caBLEv2 / QR encoder (`webauthn.js`, `cablev2.js`, `qrEncoder.js`) | Passkeys inside webviews | 🟢 Standard Android Credential Manager API. |
| BeyondCorp PAC (`beyondcorpPac.js`) | Corporate proxy configuration | 🔴 Out of scope for independent mobile port. |
| IDE install wizard (`ideInstall/*`) | Migration to desktop IDE | 🔴 Out of scope. |
| Script Jail (`scriptJail.js`) | Zero-egress sandbox for model-written page scripts | 🟠 Adopted in the Go Workspace Host browser automation tier. |
| Loopback TLS trust (`languageServer.js::setupLocalCertTrust`) | Accepts **any** certificate for `127.0.0.1`/`localhost` | ⛔ Do not replicate. Replace with strict TLS certificate pinning on host pairing. |

---

## 5. Summary of System Requirements

| System | Standalone Device MVP | Extended (With Optional Go Host) | Primary Dependency |
| :--- | :--- | :--- | :--- |
| **Agent conversations** | ✅ Fully Functional | ✅ Fully Functional | Public Gemini API (BYOK) via pluggable SPI |
| **Agent task execution** | ✅ File editing, patch, JGit | ✅ Shell, builds, tests, browser | Local tool provider + Optional Remote Host tools |
| **Workspace management** | ✅ SAF + Local app storage | ✅ Remote Host git worktrees | Android Storage Access Framework / Go Host |
| **File explorer & diffs** | ✅ Local tree + Hunk review | ✅ Remote tree + Hunk review | `WorkspaceFileSystem` abstraction |
| **Terminal** | ⚠️ Gracefully disabled | ✅ Full PTY over WebSocket | Go Workspace Host (conpty/pty) |
| **Browser integration** | ✅ In-App WebView preview | ✅ Automated headless Chrome | Android WebView / Remote `chrome-devtools-mcp` |
| **Agent Manager** | ✅ Foreground service runs | ✅ Durable background host runs | Android WorkManager / Go Host + FCM |
| **Project navigation** | ✅ Full local hierarchy | ✅ Synced with host workspaces | Room database |
| **Application settings** | ✅ Full local configuration | ✅ Includes Host pairing menu | Jetpack DataStore + Android Keystore |

**Strictly Excluded & Non-Replicated (⛔):**
- Proprietary `language_server.exe` binary and embedded UI bundles.
- Undocumented internal RPC services (`LanguageServerService`, `ExtensionServerService`).
- Internal Google endpoints (`daily-cloudcode-pa.googleapis.com/v1internal:*`).
- Antigravity OAuth client credentials or proprietary Google auth tokens.
- Hardcoded API credentials in the distributable APK.
- Google auto-update endpoints (`antigravity-hub-auto-updater-*.run.app`).
- Automation detection evasion hacks (`disable-blink-features=AutomationControlled`).
- Insecure blanket certificate trust overrides.
