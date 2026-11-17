# Phase 4.2: Network Synchronization & Power Optimization

## 1. Cellular Radio Power Characteristics
Mobile cellular modems (5G / LTE) consume substantial power transitioning between Radio Resource Control (RRC) states:
- **RRC Connected (Active Data)**: High power consumption (~250-400 mW).
- **RRC Inactive / Tail Time**: Medium power consumption (~150 mW for 5-15 seconds after packet transfer).
- **RRC Idle**: Low power consumption (~5-10 mW).

Frequent polling loops (e.g. requesting status every 2 seconds) keep the radio permanently in RRC Connected or Tail state, resulting in rapid battery drain even when idle.

---

## 2. Implemented Network Optimizations
1. **Connection Pooling & HTTP/2 Multiplexing**:
   - `RemoteHubProxyHandler` utilizes a shared OkHttp client pool, maintaining persistent TLS connections and avoiding repetitive TCP three-way handshakes and TLS 1.3 negotiations.
2. **Incremental Account Sync**:
   - `HubDataSyncManager` uses timestamp and hash-based diffing. If no remote records have changed, payload size is 0 bytes.
3. **Throttling & Debouncing**:
   - Rapid UI navigation events are debounced with a 3,000ms minimum threshold.
4. **Battery-Aware Deferral**:
   - When device battery drops below 15% and is not charging, non-essential background sync requests are deferred for up to 60 seconds, allowing the radio to sleep.
5. **Streaming RPCs over Unary Polling**:
   - Model token generation and trajectory step events use Connect-RPC streaming (`chunked` transfer encoding), eliminating repetitive HTTP poll requests.
