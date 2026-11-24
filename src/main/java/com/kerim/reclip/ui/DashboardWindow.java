package com.kerim.reclip.ui;

import com.kerim.reclip.app.ReclipApp;
import com.kerim.reclip.config.AppSettings;
import com.kerim.reclip.config.PersistedSnippet;
import com.kerim.reclip.history.ClipboardEntry;
import com.kerim.reclip.history.ContentType;
import com.kerim.reclip.history.HistoryService;
import com.kerim.reclip.platform.WindowFocusHelper;
import com.kerim.reclip.platform.macos.MacOsPermissions;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Main application panel opened when launching or clicking Reclip, with built-in
 * Recent Copies and Settings views using the same dark Apple design system.
 */
public class DashboardWindow {

    private final Stage stage;
    private final HistoryService historyService;
    private final Consumer<ClipboardEntry> onEntrySelected;
    private final Supplier<AppSettings> settingsSupplier;
    private final SettingsPane.SettingsApplyHandler onSettingsApplied;
    private final Consumer<PersistedSnippet> onAddPersistedSnippet;
    private final Consumer<PersistedSnippet> onRemovePersistedSnippet;
    private SettingsPane settingsPane;
    private final ImagePreviewSupport imagePreviewSupport = new ImagePreviewSupport();
    private final Timeline countdownTimeline;
    private volatile boolean maskPasswords = AppSettings.DEFAULT_MASK_PASSWORDS;
    private volatile int passwordExpirySeconds = AppSettings.DEFAULT_PASSWORD_EXPIRY_SECONDS;
    private volatile boolean showing;
    private volatile boolean settingsSelected;

    private final TextField searchField = new TextField();
    private final ListView<ClipboardEntry> historyList = new ListView<>();
    private final VBox recentView = new VBox(10);
    private final ToggleButton recentTab = new ToggleButton("Recent Copies");
    private final ToggleButton settingsTab = new ToggleButton("Settings");
    private final Button clearButton = new Button("Clear");
    private final VBox noticeBanner = new VBox(8);
    private final Label noticeLabel = new Label();
    private final Button noticeActionButton = new Button("Accessibility");
    private final Button noticeInputMonitoringButton = new Button("Input Monitoring");

    public DashboardWindow(
            HistoryService historyService,
            Consumer<ClipboardEntry> onEntrySelected,
            Supplier<AppSettings> settingsSupplier,
            SettingsPane.SettingsApplyHandler onSettingsApplied,
            Consumer<PersistedSnippet> onAddPersistedSnippet,
            Consumer<PersistedSnippet> onRemovePersistedSnippet) {
        this.historyService = Objects.requireNonNull(historyService, "historyService must not be null");
        this.onEntrySelected = Objects.requireNonNull(onEntrySelected, "onEntrySelected must not be null");
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier must not be null");
        this.onSettingsApplied = Objects.requireNonNull(onSettingsApplied, "onSettingsApplied must not be null");
        this.onAddPersistedSnippet = Objects.requireNonNull(onAddPersistedSnippet, "onAddPersistedSnippet must not be null");
        this.onRemovePersistedSnippet = Objects.requireNonNull(onRemovePersistedSnippet, "onRemovePersistedSnippet must not be null");
        this.stage = new Stage();
        this.countdownTimeline = new Timeline(new KeyFrame(
                javafx.util.Duration.millis(250),
                e -> {
                    if (stage.isShowing()) {
                        imagePreviewSupport.tickCountdowns(Instant.now());
                    }
                }));
        this.countdownTimeline.setCycleCount(Timeline.INDEFINITE);
        configureStage();
        refresh();
    }

    private SettingsPane getOrCreateSettingsPane() {
        if (settingsPane == null) {
            settingsPane = new SettingsPane(
                    settingsSupplier,
                    onSettingsApplied,
                    onAddPersistedSnippet,
                    onRemovePersistedSnippet,
                    null);
        }
        return settingsPane;
    }

