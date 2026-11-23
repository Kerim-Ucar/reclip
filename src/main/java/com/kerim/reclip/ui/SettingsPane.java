package com.kerim.reclip.ui;

import com.kerim.reclip.config.AppSettings;
import com.kerim.reclip.config.PersistedSnippet;
import com.kerim.reclip.hotkey.Shortcut;
import com.kerim.reclip.hotkey.ShortcutRegistrationResult;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reusable Apple-styled settings form for configuring the global shortcut,
 * visible copy count, and persisted snippets.
 */
public class SettingsPane extends VBox {

    @FunctionalInterface
    public interface SettingsApplyHandler {
        ShortcutRegistrationResult apply(Shortcut shortcut, int visibleCopies);
    }

    private final Supplier<AppSettings> settingsSupplier;
    private final SettingsApplyHandler onSettingsApplied;
    private final Consumer<PersistedSnippet> onAddPersistedSnippet;
    private final Consumer<PersistedSnippet> onRemovePersistedSnippet;

    private final TextField chordField = new TextField();
    private final ToggleButton recordButton = new ToggleButton("Record");
    private final Slider visibleCopiesSlider = new Slider(
            AppSettings.MIN_VISIBLE_COPIES,
            AppSettings.MAX_VISIBLE_COPIES,
            AppSettings.DEFAULT_VISIBLE_COPIES);
    private final Spinner<Integer> visibleCopiesSpinner = new Spinner<>(
            AppSettings.MIN_VISIBLE_COPIES,
            AppSettings.MAX_VISIBLE_COPIES,
            AppSettings.DEFAULT_VISIBLE_COPIES);
    private final TextField persistedLabelField = new TextField();
    private final TextField persistedTextField = new TextField();
    private final ListView<PersistedSnippet> persistedListView = new ListView<>();
    private final Button removePersistedButton = new Button();
    private final Label statusLabel = new Label();

    private final Set<String> heldKeys = new LinkedHashSet<>();
    private Set<String> recordedKeys = new LinkedHashSet<>(Shortcut.defaultShortcut().keys());
    private volatile boolean recording;

    public SettingsPane(
            Supplier<AppSettings> settingsSupplier,
            SettingsApplyHandler onSettingsApplied,
            Consumer<PersistedSnippet> onAddPersistedSnippet,
            Consumer<PersistedSnippet> onRemovePersistedSnippet,
            Runnable onCloseRequested) {
        super(14);
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier must not be null");
        this.onSettingsApplied = Objects.requireNonNull(onSettingsApplied, "onSettingsApplied must not be null");
        this.onAddPersistedSnippet = Objects.requireNonNull(onAddPersistedSnippet, "onAddPersistedSnippet must not be null");
        this.onRemovePersistedSnippet = Objects.requireNonNull(onRemovePersistedSnippet, "onRemovePersistedSnippet must not be null");
        buildUi(onCloseRequested);
        reload();
    }

