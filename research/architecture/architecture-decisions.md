# Architecture Decision Records (ADR) — Mobile Architecture

**Project:** Antigravity Mobile Port · **Phase:** 2 — Mobile Architecture Planning  
**Status:** Approved  
**Companion Documents:**  
- [mobile-architecture.md](mobile-architecture.md)  
- [feature-compatibility.md](feature-compatibility.md)  
- [backend-requirements.md](backend-requirements.md)  
- [initial-investigation.md](initial-investigation.md) (Phase 1, incl. errata)  
- [port-fidelity-audit.md](port-fidelity-audit.md) (P0 Fidelity Reassessment)  
- [webview-port-feasibility.md](webview-port-feasibility.md) (Option C Technical & Legal Feasibility)  
- [webview-poc-validation.md](webview-poc-validation.md) (Empirical WebView PoC Validation)  

---

## Executive Summary of Approved Decisions

Following the Phase 1 reverse-engineering investigation and the P0 Port-Fidelity Reassessment, the architecture is governed by **Option C (Hybrid Antigravity Port Architecture)**: preserving and hosting the **actual original Antigravity (`Jetski Web`) React 19 frontend** inside an Android `WebView` (`JetskiWebViewHost`), backed by an in-process native Kotlin `LanguageServerService` Connect-RPC compatibility engine (`LanguageServerGrpcWebHandler`), a decoupled `ModelProvider` SPI with native credential isolation, an optional Go-based Workspace Host, and a clean-room Jetbox fallback UI (`#101010` / `#007acc`) when no proprietary frontend bundle has been supplied by the user.

```
┌────────────────────────────────────────────────────────────────────────┐
│               PRIMARY UI: ORIGINAL JETSKI WEB FRONTEND                 │
│     • Runtime-loaded user bundle (JetskiBundleLoader) or Remote Host   │
│     • Responsive mobile layout (HE() === true on < 640px viewports)    │
│     • Clean-room Jetbox fallback (#101010 / #007acc) pre-import        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Connect-RPC (application/grpc-web+json)
                                    │ via WebView shouldInterceptRequest
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│              NATIVE ANDROID BACKEND (LanguageServerService)            │
│                    (100% Host-Independent Device Mode)                 │
├──────────────────────────┬──────────────────────────┬──────────────────┤
│    Agent Conversations   │     Project & Files      │   Diff & Workers │
│  • Public Gemini (BYOK)  │  • SAF & App Storage     │  • diff_worker   │
│  • Pluggable SPI         │  • Local Shell / Files   │  • shiki_worker  │
│  • Local Room SQLite     │  • ProjectConversations  │  • StepsUpdate   │
└──────────────────────────┴──────────────────────────┴──────────────────┘
                                    │
                                    ▼ (Optional Cryptographic Pairing)
┌────────────────────────────────────────────────────────────────────────┐
│             OPTIONAL WORKSPACE HOST (Go Static Daemon)                 │
│                   (Extended Capabilities Only)                         │
├──────────────────────────┬──────────────────────────┬──────────────────┤
│    Remote PTY Terminal   │    Arbitrary Shell / CLI │ Headless Browser │
│  • WebSocket stream      │  • Compilers / Linters   │  • Chrome + MCP  │
│  • Resumable sessions    │  • Git worktrees         │  • WebP frames   │
└──────────────────────────┴──────────────────────────┴──────────────────┘
```

---

## ADR-01: Standalone Device-Only Android MVP as Primary Milestone

### Status
**Approved**

### Context
Phase 1 revealed that the desktop Antigravity application depends entirely on a closed-source Go daemon (`language_server.exe`) running locally on desktop OSes. For mobile, requiring a running desktop daemon or cloud server to use the application would introduce severe friction, deployment complexity, and offline failure. Conversely, mobile developers need the ability to discuss code, review plans, edit local files, inspect diffs, and commit changes on the go without maintaining a desktop pairing.

