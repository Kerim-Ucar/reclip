package com.kerim.reclip.platform.macos;

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
 * macOS {@link PasteService} that checks Accessibility trust and posts a
 * {@code Cmd+V} keyboard event via CoreGraphics.
 */
public final class MacOsPasteService implements PasteService {

    private static final short KEYCODE_ANSI_V = 9;
    private static final long CG_EVENT_FLAG_MASK_COMMAND = 0x00100000L;
    private static final int CG_HID_EVENT_TAP = 0;

    @Override
    public PasteResult paste() {
        if (!MacOsPermissions.isMacOs()) {
            return PasteResult.failure("macOS auto-paste is only supported on macOS.");
        }
        if (!MacOsPermissions.isAccessibilityTrusted()) {
            return PasteResult.failure(
                    "Accessibility permission is required for auto-paste. "
                            + MacOsPermissions.accessibilityGuidance());
        }
        try {
            NativeBridge bridge = NativeBridge.load();
            if (!bridge.postCommandV()) {
                return PasteResult.failure("Unable to create macOS keyboard event for auto-paste.");
            }
            return PasteResult.success();
        } catch (Throwable t) {
            return PasteResult.failure("Auto-paste failed on macOS.");
        }
    }

    private record NativeBridge(
            MethodHandle cgEventCreateKeyboardEvent,
            MethodHandle cgEventSetFlags,
            MethodHandle cgEventPost,
            MethodHandle cfRelease) {

        private static final String CORE_GRAPHICS =
                "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics";
        private static final String CORE_FOUNDATION =
                "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

        static NativeBridge load() {
            Linker linker = Linker.nativeLinker();
            SymbolLookup coreGraphics = SymbolLookup.libraryLookup(CORE_GRAPHICS, Arena.global());
            SymbolLookup coreFoundation = SymbolLookup.libraryLookup(CORE_FOUNDATION, Arena.global());

            return new NativeBridge(
                    linker.downcallHandle(
                            coreGraphics.find("CGEventCreateKeyboardEvent").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_SHORT,
                                    ValueLayout.JAVA_BOOLEAN)),
                    linker.downcallHandle(
                            coreGraphics.find("CGEventSetFlags").orElseThrow(),
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
                    linker.downcallHandle(
                            coreGraphics.find("CGEventPost").orElseThrow(),
                            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
                    linker.downcallHandle(
                            coreFoundation.find("CFRelease").orElseThrow(),
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)));
        }

        boolean postCommandV() throws Throwable {
            MemorySegment keyDown = (MemorySegment) cgEventCreateKeyboardEvent.invokeExact(
                    MemorySegment.NULL, KEYCODE_ANSI_V, true);
            if (keyDown.equals(MemorySegment.NULL)) {
                return false;
            }
            MemorySegment keyUp = MemorySegment.NULL;
            try {
                keyUp = (MemorySegment) cgEventCreateKeyboardEvent.invokeExact(
                        MemorySegment.NULL, KEYCODE_ANSI_V, false);
                if (keyUp.equals(MemorySegment.NULL)) {
                    return false;
                }
                cgEventSetFlags.invokeExact(keyDown, CG_EVENT_FLAG_MASK_COMMAND);
                cgEventSetFlags.invokeExact(keyUp, CG_EVENT_FLAG_MASK_COMMAND);
                cgEventPost.invokeExact(CG_HID_EVENT_TAP, keyDown);
                cgEventPost.invokeExact(CG_HID_EVENT_TAP, keyUp);
                return true;
            } finally {
                if (!keyUp.equals(MemorySegment.NULL)) {
                    cfRelease.invokeExact(keyUp);
                }
                cfRelease.invokeExact(keyDown);
            }
        }
    }
}
