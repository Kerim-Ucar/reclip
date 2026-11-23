package com.kerim.reclip.app;

import com.kerim.reclip.clipboard.AwtClipboardProvider;
import com.kerim.reclip.clipboard.ClipboardObserver;
import com.kerim.reclip.clipboard.ClipboardProvider;
import com.kerim.reclip.config.AppSettings;
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
import com.kerim.reclip.hotkey.ShortcutRegistrationResult;
import com.kerim.reclip.ipc.ReclipIpcServer;
import com.kerim.reclip.platform.WindowFocusHelper;
import com.kerim.reclip.ui.DashboardWindow;
import com.kerim.reclip.ui.PickerWindow;
import com.kerim.reclip.ui.SettingsPane;
import com.kerim.reclip.ui.SettingsWindow;
import com.kerim.reclip.ui.TrayController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.awt.desktop.AppReopenedListener;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JavaFX application entry point for Reclip.
 */
public class ReclipApp extends Application {

    public static final String APP_NAME = "Reclip";

    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);
    private SingleInstanceLock instanceLock;
    private ClipboardObserver clipboardObserver;
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
        Optional<SingleInstanceLock> acquiredLock = SingleInstanceLock.tryAcquireDefault();
        if (acquiredLock.isEmpty()) {
            ReclipIpcServer.sendCommand(ReclipIpcServer.defaultSocketPath(), "SHOW_PANEL");
            Platform.exit();
            return;
        }
        this.instanceLock = acquiredLock.get();

        Platform.setImplicitExit(false);
        WindowFocusHelper.configureBackgroundApp();

        SettingsRepository settingsRepository = new PropertiesSettingsRepository(
                PropertiesSettingsRepository.defaultSettingsPath());
        currentSettings = settingsRepository.load();

        FileHistoryRepository historyRepository = new FileHistoryRepository(
                FileHistoryRepository.defaultHistoryPath(),
                currentSettings.persistHistory());
        HistoryService historyService = new HistoryService(
                historyRepository,
                currentSettings.maxHistoryEntries());
        Instant startupTime = Instant.now();
        for (PersistedSnippet snippet : currentSettings.persistedSnippets()) {
            ClipboardEntry entry = historyService.recordText(snippet.content(), startupTime);
            historyRepository.save(entry.withPinned(true).withLabel(snippet.label()));
        }
        ClipboardProvider clipboardProvider = new AwtClipboardProvider();
        clipboardObserver = new ClipboardObserver(
                clipboardProvider,
                ClipboardObserver.DEFAULT_POLL_INTERVAL,
                event -> historyService.recordText(event.text(), event.observedAt()));
        clipboardObserver.setPaused(currentSettings.monitoringPaused());

        outsideClickDetector = new OutsideClickDetector();
        PickerWindow pickerWindow = new PickerWindow(
                primaryStage,
                historyService,
                entry -> {
                    clipboardObserver.markSelfWritten(entry.content());
                    clipboardProvider.writeText(entry.content());
                },
                outsideClickDetector);
        pickerWindow.setMaxVisibleEntries(currentSettings.visibleCopies());
        historyService.addChangeListener(pickerWindow::refresh);

        shortcutService = new JNativeHookShortcutService();
        SettingsWindow[] settingsWindowHolder = new SettingsWindow[1];
        DashboardWindow[] dashboardHolder = new DashboardWindow[1];
        Runnable togglePickerIfNotRecording = () -> {
            SettingsWindow sw = settingsWindowHolder[0];
            DashboardWindow dw = dashboardHolder[0];
            boolean recording = (sw != null && sw.isRecording()) || (dw != null && dw.isRecording());
            if (!recording) {
                if (dw != null) {
                    dw.hide();
                }
                pickerWindow.toggle();
            }
        };

        SettingsPane.SettingsApplyHandler onSettingsApplied = (newShortcut, visibleCopies) -> {
            ShortcutRegistrationResult result = shortcutService.register(newShortcut, togglePickerIfNotRecording);
            if (result.registered()) {
                currentSettings = currentSettings
                        .withShortcut(newShortcut)
                        .withVisibleCopies(visibleCopies);
                pickerWindow.setMaxVisibleEntries(visibleCopies);
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

        SettingsWindow settingsWindow = new SettingsWindow(
                () -> currentSettings,
                onSettingsApplied,
                onAddSnippet,
                onRemoveSnippet);
        settingsWindowHolder[0] = settingsWindow;

        DashboardWindow dashboardWindow = new DashboardWindow(
                historyService,
                entry -> {
                    clipboardObserver.markSelfWritten(entry.content());
                    clipboardProvider.writeText(entry.content());
                },
                () -> currentSettings,
                onSettingsApplied,
                onAddSnippet,
                onRemoveSnippet);
        dashboardHolder[0] = dashboardWindow;
        historyService.addChangeListener(dashboardWindow::refresh);

        trayController = new TrayController(
                dashboardWindow::show,
                settingsWindow::show,
                paused -> {
                    clipboardObserver.setPaused(paused);
                    currentSettings = currentSettings.withMonitoringPaused(paused);
                    settingsRepository.save(currentSettings);
                },
                historyService::clearUnpinned,
                () -> Platform.runLater(Platform::exit));
        trayController.install();

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

        primaryStage.setOnCloseRequest(event -> {
            event.consume();
            pickerWindow.hide();
        });
        clipboardObserver.start();

        ShortcutRegistrationResult registration = shortcutService.register(
                currentSettings.shortcut(),
                togglePickerIfNotRecording);
        if (!registration.registered()) {
            registration.errorMessage().ifPresent(System.err::println);
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
        if (instanceLock != null) {
            instanceLock.close();
        }
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
