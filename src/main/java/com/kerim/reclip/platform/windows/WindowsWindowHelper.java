package com.kerim.reclip.platform.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Native Win32 and Desktop Window Manager (DWM) integration for Windows 10/11
 * using the Java Foreign Function &amp; Memory API.
 *
 * <p>Handles foreground focus capture/restoration across applications, bringing
 * popup/dashboard stages to the foreground from background keyboard hooks without
 * synthesizing keystrokes, hiding the floating picker from the taskbar/Alt-Tab,
 * and applying Windows 11 dark title bar attributes.
 */
public final class WindowsWindowHelper {

    private static final int GWL_STYLE = -16;
    private static final int GWL_EXSTYLE = -20;

    private static final long WS_CAPTION = 0x00C00000L;
    private static final long WS_EX_TOOLWINDOW = 0x00000080L;
    private static final long WS_EX_APPWINDOW = 0x00040000L;

    private static final int SW_RESTORE = 9;
    private static final byte VK_NONAME = (byte) 0xFC;
    private static final int KEYEVENTF_KEYUP = 0x0002;

    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_CAPTION_COLOR = 35;
    private static final int DWMWA_TEXT_COLOR = 36;

    // COLORREF is 0x00BBGGRR: #222224 -> R=0x22, G=0x22, B=0x24; #F5F5F7 -> R=0xF5, G=0xF5, B=0xF7
    private static final int DARK_CAPTION_COLORREF = 0x00242222;
    private static final int LIGHT_TEXT_COLORREF = 0x00F7F5F5;

    private static final MemorySegment HKEY_CURRENT_USER = MemorySegment.ofAddress(0x80000001L);
    private static final int RRF_RT_REG_DWORD = 0x00000010;

    private static final int CURRENT_PID = (int) ProcessHandle.current().pid();
    private static final Object PREVIOUS_WINDOW_LOCK = new Object();
    private static MemorySegment previousForegroundHwnd = MemorySegment.NULL;

    private WindowsWindowHelper() {
    }

    /**
     * Returns whether the current operating system is Windows.
     *
     * @return {@code true} on Windows
     */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Captures the currently active foreground window if it belongs to an external
     * process so focus can be restored when the picker closes.
     */
    public static void capturePreviousApp() {
        if (!isWindows()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            Win32 win32 = Win32Holder.INSTANCE;
            if (win32 == null) {
                return;
            }
            MemorySegment fg = (MemorySegment) win32.getForegroundWindow.invokeExact();
            if (fg.equals(MemorySegment.NULL)) {
                return;
            }
            int pid = getWindowProcessId(win32, arena, fg);
            if (pid != 0 && pid != CURRENT_PID) {
                synchronized (PREVIOUS_WINDOW_LOCK) {
                    previousForegroundHwnd = fg;
                }
            }
        } catch (Throwable ignored) {
            // Best-effort foreground window capture.
        }
    }

    /**
     * Restores foreground focus to the previously captured external Windows application.
     */
    public static void restorePreviousAppFocus() {
        if (!isWindows()) {
            return;
        }
        MemorySegment hwnd;
        synchronized (PREVIOUS_WINDOW_LOCK) {
            hwnd = previousForegroundHwnd;
            previousForegroundHwnd = MemorySegment.NULL;
        }
        if (hwnd.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            Win32 win32 = Win32Holder.INSTANCE;
            if (win32 == null) {
                return;
            }
            if ((int) win32.isWindow.invokeExact(hwnd) != 0
                    && (int) win32.isWindowVisible.invokeExact(hwnd) != 0) {
                forceForeground(win32, hwnd, false);
            }
        } catch (Throwable ignored) {
            // Best-effort focus restoration.
        }
    }

    /**
     * Clears any captured previous window handle without changing focus.
     */
    public static void clearPreviousApp() {
        synchronized (PREVIOUS_WINDOW_LOCK) {
            previousForegroundHwnd = MemorySegment.NULL;
        }
    }

