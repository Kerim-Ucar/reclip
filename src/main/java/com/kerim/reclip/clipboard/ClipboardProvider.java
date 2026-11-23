package com.kerim.reclip.clipboard;

import java.util.Optional;

/**
 * Abstraction over system clipboard read/write operations.
 */
public interface ClipboardProvider {

    /**
     * Reads the current plain-text content from the clipboard, if available.
     *
     * @return the clipboard text, or empty if unavailable or unsupported
     */
    Optional<String> readText();

    /**
     * Writes plain-text content to the clipboard.
     *
     * @param text the text to write
     * @return {@code true} if the clipboard was updated, {@code false} otherwise
     */
    boolean writeText(String text);
}
