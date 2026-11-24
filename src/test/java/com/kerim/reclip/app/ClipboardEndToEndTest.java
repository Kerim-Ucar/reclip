package com.kerim.reclip.app;

import com.kerim.reclip.clipboard.ClipboardObserver;
import com.kerim.reclip.clipboard.ClipboardPayload;
import com.kerim.reclip.clipboard.ClipboardProvider;
import com.kerim.reclip.config.AppSettings;
import com.kerim.reclip.config.PropertiesSettingsRepository;
import com.kerim.reclip.history.ClipboardEntry;
import com.kerim.reclip.history.ContentType;
import com.kerim.reclip.history.FileHistoryRepository;
import com.kerim.reclip.history.HistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipboardEndToEndTest {

    @Test
    void fullCopyHistorySearchSelectAndRestartFlow(@TempDir Path tempDir) {
        Path settingsPath = tempDir.resolve("settings.properties");
        Path historyPath = tempDir.resolve("history.dat");

        PropertiesSettingsRepository settingsRepo = new PropertiesSettingsRepository(settingsPath);
        AppSettings initial = settingsRepo.load();
        assertFalse(initial.autoPaste());

        AppSettings updated = initial.withAutoPaste(true).withPersistHistory(true);
        settingsRepo.save(updated);
        AppSettings reloadedSettings = settingsRepo.load();
        assertTrue(reloadedSettings.autoPaste());
        assertTrue(reloadedSettings.persistHistory());

        FileHistoryRepository historyRepo = new FileHistoryRepository(historyPath, reloadedSettings.persistHistory());
        HistoryService historyService = new HistoryService(historyRepo, 2);
        InMemoryClipboard clipboard = new InMemoryClipboard();

        try (ClipboardObserver observer = ClipboardObserver.forTypedContent(
                clipboard,
                Duration.ofMillis(50),
                event -> historyService.recordEntry(
                        event.contentType(),
                        event.content(),
                        event.displayLabel(),
                        event.observedAt()))) {

            clipboard.setPayload(new ClipboardPayload(ContentType.TEXT, "hello world", ""));
            observer.pollOnce();

            clipboard.setPayload(new ClipboardPayload(ContentType.IMAGE, "/tmp/shot.png", "Image (1920×1080)"));
            observer.pollOnce();

            clipboard.setPayload(new ClipboardPayload(ContentType.FILES, "/tmp/notes.md", "File: notes.md"));
            observer.pollOnce();

            // Retention limit is 2 unpinned entries: "hello world" should be pruned, image & file remain
            List<ClipboardEntry> all = historyService.listAll();
            assertEquals(2, all.size());
            assertTrue(all.stream().anyMatch(e -> e.contentType() == ContentType.FILES));
            assertTrue(all.stream().anyMatch(e -> e.contentType() == ContentType.IMAGE));

            // Pin the image entry and verify search matches label
            ClipboardEntry imageEntry = historyService.search("1920").getFirst();
            historyService.togglePinned(imageEntry.id());
            assertTrue(historyService.search("shot.png").isEmpty());

            // Simulate selecting the image entry from the picker: self-written mark prevents duplicate
            observer.markSelfWritten(imageEntry.contentType(), imageEntry.content());
            assertTrue(clipboard.writeContent(imageEntry.contentType(), imageEntry.content()));
            observer.pollOnce();
            assertEquals(2, historyService.listAll().size());
        }

        // Simulate app restart with persistence enabled
        FileHistoryRepository restartedRepo = new FileHistoryRepository(historyPath, true);
        List<ClipboardEntry> persisted = restartedRepo.findAll();
        assertEquals(2, persisted.size());
        assertTrue(persisted.stream().anyMatch(e -> e.contentType() == ContentType.IMAGE && e.pinned()));
        assertTrue(persisted.stream().anyMatch(e -> e.contentType() == ContentType.FILES));
    }

    @Test
    void sensitiveEntriesTrackRemainingFractionAndExpire(@TempDir Path tempDir) {
        FileHistoryRepository repo = new FileHistoryRepository(tempDir.resolve("history.dat"), true);
        HistoryService service = new HistoryService(repo, 10);
        Instant t0 = Instant.parse("2026-11-23T20:00:00Z");

        ClipboardEntry secret = service.recordEntry(
                ContentType.TEXT, "s3cr3t-pass", "", t0, true, Duration.ofSeconds(60));
        assertTrue(secret.sensitive());
        assertEquals(0.5, secret.remainingFraction(t0.plusSeconds(30), 60_000L), 0.01);

        assertEquals(0, service.purgeExpired(t0.plusSeconds(59)));
        assertEquals(1, service.listAll().size());

        assertEquals(1, service.purgeExpired(t0.plusSeconds(60)));
        assertTrue(service.listAll().isEmpty());
    }

    private static final class InMemoryClipboard implements ClipboardProvider {

        private ClipboardPayload payload;

        @Override
        public Optional<String> readText() {
            if (payload != null && payload.contentType() == ContentType.TEXT) {
                return Optional.of(payload.content());
            }
            return Optional.empty();
        }

        @Override
        public boolean writeText(String text) {
            this.payload = new ClipboardPayload(ContentType.TEXT, text, "");
            return true;
        }

        @Override
        public Optional<ClipboardPayload> readContent() {
            return Optional.ofNullable(payload);
        }

        @Override
        public boolean writeContent(ContentType contentType, String content) {
            this.payload = new ClipboardPayload(contentType, content, "");
            return true;
        }

        void setPayload(ClipboardPayload payload) {
            this.payload = payload;
        }
    }
}
