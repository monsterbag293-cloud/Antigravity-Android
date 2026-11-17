# Phase 4.1: Mobile Compatibility & Feature Inventory

## Executive Overview
This inventory audits every component of the original Google Antigravity desktop application against the mobile Android port (`dev.agentic.workspace`). The port preserves the genuine Jetski frontend bundle, genuine ARM64 Linux Go `language_server` running in a patched PRoot container, genuine Connect-RPC / gRPC-Web protocols, and authentic Google Account authentication.

---

## 1. Top-Level Shell & Navigation Architecture

| Desktop Feature | Android Status | Adaptation / Implementation Mechanism |
|---|---|---|
| Window Titlebar (`Antigravity Workspace View Window`) | **Removed** on mobile | CSS injected via `JetskiBundleLoader` hides `[data-testid="title-menu-bar"]` and `.agy-shell > .shrink-0`, freeing 35px of vertical screen real estate. |
| Window Controls (Min, Max, Close) | **Removed** on mobile | Electron native bridge stubs window management; Android system gestures handle multitasking and back stack. |
| Left Navigation Panel | **Overlay Adapted** | Instead of splitting the 411px mobile screen into 256px + 155px, it renders as a 100% width overlay (`z-index: 50`) with pure black OLED background (`#000000`). |
| Sidebar Close Gesture | **Implemented** | Touch gesture listener detects right-to-left swipe (`dx > 45px`, `dy < 90px`) to close the open drawer. Edge swipe for opening is intentionally omitted to avoid conflicting with Android back gesture. |
| Conversation Selection | **Adapted** | Tapping a conversation closes the navigation drawer and blurs active input (`document.activeElement.blur()`) to prevent auto-focusing the Lexical editor and unintentionally opening the software keyboard. |
| Settings Modal | **Full Screen Overlay** | Injected mobile CSS sets `div[role="dialog"]` to 100% viewport width/height; right-to-left swipe gesture dismisses dialog. |
| Shortcuts Section in Settings | **Hidden** | Desktop keyboard shortcuts (`Ctrl+Shift+P`, etc.) are hidden via CSS (`[data-screen="Shortcuts"] { display: none !important; }`). |

---

## 2. Conversation & Model Systems

| Desktop Feature | Android Status | Adaptation / Implementation Mechanism |
|---|---|---|
| Cascade Agent Execution | **Full Parity** | Executes genuine Cascade trajectories via Go `language_server` in PRoot and Connect-RPC streaming. |
| Model Discovery & Selection | **Full Parity** | Exposes exact 6 canonical desktop models: Gemini 4 Argon (Medium) [New], Gemini 3.8 Flash (High), Claude Opus 5.5 (Medium) [New], Claude Sonnet 5.5 (Medium) [New], GPT-OSS 120B (Medium) [Notice], and Gemini 4 Carbon (Ultra plan enabled). |
| Quota Summary | **Full Parity** | Exposes exact 3 canonical quota groups matching desktop: `Gemini Models` (Weekly & 5h limits), `Claude and GPT models` (Weekly & 5h limits), and `Gemini 4 Argon` (Weekly limit). |
| Chat Input & Software Keyboard | **Adaptive Positioning** | `JetskiWebViewHost` Column uses `Modifier.imePadding()`. When software keyboard opens, the WebView shrinks dynamically, keeping input and Send buttons visible. |
| Voice Recording & Speech-to-Text | **Dual Integrated** | Fixed `StreamAudioTranscription` RPC session persistence in `RemoteHubProxyHandler` + added native Android `SpeechRecognizer` bridge injecting editable text via `window.__insertRecognizedSpeech()`. |

---

## 3. Workspaces, Files & Local Execution

| Desktop Feature | Android Status | Adaptation / Implementation Mechanism |
|---|---|---|
| Project Creation & Selection | **Mobile Modal** | Simplified Android project modal allows one-tap selection of existing workspace folders or instant creation in `/tmp/gemini_app/workspaces/<name>`. |
| File Browsing & Editing | **Full Parity** | File viewer and editor communicate with backend language server via `ReadFile`, `WriteFile`, and `GetFileDiff` RPCs. |
| Terminal Execution | **Termux & PRoot** | Automatic detection of `com.termux` with POSIX shell execution; models are aware of Android Linux PRoot/Termux environment (no Windows CMD/PowerShell hallucinations). |
| Touch Drag-and-Drop | **Polyfilled** | Integrated touch-to-drag polyfill with 300ms press-and-hold threshold, allowing project and tab reordering without breaking scrolling. |

---

## 4. Backend Lifecycle & Provisioning

| Desktop Feature | Android Status | Adaptation / Implementation Mechanism |
|---|---|---|
| Standalone On-Device Engine | **Full Parity** | Debian ARM64 rootfs + patched `libproot.so` running genuine Linux `language_server` binary. |
| First-Launch Setup Screen | **Implemented** | `SettingThingsUpLoadingScreen` displays OLED pure black screen, "Setting Things Up...", and Google-colour glow progress bar during initial extraction. |
| Automatic Provisioning | **Implemented** | Verifies rootfs integrity, assets, and binaries on boot; starts Go language server on dedicated port (58888). |
