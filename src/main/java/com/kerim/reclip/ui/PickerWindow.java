package com.kerim.reclip.ui;

import com.kerim.reclip.app.ReclipApp;
import com.kerim.reclip.history.ClipboardEntry;
import com.kerim.reclip.history.HistoryService;
import com.kerim.reclip.hotkey.OutsideClickDetector;
import com.kerim.reclip.platform.WindowFocusHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.Image;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Compact floating JavaFX mini-window styled like a native macOS menu.
 */
public class PickerWindow {

    public static final double DEFAULT_WIDTH = 300;
    public static final double DEFAULT_HEIGHT = 156;
    public static final int MAX_VISIBLE_ENTRIES = 3;
    private static final int PREVIEW_MAX_LENGTH = 160;

    private final Stage stage;
    private final HistoryService historyService;
    private final Consumer<ClipboardEntry> onEntrySelected;
    private final OutsideClickDetector outsideClickDetector;
    private final ListView<ClipboardEntry> historyList;
    private ContextMenu activeContextMenu;
    private int maxVisibleEntries = MAX_VISIBLE_ENTRIES;

    /**
     * Creates the mini picker window backed by the given stage, history service, and selection handler.
     *
     * @param stage                the JavaFX stage to configure
     * @param historyService       the history service to query and display
     * @param onEntrySelected      callback invoked when the user chooses an entry
     * @param outsideClickDetector detector used to dismiss the popup when clicking outside
     */
    public PickerWindow(
            Stage stage,
            HistoryService historyService,
            Consumer<ClipboardEntry> onEntrySelected,
            OutsideClickDetector outsideClickDetector) {
        this.stage = Objects.requireNonNull(stage, "stage must not be null");
        this.historyService = Objects.requireNonNull(historyService, "historyService must not be null");
        this.onEntrySelected = Objects.requireNonNull(onEntrySelected, "onEntrySelected must not be null");
        this.outsideClickDetector = Objects.requireNonNull(outsideClickDetector, "outsideClickDetector must not be null");
        this.historyList = new ListView<>();
        configureStage();
        refresh();
    }

