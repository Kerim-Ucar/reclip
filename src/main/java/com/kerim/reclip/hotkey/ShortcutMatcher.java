package com.kerim.reclip.hotkey;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Pure matching logic between {@link Shortcut} and JNativeHook key/modifier codes.
 */
final class ShortcutMatcher {

    private ShortcutMatcher() {
    }

    static boolean matches(Shortcut shortcut, int nativeModifiers, int nativeKeyCode) {
        Objects.requireNonNull(shortcut, "shortcut must not be null");
        if (toNativeKeyCode(shortcut.key()) != nativeKeyCode) {
            return false;
        }
        Set<Shortcut.Modifier> actualModifiers = extractModifiers(nativeModifiers);
        return actualModifiers.equals(shortcut.modifiers());
    }

    static int toNativeKeyCode(Shortcut.ShortcutKey key) {
        return switch (key) {
            case SPACE -> NativeKeyEvent.VC_SPACE;
            case V -> NativeKeyEvent.VC_V;
            case C -> NativeKeyEvent.VC_C;
            case H -> NativeKeyEvent.VC_H;
        };
    }

    static Set<Shortcut.Modifier> extractModifiers(int nativeModifiers) {
        EnumSet<Shortcut.Modifier> result = EnumSet.noneOf(Shortcut.Modifier.class);
        if ((nativeModifiers & NativeInputEvent.CTRL_MASK) != 0) {
            result.add(Shortcut.Modifier.CTRL);
        }
        if ((nativeModifiers & NativeInputEvent.ALT_MASK) != 0) {
            result.add(Shortcut.Modifier.ALT);
        }
        if ((nativeModifiers & NativeInputEvent.SHIFT_MASK) != 0) {
            result.add(Shortcut.Modifier.SHIFT);
        }
        if ((nativeModifiers & NativeInputEvent.META_MASK) != 0) {
            result.add(Shortcut.Modifier.META);
        }
        return result;
    }
}