    /**
     * Configures the untitled JavaFX picker popup window as a tool window (preventing
     * taskbar and Alt-Tab clutter) and brings it to the foreground with keyboard focus.
     *
     * @return {@code true} if a picker window was found and activated
     */
    public static boolean activatePickerWindow() {
        if (!isWindows()) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            Win32 win32 = Win32Holder.INSTANCE;
            if (win32 == null) {
                return false;
            }
            List<MemorySegment> windows = findProcessGlassWindows(win32, arena);
            for (MemorySegment hwnd : windows) {
                long style = (long) win32.getWindowLongPtrW.invokeExact(hwnd, GWL_STYLE);
                if ((style & WS_CAPTION) != 0) {
                    continue;
                }
                int titleLen = (int) win32.getWindowTextLengthW.invokeExact(hwnd);
                if (titleLen <= 0) {
                    // Skip untitled 1x1 hidden utility owner window.
                    continue;
                }
                long exStyle = (long) win32.getWindowLongPtrW.invokeExact(hwnd, GWL_EXSTYLE);
                long desiredExStyle = (exStyle | WS_EX_TOOLWINDOW) & ~WS_EX_APPWINDOW;
                if (exStyle != desiredExStyle) {
                    long ignored = (long) win32.setWindowLongPtrW.invokeExact(hwnd, GWL_EXSTYLE, desiredExStyle);
                }
                forceForeground(win32, hwnd, false);
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Brings the topmost visible titled JavaFX window (Dashboard or Settings) to the foreground.
     *
     * @return {@code true} if a titled window was found and activated
     */
    public static boolean activateTitledWindow() {
        if (!isWindows()) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            Win32 win32 = Win32Holder.INSTANCE;
            if (win32 == null) {
                return false;
            }
            List<MemorySegment> windows = findProcessGlassWindows(win32, arena);
            for (MemorySegment hwnd : windows) {
                long style = (long) win32.getWindowLongPtrW.invokeExact(hwnd, GWL_STYLE);
                long exStyle = (long) win32.getWindowLongPtrW.invokeExact(hwnd, GWL_EXSTYLE);
                int titleLen = (int) win32.getWindowTextLengthW.invokeExact(hwnd);
                if ((style & WS_CAPTION) != 0 && (exStyle & WS_EX_TOOLWINDOW) == 0 && titleLen > 0) {
                    forceForeground(win32, hwnd, true);
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Applies Windows 11 dark immersive title bar and caption color ({@code #222224})
     * to all visible titled JavaFX windows belonging to this process.
     */
    public static void applyDarkTitleBar() {
        if (!isWindows()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            Win32 win32 = Win32Holder.INSTANCE;
            if (win32 == null || win32.dwmSetWindowAttribute == null) {
                return;
            }
            MemorySegment darkFlag = arena.allocate(ValueLayout.JAVA_INT);
            darkFlag.set(ValueLayout.JAVA_INT, 0, 1);

            MemorySegment captionColor = arena.allocate(ValueLayout.JAVA_INT);
            captionColor.set(ValueLayout.JAVA_INT, 0, DARK_CAPTION_COLORREF);

            MemorySegment textColor = arena.allocate(ValueLayout.JAVA_INT);
            textColor.set(ValueLayout.JAVA_INT, 0, LIGHT_TEXT_COLORREF);

            for (MemorySegment hwnd : findProcessGlassWindows(win32, arena)) {
                long style = (long) win32.getWindowLongPtrW.invokeExact(hwnd, GWL_STYLE);
                long exStyle = (long) win32.getWindowLongPtrW.invokeExact(hwnd, GWL_EXSTYLE);
                int titleLen = (int) win32.getWindowTextLengthW.invokeExact(hwnd);
                if ((style & WS_CAPTION) != 0 && (exStyle & WS_EX_TOOLWINDOW) == 0 && titleLen > 0) {
                    int ignored1 = (int) win32.dwmSetWindowAttribute.invokeExact(
                            hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, darkFlag, 4);
                    int ignored2 = (int) win32.dwmSetWindowAttribute.invokeExact(
                            hwnd, DWMWA_CAPTION_COLOR, captionColor, 4);
                    int ignored3 = (int) win32.dwmSetWindowAttribute.invokeExact(
                            hwnd, DWMWA_TEXT_COLOR, textColor, 4);
                }
            }
        } catch (Throwable ignored) {
            // Best-effort dark title bar on Windows 10/11.
        }
    }

    /**
     * Queries whether the Windows system shell (taskbar/system tray) is currently
     * configured to use the Light theme. Defaults to {@code false} (dark taskbar,
     * the Windows 11 default) when unavailable.
     *
     * @return {@code true} if Windows {@code SystemUsesLightTheme} is {@code 1}
     */
    public static boolean isSystemLightTheme() {
        if (!isWindows()) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            Win32 win32 = Win32Holder.INSTANCE;
            if (win32 == null || win32.regGetValueW == null) {
                return false;
            }
            MemorySegment subKey = allocateWideString(
                    arena,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize");
            MemorySegment valueName = allocateWideString(arena, "SystemUsesLightTheme");
            MemorySegment data = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment dataSize = arena.allocate(ValueLayout.JAVA_INT);
            dataSize.set(ValueLayout.JAVA_INT, 0, 4);

            int status = (int) win32.regGetValueW.invokeExact(
                    HKEY_CURRENT_USER,
                    subKey,
                    valueName,
                    RRF_RT_REG_DWORD,
                    MemorySegment.NULL,
                    data,
                    dataSize);
            return status == 0 && data.get(ValueLayout.JAVA_INT, 0) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void forceForeground(Win32 win32, MemorySegment targetHwnd, boolean restoreIfMinimized)
            throws Throwable {
        MemorySegment currentFg = (MemorySegment) win32.getForegroundWindow.invokeExact();
        if (currentFg.equals(targetHwnd)) {
            MemorySegment ignoredFocus = (MemorySegment) win32.setFocus.invokeExact(targetHwnd);
            return;
        }
        if (restoreIfMinimized && (int) win32.isIconic.invokeExact(targetHwnd) != 0) {
            int ignored = (int) win32.showWindow.invokeExact(targetHwnd, SW_RESTORE);
        }
        // Send a no-op VK_NONAME key-up event so Windows 11's foreground lock
        // permits SetForegroundWindow without touching any real modifier/key state
        // and without attaching to an external thread that might be unresponsive.
        win32.keybdEvent.invokeExact(VK_NONAME, (byte) 0, KEYEVENTF_KEYUP, MemorySegment.NULL);
        int ignoredTop = (int) win32.bringWindowToTop.invokeExact(targetHwnd);
        int fgSet = (int) win32.setForegroundWindow.invokeExact(targetHwnd);
        if (fgSet == 0 && win32.switchToThisWindow != null) {
            win32.switchToThisWindow.invokeExact(targetHwnd, 1);
        }
        MemorySegment ignoredFocus = (MemorySegment) win32.setFocus.invokeExact(targetHwnd);
    }

    private static List<MemorySegment> findProcessGlassWindows(Win32 win32, Arena arena) throws Throwable {
        List<MemorySegment> matches = new ArrayList<>(4);
        MemorySegment pidBuf = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment classBuf = arena.allocate(ValueLayout.JAVA_CHAR, 64);

        Win32EnumCallback callback = (hwnd, lParam) -> {
            try {
                if ((int) win32.isWindowVisible.invokeExact(hwnd) == 0) {
                    return 1;
                }
                pidBuf.set(ValueLayout.JAVA_INT, 0, 0);
                int ignored = (int) win32.getWindowThreadProcessId.invokeExact(hwnd, pidBuf);
                if (pidBuf.get(ValueLayout.JAVA_INT, 0) != CURRENT_PID) {
                    return 1;
                }
                int chars = (int) win32.getClassNameW.invokeExact(hwnd, classBuf, 64);
                if (chars > 0) {
                    byte[] bytes = classBuf.asSlice(0, (long) chars * 2L).toArray(ValueLayout.JAVA_BYTE);
                    String className = new String(bytes, StandardCharsets.UTF_16LE);
                    if (className.startsWith("GlassWndClass")) {
                        matches.add(hwnd);
                    }
                }
            } catch (Throwable ignored) {
                // Continue enumeration.
            }
            return 1;
        };

        MethodHandle callbackHandle = MethodHandles.lookup()
                .findVirtual(
                        Win32EnumCallback.class,
                        "invoke",
                        MethodType.methodType(int.class, MemorySegment.class, long.class))
                .bindTo(callback);
        MemorySegment callbackStub = Linker.nativeLinker().upcallStub(
                callbackHandle,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                arena);
        int ignored = (int) win32.enumWindows.invokeExact(callbackStub, 0L);
        return matches;
    }

    private static int getWindowProcessId(Win32 win32, Arena arena, MemorySegment hwnd) throws Throwable {
        MemorySegment pidPtr = arena.allocate(ValueLayout.JAVA_INT);
        int ignored = (int) win32.getWindowThreadProcessId.invokeExact(hwnd, pidPtr);
        return pidPtr.get(ValueLayout.JAVA_INT, 0);
    }

    private static MemorySegment allocateWideString(Arena arena, String value) {
        byte[] utf16 = (value + "\0").getBytes(StandardCharsets.UTF_16LE);
        MemorySegment segment = arena.allocate(utf16.length, ValueLayout.JAVA_CHAR.byteAlignment());
        MemorySegment.copy(utf16, 0, segment, ValueLayout.JAVA_BYTE, 0, utf16.length);
        return segment;
    }

    @FunctionalInterface
    private interface Win32EnumCallback {
        int invoke(MemorySegment hwnd, long lParam);
    }

    private static final class Win32Holder {
        static final Win32 INSTANCE = isWindows() ? Win32.load() : null;
    }

    private record Win32(
            MethodHandle getForegroundWindow,
            MethodHandle getWindowThreadProcessId,
            MethodHandle isWindow,
            MethodHandle isWindowVisible,
            MethodHandle isIconic,
            MethodHandle showWindow,
            MethodHandle bringWindowToTop,
            MethodHandle setForegroundWindow,
            MethodHandle switchToThisWindow,
            MethodHandle setFocus,
            MethodHandle keybdEvent,
            MethodHandle enumWindows,
            MethodHandle getClassNameW,
            MethodHandle getWindowTextLengthW,
            MethodHandle getWindowLongPtrW,
            MethodHandle setWindowLongPtrW,
            MethodHandle dwmSetWindowAttribute,
            MethodHandle regGetValueW) {

        static Win32 load() {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());

                MethodHandle dwmSetAttr = null;
                try {
                    SymbolLookup dwmapi = SymbolLookup.libraryLookup("dwmapi.dll", Arena.global());
                    dwmSetAttr = dwmapi.find("DwmSetWindowAttribute")
                            .map(sym -> linker.downcallHandle(
                                    sym,
                                    FunctionDescriptor.of(
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.JAVA_INT)))
                            .orElse(null);
                } catch (RuntimeException ignored) {
                    // DWM optional.
                }

                MethodHandle regGetVal = null;
                try {
                    SymbolLookup advapi32 = SymbolLookup.libraryLookup("advapi32.dll", Arena.global());
                    regGetVal = advapi32.find("RegGetValueW")
                            .map(sym -> linker.downcallHandle(
                                    sym,
                                    FunctionDescriptor.of(
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS)))
                            .orElse(null);
                } catch (RuntimeException ignored) {
                    // Registry theme check optional.
                }

                return new Win32(
                        linker.downcallHandle(
                                user32.find("GetForegroundWindow").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                user32.find("GetWindowThreadProcessId").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                user32.find("IsWindow").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                user32.find("IsWindowVisible").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                user32.find("IsIconic").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                user32.find("ShowWindow").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
                        linker.downcallHandle(
                                user32.find("BringWindowToTop").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                user32.find("SetForegroundWindow").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
                        user32.find("SwitchToThisWindow")
                                .map(sym -> linker.downcallHandle(
                                        sym,
                                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)))
                                .orElse(null),
                        linker.downcallHandle(
                                user32.find("SetFocus").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                user32.find("keybd_event").orElseThrow(),
                                FunctionDescriptor.ofVoid(ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                user32.find("EnumWindows").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
                        linker.downcallHandle(
                                user32.find("GetClassNameW").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
                        linker.downcallHandle(
                                user32.find("GetWindowTextLengthW").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                user32.find("GetWindowLongPtrW").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
                        linker.downcallHandle(
                                user32.find("SetWindowLongPtrW").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)),
                        dwmSetAttr,
                        regGetVal);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
