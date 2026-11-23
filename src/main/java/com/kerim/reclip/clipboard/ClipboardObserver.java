package com.kerim.reclip.clipboard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Periodically polls a {@link ClipboardProvider} for text changes and emits
 * deduplicated {@link ClipboardTextEvent} instances.
 */
public class ClipboardObserver implements AutoCloseable {

    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);

    private final ClipboardProvider clipboardProvider;
    private final Duration pollInterval;
    private final Consumer<ClipboardTextEvent> listener;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;

    private final Object stateLock = new Object();
    private String lastObservedFingerprint;
    private ScheduledFuture<?> pollFuture;

    /**
     * Creates a clipboard observer with a dedicated daemon polling thread.
     *
     * @param clipboardProvider the clipboard provider to poll
     * @param pollInterval      the delay between polls; must be positive
     * @param listener          callback invoked when new non-empty text is observed
     */
    public ClipboardObserver(
            ClipboardProvider clipboardProvider,
            Duration pollInterval,
            Consumer<ClipboardTextEvent> listener) {
        this(clipboardProvider, pollInterval, listener, Clock.systemUTC(), createDefaultExecutor(), true);
    }

    ClipboardObserver(
            ClipboardProvider clipboardProvider,
            Duration pollInterval,
            Consumer<ClipboardTextEvent> listener,
            Clock clock,
            ScheduledExecutorService executor) {
        this(clipboardProvider, pollInterval, listener, clock, executor, false);
    }

    private ClipboardObserver(
            ClipboardProvider clipboardProvider,
            Duration pollInterval,
            Consumer<ClipboardTextEvent> listener,
            Clock clock,
            ScheduledExecutorService executor,
            boolean ownsExecutor) {
        this.clipboardProvider = Objects.requireNonNull(clipboardProvider, "clipboardProvider must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.ownsExecutor = ownsExecutor;
    }

    private static ScheduledExecutorService createDefaultExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "reclip-clipboard-observer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts periodic clipboard polling if not already running.
     */
    public void start() {
        synchronized (stateLock) {
            if (pollFuture != null && !pollFuture.isCancelled()) {
                return;
            }
            long delayMs = Math.max(1L, pollInterval.toMillis());
            pollFuture = executor.scheduleWithFixedDelay(
                    this::pollSafely,
                    0L,
                    delayMs,
                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops periodic clipboard polling.
     */
    public void stop() {
        synchronized (stateLock) {
            if (pollFuture != null) {
                pollFuture.cancel(false);
                pollFuture = null;
            }
        }
    }

    @Override
    public void close() {
        stop();
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    /**
     * Records text written by the application itself so the next poll does not
     * re-emit it as a new external clipboard change.
     *
     * @param text the text written by the application
     */
    public void markSelfWritten(String text) {
        Objects.requireNonNull(text, "text must not be null");
        synchronized (stateLock) {
            lastObservedFingerprint = fingerprint(text);
        }
    }

    /**
     * Performs a single poll cycle. Exposed for deterministic testing.
     */
    public void pollOnce() {
        Optional<String> current = clipboardProvider.readText();
        if (current.isEmpty()) {
            return;
        }
        String text = current.get();
        if (text.isEmpty()) {
            return;
        }

        String currentFingerprint = fingerprint(text);
        synchronized (stateLock) {
            if (currentFingerprint.equals(lastObservedFingerprint)) {
                return;
            }
            lastObservedFingerprint = currentFingerprint;
        }

        listener.accept(new ClipboardTextEvent(text, clock.instant()));
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (RuntimeException ignored) {
            // Keep the scheduled observer alive on transient OS or listener errors.
        }
    }

    private static String fingerprint(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
