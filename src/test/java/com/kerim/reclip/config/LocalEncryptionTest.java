package com.kerim.reclip.config;

import com.kerim.reclip.history.ClipboardEntry;
import com.kerim.reclip.history.ContentType;
import com.kerim.reclip.history.FileHistoryRepository;
import com.kerim.reclip.hotkey.Shortcut;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalEncryptionTest {

    @Test
    void encryptsAndDecryptsAndDetectsTampering() {
        LocalEncryption encryption = LocalEncryption.withKey(LocalEncryption.generateAesKey());
        byte[] ciphertext = encryption.encrypt("secret-snippet-value");
        String raw = new String(ciphertext, StandardCharsets.UTF_8);
        assertTrue(raw.startsWith("RECLIP_ENC_V1:"));
        assertFalse(raw.contains("secret-snippet-value"));
        assertEquals("secret-snippet-value", encryption.decrypt(ciphertext));

        // Flip a byte in the Base64 payload to verify GCM authentication tag failure
        byte[] tampered = ciphertext.clone();
        tampered[tampered.length - 2] = (byte) (tampered[tampered.length - 2] == 'A' ? 'B' : 'A');
        assertThrows(IllegalStateException.class, () -> encryption.decrypt(tampered));
    }

    @Test
    void transparentlyReadsLegacyPlaintextAndEncryptsOnSave(@TempDir Path tempDir) throws IOException {
        Path settingsPath = tempDir.resolve("settings.properties");
        Files.writeString(settingsPath, "ui.visibleCopies=5\n", StandardCharsets.UTF_8);

        LocalEncryption encryption = LocalEncryption.withKey(LocalEncryption.generateAesKey());
        PropertiesSettingsRepository repo = new PropertiesSettingsRepository(settingsPath, encryption);

        AppSettings loaded = repo.load();
        assertEquals(5, loaded.visibleCopies());

        AppSettings updated = new AppSettings(
                Shortcut.defaultShortcut(),
                false,
                100,
                5,
                false,
                List.of(new PersistedSnippet("token", "ghp_secret123")),
                false,
                List.of("1Password", "Bitwarden"));
        repo.save(updated);

        String diskContent = Files.readString(settingsPath, StandardCharsets.UTF_8);
        assertTrue(diskContent.startsWith("RECLIP_ENC_V1:"));
        assertFalse(diskContent.contains("ghp_secret123"));
        assertEquals(updated, repo.load());
    }

    @Test
    void encryptsPersistedHistoryFile(@TempDir Path tempDir) throws IOException {
        Path historyPath = tempDir.resolve("history.dat");
        LocalEncryption encryption = LocalEncryption.withKey(LocalEncryption.generateAesKey());

        FileHistoryRepository repo = new FileHistoryRepository(historyPath, true, encryption);
        Instant now = Instant.parse("2026-11-23T20:00:00Z");
        repo.save(new ClipboardEntry(UUID.randomUUID(), "confidential-note", ContentType.TEXT, now, now, true));

        String diskContent = Files.readString(historyPath, StandardCharsets.UTF_8);
        assertTrue(diskContent.startsWith("RECLIP_ENC_V1:"));
        assertFalse(diskContent.contains("confidential-note"));

        FileHistoryRepository reloaded = new FileHistoryRepository(historyPath, true, encryption);
        assertEquals(1, reloaded.findAll().size());
        assertEquals("confidential-note", reloaded.findAll().getFirst().content());
    }
}