### Decision
1. **The primary delivery milestone (P1) is a 100% standalone, device-only Android MVP.**
2. The Android application must be fully functional without any Workspace Host, external server, or companion desktop process.
3. Standalone features include:
   - Full agent conversation loop and multi-turn planning.
   - Project and workspace management using the Android Storage Access Framework (SAF) and internal storage.
   - Code browsing, syntax viewing, and searching.
   - Touch-optimized hunk-by-hunk diff review (accept, reject, or edit proposed changes).
   - Git operations (clone, status, diff, commit, branch, push) implemented in pure Java/Kotlin via JGit.
   - User-facing web preview via Android `WebView`.
   - On-device agent task execution via Android Foreground Service.

### Consequences
- **Positive:** Immediate utility for users; zero server infrastructure cost; offline-capable for code browsing and cached conversations; frictionless onboarding.
- **Negative:** Build/compiler execution and full PTY terminals cannot run natively on device due to Android targetSdk ≥ 29 W^X restrictions.
- **Mitigation:** Desktop-class execution (compilers, terminal, browser subagent) is provided as an optional extension (ADR-03).

---

## ADR-02: Decoupled ModelProvider SPI & Credential Isolation (BYOK)

### Status
**Approved**

### Context
Phase 1 showed that the desktop language server hardcodes proprietary Google Cloud Code internal endpoints (`daily-cloudcode-pa.googleapis.com/v1internal:*`) and proprietary OAuth flows. An independent application cannot use these endpoints. Furthermore, bundling hardcoded vendor API keys or OAuth secrets into a distributed mobile application binary violates security best practices and exposes credentials to extraction.

### Decision
1. **Strict SPI Decoupling:** The agent runtime (`:agent-runtime`) must have **zero direct dependency on Google Gemini** or any vendor SDK. All LLM interactions are abstracted behind a generic `ModelProvider` Service Provider Interface (SPI).
2. **Initial Provider:** The initial implementation uses the public, documented Google Gemini API (`https://generativelanguage.googleapis.com/v1beta`).
3. **Pluggable Architecture:** The `ModelProvider` contract specifies streaming deltas, structured function/tool calling, and multimodal inputs. The interface must allow adding Anthropic Claude, OpenAI, Vertex AI, and local Ollama providers as independent modules without modifying the agent runtime loop.
4. **Credential Isolation (BYOK):**
   - The distributable APK will contain **zero API keys, zero OAuth secrets, and zero private tokens**.
   - Users provide their own API key via an in-app setup screen (Bring-Your-Own-Key).
   - All credentials are encrypted and stored exclusively in the hardware-backed **Android Keystore** (`EncryptedSharedPreferences` / `MasterKeys.AES256_GCM_SPEC`).

### Consequences
- **Positive:** Multi-model flexibility; compliance with Google Play Store developer policies; zero risk of credential leakage from leaked/decompiled APKs; user retains full ownership and quota control.
- **Negative:** User must obtain and paste their own API key during first run.
- **Mitigation:** Clear, guided setup wizard with direct links to Google AI Studio to generate an API key in two taps.

---

## ADR-03: Independent Go-Based Workspace Host as Optional Extension

### Status
**Approved**

### Context
Android security architecture restricts executing binaries from writable directories and strictly bounds background execution. Desktop workflows that require compiling code, running tests, executing package managers (`npm`, `cargo`, `pip`), running interactive terminal sessions, or driving automated browser testing require an external environment.

### Decision
1. **Independent Implementation:** Build a clean-room, lightweight companion daemon in **Go** (`workspace-host`).
2. **Zero Dependency on Desktop Antigravity:** The host daemon does not wrap, interact with, or reuse the proprietary `language_server.exe` binary or its internal RPCs (`LanguageServerService`).
3. **Strictly Optional:** The Workspace Host is designed as an optional extension. The Android app detects when a host is not present and cleanly hides or disables only host-specific capabilities (PTY terminal, shell execution, headless browser), while keeping core chat, planning, code browsing, and diff patching 100% active.
4. **Host Responsibilities:**
   - Interactive PTY sessions over WebSocket (using `creack/pty` on POSIX, ConPTY on Windows).
   - Sandboxed shell execution with host-enforced allow/deny lists.
   - Headless Chrome browser automation via open-source `chrome-devtools-mcp`.
   - Git worktree management for subagent isolation.
   - Long-running autonomous background tasks with FCM notification triggers.

