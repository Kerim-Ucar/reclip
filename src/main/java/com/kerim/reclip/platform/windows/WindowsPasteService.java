package com.kerim.reclip.platform.windows;

import com.kerim.reclip.platform.PasteResult;
import com.kerim.reclip.platform.PasteService;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Windows {@link PasteService} that posts an atomic {@code Ctrl+V} keyboard
 * chord via Win32 {@code SendInput}.
 */
public final class WindowsPasteService implements PasteService {

    private static final int INPUT_KEYBOARD = 1;
    // 64-bit Win32 INPUT struct: 4-byte type + 4-byte padding + 32-byte union = 40 bytes.
    private static final int INPUT_SIZE_BYTES = 40;
    private static final long OFFSET_TYPE = 0L;
    private static final long OFFSET_KI_WVK = 8L;
    private static final long OFFSET_KI_DWFLAGS = 12L;

    private static final int KEYEVENTF_KEYUP = 0x0002;
    private static final short VK_CONTROL = 0x11;
    private static final short VK_V = 0x56;

    @Override
    public PasteResult paste() {
        if (!WindowsWindowHelper.isWindows()) {
            return PasteResult.failure("Windows auto-paste is only supported on Windows.");
        }
        try {
            NativeBridge bridge = NativeBridge.INSTANCE;
            if (bridge == null) {
                return PasteResult.failure("Unable to initialize Windows input API for auto-paste.");
            }
            if (!bridge.postCtrlV()) {
                return PasteResult.failure("Unable to send Ctrl+V input event for auto-paste.");
            }
            return PasteResult.success();
        } catch (Throwable t) {
            return PasteResult.failure("Auto-paste failed on Windows.");
        }
    }

    private record NativeBridge(MethodHandle sendInput) {

        static final NativeBridge INSTANCE = load();

        private static NativeBridge load() {
            if (!WindowsWindowHelper.isWindows()) {
                return null;
            }
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
                return new NativeBridge(linker.downcallHandle(
                        user32.find("SendInput").orElseThrow(),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT)));
            } catch (Throwable ignored) {
                return null;
            }
        }

        boolean postCtrlV() throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                int eventCount = 4;
                MemorySegment inputs = arena.allocate((long) INPUT_SIZE_BYTES * eventCount, 8L);
                writeKeyboardInput(inputs, 0, VK_CONTROL, 0);
                writeKeyboardInput(inputs, 1, VK_V, 0);
                writeKeyboardInput(inputs, 2, VK_V, KEYEVENTF_KEYUP);
                writeKeyboardInput(inputs, 3, VK_CONTROL, KEYEVENTF_KEYUP);

                int sent = (int) sendInput.invokeExact(eventCount, inputs, INPUT_SIZE_BYTES);
                return sent == eventCount;
            }
        }

        private static void writeKeyboardInput(MemorySegment base, int index, short vk, int flags) {
            long entryOffset = (long) index * INPUT_SIZE_BYTES;
            base.set(ValueLayout.JAVA_INT, entryOffset + OFFSET_TYPE, INPUT_KEYBOARD);
            base.set(ValueLayout.JAVA_SHORT, entryOffset + OFFSET_KI_WVK, vk);
            base.set(ValueLayout.JAVA_INT, entryOffset + OFFSET_KI_DWFLAGS, flags);
        }
    }
}
