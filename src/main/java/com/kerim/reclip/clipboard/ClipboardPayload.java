package com.kerim.reclip.clipboard;

import com.kerim.reclip.history.ContentType;

import java.util.Objects;

/**
 * Typed clipboard payload read from a {@link ClipboardProvider}.
 *
 * @param contentType  the detected clipboard content type
 * @param content      serialized content (plain text, PNG path for images, or newline-joined paths for files)
 * @param displayLabel human-readable summary for non-text types, or blank for plain text
 * @param sensitive    whether the payload came from a concealed pasteboard type or password manager
 */
public record ClipboardPayload(
        ContentType contentType,
        String content,
        String displayLabel,
        boolean sensitive) {

    public ClipboardPayload(ContentType contentType, String content, String displayLabel) {
        this(contentType, content, displayLabel, false);
    }

    public ClipboardPayload {
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
        displayLabel = displayLabel == null ? "" : displayLabel.trim();
    }

    public ClipboardPayload withSensitive(boolean isSensitive) {
        return new ClipboardPayload(contentType, content, displayLabel, isSensitive);
    }
}
