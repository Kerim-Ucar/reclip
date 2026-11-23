package com.kerim.reclip.hotkey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Platform-independent representation of a global keyboard shortcut supporting
 * any combination of one or more connected keys.
 *
 * @param keys normalized key names that must be pressed together
 */
public record Shortcut(Set<String> keys) {

    private static final List<String> MODIFIER_ORDER = List.of("CTRL", "ALT", "SHIFT", "META");

    public Shortcut {
        Objects.requireNonNull(keys, "keys must not be null");
        Set<String> normalized = new LinkedHashSet<>();
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                normalized.add(key.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("keys must not be empty");
        }
        keys = Set.copyOf(normalized);
    }

    /**
     * Creates a shortcut from one or more key names.
     *
     * @param keys key names
     * @return immutable shortcut
     */
    public static Shortcut of(String... keys) {
        return new Shortcut(new LinkedHashSet<>(Arrays.asList(keys)));
    }

    /**
     * Returns the default cross-platform shortcut ({@code Ctrl+Shift+Space}),
     * chosen to avoid OS-reserved combinations such as {@code Win+V}.
     *
     * @return the default shortcut
     */
    public static Shortcut defaultShortcut() {
        return Shortcut.of("CTRL", "SHIFT", "SPACE");
    }

    /**
     * Returns a human-readable label for this shortcut.
     *
     * @return formatted shortcut string
     */
    public String format() {
        List<String> ordered = new ArrayList<>();
        for (String mod : MODIFIER_ORDER) {
            if (keys.contains(mod)) {
                ordered.add(displayName(mod));
            }
        }
        keys.stream()
                .filter(k -> !MODIFIER_ORDER.contains(k))
                .sorted()
                .map(Shortcut::displayName)
                .forEach(ordered::add);
        return String.join(" + ", ordered);
    }

    private static String displayName(String key) {
        return switch (key) {
            case "CTRL" -> "Ctrl";
            case "ALT" -> "Alt";
            case "SHIFT" -> "Shift";
            case "META" -> "Cmd/Meta";
            case "SPACE" -> "Space";
            default -> key;
        };
    }
}