### Consequences
- **Positive:** Unlocks full desktop-grade agent execution for paired users; single static Go binary distribution across Windows, macOS, and Linux; clean architectural separation.
- **Negative:** Requires maintaining two codebases (Android app in Kotlin, Workspace Host in Go).
- **Mitigation:** The protocol between the two is defined via standard Protocol Buffers (`.proto`) and Connect-RPC, generating type-safe bindings for both languages.

---

## ADR-04: Cryptographic Zero-Trust Pairing & Encrypted Communication

### Status
**Approved**

### Context
Phase 1 revealed that the desktop Antigravity shell accepted **any** certificate on loopback (`setupLocalCertTrust` accepting all certs on `127.0.0.1` and `localhost`). While tolerable for local IPC, network communication between a mobile device and a remote workspace host over Wi-Fi or cellular networks requires rigorous mutual authentication and encryption to prevent man-in-the-middle (MITM) attacks and unauthorized shell access.

### Decision
1. **Out-of-Band Pairing via QR Code:**
   - The Go Workspace Host displays a temporary QR code containing:
     `{host_id, endpoints, cert_sha256_fingerprint, ephemeral_pairing_secret, expiry}`.
   - The pairing secret expires after 5 minutes and is single-use.
2. **Hardware-Backed Device Keys:**
   - Upon scanning, the Android app generates an EC P-256 keypair inside the Android Keystore.
   - The private key is non-exportable and hardware-isolated.
3. **Mutual Cryptographic Handshake:**
   - The app establishes a TLS 1.3 connection to the host, enforcing **strict certificate pinning** against the SHA-256 fingerprint from the QR code (rejecting any CA authority or foreign cert).
   - The app sends the pairing secret along with its client public key.
   - The host verifies the secret, registers the public key, and returns an assigned client ID.
4. **Per-Request Signatures:**
   - Every subsequent RPC call and WebSocket connection carries an `X-Client-Signature` header generated by the Android Keystore private key over `(method, path, timestamp, body_hash)`.
   - Replay protection enforces timestamp freshness (±30 seconds).
5. **Host-Side Revocation:**
   - The host maintains an authorized client list and supports instant revocation via CLI command (`workspace-host revoke <client_id>`).

### Consequences
- **Positive:** Zero pre-shared static passwords; immune to public CA compromise; hardware-enforced device identity; secure over any untrusted network (home Wi-Fi, coffee shop, or VPN).
- **Negative:** Pairing requires an initial camera scan or manual token transfer.

---

## ADR-05 (ADR-005): Option C Hybrid Antigravity Port Architecture — Original Jetski Web Frontend + Native Android Backend

### Status
**Approved (Supersedes prior independent-UI assumption in ADR-05; preserves IP, credential, and redistribution boundaries)**

### Context
The P0 Port-Fidelity Audit ([port-fidelity-audit.md](port-fidelity-audit.md)) and WebView Technical & Legal Feasibility Study ([webview-port-feasibility.md](webview-port-feasibility.md)) proved that:
1. The original Antigravity desktop application's `language_server.exe` embeds the complete **Jetski Web** React 19 SPA (`index.html`, `main.js`, `jetbox.css`, `compiled_tailwind.css`, `diff_worker.js`, `workers/shiki_worker.js`) as a standard ZIP archive at byte offset `163294824` (`5,628,057` bytes) (**V**).
2. `Jetski Web` already contains built-in web fallbacks for all 11 Electron `preload.js` globals (`OWc` `localStorage`, `vXc` Web Notifications, `cXc` RPC folder picker, `bSa` DOM context menus) and automatically activates its responsive mobile layout (`HE() === true`) when `window.electronNative` is absent, `navigator.userAgent` matches Android (`Yka() === "web.mobile"`), and `window.innerWidth < 640` (`bla() === "narrow"`) (**V**).
3. Decoding all 96 embedded Protobuf `FileDescriptorProto` definitions (`cd("...")`) inside `main.js` recovered the exact schema for `exa.language_server_pb.LanguageServerService` (364 RPC methods), and the live WebView Proof of Concept ([webview-poc-validation.md](webview-poc-validation.md)) empirically verified React 19 startup, mobile layout rendering, Web Workers, `globalThis.__JETSKI_LS_CLIENT_DECORATOR__`, and Connect-RPC (`application/grpc-web+json`) framing with `0` uncaught exceptions and `0` console errors (**V**).
4. However, the proprietary `Jetski Web` assets (`main.js`, `jetbox.css`, `compiled_tailwind.css`, `language_server.exe`) are governed by Google's proprietary license terms and cannot be committed to our source repository or pre-bundled inside a redistributable APK.

