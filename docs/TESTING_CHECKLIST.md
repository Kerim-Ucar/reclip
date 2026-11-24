# Reclip Manual & End-to-End Test Checklist

Automated unit and end-to-end integration tests run via `./gradlew test` (macOS) or `.\gradlew.bat test` (Windows).
Use this checklist to verify OS-level behavior on real hardware before tagging a release.

---

## 1. macOS Checklist (`aarch64` / `x86_64`)

### Permissions & Error Guidance
- [ ] Launch Reclip without Accessibility permission: verify global shortcut failure or auto-paste warning banner appears in the Dashboard with **Accessibility Settings** and **Dismiss** buttons.
- [ ] Click **Open Settings** in Settings → Auto-paste: verify macOS opens **System Settings → Privacy & Security → Accessibility**.
- [ ] Grant Accessibility permission and click **Re-check**: status updates to `macOS Accessibility: Granted`.
- [ ] Quit and relaunch after rebuilding/re-signing: if macOS invalidates trust, remove Reclip (`−`) and re-add (`+`) in Accessibility.

### Clipboard Monitoring & Typed Content
- [ ] Copy plain text in Safari/Notes: appears at top of picker and Dashboard without duplicates.
- [ ] Copy a high-resolution image / Retina screenshot (`Cmd+Ctrl+Shift+4` or Preview): appears as `[Image] Image (W×H)` without freezing the UI.
- [ ] Hover over an `[Image]` entry in the picker and Dashboard: `›` caret appears on the right and a scaled-down image preview popover opens without stealing focus; moving away hides both.
- [ ] Copy one or more files in Finder (`Cmd+C`): appears as `[Files] File: ...` / `[Files] Files (N): ...`, and selecting it restores the file list to the clipboard.
- [ ] Pause Monitoring from the menu-bar icon: new copies are ignored until resumed.

### Picker, Focus & Optional Auto-Paste
- [ ] Trigger global shortcut (`Ctrl+Shift+Space` by default) across multiple monitors and macOS Spaces/full-screen apps: picker opens near the cursor without switching Spaces.
- [ ] Navigate with `Up`/`Down`/`Home`/`End`, pin with `Cmd+P`, delete with `BackSpace`, dismiss with `Esc` or clicking outside.
- [ ] With **Auto-paste** disabled (default): selecting an item updates the clipboard and restores focus to the previous app without pasting.
- [ ] Enable **Auto-paste** in Settings and click **Apply**: selecting an item in the picker hides the picker, restores focus to the previous app (e.g. TextEdit, Notes, Terminal), and pastes (`Cmd+V`).

### Lifecycle & Integrations
- [ ] Launch a second instance while Reclip is running: existing Dashboard comes to front and the second process exits cleanly.
- [ ] Quit from the menu-bar icon: verify session temp images (`reclip-images-*`) are cleaned up and no background process remains.
- [ ] Optional: verify Chrome extension (`extensions/chrome/`) and macOS Services (`extensions/macos/`) open the picker/panel.

---

## 2. Windows 10 / 11 Checklist (`x86_64`)

### Startup, Tray & Theme
- [ ] Run as a standard (non-admin) user: no elevation prompt required.
- [ ] System tray icon appears in the notification area; left-click opens Dashboard, right-click opens menu (`Show Clipboard`, `Settings...`, `Pause Monitoring`, `Clear History`, `Quit Reclip`).
- [ ] Dashboard and Settings windows render with dark DWM title bars.

### Global Shortcut & Multi-Monitor DPI
- [ ] Trigger global shortcut (`Ctrl+Shift+Space` by default) on primary and secondary displays with different DPI scaling (e.g. 100% and 150%): picker opens at the logical cursor position within screen bounds.
- [ ] Click outside the picker on a high-DPI monitor: picker dismisses cleanly.
- [ ] Lock screen (`Win+L`) or UAC prompt while holding a modifier, then unlock: shortcut does not get stuck.

### Clipboard, Privacy, Auto-Paste & Packaging
- [ ] Copy text, an image (Snipping Tool), and files (File Explorer): verify capture, preview, and re-selection.
- [ ] Enable **Auto-paste** in Settings and click **Apply**: selecting an item in the picker hides the picker, restores focus to the previous app (e.g. Notepad, Terminal), and pastes (`Ctrl+V`).
- [ ] Copy from an excluded app (e.g. `1Password`, `KeePassXC`, or a process added to **Excluded apps**): verify copy is ignored, and concealed formats (`ExcludeClipboardContentFromMonitorProcessing` / `CanIncludeInClipboardHistory=0`) are masked and auto-expired.
- [ ] Verify Settings persistence in `%USERPROFILE%\.reclip\settings.properties` (AES-256-GCM encrypted) and DPAPI-protected `%USERPROFILE%\.reclip\.key` with owner-only NTFS ACL.
- [ ] Build `.\gradlew.bat packageWinApp` and launch `build\dist\Reclip\Reclip.exe`: verify startup, tray, shortcut, auto-paste, and clean exit.
- [ ] Build `.\gradlew.bat packageWinExe`: verify `build\dist\Reclip-1.0.0.exe` installer is generated.
- [ ] Optional: verify Chrome native host (`extensions\chrome\install-windows-host.ps1`).
