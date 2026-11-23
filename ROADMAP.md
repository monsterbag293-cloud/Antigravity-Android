# Antigravity Android Port — Future Roadmap

This roadmap documents the post-v1.0.0 evolution, architectural enhancements, mobile-native optimizations, and upcoming capabilities planned for future releases of Antigravity for Android.

All pre-1.0 foundation milestones (embedded Jetski Web UI, gRPC-Web proxy, PRoot Linux engine, SAF workspace synchronization, OLED/Light mode system bars, and initial release verification) have been completed and shipped in **v1.0.0** and **v1.0.1**.

---

## 📱 Mobile UI, Gestures & Ergonomics
- [ ] **1. Haptic Feedback Engine**: Contextual tactile feedback on button clicks, code block copying, tool call completions, and drawer snapping.
- [ ] **2. Tablet & Foldable Multi-Column Layout**: Native adaptive layout switching from single-conversation to dual-pane master-detail view on tablets and foldables.
- [ ] **3. Dynamic Keyboard Height Resizing**: Predictive keyboard IME animation listener to eliminate viewport jump when the virtual keyboard opens or closes.
- [ ] **4. Swipe-to-Action on Conversation List**: Left-swipe to archive, right-swipe to pin or star active chats in the sidebar drawer.
- [ ] **5. Pull-to-Refresh & State Sync**: Pull-down gesture to trigger workspace file-tree rescans and Hub health checks.
- [ ] **6. In-App Code Syntax Theme Selector**: Allow picking light/dark code highlight themes (Monokai, GitHub Dark, One Dark, Dracula, Solarized).
- [ ] **7. Bottom Sheet Quick Settings**: Swipeable bottom sheet for quick-switching models, temperature, and agent modes without opening modal dialogs.
- [ ] **8. Picture-in-Picture (PiP) Task Monitor**: PiP mini-window showing streaming agent task execution while using other Android apps.
- [ ] **9. Customizable Action Toolbar**: User-configurable floating or fixed toolbar buttons for one-tap access to frequent prompts and tool shortcuts.
- [ ] **10. Pinch-to-Zoom on Artifacts & Images**: Multi-touch zoom and pan on generated artifacts, web preview captures, and diagram cards.

---

## 🤖 Local AI & On-Device Acceleration
- [ ] **11. On-Device Gemma 2B / 7B Inference**: Native local model fallback powered by MediaPipe GenAI and Qualcomm/Tensor NPU acceleration when completely offline.
- [ ] **12. Quantized GGUF / llama.cpp Android Bridge**: Native JNI bridge allowing users to run custom GGUF models directly within the device memory space.
- [ ] **13. Local Whisper Speech-to-Text**: High-accuracy offline audio transcription replacing system speech recognizer for zero-latency voice prompting.
- [ ] **14. On-Device Code Embeddings**: Local SQLite vector embeddings (via sqlite-vec) for lightning-fast semantic code search across large workspaces.
- [ ] **15. Adaptive Power & Thermal Throttler**: Dynamically downscales agent polling frequency and background tasks when device battery is low or thermal limits are reached.

---

## 🛠️ Developer Tooling, Terminal & Git Integration
- [ ] **16. Full-Screen Terminal Screen**: Dedicated terminal tab powered by a customizable virtual terminal emulator with custom ESC/Ctrl/Alt keyboard ribbons.
- [ ] **17. Built-in Git Authentication & Key Manager**: Native Android KeyStore-backed SSH private key importer and GitHub Personal Access Token (PAT) vault.
- [ ] **18. Visual Git Diff Viewer**: Mobile-optimized side-by-side or unified inline diff viewer for reviewing agent code modifications before committing.
- [ ] **19. Git Branch Switching & Commit Dialog**: Native bottom sheet for switching git branches, staging files, and typing commit messages.
- [ ] **20. Termux IPC Bridge**: Direct Unix Domain Socket connector allowing Antigravity to reuse packages and run commands inside an existing Termux environment.
- [ ] **21. Integrated Logcat & Stderr Debug Console**: In-app live log viewer displaying PRoot stdout/stderr and language server logs with filter and search.
- [ ] **22. Android Logcat Tool for Agents**: Let the autonomous agent read device logcat directly when debugging Android projects.
- [ ] **23. Quick Shell Snippets Bar**: Horizontal scrollable bar with customizable shell commands (`npm test`, `git status`, `gradle build`, `pytest`) above the chat input.

