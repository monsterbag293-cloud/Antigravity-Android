# Phase 4.1: Automatic Backend Installation & Provisioning Pipeline

## 1. On-Device PRoot Container & Runtime
The Android port achieves standalone independence by running the genuine ARM64 Linux `language_server` binary inside an unprivileged PRoot user-space environment.

### Components:
- **Engine Binary**: `libproot.so` and `libproot_loader.so` packaged into native library directory (`context.applicationInfo.nativeLibraryDir`).
- **Filesystem Root (`rootfs`)**: Debian ARM64 root filesystem stored in `context.filesDir/rootfs`.
- **Target Binary**: `language_server` (compiled Linux ELF ARM64 binary).
- **Socket / Port**: Connect-RPC HTTP/1.1 and HTTP/2 daemon listening on localhost (default port 58888).

---

## 2. Automatic Provisioning & First-Launch Experience
When the app launches, `LocalBackendLifecycleManager` checks the status of the local environment:
1. **Integrity Check**:
   - Inspects `rootfs` directory. If missing or incomplete, triggers `BackendProvisioningManager.provision()`.
2. **First-Launch Loading Screen (`SettingThingsUpLoadingScreen`)**:
   - Displays an OLED pure black screen (`#000000`).
   - "ANTIGRAVITY" header with Google styling.
   - Subtitle: "Setting Things Up...".
   - Smooth linear progress indicator showing extraction progress (0% - 100%) and current extraction phase.
3. **Automatic Transition**:
   - Once extraction, permissions (`chmod +x`), and symlinks are established, the state transitions from `Provisioning` -> `Starting` -> `Running`.
   - The loading screen automatically dismisses, revealing the fully operational Jetski web interface.
4. **Subsequent Boots**:
   - If `rootfs` is already provisioned, `provision()` returns immediately in < 50ms, skipping the setup screen and booting straight into the conversation interface.
