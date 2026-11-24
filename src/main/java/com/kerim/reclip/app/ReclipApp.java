package com.kerim.reclip.app;

import com.kerim.reclip.clipboard.AwtClipboardProvider;
import com.kerim.reclip.clipboard.ClipboardObserver;
import com.kerim.reclip.clipboard.ClipboardProvider;
import com.kerim.reclip.config.AppSettings;
import com.kerim.reclip.config.LocalEncryption;
import com.kerim.reclip.config.PersistedSnippet;
import com.kerim.reclip.config.PropertiesSettingsRepository;
import com.kerim.reclip.config.SettingsRepository;
import com.kerim.reclip.history.ClipboardEntry;
import com.kerim.reclip.history.ContentType;
import com.kerim.reclip.history.FileHistoryRepository;
import com.kerim.reclip.history.HistoryService;
import com.kerim.reclip.hotkey.GlobalShortcutService;
import com.kerim.reclip.hotkey.JNativeHookShortcutService;
import com.kerim.reclip.hotkey.OutsideClickDetector;
import com.kerim.reclip.hotkey.Shortcut;
import com.kerim.reclip.hotkey.ShortcutRegistrationResult;
import com.kerim.reclip.ipc.ReclipIpcServer;
import com.kerim.reclip.platform.PasteService;
import com.kerim.reclip.platform.WindowFocusHelper;
import com.kerim.reclip.platform.macos.MacOsPasteService;
import com.kerim.reclip.platform.macos.MacOsPasteboard;
import com.kerim.reclip.platform.macos.MacOsPermissions;
import com.kerim.reclip.platform.windows.WindowsClipboard;
import com.kerim.reclip.platform.windows.WindowsPasteService;
import com.kerim.reclip.platform.windows.WindowsWindowHelper;
import com.kerim.reclip.ui.DashboardWindow;
import com.kerim.reclip.ui.PickerWindow;
import com.kerim.reclip.ui.SettingsPane;
import com.kerim.reclip.ui.SettingsWindow;
import com.kerim.reclip.ui.TrayController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.Desktop;
import java.awt.desktop.AppReopenedListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JavaFX application entry point for Reclip.
 */
public class ReclipApp extends Application {

