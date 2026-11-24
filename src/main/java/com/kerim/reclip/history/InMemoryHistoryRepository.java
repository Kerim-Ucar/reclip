package com.kerim.reclip.history;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Thread-safe in-memory {@link HistoryRepository}.
 */
public class InMemoryHistoryRepository implements HistoryRepository {

    private static final Comparator<ClipboardEntry> DISPLAY_ORDER = Comparator
            .comparing(ClipboardEntry::pinned).reversed()
            .thenComparing(ClipboardEntry::lastUsedAt, Comparator.reverseOrder())
            .thenComparing(ClipboardEntry::createdAt, Comparator.reverseOrder());

    private final Map<UUID, ClipboardEntry> entriesById = new LinkedHashMap<>();

    @Override
    public synchronized void save(ClipboardEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        entriesById.put(entry.id(), entry);
    }

    @Override
    public synchronized Optional<ClipboardEntry> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(entriesById.get(id));
    }

    @Override
    public synchronized Optional<ClipboardEntry> findByContent(ContentType contentType, String content) {
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        return entriesById.values().stream()
                .filter(entry -> entry.contentType() == contentType && entry.content().equals(content))
                .findFirst();
    }

    @Override
    public synchronized List<ClipboardEntry> findAll() {
        return entriesById.values().stream()
                .sorted(DISPLAY_ORDER)
                .toList();
    }

    @Override
    public synchronized List<ClipboardEntry> search(String query) {
        if (query == null || query.isBlank()) {
            return findAll();
        }
        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        return entriesById.values().stream()
                .filter(entry -> matchesQuery(entry, normalizedQuery))
                .sorted(Comparator
                        .comparing((ClipboardEntry entry) -> !entry.pinned())
                        .thenComparingInt(entry -> matchScore(entry.displayText(), normalizedQuery))
                        .thenComparing(ClipboardEntry::lastUsedAt, Comparator.reverseOrder()))
                .toList();
    }

    private static boolean matchesQuery(ClipboardEntry entry, String normalizedQuery) {
        if (entry.label().toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
            return true;
        }
        if (entry.contentType() == ContentType.IMAGE) {
            return false;
        }
        return entry.content().toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    @Override
    public synchronized boolean deleteById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return entriesById.remove(id) != null;
    }

    @Override
    public synchronized void clearUnpinned() {
        entriesById.values().removeIf(entry -> !entry.pinned());
    }

    @Override
    public synchronized int pruneUnpinned(int maxUnpinned) {
        if (maxUnpinned < 0) {
            throw new IllegalArgumentException("maxUnpinned must be non-negative");
        }
        List<ClipboardEntry> unpinnedOldestFirst = entriesById.values().stream()
                .filter(entry -> !entry.pinned())
                .sorted(Comparator.comparing(ClipboardEntry::lastUsedAt)
                        .thenComparing(ClipboardEntry::createdAt))
                .toList();

        int excess = unpinnedOldestFirst.size() - maxUnpinned;
        if (excess <= 0) {
            return 0;
        }
        for (int i = 0; i < excess; i++) {
            entriesById.remove(unpinnedOldestFirst.get(i).id());
        }
        return excess;
    }

    private static int matchScore(String content, String normalizedQuery) {
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.startsWith(normalizedQuery)) {
            return 0;
        }
        return 1;
    }
}
