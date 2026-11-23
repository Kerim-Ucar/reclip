package com.kerim.reclip.ui;

import com.kerim.reclip.app.ReclipApp;
import com.kerim.reclip.config.AppSettings;
import com.kerim.reclip.config.PersistedSnippet;
import com.kerim.reclip.platform.WindowFocusHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Standalone Settings window opened from the system tray / menu bar.
 */
public class SettingsWindow {

    private final Stage stage;
    private final SettingsPane settingsPane;

    public SettingsWindow(
            Supplier<AppSettings> settingsSupplier,
            SettingsPane.SettingsApplyHandler onSettingsApplied,
            Consumer<PersistedSnippet> onAddPersistedSnippet,
            Consumer<PersistedSnippet> onRemovePersistedSnippet) {
        this.stage = new Stage();
        this.settingsPane = new SettingsPane(
                settingsSupplier,
                onSettingsApplied,
                onAddPersistedSnippet,
                onRemovePersistedSnippet,
                stage::hide);
        configureStage();
    }

    private void configureStage() {
        stage.setTitle(ReclipApp.APP_NAME + " Settings");
        stage.setResizable(false);
        stage.setOnCloseRequest(event -> {
            event.consume();
            stage.hide();
        });
        try (InputStream iconStream = getClass().getResourceAsStream("/icons/app-icon.png")) {
            if (iconStream != null) {
                Objects.requireNonNull(iconStream);
                stage.getIcons().add(new Image(iconStream));
            }
        } catch (IOException ignored) {
            // Best-effort window icon.
        }

        settingsPane.getStyleClass().add("settings-root");
        settingsPane.setPadding(new Insets(16));
        settingsPane.setPrefWidth(430);

        Scene scene = new Scene(settingsPane);
        URL stylesheet = getClass().getResource("/styles/settings.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        stage.setScene(scene);
    }

    public boolean isRecording() {
        return settingsPane.isRecording();
    }

    public void show() {
        Runnable showTask = () -> {
            settingsPane.reload();
            stage.setOpacity(0);
            stage.show();
            WindowFocusHelper.applyDarkTitleBar();
            WindowFocusHelper.activateAppOnly();
            stage.setOpacity(1);
            stage.toFront();
            stage.requestFocus();
        };
        if (Platform.isFxApplicationThread()) {
            showTask.run();
        } else {
            Platform.runLater(showTask);
        }
    }
}