    public static final String APP_NAME = "Reclip";

    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);
    private final Object expiryLock = new Object();
    private SingleInstanceLock instanceLock;
    private AwtClipboardProvider clipboardProvider;
    private ClipboardObserver clipboardObserver;
    private ExecutorService clipboardWriteExecutor;
    private ScheduledExecutorService expiryExecutor;
    private ScheduledFuture<?> pendingExpiryTask;
    private GlobalShortcutService shortcutService;
    private OutsideClickDetector outsideClickDetector;
    private TrayController trayController;
    private ReclipIpcServer ipcServer;
    private volatile AppSettings currentSettings;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        if (Boolean.getBoolean("reclip.aot.train")) {
            runAotTraining(primaryStage);
            return;
        }
        Optional<SingleInstanceLock> acquiredLock = SingleInstanceLock.tryAcquireDefault();
        if (acquiredLock.isEmpty()) {
            ReclipIpcServer.sendCommand(ReclipIpcServer.defaultSocketPath(), "SHOW_PANEL");
            Platform.exit();
            return;
        }
        this.instanceLock = acquiredLock.get();

        Platform.setImplicitExit(false);
        WindowFocusHelper.configureBackgroundApp();

        LocalEncryption localEncryption = LocalEncryption.defaultEncryptor();
        SettingsRepository settingsRepository = new PropertiesSettingsRepository(
                PropertiesSettingsRepository.defaultSettingsPath(),
                localEncryption);
        currentSettings = settingsRepository.load();

        FileHistoryRepository historyRepository = new FileHistoryRepository(
                FileHistoryRepository.defaultHistoryPath(),
                currentSettings.persistHistory(),
                localEncryption);
        HistoryService historyService = new HistoryService(
                historyRepository,
                currentSettings.maxHistoryEntries());
        Instant startupTime = Instant.now();
        for (PersistedSnippet snippet : currentSettings.persistedSnippets()) {
            ClipboardEntry entry = historyService.recordText(snippet.content(), startupTime);
            historyRepository.save(entry.withPinned(true).withLabel(snippet.label()));
        }
        clipboardProvider = new AwtClipboardProvider();
        clipboardObserver = ClipboardObserver.forTypedContent(
                clipboardProvider,
                ClipboardObserver.DEFAULT_POLL_INTERVAL,
                event -> {
                    if (isFrontmostAppExcluded(currentSettings.excludedApps())) {
                        return;
                    }
                    historyService.recordEntry(
                            event.contentType(),
                            event.content(),
                            event.displayLabel(),
                            event.observedAt(),
                            event.sensitive(),
                            Duration.ofSeconds(currentSettings.passwordExpirySeconds()));
                });
        clipboardObserver.setPaused(currentSettings.monitoringPaused());
        historyService.addDeleteListener(clipboardObserver::clearLastObserved);

        DashboardWindow[] dashboardHolder = new DashboardWindow[1];
        clipboardWriteExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "reclip-clipboard-writer");
            thread.setDaemon(true);
            return thread;
        });
        java.util.function.Consumer<ClipboardEntry> writeEntryToClipboard = entry -> {
            clipboardObserver.markSelfWritten(entry.contentType(), entry.content());
            Runnable writeTask = () -> {
                if (clipboardProvider.writeContent(entry.contentType(), entry.content())) {
                    if (entry.sensitive() && currentSettings.oneTimePasswords()) {
                        historyService.deleteEntry(entry.id());
                    }
                } else {
                    DashboardWindow dw = dashboardHolder[0];
                    if (dw != null) {
                        dw.showNotice(
                                "Unable to write to the system clipboard. Another app may be holding the clipboard lock.",
                                false);
                    }
                }
            };
            if (Platform.isFxApplicationThread()) {
                clipboardWriteExecutor.execute(writeTask);
            } else {
                writeTask.run();
            }
        };

        outsideClickDetector = new OutsideClickDetector();
        PasteService pasteService = createPasteService();
        Stage pickerStage = createPickerStage(primaryStage);
        PickerWindow pickerWindow = new PickerWindow(
                pickerStage,
                historyService,
                writeEntryToClipboard,
                outsideClickDetector,
                pasteService);
        pickerWindow.setMaxVisibleEntries(currentSettings.visibleCopies());
        pickerWindow.setAutoPasteEnabled(currentSettings.autoPaste());
        pickerWindow.setMaskPasswords(currentSettings.maskPasswords());
        pickerWindow.setPasswordExpirySeconds(currentSettings.passwordExpirySeconds());
        pickerWindow.setOnPasteError(message -> {
            DashboardWindow dw = dashboardHolder[0];
            if (dw != null) {
                dw.showNotice(message, true);
            }
        });
        historyService.addChangeListener(pickerWindow::refresh);

        shortcutService = new JNativeHookShortcutService();
        SettingsWindow[] settingsWindowHolder = new SettingsWindow[1];
        Runnable togglePickerIfNotRecording = () -> {
            SettingsWindow sw = settingsWindowHolder[0];
            DashboardWindow dw = dashboardHolder[0];
            boolean recording = (sw != null && sw.isRecording()) || (dw != null && dw.isRecording());
            if (!recording) {
                if (dw != null && !dw.isSettingsActive()) {
                    dw.hide();
                }
                pickerWindow.toggle();
            }
        };

        SettingsPane.SettingsApplyHandler onSettingsApplied = (
                newShortcut, visibleCopies, autoPaste, excludedApps, maskPasswords, expirySeconds, oneTimePasswords) -> {
            ShortcutRegistrationResult result = shortcutService.register(newShortcut, togglePickerIfNotRecording);
            if (result.registered()) {
                currentSettings = currentSettings
                        .withShortcut(newShortcut)
                        .withVisibleCopies(visibleCopies)
                        .withAutoPaste(autoPaste)
                        .withExcludedApps(excludedApps)
                        .withMaskPasswords(maskPasswords)
                        .withPasswordExpirySeconds(expirySeconds)
                        .withOneTimePasswords(oneTimePasswords);
                pickerWindow.setMaxVisibleEntries(visibleCopies);
                pickerWindow.setAutoPasteEnabled(autoPaste);
                pickerWindow.setMaskPasswords(maskPasswords);
                pickerWindow.setPasswordExpirySeconds(expirySeconds);
                DashboardWindow dw = dashboardHolder[0];
                if (dw != null) {
                    dw.setMaskPasswords(maskPasswords);
                    dw.setPasswordExpirySeconds(expirySeconds);
                }
                settingsRepository.save(currentSettings);
            }
            return result;
        };
        java.util.function.Consumer<PersistedSnippet> onAddSnippet = snippetToAdd -> {
            List<PersistedSnippet> updated = new ArrayList<>(currentSettings.persistedSnippets());
            updated.removeIf(existing -> existing.content().equals(snippetToAdd.content()));
            updated.add(snippetToAdd);
            currentSettings = currentSettings.withPersistedSnippets(updated);
            settingsRepository.save(currentSettings);
            ClipboardEntry entry = historyService.recordText(snippetToAdd.content(), Instant.now());
            historyRepository.save(entry.withPinned(true).withLabel(snippetToAdd.label()));
            pickerWindow.refresh();
        };
        java.util.function.Consumer<PersistedSnippet> onRemoveSnippet = snippetToRemove -> {
            List<PersistedSnippet> updated = new ArrayList<>(currentSettings.persistedSnippets());
            if (updated.removeIf(existing -> existing.content().equals(snippetToRemove.content()))) {
                currentSettings = currentSettings.withPersistedSnippets(updated);
                settingsRepository.save(currentSettings);
                historyRepository.findByContent(ContentType.TEXT, snippetToRemove.content())
                        .ifPresent(entry -> historyService.deleteEntry(entry.id()));
            }
        };

        Runnable showSettingsWindow = () -> {
            Runnable showTask = () -> {
                if (settingsWindowHolder[0] == null) {
                    settingsWindowHolder[0] = new SettingsWindow(
                            () -> currentSettings,
                            onSettingsApplied,
                            onAddSnippet,
                            onRemoveSnippet);
                }
                settingsWindowHolder[0].show();
            };
            if (Platform.isFxApplicationThread()) {
                showTask.run();
            } else {
                Platform.runLater(showTask);
            }
        };

        DashboardWindow dashboardWindow = new DashboardWindow(
                historyService,
                writeEntryToClipboard,
                () -> currentSettings,
                onSettingsApplied,
                onAddSnippet,
                onRemoveSnippet);
        dashboardHolder[0] = dashboardWindow;
        dashboardWindow.setMaskPasswords(currentSettings.maskPasswords());
        dashboardWindow.setPasswordExpirySeconds(currentSettings.passwordExpirySeconds());
        historyService.addChangeListener(dashboardWindow::refresh);
        historyService.addChangeListener(() -> rescheduleSensitiveExpiry(historyService));
        rescheduleSensitiveExpiry(historyService);

        trayController = new TrayController(
                dashboardWindow::show,
                showSettingsWindow,
                paused -> {
                    clipboardObserver.setPaused(paused);
                    currentSettings = currentSettings.withMonitoringPaused(paused);
                    try {
                        settingsRepository.save(currentSettings);
                    } catch (RuntimeException e) {
                        dashboardWindow.showNotice("Unable to save monitoring preference: " + e.getMessage(), false);
                    }
                },
                () -> {
                    Instant refreshTime = Instant.now();
                    for (PersistedSnippet snippet : currentSettings.persistedSnippets()) {
                        ClipboardEntry entry = historyService.recordText(snippet.content(), refreshTime);
                        historyRepository.save(entry.withPinned(true).withLabel(snippet.label()));
                    }
                    pickerWindow.refresh();
                    dashboardWindow.refresh();
                },
                historyService::clearUnpinned,
                () -> Platform.runLater(Platform::exit),
                currentSettings.monitoringPaused());
        if (!trayController.install()) {
            System.err.println("System tray / menu-bar icon is unavailable on this desktop.");
        }

        ipcServer = new ReclipIpcServer(
                ReclipIpcServer.defaultSocketPath(),
                () -> {
                    dashboardWindow.hide();
                    pickerWindow.show();
                },
                dashboardWindow::show);
        try {
            ipcServer.start();
        } catch (IOException e) {
            System.err.println("Unable to start local IPC server: " + e.getMessage());
        }

        registerAppReopenHandler(dashboardWindow::show);

        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanupResources, "reclip-shutdown"));

        pickerStage.setOnCloseRequest(event -> {
            event.consume();
            pickerWindow.hide();
        });
        clipboardObserver.ignoreInitialClipboard();
        clipboardObserver.start();

        ShortcutRegistrationResult registration = shortcutService.register(
                currentSettings.shortcut(),
                togglePickerIfNotRecording);
        if (!registration.registered()) {
            String message = registration.errorMessage().orElse("Unable to register global shortcut.");
            System.err.println(message);
            dashboardWindow.showNotice(message, true);
        } else if (MacOsPermissions.isMacOs()
                && !MacOsPermissions.isAccessibilityTrusted()) {
            dashboardWindow.showNotice(
                    "Permissions required for global shortcuts & auto-paste. "
                            + MacOsPermissions.accessibilityGuidance(),
                    true);
        }

        dashboardWindow.show();
    }

    @Override
    public void stop() {
        cleanupResources();
        Platform.exit();
        System.exit(0);
    }

    private void cleanupResources() {
        if (!cleanedUp.compareAndSet(false, true)) {
            return;
        }
        if (ipcServer != null) {
            ipcServer.close();
        }
        if (trayController != null) {
            trayController.close();
        }
        if (outsideClickDetector != null) {
            outsideClickDetector.close();
        }
        if (shortcutService != null) {
            shortcutService.close();
        }
        if (clipboardObserver != null) {
            clipboardObserver.close();
        }
        if (clipboardWriteExecutor != null) {
            clipboardWriteExecutor.shutdownNow();
        }
        synchronized (expiryLock) {
            if (pendingExpiryTask != null) {
                pendingExpiryTask.cancel(false);
                pendingExpiryTask = null;
            }
            if (expiryExecutor != null) {
                expiryExecutor.shutdownNow();
                expiryExecutor = null;
            }
        }
        if (clipboardProvider != null) {
            clipboardProvider.clearImageCache();
        }
        if (instanceLock != null) {
            instanceLock.close();
        }
    }

    private void rescheduleSensitiveExpiry(HistoryService historyService) {
        synchronized (expiryLock) {
            if (pendingExpiryTask != null) {
                pendingExpiryTask.cancel(false);
                pendingExpiryTask = null;
            }
            if (cleanedUp.get()) {
                return;
            }
            Optional<Instant> nextExpiry = historyService.nextSensitiveExpiry();
            if (nextExpiry.isEmpty()) {
                return;
            }
            if (expiryExecutor == null) {
                expiryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "reclip-sensitive-expiry");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            long delayMs = Math.max(25L, Duration.between(Instant.now(), nextExpiry.get()).toMillis() + 25L);
            pendingExpiryTask = expiryExecutor.schedule(() -> {
                historyService.purgeExpired(Instant.now());
                rescheduleSensitiveExpiry(historyService);
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void runAotTraining(Stage primaryStage) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("reclip-aot-train-");
            LocalEncryption encryption = LocalEncryption.withKey(LocalEncryption.generateAesKey());
            PropertiesSettingsRepository settingsRepo = new PropertiesSettingsRepository(
                    tempDir.resolve("settings.properties"), encryption);
            AppSettings initialSettings = settingsRepo.load();
            settingsRepo.save(initialSettings);
            AppSettings loadedSettings = settingsRepo.load();

            FileHistoryRepository historyRepo = new FileHistoryRepository(
                    tempDir.resolve("history.txt"), true, encryption);
            HistoryService historyService = new HistoryService(historyRepo, loadedSettings.maxHistoryEntries());
            Instant now = Instant.now();
            ClipboardEntry textEntry = historyService.recordText("Reclip AOT warmup", now);
            historyService.recordEntry(
                    ContentType.TEXT, "aot-secret-sample", "", now, true, Duration.ofSeconds(60));
            historyService.togglePinned(textEntry.id());
            historyService.search("Reclip");
            historyService.purgeExpired(now.plusSeconds(120));

            Shortcut.defaultShortcut().format();

            OutsideClickDetector detector = new OutsideClickDetector();
            PasteService pasteService = createPasteService();
            Stage pickerStage = createPickerStage(primaryStage);
            PickerWindow pickerWindow = new PickerWindow(
                    pickerStage, historyService, entry -> {}, detector, pasteService);
            pickerWindow.refresh();
            if (pickerStage.getScene() != null && pickerStage.getScene().getRoot() != null) {
                pickerStage.getScene().getRoot().applyCss();
                pickerStage.getScene().getRoot().layout();
            }

            DashboardWindow dashboardWindow = new DashboardWindow(
                    historyService,
                    entry -> {},
                    () -> loadedSettings,
                    (s, v, a, e, m, exp, otp) -> ShortcutRegistrationResult.success(),
                    snippet -> {},
                    snippet -> {});
            dashboardWindow.refresh();

            SettingsWindow settingsWindow = new SettingsWindow(
                    () -> loadedSettings,
                    (s, v, a, e, m, exp, otp) -> ShortcutRegistrationResult.success(),
                    snippet -> {},
                    snippet -> {});
            settingsWindow.isRecording();

            detector.close();
        } catch (IOException | RuntimeException e) {
            System.err.println("AOT training warning: " + e.getMessage());
        } finally {
            if (tempDir != null) {
                try (var stream = Files.walk(tempDir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
                } catch (IOException ignored) {
                }
            }
            Platform.runLater(Platform::exit);
        }
    }

    private static Stage createPickerStage(Stage primaryStage) {
        if (!WindowsWindowHelper.isWindows()) {
            return primaryStage;
        }
        // Own the transparent popup with an invisible UTILITY stage so Windows never
        // creates a taskbar button when toggling the floating picker.
        primaryStage.initStyle(StageStyle.UTILITY);
        primaryStage.setTitle("");
        primaryStage.setOpacity(0.0);
        primaryStage.setWidth(1);
        primaryStage.setHeight(1);
        primaryStage.setX(-32000);
        primaryStage.setY(-32000);
        primaryStage.show();

        Stage pickerStage = new Stage();
        pickerStage.initOwner(primaryStage);
        return pickerStage;
    }

    private static PasteService createPasteService() {
        return WindowsWindowHelper.isWindows() ? new WindowsPasteService() : new MacOsPasteService();
    }

    private static boolean isFrontmostAppExcluded(List<String> excludedApps) {
        if (WindowsWindowHelper.isWindows()) {
            return WindowsClipboard.isFrontmostAppExcluded(excludedApps);
        }
        return MacOsPasteboard.isFrontmostAppExcluded(excludedApps);
    }

    private static void registerAppReopenHandler(Runnable onReopen) {
        WindowFocusHelper.registerReopenHandler(onReopen);
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().addAppEventListener((AppReopenedListener) event -> onReopen.run());
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Best-effort reopen handler.
        }
    }
}
