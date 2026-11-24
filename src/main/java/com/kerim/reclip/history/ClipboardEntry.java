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
 * @param sensitive   whether the entry is a password/secret that expires automatically
 * @param expiresAt   optional expiration timestamp for sensitive entries (null if non-expiring)
 */
public record ClipboardEntry(
        UUID id,
        String content,
        ContentType contentType,
        Instant createdAt,
        Instant lastUsedAt,
        boolean pinned,
        String label,
        boolean sensitive,
        Instant expiresAt) {

    public ClipboardEntry(
            UUID id,
            String content,
            ContentType contentType,
            Instant createdAt,
            Instant lastUsedAt,
            boolean pinned) {
        this(id, content, contentType, createdAt, lastUsedAt, pinned, "", false, null);
    }

    public ClipboardEntry(
            UUID id,
            String content,
            ContentType contentType,
            Instant createdAt,
            Instant lastUsedAt,
            boolean pinned,
            String label) {
        this(id, content, contentType, createdAt, lastUsedAt, pinned, label, false, null);
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
        if (!label.isEmpty()) {
            return label;
        }
        return switch (contentType) {
            case TEXT -> content;
            case IMAGE -> "Image";
            case FILES -> {
                String firstLine = content.split("\\R", 2)[0];
                int lastSlash = Math.max(firstLine.lastIndexOf('/'), firstLine.lastIndexOf('\\'));
                String name = lastSlash >= 0 ? firstLine.substring(lastSlash + 1) : firstLine;
                yield "File: " + name;
            }
        };
    }

    /**
     * Returns a type-tagged display string suitable for list cells in the picker and dashboard.
     *
     * @return formatted entry label with type badge for non-text entries
     */
    public String displayWithTypeBadge() {
        return switch (contentType) {
            case TEXT -> displayText();
            case IMAGE -> "[Image] " + displayText();
            case FILES -> "[Files] " + displayText();
        };
    }

    /**
     * Returns a copy of this entry with an updated {@code lastUsedAt} timestamp.
     *
     * @param timestamp the new last-used timestamp
     * @return updated clipboard entry
     */
    public ClipboardEntry withLastUsedAt(Instant timestamp) {
        return new ClipboardEntry(id, content, contentType, createdAt, timestamp, pinned, label, sensitive, expiresAt);
    }

    /**
     * Returns a copy of this entry with the given pin state.
     *
     * @param pinned whether the entry should be pinned
     * @return updated clipboard entry
     */
    public ClipboardEntry withPinned(boolean pinned) {
        return new ClipboardEntry(id, content, contentType, createdAt, lastUsedAt, pinned, label, sensitive, expiresAt);
    }

    /**
     * Returns a copy of this entry with the given display label.
     *
     * @param newLabel optional display label
     * @return updated clipboard entry
     */
    public ClipboardEntry withLabel(String newLabel) {
        return new ClipboardEntry(id, content, contentType, createdAt, lastUsedAt, pinned, newLabel, sensitive, expiresAt);
    }

    /**
     * Returns a copy of this entry with updated sensitive flag and expiration time.
     */
    public ClipboardEntry withSensitiveExpiry(boolean isSensitive, Instant newExpiresAt) {
        return new ClipboardEntry(id, content, contentType, createdAt, lastUsedAt, pinned, label, isSensitive, newExpiresAt);
    }

    /**
     * Returns the remaining fraction in {@code [0.0, 1.0]} before {@code expiresAt},
     * or {@code 0.0} if expired or non-expiring.
     */
    public double remainingFraction(Instant now, long totalDurationMillis) {
        if (!sensitive || expiresAt == null || totalDurationMillis <= 0) {
            return 0.0;
        }
        long remainingMs = java.time.Duration.between(now, expiresAt).toMillis();
        if (remainingMs <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) remainingMs / totalDurationMillis);
    }
}
