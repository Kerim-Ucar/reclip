# Reclip Architecture

Reclip is a modular, single-instance desktop utility built with **Java 25** and **JavaFX 25**. Platform-specific native integrations are isolated behind small interfaces and platform packages using Java's Foreign Function & Memory (FFM) API (`java.lang.foreign`), keeping core history, settings, and UI logic portable.

## Package Structure

```text
src/main/java/com/kerim/reclip/
  app/
    Launcher.java               # JavaFX launcher entry point
    ReclipApp.java              # Lifecycle, dependency wiring, executors, shutdown cleanup
    SingleInstanceLock.java     # File-channel lock preventing duplicate instances
  clipboard/
    ClipboardProvider.java      # Typed clipboard read/write interface
    AwtClipboardProvider.java   # AWT Clipboard + session PNG cache + macOS NSPasteboard fast path
    ClipboardObserver.java      # Background polling, SHA-256 deduplication, self-write suppression
    ClipboardPayload.java       # Typed payload (TEXT, IMAGE, FILES)
    ClipboardContentEvent.java  # Timestamped clipboard change event
    ClipboardTextEvent.java     # Legacy text-only event
  config/
    AppSettings.java            # Immutable settings record
    PersistedSnippet.java       # User-saved labeled snippet record
    SettingsRepository.java     # Settings persistence interface
    PropertiesSettingsRepository.java # ~/.reclip/settings.properties persistence
  history/
    ContentType.java            # TEXT, IMAGE, FILES
    ClipboardEntry.java         # Immutable history item with type-aware display formatting
    HistoryRepository.java      # Storage/query contract
    InMemoryHistoryRepository.java # Thread-safe in-memory store with search & pruning
    FileHistoryRepository.java  # Optional disk-backed history persistence
    HistoryService.java         # Deduplication, retention enforcement, listener notifications
  hotkey/
    GlobalShortcutService.java  # Global hotkey registration contract
    JNativeHookShortcutService.java # JNativeHook chord matcher with stale-key recovery
    OutsideClickDetector.java   # Native mouse press listener to dismiss the picker
    Shortcut.java               # Immutable multi-key chord value object
    ShortcutMatcher.java        # Native key/modifier normalization
    ShortcutRegistrationResult.java # Registration outcome + actionable error
  ipc/
    ReclipIpcServer.java        # Local Unix domain socket server (SHOW_PICKER / SHOW_PANEL)
    NativeMessagingHost.java    # Chrome Native Messaging bridge
  platform/
    PasteService.java           # Optional simulated-paste contract
    PasteResult.java            # Paste outcome + permission error guidance
    WindowFocusHelper.java      # Cross-platform focus capture/restoration & window styling
    macos/
      MacOsPasteService.java    # CoreGraphics Cmd+V event posting (requires Accessibility)
      MacOsPasteboard.java      # NSPasteboard changeCount & direct PNG write via FFM
      MacOsPermissions.java     # AXIsProcessTrusted check & System Settings deep link
      MacOsStatusBarTray.java   # Native NSStatusBar menu-bar integration
    windows/
      WindowsWindowHelper.java  # Win32 foreground focus, DWM dark title bars, DPI helpers
  ui/
    PickerWindow.java           # Floating cursor-positioned picker
    DashboardWindow.java        # Main panel (Recent Copies, Settings, warning banner)
    SettingsPane.java           # Scrollable Apple-styled settings form
    SettingsWindow.java         # Standalone Settings window
    ImagePreviewSupport.java    # Hover caret & scaled non-focusing image preview popup
    TrayController.java         # macOS status bar / AWT system tray facade
    WindowPositioner.java       # Multi-monitor cursor bounds clamping
```

## Threading & Concurrency Model

- **JavaFX Application Thread**: Owns all scene-graph mutations, `PickerWindow`, `DashboardWindow`, `SettingsWindow`, and `ImagePreviewSupport` popups.
- **`reclip-clipboard-observer` (daemon)**: Polls `ClipboardProvider.readContent()` on a fixed delay (`500 ms`). On macOS, `MacOsPasteboard.changeCount()` short-circuits unchanged polls in O(1) without locking AWT's `CClipboard`.
- **`reclip-clipboard-writer` / `reclip-auto-paste` (daemon)**: Offloads clipboard writes (`writeContent`, image loading, `NSPasteboard` writes) and delayed `Cmd+V` auto-paste so the JavaFX UI thread never blocks on OS clipboard locks.
- **JNativeHook Hook Thread**: Matches only the configured shortcut chord (never logging keystrokes) and dispatches picker toggles to the JavaFX thread.

## Data & Privacy Boundaries

- **No network calls or telemetry**: Everything runs locally.
- **Session image cache**: Captured clipboard images are written as content-addressed PNG files (`<sha256>.png`) inside a session temp directory (`reclip-images-*`) and deleted when Reclip quits.
- **History & settings**: Normal clipboard history stays in memory unless persistence is enabled; `~/.reclip/settings.properties` stores preferences and user-saved snippets unencrypted locally.
