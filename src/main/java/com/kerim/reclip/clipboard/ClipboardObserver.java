package com.kerim.reclip.clipboard;

import com.kerim.reclip.history.ContentType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final Consumer<ClipboardTextEvent> textListener;
    private final Consumer<ClipboardContentEvent> contentListener;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;

    private final Object stateLock = new Object();
    private String lastObservedFingerprint;
    private ScheduledFuture<?> pollFuture;
    private volatile boolean paused;

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
        this(clipboardProvider, pollInterval, listener, null, Clock.systemUTC(), createDefaultExecutor(), true);
    }

    /**
     * Creates a typed clipboard observer supporting text, images, and files.
     *
     * @param clipboardProvider the clipboard provider to poll
     * @param pollInterval      the delay between polls; must be positive
     * @param contentListener   callback invoked when new typed content is observed
     */
    public static ClipboardObserver forTypedContent(
            ClipboardProvider clipboardProvider,
            Duration pollInterval,
            Consumer<ClipboardContentEvent> contentListener) {
        Objects.requireNonNull(contentListener, "contentListener must not be null");
        return new ClipboardObserver(
                clipboardProvider, pollInterval, null, contentListener, Clock.systemUTC(), createDefaultExecutor(), true);
    }

    ClipboardObserver(
            ClipboardProvider clipboardProvider,
            Duration pollInterval,
            Consumer<ClipboardTextEvent> listener,
            Clock clock,
            ScheduledExecutorService executor) {
        this(clipboardProvider, pollInterval, listener, null, clock, executor, false);
    }

    private ClipboardObserver(
            ClipboardProvider clipboardProvider,
            Duration pollInterval,
            Consumer<ClipboardTextEvent> textListener,
            Consumer<ClipboardContentEvent> contentListener,
            Clock clock,
            ScheduledExecutorService executor,
            boolean ownsExecutor) {
        this.clipboardProvider = Objects.requireNonNull(clipboardProvider, "clipboardProvider must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        if (textListener == null && contentListener == null) {
            throw new IllegalArgumentException("At least one listener must be provided");
        }
        this.textListener = textListener;
        this.contentListener = contentListener;
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
        markSelfWritten(ContentType.TEXT, text);
    }

    /**
     * Records typed content written by the application itself so the next poll does not
     * re-emit it as a new external clipboard change.
     *
     * @param contentType the content type written by the application
     * @param content     the serialized content written by the application
     */
    public void markSelfWritten(ContentType contentType, String content) {
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        synchronized (stateLock) {
            lastObservedFingerprint = fingerprint(contentType, content);
        }
    }

    /**
     * Clears the last observed fingerprint so re-copying a previously deleted item
     * is recognized as a new clipboard event.
     */
    public void clearLastObserved() {
        synchronized (stateLock) {
            lastObservedFingerprint = null;
        }
    }

    /**
     * Seeds the observer with whatever is currently on the clipboard so pre-existing
     * clipboard contents (such as a secret copied before Reclip launched) are not
     * captured on startup.
     */
    public void ignoreInitialClipboard() {
        try {
            clipboardProvider.readContent().ifPresent(payload ->
                    markSelfWritten(payload.contentType(), payload.content()));
        } catch (RuntimeException ignored) {
            // Best-effort initial priming.
        }
    }

    /**
     * Pauses or resumes clipboard observation without stopping the background executor.
     *
     * @param paused {@code true} to pause polling, {@code false} to resume
     */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /**
     * Returns whether clipboard observation is currently paused.
     *
     * @return {@code true} if paused
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Performs a single poll cycle. Exposed for deterministic testing.
     */
    public void pollOnce() {
        if (paused) {
            return;
        }
        Optional<ClipboardPayload> current = clipboardProvider.readContent();
        if (current.isEmpty()) {
            return;
        }
        ClipboardPayload payload = current.get();
        String currentFingerprint = fingerprint(payload.contentType(), payload.content());
        synchronized (stateLock) {
            if (currentFingerprint.equals(lastObservedFingerprint)) {
                return;
            }
            lastObservedFingerprint = currentFingerprint;
        }

        Instant now = clock.instant();
        if (contentListener != null) {
            contentListener.accept(new ClipboardContentEvent(
                    payload.contentType(),
                    payload.content(),
                    payload.displayLabel(),
                    now,
                    payload.sensitive()));
        }
        if (textListener != null && payload.contentType() == ContentType.TEXT) {
            textListener.accept(new ClipboardTextEvent(payload.content(), now));
        }
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (RuntimeException ignored) {
            // Keep the scheduled observer alive on transient OS or listener errors.
        }
    }

    private static String fingerprint(ContentType contentType, String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(contentType.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
