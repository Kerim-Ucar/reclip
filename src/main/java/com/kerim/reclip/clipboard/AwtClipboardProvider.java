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
        try {
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return Optional.empty();
            }
            Object data = clipboard.getData(DataFlavor.stringFlavor);
            if (data instanceof String text) {
                return Optional.of(text);
            }
            return Optional.empty();
        } catch (IllegalStateException | UnsupportedFlavorException | IOException
                 | HeadlessException | SecurityException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean writeText(String text) {
        Objects.requireNonNull(text, "text must not be null");
        try {
            StringSelection selection = new StringSelection(text);
            clipboard.setContents(selection, null);
            return true;
        } catch (IllegalStateException | HeadlessException | SecurityException e) {
            return false;
        }
    }
}
