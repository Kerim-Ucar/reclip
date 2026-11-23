# Reclip macOS Services Integration

Installs **Open Reclip Clipboard** as a macOS Quick Action in `~/Library/Services/` so it appears under **Services** in Apple's native right-click menu (e.g. Safari, Notes, TextEdit, Finder).

## Install

```bash
./extensions/macos/install-macos-service.sh
```

Ensure Reclip is running (`./gradlew run`), then right-click in any native macOS app and choose **Services → Open Reclip Clipboard**.
