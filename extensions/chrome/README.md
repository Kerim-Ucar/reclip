# Reclip Chrome Extension (Native Messaging)

Adds **Open Reclip Clipboard** to Chrome's right-click context menu using Chrome Native Messaging and a local Unix domain socket (no HTTP server).

## Setup (macOS)

1. Build Reclip so the native host classes exist:
   ```bash
   ./gradlew classes
   ```
2. Open `chrome://extensions` in Chrome, enable **Developer mode**, click **Load unpacked**, and select `extensions/chrome`.
3. Copy the Extension ID shown in Chrome and register the Native Messaging host:
   ```bash
   ./extensions/chrome/install-macos-host.sh <YOUR_EXTENSION_ID>
   ```
4. Ensure Reclip is running (`./gradlew run`), then right-click anywhere in Chrome and choose **Open Reclip Clipboard**.

## Setup (Windows 10/11)

1. Build Reclip so the native host classes exist:
   ```powershell
   .\gradlew.bat classes
   ```
2. Open `chrome://extensions` in Chrome, enable **Developer mode**, click **Load unpacked**, and select `extensions\chrome`.
3. Copy the Extension ID shown in Chrome and register the Native Messaging host (per-user `HKCU`, no administrator privileges required):
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\extensions\chrome\install-windows-host.ps1 <YOUR_EXTENSION_ID>
   ```
4. Ensure Reclip is running (`.\gradlew.bat run` or `Reclip.exe`), then right-click anywhere in Chrome and choose **Open Reclip Clipboard**.
