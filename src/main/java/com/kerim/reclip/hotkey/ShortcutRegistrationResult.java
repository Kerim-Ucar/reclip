package com.kerim.reclip.hotkey;

import java.util.Optional;

/**
 * Result of attempting to register a global keyboard shortcut.
 *
 * @param registered   {@code true} if registration succeeded
 * @param errorMessage actionable error message if registration failed
 */
public record ShortcutRegistrationResult(boolean registered, Optional<String> errorMessage) {

    public static ShortcutRegistrationResult success() {
        return new ShortcutRegistrationResult(true, Optional.empty());
    }

    public static ShortcutRegistrationResult failure(String message) {
        return new ShortcutRegistrationResult(false, Optional.ofNullable(message));
    }
}