    private void configureStage() {
        stage.setTitle(ReclipApp.APP_NAME);
        stage.setMinWidth(440);
        stage.setMinHeight(460);
        stage.setOnCloseRequest(event -> {
            event.consume();
            hide();
        });
        stage.setOnHidden(event -> showing = false);
        try (InputStream iconStream = getClass().getResourceAsStream("/icons/app-icon.png")) {
            if (iconStream != null) {
                stage.getIcons().add(new Image(iconStream));
            }
        } catch (IOException ignored) {
            // Best-effort window icon.
        }

        ToggleGroup tabs = new ToggleGroup();
        recentTab.setToggleGroup(tabs);
        settingsTab.setToggleGroup(tabs);
        recentTab.getStyleClass().add("segment-button");
        settingsTab.getStyleClass().add("segment-button");
        recentTab.setSelected(true);

        HBox segmentBar = new HBox(2, recentTab, settingsTab);
        segmentBar.getStyleClass().add("segment-bar");
        segmentBar.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        clearButton.getStyleClass().add("push-button");
        clearButton.setOnAction(e -> historyService.clearUnpinned());

        HBox header = new HBox(8, segmentBar, spacer, clearButton);
        header.setAlignment(Pos.CENTER_LEFT);

        searchField.setPromptText("Search recent copies…");
        searchField.getStyleClass().add("chord-field");
        searchField.textProperty().addListener((obs, oldVal, newVal) -> updateResults(newVal));
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DOWN && !historyList.getItems().isEmpty()) {
                historyList.requestFocus();
                if (historyList.getSelectionModel().isEmpty()) {
                    historyList.getSelectionModel().selectFirst();
                }
                event.consume();
            }
        });

        historyList.setPlaceholder(new Label("No clipboard history yet."));
        historyList.getStyleClass().add("persisted-list");
        historyList.setCellFactory(list -> createEntryCell());
        historyList.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                ClipboardEntry selected = historyList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    onEntrySelected.accept(selected);
                }
                event.consume();
            } else if (event.getCode() == KeyCode.BACK_SPACE || event.getCode() == KeyCode.DELETE) {
                ClipboardEntry selected = historyList.getSelectionModel().getSelectedItem();
                if (selected != null && !selected.pinned()) {
                    historyService.deleteEntry(selected.id());
                }
                event.consume();
            }
        });
        VBox.setVgrow(historyList, Priority.ALWAYS);

        recentView.getChildren().setAll(searchField, historyList);
        recentView.getStyleClass().addAll("settings-group", "settings-row");

        StackPane contentStack = new StackPane(recentView);
        VBox.setVgrow(contentStack, Priority.ALWAYS);

        tabs.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                oldToggle.setSelected(true);
                return;
            }
            boolean showSettings = newToggle == settingsTab;
            settingsSelected = showSettings;
            if (showSettings) {
                SettingsPane pane = getOrCreateSettingsPane();
                pane.reload();
                contentStack.getChildren().setAll(pane);
            } else {
                contentStack.getChildren().setAll(recentView);
            }
            clearButton.setVisible(!showSettings);
            clearButton.setManaged(!showSettings);
        });

        VBox root = new VBox(12, header, buildNoticeBanner(), contentStack);
        root.getStyleClass().add("settings-root");
        root.setPadding(new Insets(16));

        Scene scene = new Scene(root, 460, 480);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && !isRecording()) {
                hide();
                event.consume();
            } else if (event.getCode() == KeyCode.F && event.isShortcutDown() && recentTab.isSelected()) {
                searchField.requestFocus();
                event.consume();
            }
        });
        URL stylesheet = getClass().getResource("/styles/settings.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        stage.setScene(scene);
    }

    private VBox buildNoticeBanner() {
        noticeLabel.setWrapText(true);
        noticeLabel.setMaxWidth(Double.MAX_VALUE);
        noticeLabel.setMinHeight(Region.USE_PREF_SIZE);
        noticeLabel.getStyleClass().add("notice-text");

        noticeActionButton.getStyleClass().add("compact-button");
        noticeActionButton.setMinWidth(Region.USE_PREF_SIZE);
        noticeActionButton.setOnAction(e -> MacOsPermissions.openAccessibilitySettings());

        noticeInputMonitoringButton.getStyleClass().add("compact-button");
        noticeInputMonitoringButton.setMinWidth(Region.USE_PREF_SIZE);
        noticeInputMonitoringButton.setOnAction(e -> MacOsPermissions.openInputMonitoringSettings());

        Button dismissButton = new Button("Dismiss");
        dismissButton.getStyleClass().add("compact-button");
        dismissButton.setMinWidth(Region.USE_PREF_SIZE);
        dismissButton.setOnAction(e -> hideNotice());

        HBox noticeActions = new HBox(8, noticeActionButton, noticeInputMonitoringButton, dismissButton);
        noticeActions.setAlignment(Pos.CENTER_LEFT);

        noticeBanner.getChildren().setAll(noticeLabel, noticeActions);
        noticeBanner.getStyleClass().add("notice-banner");
        noticeBanner.setVisible(false);
        noticeBanner.setManaged(false);
        return noticeBanner;
    }

    /**
     * Displays a non-blocking warning or permission guidance banner at the top of the panel.
     *
     * @param message                 user-facing message to display
     * @param showAccessibilityButton whether to show a shortcut button to macOS Accessibility Settings
     */
    public void showNotice(String message, boolean showAccessibilityButton) {
        if (message == null || message.isBlank()) {
            return;
        }
        Runnable task = () -> {
            noticeLabel.setText(message);
            boolean showAction = showAccessibilityButton && MacOsPermissions.isMacOs();
            noticeActionButton.setVisible(showAction);
            noticeActionButton.setManaged(showAction);
            noticeInputMonitoringButton.setVisible(showAction);
            noticeInputMonitoringButton.setManaged(showAction);
            noticeBanner.setVisible(true);
            noticeBanner.setManaged(true);
        };
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    private void hideNotice() {
        noticeBanner.setVisible(false);
        noticeBanner.setManaged(false);
    }

    public boolean isRecording() {
        return settingsPane != null && settingsPane.isRecording();
    }

    public boolean isSettingsActive() {
        return showing && settingsSelected;
    }

    public void show() {
        Runnable showTask = () -> {
            refresh();
            if (settingsTab.isSelected() && settingsPane != null) {
                settingsPane.reload();
            }
            if (stage.isIconified()) {
                stage.setIconified(false);
            }
            stage.setOpacity(0);
            stage.show();
            showing = true;
            syncCountdownTimeline();
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

    public void hide() {
        Runnable hideTask = () -> {
            countdownTimeline.stop();
            imagePreviewSupport.hide();
            imagePreviewSupport.clearCache();
            showing = false;
            stage.hide();
        };
        if (Platform.isFxApplicationThread()) {
            hideTask.run();
        } else {
            Platform.runLater(hideTask);
        }
    }

    public void setMaskPasswords(boolean maskPasswords) {
        this.maskPasswords = maskPasswords;
        refresh();
    }

    public void setPasswordExpirySeconds(int passwordExpirySeconds) {
        this.passwordExpirySeconds = Math.max(1, passwordExpirySeconds);
    }

    public void refresh() {
        if (Platform.isFxApplicationThread()) {
            updateResults(searchField.getText());
        } else {
            Platform.runLater(() -> updateResults(searchField.getText()));
        }
    }

    private void updateResults(String query) {
        List<ClipboardEntry> matches = historyService.search(query);
        historyList.getItems().setAll(matches);
        syncCountdownTimeline();
    }

    private void syncCountdownTimeline() {
        if (stage.isShowing() && historyService.hasSensitiveEntries()) {
            countdownTimeline.play();
        } else {
            countdownTimeline.stop();
        }
    }

    private ListCell<ClipboardEntry> createEntryCell() {
        ListCell<ClipboardEntry> cell = new ListCell<>() {
            private final ImagePreviewSupport.EntryCellContent content = imagePreviewSupport.installOnCell(this);

            @Override
            protected void updateItem(ClipboardEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    content.clear();
                    setText(null);
                    setGraphic(null);
                } else {
                    String prefix = item.pinned() ? "★ " : "";
                    String label = (item.sensitive() && maskPasswords)
                            ? "Password"
                            : PickerWindow.formatPreview(item.displayWithTypeBadge());
                    content.update(
                            item,
                            prefix + label,
                            item.contentType() == ContentType.IMAGE,
                            isHover(),
                            passwordExpirySeconds * 1000L);
                    setText(null);
                    setGraphic(content.root());
                }
            }
        };
        cell.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                    && !cell.isEmpty() && cell.getItem() != null) {
                onEntrySelected.accept(cell.getItem());
            }
        });
        cell.setOnContextMenuRequested(event -> {
            if (cell.isEmpty() || cell.getItem() == null) {
                return;
            }
            ClipboardEntry entry = cell.getItem();
            MenuItem copyItem = new MenuItem("Copy");
            copyItem.setOnAction(e -> onEntrySelected.accept(entry));

            MenuItem pinItem = new MenuItem(entry.pinned() ? "Unpin" : "Pin");
            pinItem.setOnAction(e -> historyService.togglePinned(entry.id()));

            MenuItem deleteItem = new MenuItem("Delete");
            deleteItem.setDisable(entry.pinned());
            deleteItem.setOnAction(e -> {
                if (!entry.pinned()) {
                    historyService.deleteEntry(entry.id());
                }
            });

            MenuItem clearItem = new MenuItem("Clear Unpinned");
            clearItem.setOnAction(e -> historyService.clearUnpinned());

            ContextMenu menu = new ContextMenu(copyItem, pinItem, deleteItem, new SeparatorMenuItem(), clearItem);
            menu.show(cell, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        return cell;
    }
}
