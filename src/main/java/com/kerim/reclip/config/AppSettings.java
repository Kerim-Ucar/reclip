package com.kerim.reclip.config;

import com.kerim.reclip.hotkey.Shortcut;

import java.util.List;
import java.util.Objects;

/**
 * Immutable user configuration for Reclip.
 *
 * @param shortcut          global shortcut used to toggle the picker
 * @param monitoringPaused  whether clipboard monitoring starts paused
 * @param maxHistoryEntries maximum number of unpinned entries retained
 * @param visibleCopies     number of recent copies shown in the popup
 * @param persistHistory    whether clipboard history is persisted locally across restarts
 * @param persistedSnippets user-managed persisted snippets always available in the picker
 */
public record AppSettings(
        Shortcut shortcut,
        boolean monitoringPaused,
        int maxHistoryEntries,
        int visibleCopies,
        boolean persistHistory,
        List<PersistedSnippet> persistedSnippets) {

    public static final int DEFAULT_MAX_HISTORY_ENTRIES = 100;
    public static final int DEFAULT_VISIBLE_COPIES = 3;
    public static final int MIN_VISIBLE_COPIES = 1;
    public static final int MAX_VISIBLE_COPIES = 15;

    public AppSettings {
        Objects.requireNonNull(shortcut, "shortcut must not be null");
        if (maxHistoryEntries <= 0) {
            throw new IllegalArgumentException("maxHistoryEntries must be positive");
        }
        if (visibleCopies < MIN_VISIBLE_COPIES || visibleCopies > MAX_VISIBLE_COPIES) {
            throw new IllegalArgumentException(
                    "visibleCopies must be between " + MIN_VISIBLE_COPIES + " and " + MAX_VISIBLE_COPIES);
        }
        persistedSnippets = persistedSnippets == null
                ? List.of()
                : List.copyOf(persistedSnippets);
    }

    public static AppSettings defaults() {
        return new AppSettings(
                Shortcut.defaultShortcut(),
                false,
                DEFAULT_MAX_HISTORY_ENTRIES,
                DEFAULT_VISIBLE_COPIES,
                false,
                List.of());
    }

    public AppSettings withShortcut(Shortcut newShortcut) {
        return new AppSettings(newShortcut, monitoringPaused, maxHistoryEntries, visibleCopies, persistHistory, persistedSnippets);
    }

    public AppSettings withMonitoringPaused(boolean paused) {
        return new AppSettings(shortcut, paused, maxHistoryEntries, visibleCopies, persistHistory, persistedSnippets);
    }

    public AppSettings withVisibleCopies(int newVisibleCopies) {
        return new AppSettings(shortcut, monitoringPaused, maxHistoryEntries, newVisibleCopies, persistHistory, persistedSnippets);
    }

    public AppSettings withPersistHistory(boolean newPersistHistory) {
        return new AppSettings(shortcut, monitoringPaused, maxHistoryEntries, visibleCopies, newPersistHistory, persistedSnippets);
    }

    public AppSettings withPersistedSnippets(List<PersistedSnippet> newSnippets) {
        return new AppSettings(shortcut, monitoringPaused, maxHistoryEntries, visibleCopies, persistHistory, newSnippets);
    }
}
