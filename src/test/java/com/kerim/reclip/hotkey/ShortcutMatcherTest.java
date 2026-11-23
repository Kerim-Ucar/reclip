package com.kerim.reclip.hotkey;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortcutMatcherTest {

    @Test
    void matchesDefaultMultiKeyChord() {
        Shortcut shortcut = Shortcut.defaultShortcut();
        Set<String> pressed = Set.of(
                ShortcutMatcher.normalizeNativeKey(NativeKeyEvent.VC_CONTROL),
                ShortcutMatcher.normalizeNativeKey(NativeKeyEvent.VC_SHIFT),
                ShortcutMatcher.normalizeNativeKey(NativeKeyEvent.VC_SPACE));

        assertTrue(ShortcutMatcher.matchesChord(shortcut, pressed));
    }

    @Test
    void normalizesWindowsRightHandModifierScancodes() {
        assertEquals("CTRL", ShortcutMatcher.normalizeNativeKey(0x0E1D));
        assertEquals("SHIFT", ShortcutMatcher.normalizeNativeKey(0x0036));
        assertEquals("ALT", ShortcutMatcher.normalizeNativeKey(0x0E38));
        assertEquals("META", ShortcutMatcher.normalizeNativeKey(0x0E5C));
    }

    @Test
    void syncModifiersClearsStaleUnheldModifiersWhileKeepingPressedModifier() {
        Set<String> pressed = new HashSet<>(Set.of("META", "ALT", "CTRL", "SPACE"));
        int modifiersMask = NativeInputEvent.CTRL_MASK | NativeInputEvent.SHIFT_MASK;

        ShortcutMatcher.syncModifiers(pressed, modifiersMask, "SPACE");

        assertEquals(Set.of("CTRL", "SHIFT", "SPACE"), pressed);

        Set<String> freshModifierPress = new HashSet<>(Set.of("CTRL"));
        ShortcutMatcher.syncModifiers(freshModifierPress, 0, "CTRL");
        assertEquals(Set.of("CTRL"), freshModifierPress);
    }

    @Test
    void matchesUnlimitedConnectedKeysAndSingleKeys() {
        Shortcut multiChord = Shortcut.of("CTRL", "SHIFT", "A", "B", "1");
        assertTrue(ShortcutMatcher.matchesChord(multiChord, Set.of("CTRL", "SHIFT", "A", "B", "1")));
        assertFalse(ShortcutMatcher.matchesChord(multiChord, Set.of("CTRL", "SHIFT", "A", "B")));
        assertFalse(ShortcutMatcher.matchesChord(multiChord, Set.of("CTRL", "SHIFT", "A", "B", "1", "2")));

        Shortcut singleKey = Shortcut.of("F8");
        assertTrue(ShortcutMatcher.matchesChord(singleKey, Set.of("F8")));
    }

    @Test
    void rejectsEmptyKeysInShortcut() {
        assertThrows(IllegalArgumentException.class, () -> new Shortcut(Set.of()));
    }

    @Test
    void buildsSuccessAndFailureRegistrationResults() {
        ShortcutRegistrationResult success = ShortcutRegistrationResult.success();
        assertTrue(success.registered());
        assertTrue(success.errorMessage().isEmpty());

        ShortcutRegistrationResult failure = ShortcutRegistrationResult.failure("Permission denied");
        assertFalse(failure.registered());
        assertEquals("Permission denied", failure.errorMessage().orElseThrow());
    }
}
