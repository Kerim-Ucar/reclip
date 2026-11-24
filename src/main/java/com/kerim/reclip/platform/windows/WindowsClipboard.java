package com.kerim.reclip.platform.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Lightweight Windows clipboard and foreground-app inspection helpers via Panama FFM
 * for lock-free polling ({@code GetClipboardSequenceNumber}), concealed secret detection,
 * and excluded-app filtering.
 */
public final class WindowsClipboard {

    private static final String FORMAT_EXCLUDE_MONITOR = "ExcludeClipboardContentFromMonitorProcessing";
    private static final String FORMAT_CAN_INCLUDE_HISTORY = "CanIncludeInClipboardHistory";

    private static final Set<String> PASSWORD_MANAGER_IDENTIFIERS = Set.of(
            "1password",
            "bitwarden",
            "keepassxc",
            "keepass",
            "dashlane",
            "lastpass",
            "enpass",
            "protonpass",
            "proton pass",
            "roboform",
            "keeper");

    private WindowsClipboard() {
    }

    /**
     * Returns the current Windows clipboard sequence number in O(1) without opening
     * the clipboard, or empty if not running on Windows or unavailable.
     */
    public static OptionalLong sequenceNumber() {
        if (!WindowsWindowHelper.isWindows()) {
            return OptionalLong.empty();
        }
        try {
            NativeBridge bridge = NativeBridge.INSTANCE;
            if (bridge == null) {
                return OptionalLong.empty();
            }
            int seq = (int) bridge.getClipboardSequenceNumber.invokeExact();
            if (seq == 0) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(Integer.toUnsignedLong(seq));
        } catch (Throwable ignored) {
            return OptionalLong.empty();
        }
    }

    /**
     * Returns whether the Windows clipboard currently advertises concealed/history-excluded
     * formats ({@code ExcludeClipboardContentFromMonitorProcessing} or
     * {@code CanIncludeInClipboardHistory == 0}).
     */
    public static boolean hasConcealedContent() {
        if (!WindowsWindowHelper.isWindows()) {
            return false;
        }
        try {
            NativeBridge bridge = NativeBridge.INSTANCE;
            if (bridge == null) {
                return false;
            }
            if (bridge.excludeMonitorFormat != 0
                    && (int) bridge.isClipboardFormatAvailable.invokeExact(bridge.excludeMonitorFormat) != 0) {
                return true;
            }
            if (bridge.canIncludeHistoryFormat != 0
                    && (int) bridge.isClipboardFormatAvailable.invokeExact(bridge.canIncludeHistoryFormat) != 0) {
                return isDwordClipboardFormatZero(bridge, bridge.canIncludeHistoryFormat);
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isDwordClipboardFormatZero(NativeBridge bridge, int formatId) throws Throwable {
        if ((int) bridge.openClipboard.invokeExact(MemorySegment.NULL) == 0) {
            return false;
        }
        try {
            MemorySegment handle = (MemorySegment) bridge.getClipboardData.invokeExact(formatId);
            if (handle.equals(MemorySegment.NULL)) {
                return false;
            }
            MemorySegment ptr = (MemorySegment) bridge.globalLock.invokeExact(handle);
            if (ptr.equals(MemorySegment.NULL)) {
                return false;
            }
            try {
                int value = ptr.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
                return value == 0;
            } finally {
                int ignored = (int) bridge.globalUnlock.invokeExact(handle);
            }
        } finally {
            int ignored = (int) bridge.closeClipboard.invokeExact();
        }
    }

    /**
     * Returns whether the current foreground Windows application matches any entry in
     * {@code excludedApps} (case-insensitive substring match against executable name/stem).
     */
    public static boolean isFrontmostAppExcluded(List<String> excludedApps) {
        if (!WindowsWindowHelper.isWindows() || excludedApps == null || excludedApps.isEmpty()) {
            return false;
        }
        List<String> identifiers = WindowsWindowHelper.frontmostAppIdentifiers();
        if (identifiers.isEmpty()) {
            return false;
        }
        for (String excluded : excludedApps) {
            if (excluded == null || excluded.isBlank()) {
                continue;
            }
            String needle = excluded.trim().toLowerCase(Locale.ROOT);
            for (String id : identifiers) {
                if (id.toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns whether the current foreground Windows application is a known password manager.
     */
    public static boolean isPasswordManagerFrontmost() {
        if (!WindowsWindowHelper.isWindows()) {
            return false;
        }
        for (String id : WindowsWindowHelper.frontmostAppIdentifiers()) {
            String lower = id.toLowerCase(Locale.ROOT);
            for (String marker : PASSWORD_MANAGER_IDENTIFIERS) {
                if (lower.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record NativeBridge(
            MethodHandle getClipboardSequenceNumber,
            MethodHandle registerClipboardFormatW,
            MethodHandle isClipboardFormatAvailable,
            MethodHandle openClipboard,
            MethodHandle closeClipboard,
            MethodHandle getClipboardData,
            MethodHandle globalLock,
            MethodHandle globalUnlock,
            int excludeMonitorFormat,
            int canIncludeHistoryFormat) {

        static final NativeBridge INSTANCE = load();

        private static NativeBridge load() {
            if (!WindowsWindowHelper.isWindows()) {
                return null;
            }
            try (Arena arena = Arena.ofConfined()) {
                Linker linker = Linker.nativeLinker();
                SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());

                MethodHandle registerFmt = linker.downcallHandle(
                        user32.find("RegisterClipboardFormatW").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                int excludeFmt = (int) registerFmt.invokeExact(allocateWideString(arena, FORMAT_EXCLUDE_MONITOR));
                int canIncludeFmt = (int) registerFmt.invokeExact(allocateWideString(arena, FORMAT_CAN_INCLUDE_HISTORY));

                return new NativeBridge(
                        linker.downcallHandle(
                                user32.find("GetClipboardSequenceNumber").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT)),
                        registerFmt,
                        linker.downcallHandle(
                                user32.find("IsClipboardFormatAvailable").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)),
                        linker.downcallHandle(
                                user32.find("OpenClipboard").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                user32.find("CloseClipboard").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT)),
                        linker.downcallHandle(
                                user32.find("GetClipboardData").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
                        linker.downcallHandle(
                                kernel32.find("GlobalLock").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                kernel32.find("GlobalUnlock").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
                        excludeFmt,
                        canIncludeFmt);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static MemorySegment allocateWideString(Arena arena, String value) {
            byte[] utf16 = (value + "\0").getBytes(StandardCharsets.UTF_16LE);
            MemorySegment segment = arena.allocate(utf16.length, ValueLayout.JAVA_CHAR.byteAlignment());
            MemorySegment.copy(utf16, 0, segment, ValueLayout.JAVA_BYTE, 0, utf16.length);
            return segment;
        }
    }
}
