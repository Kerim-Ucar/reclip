package com.kerim.reclip.clipboard;

import com.kerim.reclip.history.ContentType;

import java.time.Instant;
import java.util.Objects;

/**
 * Event emitted when the system clipboard changes to new typed content.
 *
 * @param contentType  detected content type
 * @param content      serialized content
 * @param displayLabel optional human-readable summary (e.g. image dimensions or file names)
 * @param observedAt   timestamp when the change was observed
 * @param sensitive    whether the content came from a concealed pasteboard type or password manager
 */
public record ClipboardContentEvent(
        ContentType contentType,
        String content,
        String displayLabel,
        Instant observedAt,
        boolean sensitive) {

    public ClipboardContentEvent(
            ContentType contentType,
            String content,
            String displayLabel,
            Instant observedAt) {
        this(contentType, content, displayLabel, observedAt, false);
    }

    public ClipboardContentEvent {
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        displayLabel = displayLabel == null ? "" : displayLabel.trim();
    }
}
