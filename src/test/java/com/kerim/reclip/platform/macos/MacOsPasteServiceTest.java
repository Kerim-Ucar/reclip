package com.kerim.reclip.platform.macos;

import com.kerim.reclip.platform.PasteResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacOsPasteServiceTest {

    @Test
    void pasteResultFactoryMethodsPreserveState() {
        PasteResult success = PasteResult.success();
        assertTrue(success.pasted());
        assertTrue(success.errorMessage().isEmpty());

        PasteResult failure = PasteResult.failure("Accessibility required");
        assertFalse(failure.pasted());
        assertEquals("Accessibility required", failure.errorMessage().orElseThrow());
    }

    @Test
    void accessibilityTrustCheckDoesNotThrow() {
        boolean trusted = MacOsPermissions.isAccessibilityTrusted();
        assertNotNull(Boolean.valueOf(trusted));
        assertFalse(MacOsPermissions.accessibilityGuidance().isBlank());
    }
}
