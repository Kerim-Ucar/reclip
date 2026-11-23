package com.kerim.reclip.clipboard;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClipboardObserverTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-11-22T20:00:00Z");

    @Test
    void emitsEventWhenNewTextAppearsAndDeduplicatesConsecutiveDuplicates() {
        FakeClipboardProvider provider = new FakeClipboardProvider();
        List<ClipboardTextEvent> events = new ArrayList<>();
        try (ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
             ClipboardObserver observer = newObserver(provider, events, executor)) {

            provider.setText("hello");
            observer.pollOnce();
            observer.pollOnce();

            assertEquals(1, events.size());
            assertEquals(new ClipboardTextEvent("hello", FIXED_TIME), events.getFirst());

            provider.setText("world");
            observer.pollOnce();

            provider.setText("hello");
            observer.pollOnce();

            assertEquals(3, events.size());
            assertEquals("world", events.get(1).text());
            assertEquals("hello", events.get(2).text());
        }
    }

    @Test
    void ignoresEmptyClipboardAndEmptyString() {
        FakeClipboardProvider provider = new FakeClipboardProvider();
        List<ClipboardTextEvent> events = new ArrayList<>();
        try (ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
             ClipboardObserver observer = newObserver(provider, events, executor)) {

            observer.pollOnce();
            provider.setText("");
            observer.pollOnce();

            assertEquals(0, events.size());
        }
    }

    @Test
    void markSelfWrittenPreventsReEmittingSameText() {
        FakeClipboardProvider provider = new FakeClipboardProvider();
        List<ClipboardTextEvent> events = new ArrayList<>();
        try (ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
             ClipboardObserver observer = newObserver(provider, events, executor)) {

            observer.markSelfWritten("copied-from-app");
            provider.setText("copied-from-app");
            observer.pollOnce();

            assertEquals(0, events.size());

            provider.setText("external-copy");
            observer.pollOnce();

            assertEquals(1, events.size());
            assertEquals("external-copy", events.getFirst().text());
        }
    }

    @Test
    void pausesAndResumesObservation() {
        FakeClipboardProvider provider = new FakeClipboardProvider();
        List<ClipboardTextEvent> events = new ArrayList<>();
        try (ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
             ClipboardObserver observer = newObserver(provider, events, executor)) {

            observer.setPaused(true);
            provider.setText("while-paused");
            observer.pollOnce();
            assertEquals(0, events.size());

            observer.setPaused(false);
            observer.pollOnce();
            assertEquals(1, events.size());
            assertEquals("while-paused", events.getFirst().text());
        }
    }

    @Test
    void rejectsNonPositivePollInterval() {
        FakeClipboardProvider provider = new FakeClipboardProvider();
        assertThrows(IllegalArgumentException.class,
                () -> new ClipboardObserver(provider, Duration.ZERO, event -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> new ClipboardObserver(provider, Duration.ofMillis(-10), event -> {}));
    }

    private static ClipboardObserver newObserver(
            FakeClipboardProvider provider,
            List<ClipboardTextEvent> events,
            ScheduledExecutorService executor) {
        Clock clock = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
        return new ClipboardObserver(provider, Duration.ofMillis(100), events::add, clock, executor);
    }

    private static final class FakeClipboardProvider implements ClipboardProvider {

        private String text;

        @Override
        public Optional<String> readText() {
            return Optional.ofNullable(text);
        }

        @Override
        public boolean writeText(String text) {
            this.text = text;
            return true;
        }

        void setText(String text) {
            this.text = text;
        }
    }
}
