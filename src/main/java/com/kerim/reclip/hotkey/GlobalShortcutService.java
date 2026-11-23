package com.kerim.reclip.hotkey;

/**
 * Abstraction for registering and unregistering a global keyboard shortcut.
 */
public interface GlobalShortcutService extends AutoCloseable {

    /**
     * Registers the given global shortcut and invokes {@code onTriggered} when pressed.
     *
     * @param shortcut    the shortcut combination to listen for
     * @param onTriggered callback invoked when the shortcut is triggered
     * @return registration outcome with any actionable error message
     */
    ShortcutRegistrationResult register(Shortcut shortcut, Runnable onTriggered);

    /**
     * Unregisters any active global shortcut and releases native hook resources.
     */
    void unregister();

    /**
     * Returns whether a global shortcut is currently registered.
     *
     * @return {@code true} if active
     */
    boolean isRegistered();

    @Override
    default void close() {
        unregister();
    }
}
