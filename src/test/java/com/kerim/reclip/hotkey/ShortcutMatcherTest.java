package com.kerim.reclip.hotkey;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortcutMatcherTest {

    @Test
    void matchesExactShortcutAndModifiers() {
        Shortcut shortcut = Shortcut.defaultShortcut();
        int modifiers = NativeInputEvent.CTRL_L_MASK | NativeInputEvent.SHIFT_L_MASK;

        assertTrue(ShortcutMatcher.matches(shortcut, modifiers, NativeKeyEvent.VC_SPACE));
    }

    @Test
    void rejectsMissingOrExtraModifiersAndWrongKey() {
        Shortcut shortcut = new Shortcut(EnumSet.of(Shortcut.Modifier.CTRL, Shortcut.Modifier.SHIFT), Shortcut.ShortcutKey.V);

        assertFalse(ShortcutMatcher.matches(shortcut, NativeInputEvent.CTRL_MASK, NativeKeyEvent.VC_V));
        assertFalse(ShortcutMatcher.matches(
                shortcut,
                NativeInputEvent.CTRL_MASK | NativeInputEvent.SHIFT_MASK | NativeInputEvent.ALT_MASK,
                NativeKeyEvent.VC_V));
        assertFalse(ShortcutMatcher.matches(
                shortcut,
                NativeInputEvent.CTRL_MASK | NativeInputEvent.SHIFT_MASK,
                NativeKeyEvent.VC_C));
    }

    @Test
    void rejectsEmptyModifiersInShortcut() {
        assertThrows(IllegalArgumentException.class,
                () -> new Shortcut(Set.of(), Shortcut.ShortcutKey.SPACE));
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
