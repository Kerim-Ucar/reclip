package com.kerim.reclip.history;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Coordinates clipboard history recording, deduplication, retention, and search.
 */
public class HistoryService {

    public static final int DEFAULT_MAX_UNPINNED_ENTRIES = 100;

    private final HistoryRepository repository;
    private final int maxUnpinnedEntries;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    /**
     * Creates a history service with {@link #DEFAULT_MAX_UNPINNED_ENTRIES}.
     *
     * @param repository backing history repository
     */
    public HistoryService(HistoryRepository repository) {
        this(repository, DEFAULT_MAX_UNPINNED_ENTRIES);
    }

    /**
     * Creates a history service with a custom unpinned retention limit.
     *
     * @param repository         backing history repository
     * @param maxUnpinnedEntries maximum number of unpinned entries to retain; must be positive
     */
    public HistoryService(HistoryRepository repository, int maxUnpinnedEntries) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        if (maxUnpinnedEntries <= 0) {
            throw new IllegalArgumentException("maxUnpinnedEntries must be positive");
        }
        this.maxUnpinnedEntries = maxUnpinnedEntries;
    }

    /**
     * Records captured clipboard text, deduplicating against existing entries and
     * enforcing the configured retention limit.
     *
     * @param text       the captured text
     * @param observedAt when the text was observed
     * @return the saved or promoted {@link ClipboardEntry}
     */
    public ClipboardEntry recordText(String text, Instant observedAt) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }

        Optional<ClipboardEntry> existing = repository.findByContent(ContentType.TEXT, text);
        ClipboardEntry saved = existing
                .map(entry -> entry.withLastUsedAt(observedAt))
                .orElseGet(() -> new ClipboardEntry(
                        UUID.randomUUID(),
                        text,
                        ContentType.TEXT,
                        observedAt,
                        observedAt,
                        false));

        repository.save(saved);
        repository.pruneUnpinned(maxUnpinnedEntries);
        notifyListeners();
        return saved;
    }

    /**
     * Searches clipboard history for the given query.
     *
     * @param query search query; blank returns all entries
     * @return matching entries ordered for display
     */
    public List<ClipboardEntry> search(String query) {
        return repository.search(query);
    }

    /**
     * Returns all stored clipboard entries ordered for display.
     *
     * @return all entries
     */
    public List<ClipboardEntry> listAll() {
        return repository.findAll();
    }

    /**
     * Toggles the pinned status of the given entry and notifies listeners.
     *
     * @param id entry id
     * @return the updated entry, or empty if not found
     */
    public Optional<ClipboardEntry> togglePinned(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        Optional<ClipboardEntry> existing = repository.findById(id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        ClipboardEntry updated = existing.get().withPinned(!existing.get().pinned());
        repository.save(updated);
        notifyListeners();
        return Optional.of(updated);
    }

    /**
     * Deletes an entry by id and notifies listeners if it existed.
     *
     * @param id entry id
     * @return {@code true} if an entry was removed
     */
    public boolean deleteEntry(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        boolean removed = repository.deleteById(id);
        if (removed) {
            notifyListeners();
        }
        return removed;
    }

    /**
     * Removes all unpinned entries and notifies listeners.
     */
    public void clearUnpinned() {
        repository.clearUnpinned();
        notifyListeners();
    }

    /**
     * Registers a listener notified whenever history changes.
     *
     * @param listener callback to invoke on history updates
     */
    public void addChangeListener(Runnable listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        changeListeners.add(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }
}
