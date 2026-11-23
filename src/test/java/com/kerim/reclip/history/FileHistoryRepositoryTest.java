package com.kerim.reclip.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileHistoryRepositoryTest {

    private static final Instant T1 = Instant.parse("2026-11-22T20:00:00Z");

    @Test
    void doesNotWriteToDiskWhenPersistenceIsDisabled(@TempDir Path tempDir) {
        Path historyFile = tempDir.resolve("history.dat");
        FileHistoryRepository repo = new FileHistoryRepository(historyFile, false);

        repo.save(new ClipboardEntry(UUID.randomUUID(), "secret", ContentType.TEXT, T1, T1, false));

        assertFalse(Files.exists(historyFile));
        assertEquals(1, repo.findAll().size());
    }

    @Test
    void persistsMultilineAndPinnedEntriesAcrossReloadsAndDeletesOnDisable(@TempDir Path tempDir) {
        Path historyFile = tempDir.resolve("nested/history.dat");
        FileHistoryRepository repo = new FileHistoryRepository(historyFile, true);

        UUID id = UUID.randomUUID();
        repo.save(new ClipboardEntry(id, "line1\nline2\tTabbed", ContentType.TEXT, T1, T1, true));
        assertTrue(Files.exists(historyFile));

        FileHistoryRepository reloaded = new FileHistoryRepository(historyFile, true);
        List<ClipboardEntry> entries = reloaded.findAll();
        assertEquals(1, entries.size());
        assertEquals(id, entries.getFirst().id());
        assertEquals("line1\nline2\tTabbed", entries.getFirst().content());
        assertTrue(entries.getFirst().pinned());

        reloaded.setPersistenceEnabled(false);
        assertFalse(Files.exists(historyFile));
    }
}
