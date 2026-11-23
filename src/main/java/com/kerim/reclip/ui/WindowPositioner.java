package com.kerim.reclip.ui;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;

import java.util.List;
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

    /**
     * Selects the display bounds containing {@code (cursorX, cursorY)}, or the
     * nearest display if the cursor lies on a gap or edge between monitors.
     *
     * @param cursorX       cursor X coordinate
     * @param cursorY       cursor Y coordinate
     * @param screenBounds  candidate display visual bounds
     * @param fallback      fallback bounds when {@code screenBounds} is empty
     * @return selected display bounds
     */
    static Rectangle2D selectTargetScreenBounds(
            double cursorX,
            double cursorY,
            List<Rectangle2D> screenBounds,
            Rectangle2D fallback) {
        Objects.requireNonNull(screenBounds, "screenBounds must not be null");
        Objects.requireNonNull(fallback, "fallback must not be null");
        if (screenBounds.isEmpty()) {
            return fallback;
        }
        Rectangle2D best = screenBounds.getFirst();
        double bestDistanceSq = Double.MAX_VALUE;
        for (Rectangle2D bounds : screenBounds) {
            if (bounds.contains(cursorX, cursorY)) {
                return bounds;
            }
            double dx = distanceToRange(cursorX, bounds.getMinX(), bounds.getMaxX());
            double dy = distanceToRange(cursorY, bounds.getMinY(), bounds.getMaxY());
            double distSq = dx * dx + dy * dy;
            if (distSq < bestDistanceSq) {
                bestDistanceSq = distSq;
                best = bounds;
            }
        }
        return best;
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

    private static double distanceToRange(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