### Decision
Adopt **Option C (Original Jetski Web Frontend + Native Android Backend & Clean-Room Fallback)** with strict separation across four architectural layers:

#### 1. Frontend Layer (`Jetski Web` React 19 SPA & Clean-Room Jetbox Fallback)
- **Primary UI:** The unmodified original `Jetski Web` React 19 SPA serves as the primary user interface whenever a valid bundle is imported or a remote host is connected, preserving its conversation history, projects, Cascade conversation view, `AgentInputBox`, settings bottom sheet, file/diff viewers, and Shiki syntax highlighting.
- **No-Redistribution Rule:** Proprietary Google frontend assets are **never** committed to the repository or packaged inside the APK.
- **Runtime Provisioning (`JetskiBundleLoader`):** Users supply their own licensed desktop `language_server.exe` or extracted `Jetski Web` ZIP archive at runtime via the Android Storage Access Framework (SAF). `JetskiBundleLoader` validates the ZIP structure and required entries (`index.html`, `main.js`, `jetbox.css`, `compiled_tailwind.css`, `diff_worker.js`, `workers/shiki_worker.js`) without unnecessary duplication.
- **Clean-Room Native Fallback:** When no user bundle has been imported and no remote host is connected, the app renders a clean-room Jetpack Compose fallback aligned to the verified Jetbox dark color tokens (`#101010` background, `#007acc` primary accent, `#cccccc` foreground) with clear controls to import a bundle or connect to a remote host, plus full access to all P0 native modules.

#### 2. Android Host Layer (`JetskiWebViewHost`)
- Hosts the frontend inside an Android `WebView` configured for mobile viewport rendering (`width=device-width`, dark color-scheme, touch input, DOM storage, and Web Workers).
- Injects `window.__APP_CONFIG__` (`productName`, per-session `csrfToken`, `appVersion`, `devMode`) and `globalThis.__JETSKI_LS_CLIENT_DECORATOR__` prior to `main.js` execution.
- Serves local bundle assets and ES module / classic Web Workers (`diff_worker.js`, `workers/shiki_worker.js`) from `https://appassets.androidplatform.net/`.
- Routes all `POST /exa.language_server_pb.LanguageServerService/*` requests in-process via `WebViewClient.shouldInterceptRequest` (bypassing HTTP/1.1 connection pool limits).

#### 3. RPC Compatibility Layer (`LanguageServerGrpcWebHandler` & `GrpcWebFrameCodec`)
- Implements the verified Connect-RPC / gRPC-Web+JSON wire protocol (`Content-Type: application/grpc-web+json`, `X-Grpc-Web: 1`, `x-codeium-csrf-token` validation).
- Uses the 96 recovered `FileDescriptorProto` definitions as the authoritative schema source of truth.
- Frames all responses using the 5-byte gRPC-Web data envelope (`0x00` + 4-byte big-endian length + UTF-8 Protobuf JSON) followed by the 5-byte trailer envelope (`0x80` + 4-byte big-endian length + `grpc-status: 0\r\n`).
- Enforces CSRF validation, request cancellation, timeouts, and structured gRPC status trailers (`grpc-status` + `grpc-message`).

