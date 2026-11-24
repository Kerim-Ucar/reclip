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
 * @param persistHistory         whether clipboard history is persisted locally across restarts
 * @param persistedSnippets      user-managed persisted snippets always available in the picker
 * @param autoPaste              whether selecting an item in the picker automatically pastes it
 * @param excludedApps           application names or bundle IDs ignored during clipboard monitoring
 * @param maskPasswords          whether sensitive/password entries are masked as "Password"
 * @param passwordExpirySeconds  seconds before sensitive/password entries automatically expire
 * @param oneTimePasswords       whether sensitive/password entries are removed after first use
 */
public record AppSettings(
        Shortcut shortcut,
        boolean monitoringPaused,
        int maxHistoryEntries,
        int visibleCopies,
        boolean persistHistory,
        List<PersistedSnippet> persistedSnippets,
        boolean autoPaste,
        List<String> excludedApps,
        boolean maskPasswords,
        int passwordExpirySeconds,
        boolean oneTimePasswords) {

    public static final int DEFAULT_MAX_HISTORY_ENTRIES = 100;
    public static final int DEFAULT_VISIBLE_COPIES = 3;
    public static final int MIN_VISIBLE_COPIES = 1;
    public static final int MAX_VISIBLE_COPIES = 15;
    public static final boolean DEFAULT_MASK_PASSWORDS = true;
    public static final int DEFAULT_PASSWORD_EXPIRY_SECONDS = 60;
    public static final int MIN_PASSWORD_EXPIRY_SECONDS = 10;
    public static final int MAX_PASSWORD_EXPIRY_SECONDS = 300;
    public static final boolean DEFAULT_ONE_TIME_PASSWORDS = true;
    public static final List<String> DEFAULT_EXCLUDED_APPS = List.of(
            "Passwords",
            "Keychain Access",
            "1Password",
            "Bitwarden",
            "KeePassXC");

    public AppSettings(
            Shortcut shortcut,
            boolean monitoringPaused,
            int maxHistoryEntries,
            int visibleCopies,
            boolean persistHistory,
            List<PersistedSnippet> persistedSnippets,
            boolean autoPaste) {
        this(
                shortcut,
                monitoringPaused,
                maxHistoryEntries,
                visibleCopies,
                persistHistory,
                persistedSnippets,
                autoPaste,
                DEFAULT_EXCLUDED_APPS,
                DEFAULT_MASK_PASSWORDS,
                DEFAULT_PASSWORD_EXPIRY_SECONDS,
                DEFAULT_ONE_TIME_PASSWORDS);
    }

    public AppSettings(
            Shortcut shortcut,
            boolean monitoringPaused,
            int maxHistoryEntries,
            int visibleCopies,
            boolean persistHistory,
            List<PersistedSnippet> persistedSnippets,
            boolean autoPaste,
            List<String> excludedApps) {
        this(
                shortcut,
                monitoringPaused,
                maxHistoryEntries,
                visibleCopies,
                persistHistory,
                persistedSnippets,
                autoPaste,
                excludedApps,
                DEFAULT_MASK_PASSWORDS,
                DEFAULT_PASSWORD_EXPIRY_SECONDS,
                DEFAULT_ONE_TIME_PASSWORDS);
    }

    public AppSettings(
            Shortcut shortcut,
            boolean monitoringPaused,
            int maxHistoryEntries,
            int visibleCopies,
            boolean persistHistory,
            List<PersistedSnippet> persistedSnippets,
            boolean autoPaste,
            List<String> excludedApps,
            boolean maskPasswords,
            int passwordExpirySeconds) {
        this(
                shortcut,
                monitoringPaused,
                maxHistoryEntries,
                visibleCopies,
                persistHistory,
                persistedSnippets,
                autoPaste,
                excludedApps,
                maskPasswords,
                passwordExpirySeconds,
                DEFAULT_ONE_TIME_PASSWORDS);
    }

    public AppSettings {
        Objects.requireNonNull(shortcut, "shortcut must not be null");
        if (maxHistoryEntries <= 0) {
            throw new IllegalArgumentException("maxHistoryEntries must be positive");
        }
        if (visibleCopies < MIN_VISIBLE_COPIES || visibleCopies > MAX_VISIBLE_COPIES) {
            throw new IllegalArgumentException(
                    "visibleCopies must be between " + MIN_VISIBLE_COPIES + " and " + MAX_VISIBLE_COPIES);
        }
        if (passwordExpirySeconds < MIN_PASSWORD_EXPIRY_SECONDS
                || passwordExpirySeconds > MAX_PASSWORD_EXPIRY_SECONDS) {
            throw new IllegalArgumentException(
                    "passwordExpirySeconds must be between "
                            + MIN_PASSWORD_EXPIRY_SECONDS + " and " + MAX_PASSWORD_EXPIRY_SECONDS);
        }
        persistedSnippets = persistedSnippets == null
                ? List.of()
                : List.copyOf(persistedSnippets);
        excludedApps = excludedApps == null
                ? List.of()
                : excludedApps.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();
    }

    public static AppSettings defaults() {
        return new AppSettings(
                Shortcut.defaultShortcut(),
                false,
                DEFAULT_MAX_HISTORY_ENTRIES,
                DEFAULT_VISIBLE_COPIES,
                false,
                List.of(),
                false,
                DEFAULT_EXCLUDED_APPS,
                DEFAULT_MASK_PASSWORDS,
                DEFAULT_PASSWORD_EXPIRY_SECONDS,
                DEFAULT_ONE_TIME_PASSWORDS);
    }

    public AppSettings withShortcut(Shortcut newShortcut) {
        return new AppSettings(newShortcut, monitoringPaused, maxHistoryEntries, visibleCopies, persistHistory, persistedSnippets, autoPaste, excludedApps, maskPasswords, passwordExpirySeconds, oneTimePasswords);
    }

    public AppSettings withMonitoringPaused(boolean paused) {
        return new AppSettings(shortcut, paused, maxHistoryEntries, visibleCopies, persistHistory, persistedSnippets, autoPaste, excludedApps, maskPasswords, passwordExpirySeconds, oneTimePasswords);
    }

    public AppSettings withVisibleCopies(int newVisibleCopies) {
        return new AppSettings(shortcut, monitoringPaused, maxHistoryEntries, newVisibleCopies, persistHistory, persistedSnippets, autoPaste, excludedApps, maskPasswords, passwordExpirySeconds, oneTimePasswords);
    }

    public AppSettings withPersistHistory(boolean newPersistHistory) {
        return new AppSettings(shortcut, monitoringPaused, maxHistoryEntries, visibleCopies, newPersistHistory, persistedSnippets, autoPaste, excludedApps, maskPasswords, passwordExpirySeconds, oneTimePasswords);
    }

    public AppSettings withPersistedSnippets(List<PersistedSnippet> newSnippets) {
        return new AppSettings(shortcut, monitoringPaused, maxHistoryEntries, visibleCopies, persistHistory, newSnippets, autoPaste, excludedApps, maskPasswords, passwordExpirySeconds, oneTimePasswords);
    }

    public AppSettings withAutoPaste(boolean newAutoPaste) {
        return new AppSettings(shortcut, monitoringPaused, maxHistoryEntries, visibleCopies, persistHistory, persistedSnippets, newAutoPaste, excludedApps, maskPasswords, passwordExpirySeconds, oneTimePasswords);
    }

    public AppSettings withExcludedApps(List<String> newExcludedApps) {
        return new AppSettings(shortcut, monitoringPaused, maxHistoryEntries, visibleCopies, persistHistory, persistedSnippets, autoPaste, newExcludedApps, maskPasswords, passwordExpirySeconds, oneTimePasswords);
    }

    public AppSettings withMaskPasswords(boolean newMaskPasswords) {
        return new AppSettings(shortcut, monitoringPaused, maxHistoryEntries, visibleCopies, persistHistory, persistedSnippets, autoPaste, excludedApps, newMaskPasswords, passwordExpirySeconds, oneTimePasswords);
    }

    public AppSettings withPasswordExpirySeconds(int newPasswordExpirySeconds) {
        return new AppSettings(shortcut, monitoringPaused, maxHistoryEntries, visibleCopies, persistHistory, persistedSnippets, autoPaste, excludedApps, maskPasswords, newPasswordExpirySeconds, oneTimePasswords);
    }

    public AppSettings withOneTimePasswords(boolean newOneTimePasswords) {
        return new AppSettings(shortcut, monitoringPaused, maxHistoryEntries, visibleCopies, persistHistory, persistedSnippets, autoPaste, excludedApps, maskPasswords, passwordExpirySeconds, newOneTimePasswords);
    }
}
