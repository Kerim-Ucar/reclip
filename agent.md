# Clipboard Manager — Agent Instructions

## Project purpose
Build a polished, privacy-first, cross-platform clipboard history manager for **macOS and Windows** using Java. The application runs quietly in the background. A configurable global keyboard shortcut opens a small floating, searchable picker. Selecting an entry copies it to the system clipboard; optional automatic paste inserts it into the previously focused app.

The goal is a usable desktop utility and a well-engineered SWE portfolio project. Favor reliability, responsiveness, clear architecture, and testability over unnecessary abstractions.

## Technology choices
- **Language:** Java 25; use Gradle with the Gradle Wrapper.
- **UI:** JavaFX for the popup, settings, and history views.
- **Global keyboard shortcuts:** JNativeHook, subject to platform permission requirements and compatibility testing.
- **Clipboard:** `java.awt.datatransfer` where supported. Isolate clipboard access behind an interface so platform-specific implementation can replace it.
- **Storage:** SQLite via JDBC. Use prepared statements and schema migrations.
- **Packaging:** Start with a normal JVM build. Use `jpackage` for self-contained Windows/macOS installers. Investigate GraalVM Native Image + GluonFX as an optional later optimization, not a prerequisite for the app.
- **Tests:** JUnit 5, with mocks/fakes for OS-facing code.
- **CI:** GitHub Actions for build and unit tests on macOS and Windows when feasible.

Do not assume JavaFX, AWT, JNativeHook, SQLite JNI/native libraries, and GluonFX are automatically compatible in Native Image. Prove a minimal integration spike before committing to that packaging strategy.

## Product behavior
1. Run as a single-instance background application with a tray/menu-bar entry and an explicit Quit action.
2. Monitor clipboard changes while the app runs. Initially, use a modest configurable polling interval; avoid busy waiting. Consider native OS notifications only if warranted by performance and reliability.
3. Store text snippets with timestamp, content type, and optional pin status. Support deduplication and a configurable retention limit.
4. Invoke a fast floating picker via a user-configurable global shortcut. It should appear on the current display near the cursor or in a predictable position.
5. Search clipboard history instantly; navigate with Up/Down, choose with Enter, and dismiss with Escape.
6. On selection, update the system clipboard. Automatic paste is optional, user-controlled, and must restore focus before issuing the platform-specific paste key combination.
7. Include clear-history, delete-item, pin-item, pause-monitoring, and exclude-apps/settings controls as the application matures.
8. Do not reinsert items endlessly when the app itself updates the clipboard. Track the last observed value or an appropriate fingerprint.

## Architecture
Prefer a small, modular application rather than microservices:

```text
src/main/java/.../
  app/           # startup, lifecycle, dependency wiring, single-instance logic
  clipboard/     # clipboard observer and read/write abstraction
  hotkey/        # global shortcut registration and dispatch
  history/       # history service, deduplication, retention
  persistence/   # SQLite repositories, migrations
  ui/            # JavaFX picker, settings, tray integration
  platform/      # OS detection, permissions, focus/paste adapters
  config/        # validated user settings
src/test/java/.../
```

Interfaces worth defining early:
- `ClipboardProvider`: read supported formats, write selected item.
- `HistoryRepository`: insert, search, pin, delete, prune.
- `GlobalShortcutService`: register/unregister and report conflicts/errors.
- `PasteService`: optional focus restoration and simulated paste.
- `SettingsRepository`: persist user preferences.

Keep platform APIs out of business logic; use dependency injection through constructors. Avoid adding a DI framework unless complexity justifies it.

## Concurrency and UI rules
- JavaFX scene graph modifications must occur on the **JavaFX Application Thread** (`Platform.runLater` when needed).
- Clipboard polling, database I/O, search work, and keyboard hook callbacks must never block the UI thread.
- Keyboard hook listeners should dispatch minimal work and return quickly.
- Shut down executors, unregister hooks, close database connections, and release tray resources cleanly.
- Handle clipboard contention, empty or unsupported formats, and temporary OS permission failures without crashing.
- Avoid logging snippet contents.