---

## 📂 Storage, Workspace & Cloud Sync
- [ ] **24. Two-Way Background SAF Synchronization**: Real-time bidirectional file sync between Android app sandbox and external user folders without manual triggers.
- [ ] **25. Direct GitHub / GitLab Repo Cloning**: Built-in shallow clone UI using native JGit/libgit2 to download repositories directly into workspaces.
- [ ] **26. Cloud Drive Mounts**: Direct read/write workspace integration with Google Drive, Nextcloud, and WebDAV providers.
- [ ] **27. Workspace Archive Export & Import**: Single-file `.agproj` export/import bundle containing workspace files, chats, history, and project instructions.
- [ ] **28. Multiple Isolated Workspace Profiles**: Switch between different rootfs work directories with independent environments and dependency sets.
- [ ] **29. Storage Cleaner & Cache Manager**: UI utility to safely clear PRoot package caches, temporary pip wheels, and old session cache files.

---

## 🔄 Connectivity & Remote Antigravity Features
- [ ] **30. ZeroConf / mDNS Desktop Auto-Discovery**: Automatically find and connect to desktop Antigravity instances on the local Wi-Fi network via Bonjour/mDNS.
- [ ] **31. Tailscale / WireGuard Integration**: Seamless secure remote connection to desktop work rigs over private mesh VPN networks.
- [ ] **32. WebSocket Connection Resiliency**: Auto-reconnect with exponential backoff and message deduplication when transitioning between Wi-Fi and mobile data.
- [ ] **33. Multi-Host Session Switcher**: Quick-switch profile bar between "Local Standalone (PRoot)", "Desktop Mac/PC", and "Remote Cloud VPS".
- [ ] **34. Remote Port Forwarding UI**: In-app port forward manager exposing local ports (e.g. 3000, 8080) to the Android host or browser preview.

---

## 🌐 In-App Browser & Webview Enhancements
- [ ] **35. Split Web Preview Tab**: Side-by-side or floating browser preview to test local web apps (React, Vite, Next.js) rendered directly on device.
- [ ] **36. Mobile DevTools & Inspect Element**: Embedded Eruda / vConsole overlay for inspecting DOM elements and console logs in the internal WebView.
- [ ] **37. Screenshot-to-Agent Shortcut**: Floating overlay button to capture device screenshots and attach them directly to the conversation prompt.
- [ ] **38. Custom User-Agent & Viewport Presets**: Quickly toggle browser preview between Desktop, iPad, and various smartphone resolutions.

---

## 🔒 Security, Privacy & Android System Integrations
- [ ] **39. Biometric App Lock**: Fingerprint and Face Unlock protection before opening the app or unlocking private API keys.
- [ ] **40. Android KeyStore Secure Encryption**: Hardware-backed encryption for all stored tokens, Gemini API keys, and server passwords.
- [ ] **41. App Shortcuts (Android Launcher Shortcuts)**: Long-press launcher icon to jump straight into "New Chat", "Open Workspace", or "Terminal".
- [ ] **42. Android Quick Settings Tile**: Notification panel quick toggle to start or stop the background Antigravity daemon service.
- [ ] **43. Rich Ongoing Notification Controls**: Notification with action buttons to "Pause Agent", "Abort Tool", or "View Diff" while agent runs in background.
- [ ] **44. Android Share Sheet Receiver**: Share text, code snippets, git URLs, and images directly into Antigravity from other Android apps.

---

## 🧩 Extensions, MCP & Automation
- [ ] **45. Mobile Model Context Protocol (MCP) Manager**: Visual marketplace and configuration manager for MCP servers running inside Android PRoot.
- [ ] **46. Android Intent Action MCP Server**: Dedicated MCP server exposing Android device capabilities (send SMS, open apps, read clipboard, query contacts) to agents.
- [ ] **47. Cron & WorkManager Scheduled Tasks**: Run automated prompt jobs and code checkups at scheduled intervals using Android's native `WorkManager`.
- [ ] **48. Voice-Activated Hands-Free Mode**: Continuous speech conversation loop allowing conversational hands-free agent collaboration.
- [ ] **49. Custom Agent Slash Command Editor**: In-app UI to define, edit, and organize custom slash commands (`/review`, `/test`, `/summarize`).
- [ ] **50. Homescreen Widgets**: Android home screen widgets displaying active task status, recent project shortcuts, and model token quota monitors.
