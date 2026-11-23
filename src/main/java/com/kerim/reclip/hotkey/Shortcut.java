package com.kerim.reclip.hotkey;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Platform-independent representation of a global keyboard shortcut.
 *
 * @param modifiers required modifier keys
 * @param key       primary non-modifier key
 */
public record Shortcut(Set<Modifier> modifiers, ShortcutKey key) {

    public Shortcut {
        Objects.requireNonNull(modifiers, "modifiers must not be null");
        Objects.requireNonNull(key, "key must not be null");
        if (modifiers.isEmpty()) {
            throw new IllegalArgumentException("modifiers must not be empty");
        }
        modifiers = modifiers.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(modifiers));
    }

    /**
     * Returns the default cross-platform shortcut ({@code Ctrl+Shift+Space}),
     * chosen to avoid OS-reserved combinations such as {@code Win+V}.
     *
     * @return the default shortcut
     */
    public static Shortcut defaultShortcut() {
        return new Shortcut(EnumSet.of(Modifier.CTRL, Modifier.SHIFT), ShortcutKey.SPACE);
    }

    /**
     * Supported modifier keys for global shortcuts.
     */
    public enum Modifier {
        CTRL,
        ALT,
        SHIFT,
        META
    }

    /**
     * Supported primary keys for global shortcuts.
     */
    public enum ShortcutKey {
        SPACE,
        V,
        C,
        H
    }
}
