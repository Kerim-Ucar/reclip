package com.kerim.reclip.platform;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of attempting to simulate a paste action into the previously focused application.
 *
 * @param pasted       whether the paste keystroke was dispatched
 * @param errorMessage optional user-facing error or permission guidance when paste did not run
 */
public record PasteResult(boolean pasted, Optional<String> errorMessage) {

    public PasteResult {
        Objects.requireNonNull(errorMessage, "errorMessage must not be null");
    }

    public static PasteResult success() {
        return new PasteResult(true, Optional.empty());
    }

    public static PasteResult failure(String message) {
        Objects.requireNonNull(message, "message must not be null");
        return new PasteResult(false, Optional.of(message));
    }
}
