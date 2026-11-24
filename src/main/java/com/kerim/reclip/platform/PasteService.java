package com.kerim.reclip.platform;

/**
 * Platform adapter for optional simulated paste after restoring foreground focus.
 */
@FunctionalInterface
public interface PasteService {

    /**
     * Simulates the platform paste shortcut (e.g. {@code Cmd+V} on macOS) into the
     * currently focused application.
     *
     * @return the outcome of the paste attempt
     */
    PasteResult paste();
}
