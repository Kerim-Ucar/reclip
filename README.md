# Reclip

A local-first, privacy-focused clipboard history manager built with **Java 25** and **JavaFX 25**. Reclip runs quietly in the background, monitors plain-text clipboard changes, and lets you recall recent or saved snippets from a floating cursor-positioned picker or the main dashboard panel.

## Screenshots

### Main Panel & Recent Copies
![Reclip Main Panel](ui.png)

### Cursor Picker & Settings
![Reclip Cursor Picker and Settings](mouse_and_settings.png)

## Current Features

- **Background Clipboard Monitoring**: Quietly tracks plain-text copies in the background, avoids duplicate entries, and includes a **Pause Monitoring** toggle in the menu bar / system tray.
- **Floating Cursor Picker**: Opens right near your mouse cursor across multiple monitors using a configurable global shortcut (`Ctrl + Shift + Space` by default).
  - Navigate with `Up` / `Down` / `Home` / `End`, copy with `Enter` or click, dismiss with `Esc` or clicking outside, delete with `BackSpace` / `Delete`, and pin/unpin with `Cmd+P` (`Ctrl+P`) or right-click.
- **Main Dashboard & Settings**:
  - Runs as a single-instance menu-bar / system-tray app with quick access to recent copies and settings.
  - **Recent Copies** tab with live search (`Cmd+F` / `Ctrl+F`), keyboard navigation, and right-click actions (`Copy`, `Pin`/`Unpin`, `Delete`, `Clear Unpinned`).
  - **Custom Shortcut Recorder**: Set any multi-key shortcut combination.
  - **Popup Copy Limit**: Choose how many recent copies (`1–15`) appear in the floating picker.
  - **Labeled Persisted Snippets**: Save permanent snippets with optional display labels (e.g. `Home Address` copies `123 Main St`).
- **Optional Integrations**:
  - **Chrome Extension** (`extensions/chrome/`): Adds **Open Reclip Clipboard** to Chrome's right-click menu and toolbar.
  - **macOS Services** (`extensions/macos/`): Adds **Open Reclip Clipboard** to the macOS right-click **Services** menu.

## Future Features

- **Encrypted Local Persistence**: Optional encryption at rest for saved history and persisted snippets (currently normal clipboard history stays in memory only, and saved settings/snippets in `~/.reclip/settings.properties` are unencrypted).
- **Optional Auto-Paste**: Automatically paste the selected item into your previous app instead of only copying it.
- **Rich Clipboard Formats**: Support for images and files in addition to plain text.
- **App Exclusion Rules**: Automatically ignore copies from password managers or chosen apps.

## Architecture

```text
src/main/java/com/kerim/reclip/
  app/             # Launcher, JavaFX lifecycle, single-instance file lock
  clipboard/       # ClipboardProvider abstraction, AWT implementation, polling observer
  config/          # AppSettings, PersistedSnippet, PropertiesSettingsRepository
  history/         # ClipboardEntry, HistoryRepository, InMemory/File repositories, HistoryService
  hotkey/          # GlobalShortcutService, JNativeHook chord matching, OutsideClickDetector
  ipc/             # ReclipIpcServer (Unix domain socket) & Chrome NativeMessagingHost
  platform/        # macOS AppKit & Windows Win32/DWM focus, titlebar, and tray adapters
  ui/              # PickerWindow, DashboardWindow, SettingsPane, SettingsWindow, TrayController
```

## Install (macOS)

Download **[Reclip-1.0.0.dmg](https://github.com/Kerim-Ucar/reclip/releases)**, open the `.dmg`, and drag **Reclip.app** into **Applications** (no Java or Gradle setup required).

> **macOS Permissions:** Allow Reclip under **System Settings → Privacy & Security → Accessibility** (and **Input Monitoring** if asked) so global shortcuts work. If you update the app and it asks again, remove Reclip from that list (`−`) and add it back (`+`).

## Building from Source

### Requirements

- **Java 25** (JDK 25)
- Gradle Wrapper (`./gradlew` on macOS / `.\gradlew.bat` on Windows, included)

### Running, Testing & Packaging

#### macOS

```bash
# Run unit tests
./gradlew test

# Run the application
./gradlew run

# Build build/dist/Reclip.app
./gradlew packageMacApp

# Build build/dist/Reclip-1.0.0.dmg
./gradlew packageMacDmg
```

#### Windows 10 / 11

```powershell
# Run unit tests
.\gradlew.bat test

# Run the application
.\gradlew.bat run

# Build self-contained build\dist\Reclip\Reclip.exe app image
.\gradlew.bat packageWinApp

# Build per-user Windows .exe installer (requires WiX Toolset on PATH)
.\gradlew.bat packageWinExe
```

## Optional Integrations

- **Chrome Right-Click Menu (macOS & Windows)**: See [extensions/chrome/README.md](extensions/chrome/README.md).
- **macOS Services Menu**: See [extensions/macos/README.md](extensions/macos/README.md).

## Privacy & Storage

- **Local-first**: No telemetry, analytics, cloud sync, or network calls.
- **In-memory history**: Normal clipboard history is kept in memory only and cleared when you quit.
- **Settings & persisted snippets**: Saved locally in `~/.reclip/settings.properties` and are **not encrypted by default**. Use **Pause Monitoring** before copying passwords or sensitive data.

## Platform Status

- **macOS (`aarch64`)**: Built and tested on macOS with Java 25 and JavaFX 25.
- **Windows 11 (`x86_64`)**: Built and tested on Windows 11 with Java 25 and JavaFX 25 (system tray, global shortcuts, DPI-aware cursor picker, dark DWM title bars, foreground focus restoration, and `jpackage` `Reclip.exe` app image).

## License

This project is licensed under the [MIT License](LICENSE).
