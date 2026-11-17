# Phase 4.1: Android Native Integrations Architecture

## 1. Voice Recording & Speech-to-Text Pipeline
The application supports a dual-path voice transcription pipeline:

### Path A: Genuine Backend Audio Streaming (`StreamAudioTranscription`)
- The user taps the microphone icon in the chat input toolbar.
- The web application opens `POST /exa.language_server_pb.LanguageServerService/StreamAudioTranscription`.
- `RemoteHubProxyHandler.kt` establishes an unbuffered, zero-timeout OkHttp stream:
  ```kotlin
  val liveStreamingClient = client.newBuilder()
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .build()
  ```
- The Go backend receives the request and returns an active audio session ID.
- The browser streams microphone chunks (`SendAudioChunk`) without timing out or aborting.
- When the user stops recording, `EndAudioSession` is transmitted, completing transcription.

### Path B: Android Native `SpeechRecognizer` Bridge
- In addition to backend transcription, `JetskiWebViewHost.kt` provides native Android speech recognition through `@JavascriptInterface fun startSpeechToText()`:
  - Dynamically verifies `android.permission.RECORD_AUDIO` via `audioPermissionLauncher`.
  - Instantiates `android.speech.SpeechRecognizer` with `ACTION_RECOGNIZE_SPEECH`.
  - Receives live voice inputs on the Google Pixel 10 Pro.
  - On recognition results, invokes `window.__insertRecognizedSpeech(text)` in the WebView:
    ```javascript
    window.__insertRecognizedSpeech = function(text) {
      const editor = document.querySelector('[contenteditable="true"]');
      if (editor) {
        editor.focus();
        document.execCommand('insertText', false, text.trim() + ' ');
      }
    };
    ```
- This allows speech to appear directly in the Lexical editor as editable text, allowing users to review, format, or adjust text before sending.

---

## 2. Storage Access Framework (SAF) File & Directory Chooser
- Android does not permit arbitrary desktop file picker dialogs.
- `JetskiWebViewHost.kt` intercepts `WebChromeClient.onShowFileChooser`:
  - Launches an `Intent(Intent.ACTION_GET_CONTENT)` with `CATEGORY_OPENABLE` and MIME-type filtering.
  - Supports single-file and multi-file selection.
  - Automatically resolves Content URIs into accessible streams.
- For workspace folder creation, `window.dialog.showOpenDialog()` renders a mobile modal that allows instant selection of existing workspace folders or creation of new isolated directories inside `/tmp/gemini_app/workspaces/`.

---

## 3. Hardware-Backed Keystore Secret Storage
- API keys, OAuth refresh tokens, and CSRF secrets are encrypted using AES-256-GCM backed by the Android Hardware Security Module (Titan M2 security chip on Pixel 10 Pro).
- Keys are never written to unencrypted shared preferences or cleartext SQLite databases.

---

## 4. System Notifications (`NotificationManagerCompat`)
- Bridges web notification events into native Android Notification channels (`antigravity_notifications`).
- Supports silent and priority alerts with deep-link navigation back to the active conversation.
