package com.kerim.reclip.clipboard;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable event representing newly observed clipboard text.
 *
 * @param text       the observed clipboard text
 * @param observedAt the timestamp when the text was observed
 */
public record ClipboardTextEvent(String text, Instant observedAt) {

    public ClipboardTextEvent {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }
}
