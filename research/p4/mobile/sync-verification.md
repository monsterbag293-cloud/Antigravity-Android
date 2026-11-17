# Phase 4.1: Google-Account-Based Bidirectional Synchronisation Verification

## 1. Architecture & Protocol
Account synchronization in the Android port is performed directly through the authenticated Google Antigravity account without requiring an active desktop connection.

### Synchronization Components:
1. **User Identity & Tier Verification**:
   - Dispatches `GetUserStatus` Connect-RPC to retrieve Google identity, email, and subscription plan (`teamsTier`).
   - Automatically provisions model configs corresponding to the user's plan.
2. **Usage Quota Summaries**:
   - Calls `RetrieveUserQuotaSummary` to synchronize real-time rate limits across:
     - `Gemini Models` (Weekly & 5-hour rolling windows)
     - `Claude and GPT models` (Weekly & 5-hour rolling windows)
     - `Gemini 4 Argon` (Weekly window)
3. **Projects & Workspaces**:
   - Account-level project metadata is retrieved and indexed.
   - For local storage, each project maps to a secure isolated folder in `/tmp/gemini_app/workspaces/<project-name>`.
4. **Cascade Trajectories & Conversations**:
   - `GetTrajectoriesSnapshot` retrieves remote trajectory IDs.
   - Steps and turns are synchronized incrementally, avoiding redundant payloads.
   - Conflict resolution: If local edits conflict with remote records, `.conflict` files are created rather than overwriting unsaved work.

---

## 2. Battery-Aware Throttling (Phase 4.2 Integration)
To prevent battery drain during poor network conditions or low battery states:
- If `BatteryManager.EXTRA_LEVEL < 15` and the device is discharging, automatic background synchronization is deferred for up to 60 seconds.
- An in-flight lock (`syncMutex`) prevents overlapping sync operations.
- Minimum interval of 3,000ms prevents thrashing when UI tabs change.