    private void configureStage() {
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused && stage.isShowing() && !isContextMenuShowing()) {
                hideWithoutFocusRestore();
            }
        });

        Label emptyPlaceholder = new Label("No clipboard history yet.");
        historyList.setPlaceholder(emptyPlaceholder);
        historyList.getStyleClass().add("picker-list");
        historyList.setCellFactory(list -> createEntryCell());
        VBox.setVgrow(historyList, Priority.ALWAYS);

        VBox card = new VBox(historyList);
        card.getStyleClass().add("picker-card");
        VBox.setVgrow(card, Priority.ALWAYS);

        StackPane root = new StackPane(card);
        root.setStyle("-fx-background-color: transparent;");

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT, Color.TRANSPARENT);
        URL stylesheet = getClass().getResource("/styles/picker.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            switch (event.getCode()) {
                case UP -> {
                    moveSelection(-1);
                    event.consume();
                }
                case DOWN -> {
                    moveSelection(1);
                    event.consume();
                }
                case HOME -> {
                    selectIndex(0);
                    event.consume();
                }
                case END -> {
                    selectIndex(historyList.getItems().size() - 1);
                    event.consume();
                }
                case ENTER -> {
                    selectFocusedEntry();
                    event.consume();
                }
                case BACK_SPACE, DELETE -> {
                    deleteFocusedEntry();
                    event.consume();
                }
                case P -> {
                    if (event.isShortcutDown()) {
                        togglePinFocusedEntry();
                        event.consume();
                    }
                }
                case ESCAPE -> {
                    hide();
                    event.consume();
                }
                default -> {
                }
            }
        });

        stage.setTitle(ReclipApp.APP_NAME);
        try (InputStream iconStream = getClass().getResourceAsStream("/icons/app-icon.png")) {
            if (iconStream != null) {
                stage.getIcons().add(new Image(iconStream));
            }
        } catch (IOException ignored) {
            // Best-effort window icon.
        }
        stage.setResizable(false);
        stage.setScene(scene);
    }

    /**
     * Shows the mini picker window near the cursor and focuses the list.
     */
    public void show() {
        Runnable showTask = () -> {
            WindowFocusHelper.capturePreviousApp();
            refresh();
            positionAtCursor();
            stage.show();
            WindowFocusHelper.requestAppForeground();
            stage.toFront();
            stage.requestFocus();
            historyList.requestFocus();
            Platform.runLater(() -> {
                WindowFocusHelper.requestAppForeground();
                stage.toFront();
                stage.requestFocus();
                historyList.requestFocus();
            });
            outsideClickDetector.start(this::handleNativeMousePress);
        };
        if (Platform.isFxApplicationThread()) {
            showTask.run();
        } else {
            Platform.runLater(showTask);
        }
    }

    /**
     * Hides the picker window and restores focus to the previously active application.
     */
    public void hide() {
        hideInternal(true);
    }

    private void hideWithoutFocusRestore() {
        hideInternal(false);
    }

    private void hideInternal(boolean restorePreviousFocus) {
        if (activeContextMenu != null) {
            activeContextMenu.hide();
            activeContextMenu = null;
        }
        outsideClickDetector.stop();
        Runnable hideTask = () -> {
            stage.hide();
            if (restorePreviousFocus) {
                WindowFocusHelper.restorePreviousAppFocus();
            } else {
                WindowFocusHelper.clearPreviousApp();
            }
        };
        if (Platform.isFxApplicationThread()) {
            hideTask.run();
        } else {
            Platform.runLater(hideTask);
        }
    }

    private void handleNativeMousePress(int x, int y) {
        Platform.runLater(() -> {
            if (stage.isShowing() && !isContextMenuShowing() && !containsScreenPoint(x, y)) {
                hideWithoutFocusRestore();
            }
        });
    }

    private boolean isContextMenuShowing() {
        return activeContextMenu != null && activeContextMenu.isShowing();
    }

    private boolean containsScreenPoint(int x, int y) {
        double left = stage.getX();
        double top = stage.getY();
        double right = left + stage.getWidth();
        double bottom = top + stage.getHeight();
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    /**
     * Toggles the picker window visibility on the JavaFX Application Thread.
     */
    public void toggle() {
        Runnable toggleTask = () -> {
            if (stage.isShowing() && stage.isFocused()) {
                stage.hide();
            } else {
                show();
            }
        };
        if (Platform.isFxApplicationThread()) {
            toggleTask.run();
        } else {
            Platform.runLater(toggleTask);
        }
    }

    /**
     * Updates the maximum number of recent copies displayed in the popup.
     *
     * @param maxVisibleEntries number of entries to show
     */
    public void setMaxVisibleEntries(int maxVisibleEntries) {
        if (maxVisibleEntries <= 0) {
            throw new IllegalArgumentException("maxVisibleEntries must be positive");
        }
        this.maxVisibleEntries = maxVisibleEntries;
        Runnable updateTask = this::updateResults;
        if (Platform.isFxApplicationThread()) {
            updateTask.run();
        } else {
            Platform.runLater(updateTask);
        }
    }

    /**
     * Refreshes the displayed list on the JavaFX Application Thread.
     */
    public void refresh() {
        if (Platform.isFxApplicationThread()) {
            updateResults();
        } else {
            Platform.runLater(this::updateResults);
        }
    }

    private void positionAtCursor() {
        try {
            Robot robot = new Robot();
            Point2D cursor = robot.getMousePosition();
            List<Rectangle2D> allBounds = Screen.getScreens().stream()
                    .map(Screen::getVisualBounds)
                    .toList();
            Rectangle2D bounds = WindowPositioner.selectTargetScreenBounds(
                    cursor.getX(),
                    cursor.getY(),
                    allBounds,
                    Screen.getPrimary().getVisualBounds());
            double height = stage.getHeight() > 0 ? stage.getHeight() : DEFAULT_HEIGHT;
            Point2D position = WindowPositioner.positionNearCursor(
                    cursor.getX(),
                    cursor.getY(),
                    DEFAULT_WIDTH,
                    height,
                    bounds);
            stage.setX(position.getX());
            stage.setY(position.getY());
        } catch (RuntimeException ignored) {
            stage.centerOnScreen();
        }
    }

    private void updateResults() {
        List<ClipboardEntry> recent = historyService.listAll().stream()
                .limit(maxVisibleEntries)
                .toList();
        historyList.getItems().setAll(recent);
        if (!recent.isEmpty()) {
            historyList.getSelectionModel().selectFirst();
        }
        stage.setHeight(computePopupHeight(recent));
    }

    private static double computePopupHeight(List<ClipboardEntry> entries) {
        if (entries.isEmpty()) {
            return 44.0;
        }
        double total = 10.0;
        for (ClipboardEntry entry : entries) {
            String preview = formatPreview((entry.pinned() ? "★ " : "") + entry.displayText());
            int lines = Math.max(1, (int) Math.ceil(preview.length() / 38.0));
            total += lines * 18.0 + 10.0;
        }
        return Math.min(480.0, Math.max(44.0, total));
    }

    static String formatPreview(String content) {
        String singleLine = content.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= PREVIEW_MAX_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, PREVIEW_MAX_LENGTH - 1) + "…";
    }

    private ListCell<ClipboardEntry> createEntryCell() {
        ClipboardEntryCell cell = new ClipboardEntryCell();
        cell.setWrapText(true);
        cell.setPrefWidth(0);
        cell.maxWidthProperty().bind(historyList.widthProperty().subtract(16));
        cell.setMinHeight(Control.USE_PREF_SIZE);
        cell.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && !cell.isEmpty() && cell.getItem() != null) {
                chooseEntry(cell.getItem());
            }
        });
        cell.setOnContextMenuRequested(event -> {
            if (cell.isEmpty() || cell.getItem() == null) {
                return;
            }
            if (activeContextMenu != null) {
                activeContextMenu.hide();
            }
            ClipboardEntry entry = cell.getItem();
            ContextMenu menu = buildEntryContextMenu(entry);
            activeContextMenu = menu;
            menu.show(cell, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        return cell;
    }

    private ContextMenu buildEntryContextMenu(ClipboardEntry entry) {
        MenuItem copyItem = new MenuItem("Copy");
        copyItem.setOnAction(e -> chooseEntry(entry));

        MenuItem pinItem = new MenuItem(entry.pinned() ? "Unpin" : "Pin");
        pinItem.setOnAction(e -> historyService.togglePinned(entry.id()));

        MenuItem deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(e -> historyService.deleteEntry(entry.id()));

        MenuItem clearItem = new MenuItem("Clear Unpinned");
        clearItem.setOnAction(e -> historyService.clearUnpinned());

        ContextMenu menu = new ContextMenu(copyItem, pinItem, deleteItem, new SeparatorMenuItem(), clearItem);
        menu.setOnHidden(e -> {
            if (activeContextMenu == menu) {
                activeContextMenu = null;
            }
        });
        return menu;
    }

    private void deleteFocusedEntry() {
        ClipboardEntry selected = historyList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            historyService.deleteEntry(selected.id());
        }
    }

    private void togglePinFocusedEntry() {
        ClipboardEntry selected = historyList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            historyService.togglePinned(selected.id());
        }
    }

    private void moveSelection(int delta) {
        int count = historyList.getItems().size();
        int nextIndex = nextSelectionIndex(historyList.getSelectionModel().getSelectedIndex(), count, delta);
        selectIndex(nextIndex);
    }

    private void selectIndex(int index) {
        if (index >= 0 && index < historyList.getItems().size()) {
            historyList.getSelectionModel().select(index);
            historyList.scrollTo(index);
        }
    }

    static int nextSelectionIndex(int currentIndex, int itemCount, int delta) {
        if (itemCount <= 0) {
            return -1;
        }
        if (currentIndex < 0) {
            return 0;
        }
        return Math.max(0, Math.min(itemCount - 1, currentIndex + delta));
    }

    private void selectFocusedEntry() {
        ClipboardEntry selected = historyList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            chooseEntry(selected);
        }
    }

    private void chooseEntry(ClipboardEntry entry) {
        onEntrySelected.accept(entry);
        hide();
    }

    private static final class ClipboardEntryCell extends ListCell<ClipboardEntry> {

        @Override
        protected void updateItem(ClipboardEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
            } else {
                String prefix = item.pinned() ? "★ " : "";
                setText(prefix + formatPreview(item.displayText()));
            }
        }
    }
}
