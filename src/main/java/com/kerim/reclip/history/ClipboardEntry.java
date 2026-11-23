package com.kerim.reclip.history;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable clipboard history entry.
 *
 * @param id          unique identifier of the entry
 * @param content     captured clipboard content
 * @param contentType type of clipboard content
 * @param createdAt   when the entry was first captured
 * @param lastUsedAt  when the entry was most recently copied or selected
 * @param pinned      whether the entry is pinned against retention pruning
 * @param label       optional display label (blank means display {@code content})
 */
public record ClipboardEntry(
        UUID id,
        String content,
        ContentType contentType,
        Instant createdAt,
        Instant lastUsedAt,
        boolean pinned,
        String label) {

    public ClipboardEntry(
            UUID id,
            String content,
            ContentType contentType,
            Instant createdAt,
            Instant lastUsedAt,
            boolean pinned) {
        this(id, content, contentType, createdAt, lastUsedAt, pinned, "");
    }

    public ClipboardEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(lastUsedAt, "lastUsedAt must not be null");
        label = label == null ? "" : label.trim();
    }

    /**
     * Returns the display label if non-blank, otherwise {@link #content()}.
     *
     * @return text to show in the picker
     */
    public String displayText() {
        return label.isEmpty() ? content : label;
    }

    /**
     * Returns a copy of this entry with an updated {@code lastUsedAt} timestamp.
     *
     * @param timestamp the new last-used timestamp
     * @return updated clipboard entry
     */
    public ClipboardEntry withLastUsedAt(Instant timestamp) {
        return new ClipboardEntry(id, content, contentType, createdAt, timestamp, pinned, label);
    }

    /**
     * Returns a copy of this entry with the given pin state.
     *
     * @param pinned whether the entry should be pinned
     * @return updated clipboard entry
     */
    public ClipboardEntry withPinned(boolean pinned) {
        return new ClipboardEntry(id, content, contentType, createdAt, lastUsedAt, pinned, label);
    }

    /**
     * Returns a copy of this entry with the given display label.
     *
     * @param newLabel optional display label
     * @return updated clipboard entry
     */
    public ClipboardEntry withLabel(String newLabel) {
        return new ClipboardEntry(id, content, contentType, createdAt, lastUsedAt, pinned, newLabel);
    }
}
