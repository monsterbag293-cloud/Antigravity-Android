# Antigravity Android Port - Project Roadmap

This roadmap documents the architectural milestones, core engine components, native Android features, and upcoming capabilities for the standalone Android port of Google Antigravity.

---

## Phase 0: Standalone Engine & Jetski Web Frontend
- [x] Extract and package embedded Jetski Web UI bundle from desktop binary
- [x] Implement in-process Android Web Resource Asset Loader (`JetskiBundleLoader`)
- [x] Custom local scheme / localhost handler (`http://127.0.0.1:42100` / `android.asset`)
- [x] Intercept gRPC-Web binary frames and route to local handlers
- [x] Inject mobile touch event bridges and responsive viewport meta tags
- [x] Fix mobile touch scroll teleportation bugs in chat message history
- [x] Wrap and style model quota badges to prevent text clipping on narrow mobile screens

---

## Phase 1: Local Model Provider & Agent Engine
- [x] Built-in fallback Gemini API client (`GeminiFallbackEngine`)
- [x] Streaming agent updates (`StreamAgentStateUpdates`) with real-time token delivery
- [x] Tool execution dispatch pipeline (read file, edit file, write file, grep, directory list)
- [x] Bash command execution bridge via local Linux environment
- [x] Synthesize model quota metrics (`GetModelCatalog`) when remote metrics are missing
- [x] Offline persistent cache store for Zero-Project conversations (`ConversationCacheStore`)
- [x] Auto-restore cached zero-project sessions on fresh app launch

---

## Phase 2: On-Device Linux Runtime (PRoot + Python 3.12)
- [x] Integrated ARM64 Linux rootfs extraction and provisioning
- [x] PRoot binary integration for unprivileged chroot execution on Android
- [x] Python 3.12 hermetic runtime support inside PRoot environment
- [x] Dynamic linking and libc fixups (`/linker64`, `/system/bin` bridge)
- [x] Virtual `/dev`, `/proc`, and Android internal storage bindings
- [x] Real-time PTY terminal output streaming to Jetski UI

---

## Phase 3: Desktop Language Server & Provisioning
- [x] Direct native `language_server` binary launcher (`LocalBackendLifecycleManager`)
- [x] Remote Hub Mode client (`RemoteHubProxyHandler`) to connect to desktop Antigravity instance
- [x] Automatic provisioning and download manager for `language_server` Linux-arm64 binaries
- [x] Upgraded engine compatibility to Antigravity Desktop `2.32.1`
- [x] Dynamic binary slicing & embedded ZIP boundary discovery for runtime assets
- [x] Token refresh and Google OAuth credential relay

---

## Phase 4: Native Android System Integrations
- [x] Android Storage Access Framework (SAF) folder picker for importing external repositories
- [x] Direct file copy and synchronization from external storage to app workspace
- [x] Pure Black OLED theme with dynamic system bar adaptation
- [x] Edge-to-edge system insets handling (Status bar, Navigation bar, Display Cutouts)
- [x] Modal dialog boundary clamping and responsive mobile padding (Project Settings, Global Settings)
- [x] Native Speech-to-Text (STT) voice input integration
- [x] Dynamic recording indicator and audio waveform visualization overlay in chat input
- [x] Back button interception and modal dismiss gesture handlers

---

## Phase 5: Verification & Testing
- [x] Unit test suite covering gRPC-Web serialization and proto framing
- [x] Offline cache persistence and snapshot restoration tests
- [x] Quota fallback and catalog synthesis tests
- [x] Live on-device ADB regression testing across orientation and settings flows
- [x] Release packaging pipeline (`dist/antigravity-android-preview.apk`)

---

## Phase 6: Future Milestones & Upcoming Features
- [ ] Direct Termux socket & environment bridge
- [ ] On-device local SLM inference (MediaPipe / Gemma 2B via NPU/GPU acceleration)
- [ ] Background persistent daemon service for continuous long-running tasks
- [ ] Git credentials manager & native SSH key importer in Android Settings
- [ ] Biometric app lock (Fingerprint / Face Unlock) for secure credential storage
- [ ] Multi-window and Android tablet split-screen layout optimizations
