package com.kerim.reclip.hotkey;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Listens for native mouse presses while the popup is visible.
 */
public class OutsideClickDetector implements AutoCloseable {

    private final Object lock = new Object();
    private NativeMouseListener activeListener;

    /**
     * Starts listening for native mouse presses and forwards screen coordinates
     * to {@code onMousePressed}.
     *
     * @param onMousePressed callback receiving {@code (screenX, screenY)} on mouse press
     */
    public void start(BiConsumer<Integer, Integer> onMousePressed) {
        Objects.requireNonNull(onMousePressed, "onMousePressed must not be null");

        synchronized (lock) {
            stop();
            if (!GlobalScreen.isNativeHookRegistered()) {
                return;
            }
            NativeMouseListener listener = new NativeMouseListener() {
                @Override
                public void nativeMousePressed(NativeMouseEvent event) {
                    onMousePressed.accept(event.getX(), event.getY());
                }
            };
            GlobalScreen.addNativeMouseListener(listener);
            activeListener = listener;
        }
    }

    /**
     * Stops listening for outside mouse presses.
     */
    public void stop() {
        synchronized (lock) {
            if (activeListener != null) {
                GlobalScreen.removeNativeMouseListener(activeListener);
                activeListener = null;
            }
        }
    }

    @Override
    public void close() {
        stop();
    }
}
