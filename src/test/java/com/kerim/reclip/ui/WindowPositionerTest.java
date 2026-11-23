package com.kerim.reclip.ui;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowPositionerTest {

    @Test
    void positionsBottomRightOfCursorWhenItFits() {
        Rectangle2D screen = new Rectangle2D(0, 0, 1920, 1080);

        Point2D pos = WindowPositioner.positionNearCursor(400, 300, 320, 176, screen);

        assertEquals(404, pos.getX());
        assertEquals(304, pos.getY());
    }

    @Test
    void flipsToLeftAndAboveWhenNearBottomRightEdge() {
        Rectangle2D screen = new Rectangle2D(0, 0, 1920, 1080);

        Point2D pos = WindowPositioner.positionNearCursor(1900, 1060, 320, 176, screen);

        assertEquals(1900 - 4 - 320, pos.getX());
        assertEquals(1060 - 4 - 176, pos.getY());
    }

    @Test
    void flipsOnlyVerticallyWhenNearBottomEdge() {
        Rectangle2D screen = new Rectangle2D(0, 0, 1920, 1080);

        Point2D pos = WindowPositioner.positionNearCursor(400, 1000, 320, 176, screen);

        assertEquals(404, pos.getX());
        assertEquals(1000 - 4 - 176, pos.getY());
    }

    @Test
    void clampsWithinSecondaryMonitorWithNegativeCoordinates() {
        Rectangle2D leftMonitor = new Rectangle2D(-1920, 0, 1920, 1080);

        Point2D pos = WindowPositioner.positionNearCursor(-1925, -5, 320, 176, leftMonitor);

        assertEquals(-1912, pos.getX());
        assertEquals(8, pos.getY());
    }
}
