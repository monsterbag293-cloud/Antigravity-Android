# Phase 4.2: Background Execution & Lifecycle Management

## 1. Android Lifecycle Compliance
Mobile apps must never maintain persistent, unthrottled background CPU execution without an active user notification or explicit user intent. The Antigravity port strictly adheres to Android 14/15 background execution constraints:

### Lifecycle States:
- **`ON_RESUME`**:
  - `webView.onResume()` and `webView.resumeTimers()` are called.
  - Checks if the user authenticated in an external browser; triggers a single non-blocking status refresh.
- **`ON_PAUSE`**:
  - `webView.onPause()` suspends JavaScript event loops and RAF (RequestAnimationFrame) renders.
  - Active audio capture sessions are immediately halted.
- **`ON_STOP` / Screen Off**:
  - WebView execution stops completely.
  - Android PowerManager allows CPU cores to enter sleep state.
- **`ON_DESTROY`**:
  - If the user swipes the app away from recents (`isFinishing == true`), all child processes, sockets, and PRoot instances are cleanly destroyed.

---

## 2. Wake Lock Audit
- A complete grep of the workspace confirms that zero `PARTIAL_WAKE_LOCK` or `SCREEN_BRIGHT_WAKE_LOCK` flags are held during idle states.
- The device is permitted to enter Android Doze mode and App Standby Buckets normally.
