package com.kerim.reclip.app;

import com.kerim.reclip.clipboard.AwtClipboardProvider;
import com.kerim.reclip.clipboard.ClipboardObserver;
import com.kerim.reclip.clipboard.ClipboardProvider;
import com.kerim.reclip.history.HistoryService;
import com.kerim.reclip.history.InMemoryHistoryRepository;
import com.kerim.reclip.hotkey.GlobalShortcutService;
import com.kerim.reclip.hotkey.JNativeHookShortcutService;
import com.kerim.reclip.hotkey.OutsideClickDetector;
import com.kerim.reclip.hotkey.Shortcut;
import com.kerim.reclip.hotkey.ShortcutRegistrationResult;
import com.kerim.reclip.platform.WindowFocusHelper;
import com.kerim.reclip.ui.PickerWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * JavaFX application entry point for Reclip.
 */
public class ReclipApp extends Application {

    public static final String APP_NAME = "Reclip";

    private ClipboardObserver clipboardObserver;
    private GlobalShortcutService shortcutService;
    private OutsideClickDetector outsideClickDetector;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        Platform.setImplicitExit(false);
        WindowFocusHelper.configureBackgroundApp();

        HistoryService historyService = new HistoryService(new InMemoryHistoryRepository());
        ClipboardProvider clipboardProvider = new AwtClipboardProvider();
        clipboardObserver = new ClipboardObserver(
                clipboardProvider,
                ClipboardObserver.DEFAULT_POLL_INTERVAL,
                event -> historyService.recordText(event.text(), event.observedAt()));

        outsideClickDetector = new OutsideClickDetector();
        PickerWindow pickerWindow = new PickerWindow(
                primaryStage,
                historyService,
                entry -> {
                    clipboardObserver.markSelfWritten(entry.content());
                    clipboardProvider.writeText(entry.content());
                },
                outsideClickDetector);
        historyService.addChangeListener(pickerWindow::refresh);

        primaryStage.setOnCloseRequest(event -> Platform.exit());
        clipboardObserver.start();

        shortcutService = new JNativeHookShortcutService();
        ShortcutRegistrationResult registration = shortcutService.register(
                Shortcut.defaultShortcut(),
                pickerWindow::toggle);
        if (!registration.registered()) {
            registration.errorMessage().ifPresent(System.err::println);
        }

        pickerWindow.show();
    }

    @Override
    public void stop() {
        if (outsideClickDetector != null) {
            outsideClickDetector.close();
        }
        if (shortcutService != null) {
            shortcutService.close();
        }
        if (clipboardObserver != null) {
            clipboardObserver.close();
        }
        Platform.exit();
    }
}
