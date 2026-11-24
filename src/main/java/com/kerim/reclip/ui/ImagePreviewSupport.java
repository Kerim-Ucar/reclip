package com.kerim.reclip.ui;

import com.kerim.reclip.history.ClipboardEntry;
import com.kerim.reclip.history.ContentType;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.stage.Popup;
import javafx.stage.Screen;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Shared hover-caret and scaled-down image preview popover for {@link ContentType#IMAGE}
 * entries in {@link PickerWindow} and {@link DashboardWindow}.
 */
final class ImagePreviewSupport {

    private static final double MAX_PREVIEW_WIDTH = 220.0;
    private static final double MAX_PREVIEW_HEIGHT = 160.0;
    private static final double POPUP_GAP = 8.0;
    private static final int MAX_CACHED_IMAGES = 12;

    private final Popup previewPopup = new Popup();
    private final ImageView previewImageView = new ImageView();
    private final Set<EntryCellContent> activeCells = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<UUID, Image> imageCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Image> eldest) {
            return size() > MAX_CACHED_IMAGES;
        }
    };

    ImagePreviewSupport() {
        previewImageView.setFitWidth(MAX_PREVIEW_WIDTH);
        previewImageView.setFitHeight(MAX_PREVIEW_HEIGHT);
        previewImageView.setPreserveRatio(true);
        previewImageView.setSmooth(true);

        StackPane card = new StackPane(previewImageView);
        card.setPadding(new Insets(6));
        card.setStyle(
                "-fx-background-color: rgba(34, 34, 36, 0.96);"
                        + "-fx-border-color: rgba(255, 255, 255, 0.22);"
                        + "-fx-border-width: 1;"
                        + "-fx-background-radius: 10;"
                        + "-fx-border-radius: 10;"
                        + "-fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.5), 14, 0, 0, 4);");

        previewPopup.setAutoHide(false);
        previewPopup.setConsumeAutoHidingEvents(false);
        previewPopup.getContent().add(card);
    }

    /**
     * Configures a {@link ListCell} to display a hover-only caret and scaled image popover
     * whenever the cell represents a {@link ContentType#IMAGE} entry.
     */
    EntryCellContent installOnCell(ListCell<ClipboardEntry> cell) {
        Label textLabel = new Label();
        textLabel.setWrapText(true);
        textLabel.setMinHeight(Control.USE_PREF_SIZE);
        textLabel.getStyleClass().add("entry-text");
        HBox.setHgrow(textLabel, Priority.ALWAYS);
        textLabel.setMaxWidth(Double.MAX_VALUE);

        Label caretLabel = new Label("›");
        caretLabel.getStyleClass().add("image-hover-caret");
        caretLabel.setVisible(false);
        caretLabel.setManaged(false);

        Circle track = new Circle(7, 7, 5.5);
        track.setFill(Color.TRANSPARENT);
        track.setStroke(Color.rgb(255, 255, 255, 0.18));
        track.setStrokeWidth(2.0);

        Arc progressArc = new Arc(7, 7, 5.5, 5.5, 90, 0);
        progressArc.setType(ArcType.OPEN);
        progressArc.setFill(Color.TRANSPARENT);
        progressArc.setStroke(Color.web("#30D158"));
        progressArc.setStrokeWidth(2.0);
        progressArc.setStrokeLineCap(StrokeLineCap.ROUND);

        Pane ringPane = new Pane(track, progressArc);
        ringPane.setMinSize(14, 14);
        ringPane.setPrefSize(14, 14);
        ringPane.setMaxSize(14, 14);
        ringPane.setVisible(false);
        ringPane.setManaged(false);

        HBox row = new HBox(6, ringPane, textLabel, caretLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);

        cell.hoverProperty().addListener((obs, wasHovered, isHovered) -> {
            ClipboardEntry item = cell.getItem();
            boolean isImage = !cell.isEmpty() && item != null && item.contentType() == ContentType.IMAGE;
            if (isHovered && isImage) {
                caretLabel.setVisible(true);
                caretLabel.setManaged(true);
                showPreviewFor(cell, item);
            } else {
                caretLabel.setVisible(false);
                caretLabel.setManaged(false);
                if (!isHovered || !isImage) {
                    hide();
                }
            }
        });

        EntryCellContent content = new EntryCellContent(row, textLabel, caretLabel, ringPane, progressArc);
        activeCells.add(content);
        return content;
    }

    void tickCountdowns(Instant now) {
        for (EntryCellContent content : activeCells) {
            content.refreshCountdown(now);
        }
    }

    void hide() {
        if (previewPopup.isShowing()) {
            previewPopup.hide();
        }
    }

    private void showPreviewFor(ListCell<ClipboardEntry> cell, ClipboardEntry entry) {
        if (cell.getScene() == null || cell.getScene().getWindow() == null || !cell.getScene().getWindow().isShowing()) {
            return;
        }
        Image image = decodeImage(entry);
        if (image == null || image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
            hide();
            return;
        }
        previewImageView.setImage(image);

        Bounds screenBounds = cell.localToScreen(cell.getBoundsInLocal());
        if (screenBounds == null) {
            return;
        }
        double scale = Math.min(
                1.0,
                Math.min(MAX_PREVIEW_WIDTH / image.getWidth(), MAX_PREVIEW_HEIGHT / image.getHeight()));
        double popupWidth = Math.max(48.0, image.getWidth() * scale + 14.0);
        double popupHeight = Math.max(48.0, image.getHeight() * scale + 14.0);

        Rectangle2D visualBounds = Screen.getScreensForRectangle(
                        screenBounds.getMinX(), screenBounds.getMinY(),
                        Math.max(1.0, screenBounds.getWidth()), Math.max(1.0, screenBounds.getHeight()))
                .stream()
                .findFirst()
                .map(Screen::getVisualBounds)
                .orElseGet(() -> Screen.getPrimary().getVisualBounds());

        double x = screenBounds.getMaxX() + POPUP_GAP;
        if (x + popupWidth > visualBounds.getMaxX()) {
            x = screenBounds.getMinX() - popupWidth - POPUP_GAP;
        }
        x = Math.max(visualBounds.getMinX(), Math.min(x, visualBounds.getMaxX() - popupWidth));

        double y = screenBounds.getMinY();
        y = Math.max(visualBounds.getMinY(), Math.min(y, visualBounds.getMaxY() - popupHeight));

        previewPopup.show(cell.getScene().getWindow(), x, y);
    }

    private Image decodeImage(ClipboardEntry entry) {
        Image cached = imageCache.get(entry.id());
        if (cached != null) {
            return cached;
        }
        try {
            Image decoded;
            Path imagePath = Path.of(entry.content());
            if (Files.isRegularFile(imagePath)) {
                decoded = new Image(imagePath.toUri().toString(), MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT, true, true);
            } else {
                byte[] pngBytes = Base64.getDecoder().decode(entry.content());
                decoded = new Image(
                        new ByteArrayInputStream(pngBytes), MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT, true, true);
            }
            if (!decoded.isError()) {
                imageCache.put(entry.id(), decoded);
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static final class EntryCellContent {
        private final HBox root;
        private final Label textLabel;
        private final Label caretLabel;
        private final Pane ringPane;
        private final Arc progressArc;
        private ClipboardEntry boundEntry;
        private long boundExpiryMillis;

        private EntryCellContent(HBox root, Label textLabel, Label caretLabel, Pane ringPane, Arc progressArc) {
            this.root = root;
            this.textLabel = textLabel;
            this.caretLabel = caretLabel;
            this.ringPane = ringPane;
            this.progressArc = progressArc;
        }

        void update(ClipboardEntry entry, String text, boolean isImage, boolean isHovered, long expiryMillis) {
            this.boundEntry = entry;
            this.boundExpiryMillis = expiryMillis;
            textLabel.setText(text);
            boolean showCaret = isImage && isHovered;
            caretLabel.setVisible(showCaret);
            caretLabel.setManaged(showCaret);
            refreshCountdown(Instant.now());
        }

        void clear() {
            this.boundEntry = null;
            this.boundExpiryMillis = 0L;
        }

        void refreshCountdown(Instant now) {
            if (boundEntry == null || !boundEntry.sensitive()) {
                ringPane.setVisible(false);
                ringPane.setManaged(false);
                return;
            }
            double remainingFraction = boundEntry.remainingFraction(now, boundExpiryMillis);
            boolean showRing = remainingFraction > 0.0;
            ringPane.setVisible(showRing);
            ringPane.setManaged(showRing);
            if (showRing) {
                progressArc.setLength(-360.0 * Math.max(0.0, Math.min(1.0, remainingFraction)));
            }
        }

        HBox root() {
            return root;
        }
    }
}
