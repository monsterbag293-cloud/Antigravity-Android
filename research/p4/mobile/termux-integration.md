# Phase 4.1: Termux Environment Detection & AI Shell Awareness

## 1. Termux Integration & Detection
The application actively detects whether Termux (`com.termux`) is installed on the user's Android device:
- **Detection Method**:
  ```kotlin
  fun isTermuxAvailable(): Boolean {
      val termuxDir = File("/data/data/com.termux")
      val termuxSh = File("/data/data/com.termux/files/usr/bin/sh")
      val termuxBash = File("/data/data/com.termux/files/usr/bin/bash")
      return (termuxDir.exists() && termuxDir.canRead()) || termuxSh.exists() || termuxBash.exists()
  }
  ```
- **Shell Prioritization**:
  When executing local commands via `SandboxedCommandExecutor`:
  1. `/data/data/com.termux/files/usr/bin/bash` (if available and executable)
  2. `/data/data/com.termux/files/usr/bin/sh`
  3. `/system/bin/sh` (Android standard shell)
  4. `/bin/sh` (PRoot Debian environment)

---

## 2. AI Environment Awareness
When Cascade models execute commands or generate code, they must understand that they are running in an Android ARM64 Linux / POSIX shell environment, rather than Windows PowerShell or Command Prompt.
- `SandboxedCommandExecutor.getEnvironmentInfo()` provides the environment metadata:
  - `os`: `"android-linux"`
  - `arch`: `"aarch64"`
  - `shell`: `"sh/bash"`
  - `termux_installed`: `true` (verified on connected Google Pixel 10 Pro)
  - `system_description`: `"Android ARM64 Linux Environment (Termux / PRoot sandbox, POSIX sh/bash, do not use Windows PowerShell or CMD commands)"`
- This completely prevents the model from hallucinating `Get-ChildItem`, `dir /s`, or `cmd.exe /c` on Android.
