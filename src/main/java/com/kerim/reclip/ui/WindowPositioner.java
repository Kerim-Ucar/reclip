package com.kerim.reclip.ui;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;

import java.util.Objects;

/**
 * Computes popup coordinates relative to the mouse cursor, placing the box to
 * the bottom-right by default and flipping horizontally or vertically when it
 * would overflow the active display's visual bounds.
 */
final class WindowPositioner {

    private static final double CURSOR_OFFSET = 4.0;
    private static final double SCREEN_MARGIN = 8.0;

    private WindowPositioner() {
    }

    static Point2D positionNearCursor(
            double cursorX,
            double cursorY,
            double windowWidth,
            double windowHeight,
            Rectangle2D screenBounds) {
        Objects.requireNonNull(screenBounds, "screenBounds must not be null");

        double minX = screenBounds.getMinX() + SCREEN_MARGIN;
        double maxX = Math.max(minX, screenBounds.getMaxX() - windowWidth - SCREEN_MARGIN);
        double minY = screenBounds.getMinY() + SCREEN_MARGIN;
        double maxY = Math.max(minY, screenBounds.getMaxY() - windowHeight - SCREEN_MARGIN);

        boolean fitsRight = cursorX + CURSOR_OFFSET + windowWidth <= screenBounds.getMaxX() - SCREEN_MARGIN;
        boolean fitsBelow = cursorY + CURSOR_OFFSET + windowHeight <= screenBounds.getMaxY() - SCREEN_MARGIN;

        double rawX = fitsRight
                ? cursorX + CURSOR_OFFSET
                : cursorX - CURSOR_OFFSET - windowWidth;
        double rawY = fitsBelow
                ? cursorY + CURSOR_OFFSET
                : cursorY - CURSOR_OFFSET - windowHeight;

        return new Point2D(clamp(rawX, minX, maxX), clamp(rawY, minY, maxY));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
