package com.kerim.reclip.app;

import com.kerim.reclip.ui.PickerWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReclipAppTest {

    @Test
    void appNameIsReclip() {
        assertEquals("Reclip", ReclipApp.APP_NAME);
    }

    @Test
    void defaultWindowDimensionsArePositive() {
        assertTrue(PickerWindow.DEFAULT_WIDTH > 0);
        assertTrue(PickerWindow.DEFAULT_HEIGHT > 0);
    }
}
