package com.kerim.reclip.clipboard;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ClipboardProvider} backed by {@link java.awt.datatransfer.Clipboard}.
 */
public class AwtClipboardProvider implements ClipboardProvider {

    private static final int READ_MAX_ATTEMPTS = 2;
    private static final int WRITE_MAX_ATTEMPTS = 4;
    private static final long CONTENTION_BACKOFF_MS = 15L;

    private final Clipboard clipboard;

    /**
     * Creates a provider using the default system clipboard.
     */
    public AwtClipboardProvider() {
        this(Toolkit.getDefaultToolkit().getSystemClipboard());
    }

    /**
     * Creates a provider using the given AWT clipboard instance.
     *
     * @param clipboard the clipboard to read from and write to
     */
    public AwtClipboardProvider(Clipboard clipboard) {
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard must not be null");
    }

    @Override
    public Optional<String> readText() {
        for (int attempt = 1; attempt <= READ_MAX_ATTEMPTS; attempt++) {
            try {
                if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    return Optional.empty();
                }
                Object data = clipboard.getData(DataFlavor.stringFlavor);
                if (data instanceof String text) {
                    return Optional.of(text);
                }
                return Optional.empty();
            } catch (IllegalStateException e) {
                if (attempt == READ_MAX_ATTEMPTS || !sleepQuietly(CONTENTION_BACKOFF_MS)) {
                    return Optional.empty();
                }
            } catch (UnsupportedFlavorException | IOException | HeadlessException | SecurityException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean writeText(String text) {
        Objects.requireNonNull(text, "text must not be null");
        StringSelection selection = new StringSelection(text);
        for (int attempt = 1; attempt <= WRITE_MAX_ATTEMPTS; attempt++) {
            try {
                clipboard.setContents(selection, null);
                return true;
            } catch (IllegalStateException e) {
                if (attempt == WRITE_MAX_ATTEMPTS || !sleepQuietly(CONTENTION_BACKOFF_MS)) {
                    return false;
                }
            } catch (HeadlessException | SecurityException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
