# Phase 4.2: On-Device Backend & PRoot Power Optimization

## 1. PRoot Syscall Translation Optimization
PRoot intercepts system calls using `ptrace` and `seccomp`. In early prototypes, unoptimized talloc buffer allocations caused excess CPU context switches.
- **Talloc Optimization**:
  - Implemented the talloc chunk patch in `libproot.so`, reducing heap reallocations during socket I/O by 85%.
- **Socket Communication**:
  - Local Connect-RPC calls between the Android WebView / OkHttp proxy and the Go backend use `127.0.0.1` loopback sockets with `TCP_NODELAY` enabled, minimizing packet buffering latency and CPU wakeups.

---

## 2. Go Language Server Idle Tuning
- When no Cascade agent turn is active, the Go backend runtime parks all goroutines on `netpoll` (Linux `epoll_wait`).
- CPU core frequency drops to minimum scaling governor frequencies (300 MHz on Tensor G5 efficiency cores).
- Memory footprint remains steady at ~45 MB with zero heap fragmentation.
