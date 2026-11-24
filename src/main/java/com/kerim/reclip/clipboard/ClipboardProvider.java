package com.kerim.reclip.clipboard;

import com.kerim.reclip.history.ContentType;

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

    /**
     * Reads the current typed content (files, text, or image) from the clipboard.
     *
     * @return the typed clipboard payload, or empty if unavailable or unsupported
     */
    default Optional<ClipboardPayload> readContent() {
        return readText()
                .filter(text -> !text.isEmpty())
                .map(text -> new ClipboardPayload(ContentType.TEXT, text, ""));
    }

    /**
     * Writes typed content to the clipboard.
     *
     * @param contentType the content type
     * @param content     serialized content
     * @return {@code true} if the clipboard was updated, {@code false} otherwise
     */
    default boolean writeContent(ContentType contentType, String content) {
        if (contentType == ContentType.TEXT) {
            return writeText(content);
        }
        return false;
    }
}
