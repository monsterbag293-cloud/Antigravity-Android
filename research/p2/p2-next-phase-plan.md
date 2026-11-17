# Phase P3: Implementation & Validation Plan — Dual-Mode Antigravity Integration

## Overview
Phase P3 builds upon the findings of Phase P2 to implement the **Dual-Mode Hybrid Architecture**. It enables genuine Antigravity AI execution by pairing with a desktop daemon while establishing robust native SQLite persistence and dynamic model routing on Android.

---

### Step P3.1: Remote Hub Pairing & Network Relay Transport (Mode A)
- **Objective:** Allow the Android Jetski Web frontend to connect directly to a running desktop `language_server.exe` instance using the discovered port and CSRF token.
- **Files Affected:**
  - `core/network/src/main/kotlin/dev/agentic/core/network/RemoteHubClient.kt`
  - `core/network/src/main/kotlin/dev/agentic/core/network/LanguageServerGrpcWebHandler.kt`
  - `app/src/main/kotlin/dev/agentic/antigravity/ui/HostPairingScreen.kt`
  - `app/src/main/res/xml/network_security_config.xml`
- **Dependencies:** Desktop `language_server.exe` running with `--subclient_type=hub`.
- **Implementation Details:**
  1. Add pairing settings UI in Android app allowing input of desktop host IP, port, and CSRF token (or scanning a QR code).
  2. Implement `RemoteHubProxyHandler` in `LanguageServerGrpcWebHandler`: when paired mode is active, incoming Connect-RPC / gRPC-Web requests from WebView are proxied to `https://<desktop_ip>:<port>/` with header `x-codeium-csrf-token: <token>`.
  3. Configure Android WebView `onReceivedSslError` to permit self-signed certificates for the configured desktop host IP.
- **Acceptance Criteria:**
  - Entering desktop hub details connects successfully.
  - Jetski Web frontend renders conversation history and active agent status from desktop daemon.
  - Sending a message executes the original desktop agent and streams responses back.
- **Tests Required:**
  - `RemoteHubProxyTest.kt` (offline MockWebServer testing gRPC-Web relay with CSRF headers).
  - Manual verification on Android device on local Wi-Fi.
- **Known Risks:** Network latency or firewall blocks on local LAN; self-signed certificate rejection if SSL handler is misconfigured.

---

### Step P3.2: SQLite Trajectory & Step Database Persistence (Mode B)
- **Objective:** Replace in-memory transient step storage with persistent SQLite databases matching the desktop schema (`~/.gemini/antigravity/conversations/<id>.db`).
- **Files Affected:**
  - `core/database/src/main/kotlin/dev/agentic/core/database/TrajectorySqliteHelper.kt`
  - `core/database/src/main/kotlin/dev/agentic/core/database/StepEntity.kt`
  - `core/network/src/main/kotlin/dev/agentic/core/network/AgentRuntime.kt`
  - `core/network/src/main/kotlin/dev/agentic/core/network/LanguageServerGrpcWebHandler.kt`
- **Dependencies:** Android SQLiteDatabase, Protobuf Java runtime.
- **Implementation Details:**
  1. Create `TrajectorySqliteHelper.kt` executing exact DDL:
     - `CREATE TABLE steps (idx INTEGER PRIMARY KEY, step_type INTEGER, status INTEGER, has_subtrajectory NUMERIC, metadata BLOB, error_details BLOB, permissions BLOB, task_details BLOB, render_info BLOB, step_payload BLOB, step_format INTEGER)`
     - `CREATE TABLE trajectory_meta (trajectory_id TEXT PRIMARY KEY, cascade_id TEXT, trajectory_type INTEGER, source INTEGER)`
     - `CREATE TABLE trajectory_metadata_blob (id TEXT PRIMARY KEY, data BLOB)`
  2. Update `AgentRuntime.kt` to persist every agent step, tool call, and tool output immediately to SQLite upon state changes.
  3. Wire `GetCascadeTrajectory` RPC to read steps directly from the SQLite database.
- **Acceptance Criteria:**
  - Conversations and steps persist across complete application termination and relaunch.
  - Exported desktop `.db` files can be placed in Android app storage and viewed seamlessly in the UI.
- **Tests Required:**
  - `TrajectorySqlitePersistenceTest.kt` (verifies table creation, step insert/query, and binary blob round-trip).
- **Known Risks:** Blob serialization discrepancies if Protobuf schema definitions differ between Go and Kotlin.

---

### Step P3.3: Conversation Summaries & FTS5 Full-Text Search
- **Objective:** Implement `conversation_summaries.db` with FTS5 search indexing for conversation listing and fast searching.
- **Files Affected:**
  - `core/database/src/main/kotlin/dev/agentic/core/database/ConversationSummariesHelper.kt`
  - `core/network/src/main/kotlin/dev/agentic/core/network/LanguageServerGrpcWebHandler.kt`
- **Dependencies:** Android SQLite FTS5 module.
- **Implementation Details:**
  1. Implement `ConversationSummariesHelper.kt` with tables:
     - `conversation_summaries` (`conversation_id`, `title`, `preview`, `step_count`, `last_modified_time`, `workspace_uris`, `status`, etc.)
     - `conversations_fts` (`USING fts5(conversation_id UNINDEXED, ..., user_body, agent_body, tokenize='unicode61 remove_diacritics 2')`)
  2. Wire `GetConversationSummaries` to query the table ordered by `last_modified_time DESC`.
  3. Wire `SearchConversations` to query `conversations_fts` using `MATCH ?`.
