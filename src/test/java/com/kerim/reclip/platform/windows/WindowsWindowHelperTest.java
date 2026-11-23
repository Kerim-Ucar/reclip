package com.kerim.reclip.platform.windows;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsWindowHelperTest {

    @Test
    void detectsWindowsFromOsNameProperty() {
        boolean expected = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        assertEquals(expected, WindowsWindowHelper.isWindows());
    }

    @Test
    void nativeHelpersFailGracefullyWithoutActiveStages() {
        assertDoesNotThrow(WindowsWindowHelper::capturePreviousApp);
        assertDoesNotThrow(WindowsWindowHelper::clearPreviousApp);
        assertDoesNotThrow(WindowsWindowHelper::restorePreviousAppFocus);
        assertDoesNotThrow(WindowsWindowHelper::applyDarkTitleBar);
        assertDoesNotThrow(WindowsWindowHelper::isSystemLightTheme);
    }
}
