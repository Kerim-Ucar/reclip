package com.kerim.reclip.hotkey;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Pure matching and key-normalization logic between {@link Shortcut} and JNativeHook events.
 */
public final class ShortcutMatcher {

    private static final int VC_CONTROL_RIGHT = 0x0E1D;
    private static final int VC_SHIFT_RIGHT = 0x0036;
    private static final int VC_ALT_RIGHT = 0x0E38;
    private static final int VC_META_RIGHT = 0x0E5C;

    private ShortcutMatcher() {
    }

    static boolean matchesChord(Shortcut shortcut, Set<String> pressedKeys) {
        Objects.requireNonNull(shortcut, "shortcut must not be null");
        Objects.requireNonNull(pressedKeys, "pressedKeys must not be null");
        return pressedKeys.equals(shortcut.keys());
    }

    static String normalizeNativeKey(int nativeKeyCode) {
        return switch (nativeKeyCode) {
            case NativeKeyEvent.VC_CONTROL, VC_CONTROL_RIGHT -> "CTRL";
            case NativeKeyEvent.VC_SHIFT, VC_SHIFT_RIGHT -> "SHIFT";
            case NativeKeyEvent.VC_ALT, VC_ALT_RIGHT -> "ALT";
            case NativeKeyEvent.VC_META, VC_META_RIGHT -> "META";
            case NativeKeyEvent.VC_SPACE -> "SPACE";
            case NativeKeyEvent.VC_ENTER -> "ENTER";
            case NativeKeyEvent.VC_TAB -> "TAB";
            case NativeKeyEvent.VC_BACKQUOTE -> "BACK_QUOTE";
            case NativeKeyEvent.VC_MINUS -> "MINUS";
            case NativeKeyEvent.VC_EQUALS -> "EQUALS";
            case NativeKeyEvent.VC_OPEN_BRACKET -> "OPEN_BRACKET";
            case NativeKeyEvent.VC_CLOSE_BRACKET -> "CLOSE_BRACKET";
            case NativeKeyEvent.VC_BACK_SLASH -> "BACK_SLASH";
            case NativeKeyEvent.VC_SEMICOLON -> "SEMICOLON";
            case NativeKeyEvent.VC_QUOTE -> "QUOTE";
            case NativeKeyEvent.VC_COMMA -> "COMMA";
            case NativeKeyEvent.VC_PERIOD -> "PERIOD";
            case NativeKeyEvent.VC_SLASH -> "SLASH";
            default -> {
                String text = NativeKeyEvent.getKeyText(nativeKeyCode);
                if (text == null || text.isBlank()) {
                    yield "KEY_" + nativeKeyCode;
                }
                yield text.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
            }
        };
    }

    static void syncModifiers(Set<String> pressedKeys, int nativeModifiers, String currentPressedKey) {
        syncFlag(pressedKeys, (nativeModifiers & NativeInputEvent.CTRL_MASK) != 0, "CTRL", currentPressedKey);
        syncFlag(pressedKeys, (nativeModifiers & NativeInputEvent.SHIFT_MASK) != 0, "SHIFT", currentPressedKey);
        syncFlag(pressedKeys, (nativeModifiers & NativeInputEvent.ALT_MASK) != 0, "ALT", currentPressedKey);
        syncFlag(pressedKeys, (nativeModifiers & NativeInputEvent.META_MASK) != 0, "META", currentPressedKey);
    }

    private static void syncFlag(
            Set<String> pressedKeys,
            boolean active,
            String modifierKey,
            String currentPressedKey) {
        if (active || modifierKey.equals(currentPressedKey)) {
            pressedKeys.add(modifierKey);
        } else {
            pressedKeys.remove(modifierKey);
        }
    }
}
