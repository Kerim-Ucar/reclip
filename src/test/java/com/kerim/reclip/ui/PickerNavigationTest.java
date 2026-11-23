package com.kerim.reclip.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PickerNavigationTest {

    @Test
    void returnsMinusOneWhenListIsEmpty() {
        assertEquals(-1, PickerWindow.nextSelectionIndex(0, 0, 1));
        assertEquals(-1, PickerWindow.nextSelectionIndex(-1, 0, -1));
    }

    @Test
    void selectsFirstItemWhenNothingIsSelected() {
        assertEquals(0, PickerWindow.nextSelectionIndex(-1, 3, 1));
        assertEquals(0, PickerWindow.nextSelectionIndex(-1, 3, -1));
    }

    @Test
    void movesUpAndDownClampedWithinBounds() {
        assertEquals(1, PickerWindow.nextSelectionIndex(0, 3, 1));
        assertEquals(2, PickerWindow.nextSelectionIndex(1, 3, 1));
        assertEquals(2, PickerWindow.nextSelectionIndex(2, 3, 1));
        assertEquals(1, PickerWindow.nextSelectionIndex(2, 3, -1));
        assertEquals(0, PickerWindow.nextSelectionIndex(0, 3, -1));
    }
}
