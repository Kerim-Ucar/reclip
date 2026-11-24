package com.kerim.reclip.hotkey;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link GlobalShortcutService} implementation backed by JNativeHook.
 * Only recognizes the configured shortcut chord and never logs or records keystrokes.
 */
public class JNativeHookShortcutService implements GlobalShortcutService {

    private static final long STALE_CHORD_IDLE_NANOS = 3_000_000_000L;

    private final Object lock = new Object();
    private final Set<String> pressedKeys = new HashSet<>();
    private NativeKeyListener activeListener;
    private boolean hookRegistered;
    private boolean chordTriggered;
    private long lastKeyEventNanos;

    @Override
    public ShortcutRegistrationResult register(Shortcut shortcut, Runnable onTriggered) {
        Objects.requireNonNull(shortcut, "shortcut must not be null");
        Objects.requireNonNull(onTriggered, "onTriggered must not be null");

        synchronized (lock) {
            unregisterListenerOnly();
            pressedKeys.clear();
            chordTriggered = false;
            lastKeyEventNanos = 0L;
            silenceJNativeHookLogger();

            try {
                configureWritableLibraryPath();
                if (!GlobalScreen.isNativeHookRegistered()) {
                    GlobalScreen.registerNativeHook();
                }
                hookRegistered = true;

                NativeKeyListener listener = new NativeKeyListener() {
                    @Override
                    public void nativeKeyPressed(NativeKeyEvent event) {
                        boolean shouldFire = false;
                        synchronized (lock) {
                            long now = System.nanoTime();
                            if (lastKeyEventNanos != 0L && (now - lastKeyEventNanos) > STALE_CHORD_IDLE_NANOS) {
                                // Clear any keys whose release events were swallowed by OS desktop
                                // switches (e.g. Win+L lock screen, Ctrl+Alt+Del, or UAC prompts).
                                pressedKeys.clear();
                                chordTriggered = false;
                            }
                            lastKeyEventNanos = now;

                            String normalized = ShortcutMatcher.normalizeNativeKey(event.getKeyCode());
                            pressedKeys.add(normalized);
                            ShortcutMatcher.syncModifiers(pressedKeys, event.getModifiers(), normalized);
                            if (!chordTriggered && ShortcutMatcher.matchesChord(shortcut, pressedKeys)) {
                                chordTriggered = true;
                                shouldFire = true;
                            }
                        }
                        if (shouldFire) {
                            onTriggered.run();
                        }
                    }

                    @Override
                    public void nativeKeyReleased(NativeKeyEvent event) {
                        synchronized (lock) {
                            lastKeyEventNanos = System.nanoTime();
                            pressedKeys.remove(ShortcutMatcher.normalizeNativeKey(event.getKeyCode()));
                            if (pressedKeys.isEmpty() || !ShortcutMatcher.matchesChord(shortcut, pressedKeys)) {
                                chordTriggered = false;
                            }
                        }
                    }
                };
                GlobalScreen.addNativeKeyListener(listener);
                activeListener = listener;
                return ShortcutRegistrationResult.success();
            } catch (NativeHookException | UnsatisfiedLinkError | SecurityException e) {
                hookRegistered = GlobalScreen.isNativeHookRegistered();
                return ShortcutRegistrationResult.failure(buildActionableErrorMessage(e));
            }
        }
    }

    @Override
    public void unregister() {
        synchronized (lock) {
            unregisterListenerOnly();
            if (hookRegistered && GlobalScreen.isNativeHookRegistered()) {
                try {
                    GlobalScreen.unregisterNativeHook();
                } catch (NativeHookException ignored) {
                    // Best-effort cleanup during shutdown.
                }
            }
            hookRegistered = false;
        }
    }

    @Override
    public boolean isRegistered() {
        synchronized (lock) {
            return hookRegistered && activeListener != null && GlobalScreen.isNativeHookRegistered();
        }
    }

    private void unregisterListenerOnly() {
        if (activeListener != null) {
            GlobalScreen.removeNativeKeyListener(activeListener);
            activeListener = null;
        }
    }

    private static void configureWritableLibraryPath() {
        if (System.getProperty("jnativehook.lib.path") != null) {
            return;
        }
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return;
        }
        try {
            Path libDir = Path.of(userHome, ".reclip", "lib");
            Files.createDirectories(libDir);
            System.setProperty("jnativehook.lib.path", libDir.toString());
        } catch (IOException | SecurityException ignored) {
            // Fall back to JNativeHook's default extraction directory.
        }
    }

    private static void silenceJNativeHookLogger() {
        Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        logger.setLevel(Level.WARNING);
        logger.setUseParentHandlers(false);
    }

    private static String buildActionableErrorMessage(Throwable error) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return "Unable to register global shortcut. Add Reclip to both System Settings → Privacy & Security → Accessibility "
                    + "and System Settings → Privacy & Security → Input Monitoring ("
                    + error.getMessage() + ").";
        }
        return "Unable to register global shortcut: " + error.getMessage();
    }
}
