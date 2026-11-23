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
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
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
            if (!isFocused && stage.isShowing()) {
                hide();
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
                case ENTER -> {
                    selectFocusedEntry();
                    event.consume();
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
        stage.setResizable(false);
        stage.setScene(scene);
    }

    /**
     * Shows the mini picker window near the cursor and focuses the list.
     */
    public void show() {
        Runnable showTask = () -> {
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
     * Hides the picker window without exiting the application.
     */
    public void hide() {
        outsideClickDetector.stop();
        if (Platform.isFxApplicationThread()) {
            stage.hide();
        } else {
            Platform.runLater(stage::hide);
        }
    }

    private void handleNativeMousePress(int x, int y) {
        Platform.runLater(() -> {
            if (stage.isShowing() && !containsScreenPoint(x, y)) {
                hide();
            }
        });
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
            List<Screen> screens = Screen.getScreensForRectangle(cursor.getX(), cursor.getY(), 1, 1);
            Rectangle2D bounds = screens.isEmpty()
                    ? Screen.getPrimary().getVisualBounds()
                    : screens.getFirst().getVisualBounds();
            Point2D position = WindowPositioner.positionNearCursor(
                    cursor.getX(),
                    cursor.getY(),
                    DEFAULT_WIDTH,
                    DEFAULT_HEIGHT,
                    bounds);
            stage.setX(position.getX());
            stage.setY(position.getY());
        } catch (RuntimeException ignored) {
            stage.centerOnScreen();
        }
    }

    private void updateResults() {
        List<ClipboardEntry> recent = historyService.listAll().stream()
                .limit(MAX_VISIBLE_ENTRIES)
                .toList();
        historyList.getItems().setAll(recent);
        if (!recent.isEmpty()) {
            historyList.getSelectionModel().selectFirst();
        }
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
        return cell;
    }

    private void moveSelection(int delta) {
        int count = historyList.getItems().size();
        int nextIndex = nextSelectionIndex(historyList.getSelectionModel().getSelectedIndex(), count, delta);
        if (nextIndex >= 0) {
            historyList.getSelectionModel().select(nextIndex);
            historyList.scrollTo(nextIndex);
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
                String prefix = item.pinned() ? "[Pinned] " : "";
                setText(prefix + formatPreview(item.content()));
            }
        }
    }
}