#### 4. Native Android Backend Layer (`core:agent`, `core:storage`, `core:workspace`, `core:network`)
- **Credential Isolation:** API keys and tokens remain exclusively in native Android storage (`CredentialStore` / Android Keystore) and are **never** exposed to WebView JavaScript.
- **Legitimate Authentication Gate:** `HasAuthToken` and `GetAuthStatus` report `true` **only** when a valid credential is configured in native Android storage (or an explicit isolated test fixture is injected in unit tests). When no API key is configured, `HasAuthToken` returns `false` so authentication state is never faked in production.
- **Real AI Cascade Execution:** `StartCascade` and `SendUserCascadeMessage` route prompts from `Jetski Web`'s `AgentInputBox` through our `AgentOrchestrator` and `ModelProvider` SPI (`GeminiModelProvider` calling the public `https://generativelanguage.googleapis.com/v1beta` streaming endpoint), persisting turns in Room (`ConversationRepository`) and streaming live `StreamAgentStateUpdatesResponse` (`StepsUpdate` + `TurnsUpdate`) back into the `Jetski Web` Cascade UI.
- **Strictly Prohibited Endpoints:** Never call `daily-cloudcode-pa.googleapis.com/v1internal:*`, never contact `antigravity-hub-auto-updater-*.run.app`, and never impersonate Google's proprietary OAuth client.

### Unresolved Technical & Licensing Questions
1. **Desktop Version Drift (`[H]`):** Future desktop releases of `language_server.exe` may add or modify `LanguageServerService` RPC fields or move the embedded ZIP offset. *Mitigation:* `JetskiBundleLoader` scans dynamically for the ZIP central directory containing `index.html` + `main.js` rather than relying solely on a fixed byte offset, and `@bufbuild/protobuf` inside `main.js` is configured with `ignoreUnknownFields: true`.
2. **Unverified Long-Tail RPCs (`[V]`):** Of the 364 total methods defined on `LanguageServerService`, 26+ core startup, project, workspace, file, command, and Cascade trajectory RPCs have been empirically verified in our PoC. Remaining desktop-only or enterprise RPCs (e.g., Cider/CitC internal workspace RPCs, Battle Mode arms, WebRTC mesh) return clean empty proto defaults or `UNIMPLEMENTED` (`grpc-status: 12`) until individually verified.
3. **Terms of Service Scope for User-Supplied Bundles (`[D]`):** While loading a user's own locally installed `language_server.exe` at runtime avoids redistributing Google copyright material in our repository or APK, end users remain subject to Google's Antigravity Terms of Service regarding personal use of their local installation.
4. **Google Account Sign-In vs. Public Gemini API Entitlements (`[V]`):** Signing in with a consumer Google account does not grant third-party clients access to `daily-cloudcode-pa.googleapis.com/v1internal`. Real AI conversations on Android therefore require either a user-supplied Gemini API key (Google AI Studio / Vertex AI BYOK) or pairing with an authorised remote host.

---

## Decision Matrix & Impact Summary

| Architectural Area | Decision | Standalone Device Mode Impact | Extended Host Mode Impact |
| :--- | :--- | :--- | :--- |
| **Primary Scope** | Device-Only Capable (ADR-01) | Fully functional on device | Unlocks optional remote execution |
| **Frontend & UI** | Option C Hybrid Port (ADR-05) | Hosts user-imported `Jetski Web` in `JetskiWebViewHost` (or Clean-Room Jetbox fallback pre-import) | Connects `Jetski Web` to local or remote host |
| **RPC Compatibility** | `LanguageServerGrpcWebHandler` (ADR-05) | In-process `shouldInterceptRequest` gRPC-Web+JSON handler for verified `LanguageServerService` RPCs | Can proxy or bridge to remote host |
| **Intelligence** | Gemini API (BYOK) via SPI (ADR-02) | Key held in native Android Keystore; powers real `SendUserCascadeMessage` streaming | Reused or delegated to host |
| **Remote Execution** | Go Workspace Host (ADR-03) | Not required / Local Android shell & SAF fallback | Powers full PTY, compilers, headless Chrome |
| **Security & Pairing** | Hardware ECDSA + Pinned TLS (ADR-04) | Per-session CSRF token + native credential isolation | Mutual zero-trust TLS channel |