- **Acceptance Criteria:**
  - Past conversations appear in the sidebar drawer with correct titles, timestamps, and message counts.
  - Searching keywords in the conversation search bar returns matching conversations instantly.
- **Tests Required:**
  - `ConversationSummariesTest.kt` (summary insertion, updating, and FTS5 search queries).
- **Known Risks:** FTS5 syntax variations across older Android API levels (mitigate by ensuring minimum SDK >= 26).

---

### Step P3.4: Dynamic Model Catalog & Model Tier Routing
- **Objective:** Connect the original Jetski model selector UI to dynamic backend configurations supporting model tiers.
- **Files Affected:**
  - `core/network/src/main/kotlin/dev/agentic/core/network/LanguageServerGrpcWebHandler.kt`
  - `core/network/src/main/kotlin/dev/agentic/core/network/GeminiModelProvider.kt`
  - `core/network/src/main/kotlin/dev/agentic/core/network/AgentRuntime.kt`
- **Dependencies:** Protobuf schemas for `CascadeModelConfigData`.
- **Implementation Details:**
  1. Populate `GetCascadeModelConfigsResponse` with client model configs:
     - Flash-Lite tier (`MODEL_TIER_FLASH_LITE` -> `gemini-3.5-flash-lite`, low thinking)
     - Flash tier (`MODEL_TIER_FLASH` -> `gemini-2.5-flash`)
     - Pro tier (`MODEL_TIER_PRO` -> `gemini-2.5-pro`)
  2. In `SendUserCascadeMessage`, extract the user-selected model from `model_preference` and route to the corresponding model configuration in `GeminiModelProvider`.
  3. Persist selection to `antigravity_state.pbtxt` (`last_selected_agent_model`).
- **Acceptance Criteria:**
  - Jetski UI displays model options in the dropdown picker.
  - Selecting a model changes the active model used for subsequent agent generation.
- **Tests Required:**
  - `ModelSelectorConfigTest.kt` (RPC serialization and model routing validation).
- **Known Risks:** Invalid model names causing upstream 404/400 errors (mitigated by strict model ID validation).

---

### Step P3.5: Real-Time Unbuffered Streaming Pipeline & Turn Diffs
- **Objective:** Implement true unbuffered gRPC-Web streaming for real-time thought circulation and implement `GetTurnDiff` file modification tracking.
- **Files Affected:**
  - `core/network/src/main/kotlin/dev/agentic/core/network/LanguageServerGrpcWebHandler.kt`
  - `tools/local/src/main/kotlin/dev/agentic/tools/local/LocalWorkspaceFileTool.kt`
- **Dependencies:** Kotlin Coroutines `Channel`, OkHttp / WebResourceResponse piped stream.
- **Implementation Details:**
  1. Build a `PipedInputStream` streaming bridge: as tokens, thoughts, and tool events arrive from `GeminiModelProvider`, format them into 5-byte gRPC-Web data frames and write directly to `PipedOutputStream`.
  2. Implement before/after file snapshotting in `LocalWorkspaceFileTool`: calculate unified diffs upon file edits and store in memory/SQLite.
  3. Implement `GetTurnDiff` RPC returning the unified diff for display in the Cascade chat view.
- **Acceptance Criteria:**
  - Tokens and thoughts render smoothly in real time without batching delays.
  - Code changes made by the agent display interactive visual diffs in the chat UI.
- **Tests Required:**
  - `StreamingFramingTest.kt` (verifies continuous byte output and trailer framing).
  - `TurnDiffTest.kt` (verifies diff calculation between original and edited files).
- **Known Risks:** Deadlock in piped streams if consumer does not read while producer writes (mitigate by using proper buffer sizing and coroutine dispatchers).

---

### Step P3.6: Host Bridge & Electron Preload Emulation
- **Objective:** Implement native Android JavaScript interface replacing Electron preload APIs for file dialogs, notifications, and safe-area adjustments.
- **Files Affected:**
  - `app/src/main/kotlin/dev/agentic/antigravity/ui/AntigravityHostBridge.kt`
  - `app/src/main/kotlin/dev/agentic/antigravity/ui/JetskiWebView.kt`
- **Dependencies:** Android Jetpack Activity Result APIs (`OpenDocumentTree`).
- **Implementation Details:**
  1. Implement `AntigravityHostBridge` exposing `@JavascriptInterface` methods:
     - `dialog:open-workspace` -> launches Android SAF folder picker.
     - `notification:send` -> posts Android system notification.
     - `window:set-title-bar-overlay` -> adjusts window padding for Android system insets.
  2. Inject bridge into WebView via `addJavascriptInterface(bridge, "AntigravityAndroidBridge")` with companion preload shims mapping to `window.dialog`, `window.electronNative`, and `window.agent`.
- **Acceptance Criteria:**
  - Clicking "Open Workspace" opens the native Android folder picker and mounts the selected directory.
  - Agent background task completions trigger native Android notifications.
- **Tests Required:**
  - `HostBridgeInterfaceTest.kt` (verifies JSON parsing and interface method dispatch).
- **Known Risks:** Activity recreation during file picker interaction (mitigate by properly handling Android Activity lifecycle).
