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
