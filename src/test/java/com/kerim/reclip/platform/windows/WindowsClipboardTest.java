package com.kerim.reclip.platform.windows;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WindowsClipboardTest {

    @Test
    void nativeHelpersFailGracefully() {
        assertDoesNotThrow(WindowsClipboard::sequenceNumber);
        assertDoesNotThrow(WindowsClipboard::hasConcealedContent);
        assertDoesNotThrow(WindowsClipboard::isPasswordManagerFrontmost);
        assertDoesNotThrow(WindowsWindowHelper::frontmostAppIdentifiers);
        assertFalse(WindowsClipboard.isFrontmostAppExcluded(List.of()));
    }
}
