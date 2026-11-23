package com.kerim.reclip.config;

import com.kerim.reclip.hotkey.Shortcut;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cross-platform {@link SettingsRepository} backed by a {@link Properties} file.
 */
public final class PropertiesSettingsRepository implements SettingsRepository {

    private static final String KEY_SHORTCUT_KEYS = "shortcut.keys";
    private static final String KEY_SHORTCUT_MODIFIERS = "shortcut.modifiers";
    private static final String KEY_SHORTCUT_KEY = "shortcut.key";
    private static final String KEY_MONITORING_PAUSED = "monitoring.paused";
    private static final String KEY_MAX_HISTORY = "history.maxEntries";
    private static final String KEY_VISIBLE_COPIES = "ui.visibleCopies";
    private static final String KEY_PERSIST_HISTORY = "history.persist";
    private static final String KEY_PERSISTED_COUNT = "persisted.count";
    private static final String KEY_PERSISTED_PREFIX = "persisted.";

    private final Path settingsPath;

    public PropertiesSettingsRepository(Path settingsPath) {
        this.settingsPath = Objects.requireNonNull(settingsPath, "settingsPath must not be null");
    }

    public static Path defaultSettingsPath() {
        return Path.of(System.getProperty("user.home"), ".reclip", "settings.properties");
    }

    @Override
    public AppSettings load() {
        if (!Files.exists(settingsPath)) {
            return AppSettings.defaults();
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(settingsPath)) {
            props.load(in);
        } catch (IOException e) {
            return AppSettings.defaults();
        }

        Shortcut shortcut = parseShortcut(
                props.getProperty(KEY_SHORTCUT_KEYS),
                props.getProperty(KEY_SHORTCUT_MODIFIERS),
                props.getProperty(KEY_SHORTCUT_KEY));
        boolean paused = Boolean.parseBoolean(props.getProperty(KEY_MONITORING_PAUSED, "false"));
        int maxHistory = parsePositiveInt(
                props.getProperty(KEY_MAX_HISTORY),
                AppSettings.DEFAULT_MAX_HISTORY_ENTRIES);
        int visibleCopies = parseClampedInt(
                props.getProperty(KEY_VISIBLE_COPIES),
                AppSettings.DEFAULT_VISIBLE_COPIES,
                AppSettings.MIN_VISIBLE_COPIES,
                AppSettings.MAX_VISIBLE_COPIES);
        boolean persistHistory = Boolean.parseBoolean(props.getProperty(KEY_PERSIST_HISTORY, "false"));
        List<PersistedSnippet> persistedSnippets = parsePersistedSnippets(props);

        return new AppSettings(shortcut, paused, maxHistory, visibleCopies, persistHistory, persistedSnippets);
    }

    @Override
    public void save(AppSettings settings) {
        Objects.requireNonNull(settings, "settings must not be null");
        Properties props = new Properties();
        String serializedKeys = settings.shortcut().keys().stream()
                .sorted()
                .collect(Collectors.joining(","));
        props.setProperty(KEY_SHORTCUT_KEYS, serializedKeys);
        props.setProperty(KEY_MONITORING_PAUSED, Boolean.toString(settings.monitoringPaused()));
        props.setProperty(KEY_MAX_HISTORY, Integer.toString(settings.maxHistoryEntries()));
        props.setProperty(KEY_VISIBLE_COPIES, Integer.toString(settings.visibleCopies()));
        props.setProperty(KEY_PERSIST_HISTORY, Boolean.toString(settings.persistHistory()));
        List<PersistedSnippet> persisted = settings.persistedSnippets();
        props.setProperty(KEY_PERSISTED_COUNT, Integer.toString(persisted.size()));
        for (int i = 0; i < persisted.size(); i++) {
            PersistedSnippet snippet = persisted.get(i);
            props.setProperty(KEY_PERSISTED_PREFIX + i + ".label", snippet.label());
            props.setProperty(KEY_PERSISTED_PREFIX + i + ".content", snippet.content());
        }

        try {
            Path parent = settingsPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(settingsPath)) {
                props.store(out, "Reclip user settings");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save settings to " + settingsPath, e);
        }
    }

    private static Shortcut parseShortcut(String rawKeys, String legacyModifiers, String legacyKey) {
        Set<String> keys = new LinkedHashSet<>();
        if (rawKeys != null && !rawKeys.isBlank()) {
            for (String token : rawKeys.split(",")) {
                String trimmed = token.trim().toUpperCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    keys.add(trimmed);
                }
            }
        } else if (legacyModifiers != null && !legacyModifiers.isBlank() && legacyKey != null && !legacyKey.isBlank()) {
            for (String token : legacyModifiers.split(",")) {
                String trimmed = token.trim().toUpperCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    keys.add(trimmed);
                }
            }
            keys.add(legacyKey.trim().toUpperCase(Locale.ROOT));
        }
        return keys.isEmpty() ? Shortcut.defaultShortcut() : new Shortcut(keys);
    }

    private static List<PersistedSnippet> parsePersistedSnippets(Properties props) {
        int count = parsePositiveInt(props.getProperty(KEY_PERSISTED_COUNT), 0);
        if (count <= 0) {
            return List.of();
        }
        List<PersistedSnippet> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String content = props.getProperty(KEY_PERSISTED_PREFIX + i + ".content");
            String label = props.getProperty(KEY_PERSISTED_PREFIX + i + ".label", "");
            if (content == null) {
                content = props.getProperty(KEY_PERSISTED_PREFIX + i);
            }
            if (content != null && !content.isEmpty()) {
                result.add(new PersistedSnippet(label, content));
            }
        }
        return List.copyOf(result);
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseClampedInt(String raw, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return (value >= min && value <= max) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
