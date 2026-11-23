package com.kerim.reclip.history;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence and query abstraction for clipboard history entries.
 */
public interface HistoryRepository {

    /**
     * Inserts or updates an entry by its {@link ClipboardEntry#id()}.
     *
     * @param entry the entry to save
     */
    void save(ClipboardEntry entry);

    /**
     * Finds an existing entry matching the given content type and exact content.
     *
     * @param contentType the content type
     * @param content     the exact content
     * @return matching entry if present
     */
    Optional<ClipboardEntry> findByContent(ContentType contentType, String content);

    /**
     * Returns all entries ordered for display (pinned first, then most recently used).
     *
     * @return immutable list of entries
     */
    List<ClipboardEntry> findAll();

    /**
     * Searches entries matching the given query string.
     *
     * @param query search text; blank returns all entries
     * @return immutable list of matching entries
     */
    List<ClipboardEntry> search(String query);

    /**
     * Deletes an entry by id.
     *
     * @param id the entry id
     * @return {@code true} if an entry was removed
     */
    boolean deleteById(UUID id);

    /**
     * Removes all unpinned entries.
     */
    void clearUnpinned();

    /**
     * Prunes the oldest unpinned entries so at most {@code maxUnpinned} remain.
     *
     * @param maxUnpinned maximum number of unpinned entries to keep
     * @return number of removed entries
     */
    int pruneUnpinned(int maxUnpinned);
}
