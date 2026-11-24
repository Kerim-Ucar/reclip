package com.kerim.reclip.config;

import com.kerim.reclip.hotkey.Shortcut;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesSettingsRepositoryTest {

    @Test
    void returnsDefaultsWhenFileDoesNotExist(@TempDir Path tempDir) {
        PropertiesSettingsRepository repo = new PropertiesSettingsRepository(tempDir.resolve("missing.properties"));

        AppSettings defaults = AppSettings.defaults();
        assertFalse(defaults.autoPaste());
        assertTrue(defaults.maskPasswords());
        assertTrue(defaults.oneTimePasswords());
        assertEquals(60, defaults.passwordExpirySeconds());
        assertEquals(AppSettings.DEFAULT_EXCLUDED_APPS, defaults.excludedApps());
        assertEquals(defaults, repo.load());
    }

    @Test
    void savesAndLoadsCustomMultiKeyShortcut(@TempDir Path tempDir) {
        Path path = tempDir.resolve("nested/settings.properties");
        PropertiesSettingsRepository repo = new PropertiesSettingsRepository(path);

        Shortcut custom = Shortcut.of("ALT", "SHIFT", "A", "B");
        AppSettings expected = new AppSettings(
                custom,
                true,
                50,
                7,
                true,
                List.of(new PersistedSnippet("roblox id", "123456789"), new PersistedSnippet("", "plain")),
                true,
                List.of("1Password", "Bitwarden"),
                false,
                90,
                false);

        repo.save(expected);
        AppSettings loaded = repo.load();

        assertEquals(expected, loaded);
    }

    @Test
    void fallsBackToDefaultsOnCorruptValues(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("bad.properties");
        Files.writeString(path, "shortcut.keys=\nhistory.maxEntries=-5\n");

        PropertiesSettingsRepository repo = new PropertiesSettingsRepository(path);
        AppSettings loaded = repo.load();

        assertEquals(Shortcut.defaultShortcut(), loaded.shortcut());
        assertEquals(AppSettings.DEFAULT_MAX_HISTORY_ENTRIES, loaded.maxHistoryEntries());
        assertTrue(!loaded.monitoringPaused());
    }
}