## Privacy and security requirements
Clipboard histories can contain passwords, authentication tokens, personal data, and other secrets.
- **Local-first:** no telemetry, analytics, syncing, cloud uploads, or network calls by default.
- Provide prominent **Pause monitoring**, **Clear history**, and **Quit** actions.
- Document that clipboard content is stored locally and that SQLite storage is **not encrypted by default**. Do not advertise encryption unless implemented and tested.
- Offer an option to disable persistence entirely; default retention should be bounded.
- Implement exclusion rules where technically feasible; explicitly document that detecting the originating application and sensitive clipboard formats may require platform-specific APIs and may be imperfect.
- Never capture keystrokes as a way to reconstruct clipboard content. JNativeHook should only recognize the configured shortcut.
- Never log or send clipboard contents in diagnostics, crash reports, or tests.
- Do not request OS privileges or permissions unrelated to a user-enabled feature.

## Platform constraints
### macOS
- Test global shortcuts, Accessibility/Input Monitoring permissions, focus management, menu-bar behavior, and Cmd+V simulation on a real macOS installation.
- Explain permissions only where necessary, with actionable error messages.
- Account for macOS app bundling/signing/notarization considerations when distributing.
- Verify shortcut combinations do not conflict with system-reserved keys.

### Windows
- Test global shortcuts and tray behavior on Windows 10/11.
- Avoid binding reserved shortcuts such as Win+V by default.
- Verify focus restoration, Ctrl+V simulation, DPI scaling, and multiple monitors.
- Test standard-user permissions; do not require administrator elevation.

## Milestones (build in this order)
### Phase 1 — Functional MVP
- [x] Gradle project with runnable JavaFX UI.
- [x] Clipboard observer for text with deduplication.
- [x] In-memory history and searchable popup.
- [x] Global shortcut triggers popup.
- [x] Arrow/Enter/Escape navigation and clipboard selection.

### Phase 2 — Everyday usability
- [ ] SQLite persistence and bounded retention.
- [x] System tray/menu-bar controls and single-instance startup.
- [x] Configurable shortcut, pinned entries, delete/clear history.
- [x] Settings for monitoring pause and persistence mode.
- [x] Usable keyboard focus and multi-monitor positioning.

### Phase 3 — Polish and robustness
- [ ] Optional auto-paste, opt-in and disabled by default.
- [ ] Better error handling and permission guidance.
- [ ] Clipboard image/file support if practical, with clear type handling.
- [ ] End-to-end tests/manual test checklist for macOS and Windows.
- [ ] README with demo GIF/screenshots, architecture, and install instructions.

### Phase 4 — Distribution and optimization
- [ ] Package with `jpackage` on each target OS.
- [ ] Measure cold start, idle memory, CPU use, and popup latency.
- [ ] Prototype GraalVM Native Image / GluonFX in a separate branch.
- [ ] Keep `jpackage` as the supported fallback if native image adds too much complexity.

## Engineering conventions
- Prefer clear, small classes and meaningful names; avoid giant controllers and static global state.
- Use immutable records/value objects for data transferred between services where appropriate.
- Validate user settings, file paths, and clipboard formats at boundaries.
- Use parameterized queries; do not concatenate user input into SQL.
- Do not introduce dependencies without a reason and a check of license, maintenance, and platform support.
- Keep platform-specific behaviors behind adapters with explicit capability checks.
- Avoid premature optimization. Profile before replacing simple approaches.
- Write meaningful unit tests for history deduplication, search ranking, retention, settings, and error handling.
- Add integration tests for SQLite migrations and repositories.
- Do not commit generated binaries, runtime databases, secrets, or machine-specific paths.

## Agent workflow
Before editing:
1. Inspect the existing Gradle configuration, package structure, and relevant classes.
2. Summarize the intended change and affected components.
3. Favor small, cohesive changes with tests over large rewrites.

While editing:
1. Preserve existing behavior and public interfaces unless a change is justified.
2. Separate macOS/Windows behavior rather than introducing fragile conditional code throughout the app.
3. Keep the application launchable at the end of each feature increment.
4. Do not silently change dependencies, Java versions, or packaging strategy.

After editing:
1. Run the project tests via `./gradlew test` (or `gradlew.bat test` on Windows).
2. Run the app via the project's documented Gradle task once available.
3. Report tests run, results, platform-specific behavior not tested, and remaining limitations.
4. Update README/configuration documentation if behavior or setup changed.

## Definition of done
A feature is done only when it works on the relevant target platform, has appropriate tests where automatable, fails gracefully when permissions or APIs are unavailable, does not leak clipboard content to logs/network services, and is documented well enough for another developer to reproduce.

## Scope guardrails
- **Do not** add accounts, subscriptions, analytics, cloud sync, or a backend server unless explicitly requested.
- **Do not** implement hidden keylogging or background surveillance.
- **Do not** require GraalVM to complete the MVP.
- **Do not** claim cross-platform compatibility without testing both operating systems.
