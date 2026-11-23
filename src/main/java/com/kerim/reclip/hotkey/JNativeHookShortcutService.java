package com.kerim.reclip.hotkey;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link GlobalShortcutService} implementation backed by JNativeHook.
 * Only recognizes the configured shortcut and never logs or records keystrokes.
 */
public class JNativeHookShortcutService implements GlobalShortcutService {

    private final Object lock = new Object();
    private NativeKeyListener activeListener;
    private boolean hookRegistered;

    @Override
    public ShortcutRegistrationResult register(Shortcut shortcut, Runnable onTriggered) {
        Objects.requireNonNull(shortcut, "shortcut must not be null");
        Objects.requireNonNull(onTriggered, "onTriggered must not be null");

        synchronized (lock) {
            unregisterListenerOnly();
            silenceJNativeHookLogger();

            try {
                if (!GlobalScreen.isNativeHookRegistered()) {
                    GlobalScreen.registerNativeHook();
                }
                hookRegistered = true;

                NativeKeyListener listener = new NativeKeyListener() {
                    @Override
                    public void nativeKeyPressed(NativeKeyEvent event) {
                        if (ShortcutMatcher.matches(shortcut, event.getModifiers(), event.getKeyCode())) {
                            onTriggered.run();
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

    private static void silenceJNativeHookLogger() {
        Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        logger.setLevel(Level.WARNING);
        logger.setUseParentHandlers(false);
    }

    private static String buildActionableErrorMessage(Throwable error) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return "Unable to register global shortcut. Grant Accessibility / Input Monitoring permission in System Settings > Privacy & Security ("
                    + error.getMessage() + ").";
        }
        return "Unable to register global shortcut: " + error.getMessage();
    }
}
