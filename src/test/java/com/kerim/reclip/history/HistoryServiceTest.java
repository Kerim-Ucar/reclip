package com.kerim.reclip.history;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HistoryServiceTest {

    private static final Instant T1 = Instant.parse("2026-11-22T20:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-22T20:00:05Z");
    private static final Instant T3 = Instant.parse("2026-11-22T20:00:10Z");

    @Test
    void recordsAndOrdersMostRecentlyUsedFirst() {
        HistoryService service = new HistoryService(new InMemoryHistoryRepository());

        service.recordText("first", T1);
        service.recordText("second", T2);

        List<ClipboardEntry> entries = service.listAll();
        assertEquals(2, entries.size());
        assertEquals("second", entries.get(0).content());
        assertEquals("first", entries.get(1).content());
    }

    @Test
    void deduplicatesByPromotingExistingEntry() {
        HistoryService service = new HistoryService(new InMemoryHistoryRepository());

        ClipboardEntry first = service.recordText("alpha", T1);
        service.recordText("beta", T2);
        ClipboardEntry promoted = service.recordText("alpha", T3);

        List<ClipboardEntry> entries = service.listAll();
        assertEquals(2, entries.size());
        assertEquals(first.id(), promoted.id());
        assertEquals("alpha", entries.get(0).content());
        assertEquals(T3, entries.get(0).lastUsedAt());
        assertEquals("beta", entries.get(1).content());
    }

    @Test
    void prunesOldestUnpinnedEntriesBeyondRetentionLimit() {
        InMemoryHistoryRepository repository = new InMemoryHistoryRepository();
        HistoryService service = new HistoryService(repository, 2);

        ClipboardEntry pinned = service.recordText("pinned-item", T1);
        repository.save(pinned.withPinned(true));

        service.recordText("one", T1.plusSeconds(1));
        service.recordText("two", T1.plusSeconds(2));
        service.recordText("three", T1.plusSeconds(3));

        List<ClipboardEntry> entries = service.listAll();
        assertEquals(3, entries.size());
        assertEquals("pinned-item", entries.get(0).content());
        assertEquals("three", entries.get(1).content());
        assertEquals("two", entries.get(2).content());
    }

    @Test
    void searchesCaseInsensitivelyAndRanksPrefixMatchesFirst() {
        HistoryService service = new HistoryService(new InMemoryHistoryRepository());

        service.recordText("hello world", T1);
        service.recordText("my java snippet", T2);
        service.recordText("JavaFX picker", T3);

        List<ClipboardEntry> matches = service.search("java");
        assertEquals(2, matches.size());
        assertEquals("JavaFX picker", matches.get(0).content());
        assertEquals("my java snippet", matches.get(1).content());
        assertEquals(3, service.search("  ").size());
    }

    @Test
    void notifiesChangeListenersWhenRecordingText() {
        HistoryService service = new HistoryService(new InMemoryHistoryRepository());
        AtomicInteger notifications = new AtomicInteger();
        service.addChangeListener(notifications::incrementAndGet);

        service.recordText("item", T1);

        assertEquals(1, notifications.get());
    }

    @Test
    void validatesArguments() {
        InMemoryHistoryRepository repository = new InMemoryHistoryRepository();
        assertThrows(IllegalArgumentException.class, () -> new HistoryService(repository, 0));

        HistoryService service = new HistoryService(repository);
        assertThrows(IllegalArgumentException.class, () -> service.recordText("", T1));
    }
}
