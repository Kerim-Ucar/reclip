package com.kerim.reclip.platform.windows;

import com.kerim.reclip.platform.PasteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsPasteServiceTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void failsGracefullyOffWindows() {
        WindowsPasteService service = new WindowsPasteService();
        PasteResult result = service.paste();
        assertFalse(result.pasted());
        assertTrue(result.errorMessage().isPresent());
        assertNotNull(result.errorMessage().get());
    }
}
