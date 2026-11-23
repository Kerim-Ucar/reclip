package com.kerim.reclip.config;

import java.util.Objects;

/**
 * A user-managed persisted clipboard snippet with an optional display label.
 *
 * @param label   optional display name (blank means fall back to {@code content})
 * @param content the text copied to the clipboard when selected
 */
public record PersistedSnippet(String label, String content) {

    public PersistedSnippet {
        label = label == null ? "" : label.trim();
        Objects.requireNonNull(content, "content must not be null");
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
    }

    /**
     * Returns the label if non-blank, otherwise {@link #content()}.
     *
     * @return display name for UI presentation
     */
    public String displayName() {
        return label.isEmpty() ? content : label;
    }
}
