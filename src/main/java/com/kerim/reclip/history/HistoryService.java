package com.kerim.reclip.history;

import java.time.Duration;
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
    private final List<Runnable> deleteListeners = new CopyOnWriteArrayList<>();

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
        return recordEntry(ContentType.TEXT, text, "", observedAt);
    }

    /**
     * Records captured typed clipboard content (text, image, or files), deduplicating
     * against existing entries and enforcing the configured retention limit.
     *
     * @param contentType the content type
     * @param content     the captured serialized content
     * @param label       optional display label (e.g. image dimensions or file names)
     * @param observedAt  when the content was observed
     * @return the saved or promoted {@link ClipboardEntry}
     */
    public ClipboardEntry recordEntry(ContentType contentType, String content, String label, Instant observedAt) {
        return recordEntry(contentType, content, label, observedAt, false, Duration.ZERO);
    }

    /**
     * Records captured typed clipboard content (text, image, or files) with optional
     * sensitive expiration, deduplicating against existing entries and enforcing retention.
     *
     * @param contentType the content type
     * @param content     the captured serialized content
     * @param label       optional display label (e.g. image dimensions or file names)
     * @param observedAt  when the content was observed
     * @param sensitive   whether the entry is a secret/password that expires automatically
     * @param expiry      expiration duration when {@code sensitive} is {@code true}
     * @return the saved or promoted {@link ClipboardEntry}
     */
    public ClipboardEntry recordEntry(
            ContentType contentType,
            String content,
            String label,
            Instant observedAt,
            boolean sensitive,
            Duration expiry) {
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        Objects.requireNonNull(expiry, "expiry must not be null");
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }

        Instant expiresAt = sensitive ? observedAt.plus(expiry) : null;
        Optional<ClipboardEntry> existing = repository.findByContent(contentType, content);
        ClipboardEntry saved = existing
                .map(entry -> {
                    boolean keepSensitive = entry.sensitive() || sensitive;
                    Instant nextExpiry = sensitive ? expiresAt : entry.expiresAt();
                    return entry.withLastUsedAt(observedAt).withSensitiveExpiry(keepSensitive, nextExpiry);
                })
                .orElseGet(() -> new ClipboardEntry(
                        UUID.randomUUID(),
                        content,
                        contentType,
                        observedAt,
                        observedAt,
                        false,
                        label,
                        sensitive,
                        expiresAt));

        repository.save(saved);
        repository.pruneUnpinned(maxUnpinnedEntries);
        notifyListeners();
        return saved;
    }

    /**
     * Returns {@code true} if at least one sensitive entry with an expiration is present.
     *
     * @return whether any sensitive entry exists
     */
    public boolean hasSensitiveEntries() {
        return nextSensitiveExpiry().isPresent();
    }

    /**
     * Returns the earliest expiration timestamp among sensitive entries, if any exist.
     *
     * @return earliest expiration instant, or empty if no sensitive entries have an expiry
     */
    public Optional<Instant> nextSensitiveExpiry() {
        Instant earliest = null;
        for (ClipboardEntry entry : repository.findAll()) {
            if (entry.sensitive() && entry.expiresAt() != null) {
                if (earliest == null || entry.expiresAt().isBefore(earliest)) {
                    earliest = entry.expiresAt();
                }
            }
        }
        return Optional.ofNullable(earliest);
    }

    /**
     * Removes any sensitive entries whose expiration timestamp has passed.
     *
     * @param now current timestamp
     * @return number of expired entries removed
     */
    public int purgeExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        int removed = 0;
        for (ClipboardEntry entry : repository.findAll()) {
            if (entry.sensitive() && entry.expiresAt() != null && !entry.expiresAt().isAfter(now)) {
                if (repository.deleteById(entry.id())) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            notifyDeleteListeners();
            notifyListeners();
        }
        return removed;
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
            notifyDeleteListeners();
            notifyListeners();
        }
        return removed;
    }

    /**
     * Removes all unpinned entries and notifies listeners.
     */
    public void clearUnpinned() {
        repository.clearUnpinned();
        notifyDeleteListeners();
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

    /**
     * Registers a listener notified when entries are deleted or cleared.
     *
     * @param listener callback to invoke on deletion
     */
    public void addDeleteListener(Runnable listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        deleteListeners.add(listener);
    }

    private void notifyDeleteListeners() {
        for (Runnable listener : deleteListeners) {
            listener.run();
        }
    }

    private void notifyListeners() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }
}