    private void buildUi(Runnable onCloseRequested) {
        Label shortcutTitle = new Label("Global shortcut:");
        shortcutTitle.getStyleClass().add("row-title");
        Label shortcutHint = new Label("Hold any combination of keys");
        shortcutHint.getStyleClass().add("row-subtitle");

        chordField.setEditable(false);
        chordField.setPromptText("Press keys…");
        chordField.getStyleClass().add("chord-field");
        chordField.setOnKeyPressed(this::handleKeyPressed);
        chordField.setOnKeyReleased(this::handleKeyReleased);
        HBox.setHgrow(chordField, Priority.ALWAYS);

        recordButton.getStyleClass().add("record-button");
        recordButton.setOnAction(e -> {
            if (recordButton.isSelected()) {
                recording = true;
                recordButton.setText("Recording…");
                heldKeys.clear();
                recordedKeys.clear();
                chordField.clear();
                chordField.requestFocus();
            } else {
                recording = false;
                recordButton.setText("Record");
            }
        });

        Button clearButton = new Button("Clear");
        clearButton.getStyleClass().add("push-button");
        clearButton.setOnAction(e -> {
            recording = false;
            recordButton.setSelected(false);
            recordButton.setText("Record");
            heldKeys.clear();
            recordedKeys.clear();
            chordField.clear();
        });

        HBox shortcutControls = new HBox(8, chordField, recordButton, clearButton);
        shortcutControls.setAlignment(Pos.CENTER_LEFT);
        VBox shortcutRow = new VBox(8, new VBox(2, shortcutTitle, shortcutHint), shortcutControls);
        shortcutRow.getStyleClass().add("settings-row");

        Region divider = new Region();
        divider.getStyleClass().add("settings-divider");

        Label copiesTitle = new Label("Copies shown:");
        copiesTitle.getStyleClass().add("row-title");
        Label copiesHint = new Label("Recent items displayed in popup (1–15)");
        copiesHint.getStyleClass().add("row-subtitle");

        visibleCopiesSlider.setMajorTickUnit(7);
        visibleCopiesSlider.setMinorTickCount(6);
        visibleCopiesSlider.setSnapToTicks(true);
        visibleCopiesSlider.setShowTickMarks(true);
        visibleCopiesSlider.setShowTickLabels(true);
        visibleCopiesSlider.getStyleClass().add("apple-slider");
        HBox.setHgrow(visibleCopiesSlider, Priority.ALWAYS);

        visibleCopiesSpinner.setEditable(true);
        visibleCopiesSpinner.setPrefWidth(74);
        visibleCopiesSpinner.getStyleClass().add("apple-spinner");

        visibleCopiesSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int rounded = (int) Math.round(newVal.doubleValue());
            if (newVal.doubleValue() != rounded) {
                visibleCopiesSlider.setValue(rounded);
            }
            if (!Objects.equals(visibleCopiesSpinner.getValue(), rounded)) {
                visibleCopiesSpinner.getValueFactory().setValue(rounded);
            }
        });
        visibleCopiesSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && Math.round(visibleCopiesSlider.getValue()) != newVal) {
                visibleCopiesSlider.setValue(newVal);
            }
        });

        HBox copiesControls = new HBox(12, visibleCopiesSlider, visibleCopiesSpinner);
        copiesControls.setAlignment(Pos.CENTER_LEFT);
        VBox copiesRow = new VBox(8, new VBox(2, copiesTitle, copiesHint), copiesControls);
        copiesRow.getStyleClass().add("settings-row");

        Region divider2 = new Region();
        divider2.getStyleClass().add("settings-divider");

        Label persistedTitle = new Label("Persisted texts:");
        persistedTitle.getStyleClass().add("row-title");
        Label persistedHint = new Label("Optional name (shown in popup) + text to copy");
        persistedHint.getStyleClass().add("row-subtitle");

        persistedLabelField.setPromptText("Name (optional)");
        persistedLabelField.getStyleClass().add("chord-field");
        persistedLabelField.setPrefWidth(130);
        persistedLabelField.setOnAction(e -> persistedTextField.requestFocus());

        persistedTextField.setPromptText("Text to copy…");
        persistedTextField.getStyleClass().add("chord-field");
        persistedTextField.setOnAction(e -> addPersistedSnippet());
        HBox.setHgrow(persistedTextField, Priority.ALWAYS);

        Button addPersistedButton = new Button();
        addPersistedButton.getStyleClass().add("add-button");
        addPersistedButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        addPersistedButton.setGraphic(createGlyph("M5 0 V10 M0 5 H10", Color.web("#F5F5F7")));
        addPersistedButton.setTooltip(new Tooltip("Add persisted text"));
        addPersistedButton.setOnAction(e -> addPersistedSnippet());

        removePersistedButton.getStyleClass().add("remove-button");
        removePersistedButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        removePersistedButton.setGraphic(createGlyph("M0 5 H10", Color.web("#F5F5F7")));
        removePersistedButton.setTooltip(new Tooltip("Remove selected persisted text"));
        removePersistedButton.setDisable(true);
        removePersistedButton.setOnAction(e -> removeSelectedPersistedSnippet());

        HBox persistedInputRow = new HBox(8, persistedLabelField, persistedTextField, addPersistedButton, removePersistedButton);
        persistedInputRow.setAlignment(Pos.CENTER_LEFT);

        persistedListView.setPlaceholder(new Label("No persisted texts yet."));
        persistedListView.getStyleClass().add("persisted-list");
        persistedListView.setPrefHeight(104);
        persistedListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(PersistedSnippet item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else if (item.label().isEmpty()) {
                    setText(item.content());
                } else {
                    setText(item.label() + ": " + item.content());
                }
            }
        });
        persistedListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) ->
                removePersistedButton.setDisable(newVal == null));

        VBox persistRow = new VBox(8, new VBox(2, persistedTitle, persistedHint), persistedInputRow, persistedListView);
        persistRow.getStyleClass().add("settings-row");

        VBox groupedForm = new VBox(shortcutRow, divider, copiesRow, divider2, persistRow);
        groupedForm.getStyleClass().add("settings-group");

        statusLabel.setWrapText(true);
        statusLabel.getStyleClass().add("status-ok");

        Button saveButton = new Button("Apply");
        saveButton.setDefaultButton(true);
        saveButton.getStyleClass().add("primary-button");
        saveButton.setOnAction(e -> applySettings());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, statusLabel, spacer);
        if (onCloseRequested != null) {
            Button closeButton = new Button("Close");
            closeButton.setCancelButton(true);
            closeButton.getStyleClass().add("push-button");
            closeButton.setOnAction(e -> onCloseRequested.run());
            footer.getChildren().add(closeButton);
        }
        footer.getChildren().add(saveButton);
        footer.setAlignment(Pos.CENTER_LEFT);

        getChildren().setAll(groupedForm, footer);
    }

    public boolean isRecording() {
        return recording;
    }

    public void reload() {
        AppSettings current = settingsSupplier.get();
        recording = false;
        recordButton.setSelected(false);
        recordButton.setText("Record");
        heldKeys.clear();
        recordedKeys = new LinkedHashSet<>(current.shortcut().keys());
        chordField.setText(current.shortcut().format());

        SpinnerValueFactory<Integer> factory = visibleCopiesSpinner.getValueFactory();
        factory.setValue(current.visibleCopies());
        visibleCopiesSlider.setValue(current.visibleCopies());
        persistedListView.getItems().setAll(current.persistedSnippets());
        persistedLabelField.clear();
        persistedTextField.clear();
        setStatus("", false);
    }

    private void addPersistedSnippet() {
        String text = persistedTextField.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        String label = persistedLabelField.getText();
        PersistedSnippet snippet = new PersistedSnippet(label, text);
        persistedListView.getItems().removeIf(existing -> existing.content().equals(snippet.content()));
        persistedListView.getItems().add(snippet);
        onAddPersistedSnippet.accept(snippet);
        setStatus("Saved", false);
        persistedLabelField.clear();
        persistedTextField.clear();
        persistedLabelField.requestFocus();
    }

    private void removeSelectedPersistedSnippet() {
        PersistedSnippet selected = persistedListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        persistedListView.getItems().remove(selected);
        onRemovePersistedSnippet.accept(selected);
        setStatus("Saved", false);
    }

    private void handleKeyPressed(KeyEvent event) {
        event.consume();
        if (!recordButton.isSelected()) {
            recording = true;
            recordButton.setSelected(true);
            recordButton.setText("Recording…");
        }
        String normalized = normalizeJavaFxKey(event.getCode());
        if (normalized == null) {
            return;
        }
        if (heldKeys.isEmpty()) {
            recordedKeys.clear();
        }
        heldKeys.add(normalized);
        syncJavaFxModifiers(heldKeys, event);
        recordedKeys.addAll(heldKeys);
        chordField.setText(new Shortcut(recordedKeys).format());
        setStatus("", false);
    }

    private void handleKeyReleased(KeyEvent event) {
        event.consume();
        String normalized = normalizeJavaFxKey(event.getCode());
        if (normalized != null) {
            heldKeys.remove(normalized);
        }
        if (heldKeys.isEmpty() && !recordedKeys.isEmpty()) {
            recording = false;
            recordButton.setSelected(false);
            recordButton.setText("Record");
        }
    }

    private static void syncJavaFxModifiers(Set<String> target, KeyEvent event) {
        if (event.isControlDown()) {
            target.add("CTRL");
        }
        if (event.isShiftDown()) {
            target.add("SHIFT");
        }
        if (event.isAltDown()) {
            target.add("ALT");
        }
        if (event.isMetaDown()) {
            target.add("META");
        }
    }

    private static String normalizeJavaFxKey(KeyCode code) {
        if (code == null || code == KeyCode.UNDEFINED) {
            return null;
        }
        return switch (code) {
            case CONTROL -> "CTRL";
            case SHIFT -> "SHIFT";
            case ALT, ALT_GRAPH -> "ALT";
            case META, COMMAND -> "META";
            case SPACE -> "SPACE";
            case ENTER -> "ENTER";
            case TAB -> "TAB";
            case BACK_QUOTE -> "BACK_QUOTE";
            case MINUS -> "MINUS";
            case EQUALS -> "EQUALS";
            case OPEN_BRACKET -> "OPEN_BRACKET";
            case CLOSE_BRACKET -> "CLOSE_BRACKET";
            case BACK_SLASH -> "BACK_SLASH";
            case SEMICOLON -> "SEMICOLON";
            case QUOTE -> "QUOTE";
            case COMMA -> "COMMA";
            case PERIOD -> "PERIOD";
            case SLASH -> "SLASH";
            default -> code.getName().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        };
    }

    private void setStatus(String message, boolean error) {
        statusLabel.getStyleClass().removeAll("status-ok", "status-error");
        statusLabel.getStyleClass().add(error ? "status-error" : "status-ok");
        statusLabel.setText(message);
    }

    private void applySettings() {
        if (recordedKeys.isEmpty()) {
            setStatus("Press at least one key in the shortcut box.", true);
            return;
        }
        int copies = visibleCopiesSpinner.getValue();
        Shortcut next = new Shortcut(recordedKeys);
        ShortcutRegistrationResult result = onSettingsApplied.apply(next, copies);
        if (result.registered()) {
            setStatus("Saved", false);
        } else {
            setStatus(result.errorMessage().orElse("Failed to register shortcut."), true);
        }
    }

    private static SVGPath createGlyph(String pathData, Color color) {
        SVGPath glyph = new SVGPath();
        glyph.setContent(pathData);
        glyph.setStroke(color);
        glyph.setStrokeWidth(1.9);
        glyph.setStrokeLineCap(StrokeLineCap.ROUND);
        glyph.setFill(Color.TRANSPARENT);
        return glyph;
    }
}
