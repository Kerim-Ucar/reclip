package com.kerim.reclip.platform;

import com.kerim.reclip.platform.windows.WindowsWindowHelper;

import java.awt.Desktop;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;

/**
 * Configures OS-level window Space behavior, native macOS vibrancy blur, and
 * foreground focus capture/restoration when triggered from a global shortcut.
 */
public final class WindowFocusHelper {

    private static final long NS_APPLICATION_ACTIVATION_POLICY_ACCESSORY = 1L;
    private static final long NS_APPLICATION_ACTIVATE_IGNORING_OTHER_APPS = 1L << 1;
    private static final long NS_WINDOW_COLLECTION_BEHAVIOR_CAN_JOIN_ALL_SPACES = 1L << 0;
    private static final long NS_WINDOW_COLLECTION_BEHAVIOR_FULL_SCREEN_AUXILIARY = 1L << 8;
    private static final long ALL_SPACES_BEHAVIOR =
            NS_WINDOW_COLLECTION_BEHAVIOR_CAN_JOIN_ALL_SPACES
                    | NS_WINDOW_COLLECTION_BEHAVIOR_FULL_SCREEN_AUXILIARY;

    private static final long NS_VISUAL_EFFECT_MATERIAL_MENU = 5L;
    private static final long NS_VISUAL_EFFECT_BLENDING_BEHIND_WINDOW = 0L;
    private static final long NS_VISUAL_EFFECT_STATE_ACTIVE = 1L;
    private static final long NS_VIEW_RESIZABLE = 2L | 16L;
    private static final long NS_WINDOW_BELOW = -1L;
    private static final double CORNER_RADIUS = 10.0;

    private static final MemoryLayout NS_RECT_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE);

    private static final Object PREVIOUS_APP_LOCK = new Object();
    private static MemorySegment previousFrontmostApp = MemorySegment.NULL;
    private static Arena reopenArena;
    private static Runnable reopenCallback;

    private WindowFocusHelper() {
    }

    /**
     * Registers a native macOS {@code applicationShouldHandleReopen:hasVisibleWindows:}
     * handler on the JavaFX {@code NSApplication} delegate so clicking the app icon
     * while already running reopens the main panel.
     *
     * @param onReopen callback invoked when macOS sends a reopen event
     */
    public static void registerReopenHandler(Runnable onReopen) {
        if (!isMacOs() || onReopen == null) {
            return;
        }
        reopenCallback = onReopen;
        if (reopenArena != null) {
            return;
        }
        try {
            reopenArena = Arena.ofShared();
            ObjC objc = ObjC.load();
            MemorySegment sharedApp = objc.sharedApplication(reopenArena);
            if (sharedApp.equals(MemorySegment.NULL)) {
                return;
            }
            MemorySegment delegate = objc.msgSendObj(sharedApp, reopenArena.allocateFrom("delegate"));
            if (delegate.equals(MemorySegment.NULL)) {
                return;
            }
            MemorySegment delegateClass = (MemorySegment) objc.objectGetClass.invokeExact(delegate);
            if (delegateClass.equals(MemorySegment.NULL)) {
                return;
            }
            MethodHandle target = MethodHandles.lookup().findStatic(
                    WindowFocusHelper.class,
                    "handleMacOsReopen",
                    MethodType.methodType(boolean.class, MemorySegment.class, MemorySegment.class, MemorySegment.class, boolean.class));
            FunctionDescriptor desc = FunctionDescriptor.of(
                    ValueLayout.JAVA_BOOLEAN,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_BOOLEAN);
            MemorySegment stub = Linker.nativeLinker().upcallStub(target, desc, reopenArena);
            MemorySegment ignored = (MemorySegment) objc.classReplaceMethod.invokeExact(
                    delegateClass,
                    objc.sel(reopenArena.allocateFrom("applicationShouldHandleReopen:hasVisibleWindows:")),
                    stub,
                    reopenArena.allocateFrom("B@:@B"));
        } catch (Throwable ignored) {
            // Best-effort native reopen handler.
        }
    }

    @SuppressWarnings("unused")
    private static boolean handleMacOsReopen(
            MemorySegment self,
            MemorySegment sel,
            MemorySegment app,
            boolean hasVisibleWindows) {
        Runnable callback = reopenCallback;
        if (callback != null) {
            callback.run();
        }
        return true;
    }

    /**
     * Configures the app on macOS as an accessory app so activating it does not
     * switch Spaces back to the main desktop.
     */
    public static void configureBackgroundApp() {
        if (!isMacOs()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            ObjC objc = ObjC.load();
            MemorySegment sharedApp = objc.sharedApplication(arena);
            if (!sharedApp.equals(MemorySegment.NULL)) {
                objc.msgSendBoolLong(sharedApp, arena.allocateFrom("setActivationPolicy:"),
                        NS_APPLICATION_ACTIVATION_POLICY_ACCESSORY);
            }
        } catch (Throwable ignored) {
            // Best-effort macOS accessory configuration.
        }
    }

    /**
     * Captures the currently active external application before Reclip takes
     * foreground focus, so it can be restored when the popup closes.
     */
    public static void capturePreviousApp() {
        if (WindowsWindowHelper.isWindows()) {
            WindowsWindowHelper.capturePreviousApp();
            return;
        }
        if (!isMacOs()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            ObjC objc = ObjC.load();
            MemorySegment workspaceClass = objc.getClass(arena.allocateFrom("NSWorkspace"));
            if (workspaceClass.equals(MemorySegment.NULL)) {
                return;
            }
            MemorySegment workspace = objc.msgSendObj(workspaceClass, arena.allocateFrom("sharedWorkspace"));
            if (workspace.equals(MemorySegment.NULL)) {
                return;
            }
            MemorySegment frontApp = objc.msgSendObj(workspace, arena.allocateFrom("frontmostApplication"));
            if (frontApp.equals(MemorySegment.NULL)) {
                return;
            }
            int pid = (int) objc.msgSendInt.invokeExact(frontApp, objc.sel(arena.allocateFrom("processIdentifier")));
            if (pid == ProcessHandle.current().pid()) {
                return;
            }
            objc.msgSendObj(frontApp, arena.allocateFrom("retain"));
            synchronized (PREVIOUS_APP_LOCK) {
                releasePreviousAppLocked(objc, arena);
                previousFrontmostApp = frontApp;
            }
        } catch (Throwable ignored) {
            // Best-effort capture.
        }
    }

    /**
     * Restores focus to the previously captured external application.
     */
    public static void restorePreviousAppFocus() {
        if (WindowsWindowHelper.isWindows()) {
            WindowsWindowHelper.restorePreviousAppFocus();
            return;
        }
        if (!isMacOs()) {
            return;
        }
        MemorySegment appToRestore;
        synchronized (PREVIOUS_APP_LOCK) {
            appToRestore = previousFrontmostApp;
            previousFrontmostApp = MemorySegment.NULL;
        }
        if (appToRestore.equals(MemorySegment.NULL)) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            ObjC objc = ObjC.load();
            objc.msgSendBoolLong(appToRestore, arena.allocateFrom("activateWithOptions:"),
                    NS_APPLICATION_ACTIVATE_IGNORING_OTHER_APPS);
            objc.msgSendVoid.invokeExact(appToRestore, objc.sel(arena.allocateFrom("release")));
        } catch (Throwable ignored) {
            // Best-effort restore.
        }
    }

    /**
     * Clears any captured previous application without changing focus (e.g. when
     * the user clicked directly onto another window).
     */
    public static void clearPreviousApp() {
        if (WindowsWindowHelper.isWindows()) {
            WindowsWindowHelper.clearPreviousApp();
            return;
        }
        if (!isMacOs()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            ObjC objc = ObjC.load();
            synchronized (PREVIOUS_APP_LOCK) {
                releasePreviousAppLocked(objc, arena);
            }
        } catch (Throwable ignored) {
            synchronized (PREVIOUS_APP_LOCK) {
                previousFrontmostApp = MemorySegment.NULL;
            }
        }
    }

    private static void releasePreviousAppLocked(ObjC objc, Arena arena) throws Throwable {
        if (!previousFrontmostApp.equals(MemorySegment.NULL)) {
            objc.msgSendVoid.invokeExact(previousFrontmostApp, objc.sel(arena.allocateFrom("release")));
            previousFrontmostApp = MemorySegment.NULL;
        }
    }

    /**
     * Brings the application to the foreground without modifying macOS window Space
     * behavior or adding vibrancy backdrops (used for Dashboard/Settings).
     */
    public static void activateAppOnly() {
        if (WindowsWindowHelper.isWindows()) {
            if (WindowsWindowHelper.activateTitledWindow()) {
                return;
            }
            requestAwtForeground();
            return;
        }
        if (!isMacOs()) {
            requestAwtForeground();
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            ObjC objc = ObjC.load();
            MemorySegment sharedApp = objc.sharedApplication(arena);
            if (!sharedApp.equals(MemorySegment.NULL)) {
                objc.msgSendVoidBool(sharedApp, arena.allocateFrom("activateIgnoringOtherApps:"), true);
            }
        } catch (Throwable ignored) {
            // Best-effort foreground activation.
        }
    }

    /**
     * Styles titled application windows (such as Dashboard and Settings) with a dark
     * title bar matching {@code #222224} on macOS and Windows 11.
     */
    public static void applyDarkTitleBar() {
        if (WindowsWindowHelper.isWindows()) {
            WindowsWindowHelper.applyDarkTitleBar();
            return;
        }
        if (!isMacOs()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            ObjC objc = ObjC.load();
            MemorySegment sharedApp = objc.sharedApplication(arena);
            if (sharedApp.equals(MemorySegment.NULL)) {
                return;
            }
            MemorySegment windows = objc.msgSendObj(sharedApp, arena.allocateFrom("windows"));
            if (windows.equals(MemorySegment.NULL)) {
                return;
            }
            MemorySegment darkAppearance = createDarkAquaAppearance(objc, arena);
            MemorySegment nsColorClass = objc.getClass(arena.allocateFrom("NSColor"));
            MemorySegment bgColor = (MemorySegment) objc.msgSendObj4Doubles.invokeExact(
                    nsColorClass,
                    objc.sel(arena.allocateFrom("colorWithSRGBRed:green:blue:alpha:")),
                    34.0 / 255.0, 34.0 / 255.0, 36.0 / 255.0, 1.0);

            long count = objc.msgSendLong(windows, arena.allocateFrom("count"));
            MemorySegment objectAtIndexSel = objc.sel(arena.allocateFrom("objectAtIndex:"));
            MemorySegment styleMaskSel = objc.sel(arena.allocateFrom("styleMask"));
            MemorySegment setAppearanceSel = objc.sel(arena.allocateFrom("setAppearance:"));
            MemorySegment setTitlebarTransparentSel = objc.sel(arena.allocateFrom("setTitlebarAppearsTransparent:"));
            MemorySegment setBgColorSel = objc.sel(arena.allocateFrom("setBackgroundColor:"));

            for (long i = 0; i < count; i++) {
                MemorySegment window = (MemorySegment) objc.msgSendObjLong.invokeExact(windows, objectAtIndexSel, i);
                if (window.equals(MemorySegment.NULL)) {
                    continue;
                }
                long styleMask = (long) objc.msgSendLong.invokeExact(window, styleMaskSel);
                if ((styleMask & 1L) != 0) {
                    if (!darkAppearance.equals(MemorySegment.NULL)) {
                        objc.msgSendVoidObj.invokeExact(window, setAppearanceSel, darkAppearance);
                    }
                    objc.msgSendVoidBool.invokeExact(window, setTitlebarTransparentSel, true);
                    if (!bgColor.equals(MemorySegment.NULL)) {
                        objc.msgSendVoidObj.invokeExact(window, setBgColorSel, bgColor);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Best-effort dark title bar.
        }
    }

    /**
     * Brings the picker popup to the foreground on the active desktop/Space
     * (applying macOS vibrancy or Windows tool-window foreground activation).
     */
    public static void requestAppForeground() {
        if (WindowsWindowHelper.isWindows() && WindowsWindowHelper.activatePickerWindow()) {
            return;
        }
        if (isMacOs() && activateMacOsApp()) {
            return;
        }
        requestAwtForeground();
    }

    private static boolean activateMacOsApp() {
        try (Arena arena = Arena.ofConfined()) {
            ObjC objc = ObjC.load();
            MemorySegment sharedApp = objc.sharedApplication(arena);

            if (!sharedApp.equals(MemorySegment.NULL)) {
                objc.msgSendBoolLong(sharedApp, arena.allocateFrom("setActivationPolicy:"),
                        NS_APPLICATION_ACTIVATION_POLICY_ACCESSORY);
                configureMacOsWindows(objc, sharedApp, arena);
                objc.msgSendVoidBool(sharedApp, arena.allocateFrom("activateIgnoringOtherApps:"), true);
            }

            MemorySegment runningAppClass = objc.getClass(arena.allocateFrom("NSRunningApplication"));
            MemorySegment runningApp = objc.msgSendObj(runningAppClass, arena.allocateFrom("currentApplication"));
            if (!runningApp.equals(MemorySegment.NULL)) {
                objc.msgSendBoolLong(runningApp, arena.allocateFrom("activateWithOptions:"),
                        NS_APPLICATION_ACTIVATE_IGNORING_OTHER_APPS);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void configureMacOsWindows(ObjC objc, MemorySegment sharedApp, Arena arena) throws Throwable {
        MemorySegment windows = objc.msgSendObj(sharedApp, arena.allocateFrom("windows"));
        if (windows.equals(MemorySegment.NULL)) {
            return;
        }
        MemorySegment statusBarWindowClass = objc.getClass(arena.allocateFrom("NSStatusBarWindow"));
        long count = objc.msgSendLong(windows, arena.allocateFrom("count"));
        MemorySegment objectAtIndexSel = objc.sel(arena.allocateFrom("objectAtIndex:"));
        MemorySegment isKindOfClassSel = objc.sel(arena.allocateFrom("isKindOfClass:"));
        MemorySegment levelSel = objc.sel(arena.allocateFrom("level"));
        MemorySegment styleMaskSel = objc.sel(arena.allocateFrom("styleMask"));
        MemorySegment setBehaviorSel = objc.sel(arena.allocateFrom("setCollectionBehavior:"));
        MemorySegment setHasShadowSel = objc.sel(arena.allocateFrom("setHasShadow:"));

        for (long i = 0; i < count; i++) {
            MemorySegment window = (MemorySegment) objc.msgSendObjLong.invokeExact(windows, objectAtIndexSel, i);
            if (window.equals(MemorySegment.NULL)) {
                continue;
            }
            if (!statusBarWindowClass.equals(MemorySegment.NULL)
                    && (boolean) objc.msgSendBoolObj.invokeExact(window, isKindOfClassSel, statusBarWindowClass)) {
                continue;
            }
            long windowLevel = (long) objc.msgSendLong.invokeExact(window, levelSel);
            if (windowLevel >= 25L) {
                continue;
            }
            long styleMask = (long) objc.msgSendLong.invokeExact(window, styleMaskSel);
            if ((styleMask & 1L) != 0) {
                continue;
            }
            objc.msgSendVoidLong.invokeExact(window, setBehaviorSel, ALL_SPACES_BEHAVIOR);
            objc.msgSendVoidBool.invokeExact(window, setHasShadowSel, true);
            ensureVibrancyBackdrop(objc, window, arena);
        }
    }

    private static void ensureVibrancyBackdrop(ObjC objc, MemorySegment window, Arena arena) throws Throwable {
        MemorySegment contentView = objc.msgSendObj(window, arena.allocateFrom("contentView"));
        if (contentView.equals(MemorySegment.NULL)) {
            return;
        }

        MemorySegment effectClass = objc.getClass(arena.allocateFrom("NSVisualEffectView"));
        if (effectClass.equals(MemorySegment.NULL)) {
            return;
        }

        MemorySegment subviews = objc.msgSendObj(contentView, arena.allocateFrom("subviews"));
        if (!subviews.equals(MemorySegment.NULL)) {
            long subCount = objc.msgSendLong(subviews, arena.allocateFrom("count"));
            MemorySegment objectAtIndexSel = objc.sel(arena.allocateFrom("objectAtIndex:"));
            MemorySegment isKindOfClassSel = objc.sel(arena.allocateFrom("isKindOfClass:"));
            for (long i = 0; i < subCount; i++) {
                MemorySegment subview = (MemorySegment) objc.msgSendObjLong.invokeExact(subviews, objectAtIndexSel, i);
                if (!subview.equals(MemorySegment.NULL)
                        && (boolean) objc.msgSendBoolObj.invokeExact(subview, isKindOfClassSel, effectClass)) {
                    return;
                }
            }
        }

        MemorySegment bounds = (MemorySegment) objc.msgSendRect.invokeExact(
                (java.lang.foreign.SegmentAllocator) arena,
                contentView,
                objc.sel(arena.allocateFrom("bounds")));

        MemorySegment allocated = objc.msgSendObj(effectClass, arena.allocateFrom("alloc"));
        MemorySegment effectView = (MemorySegment) objc.msgSendObjRect.invokeExact(
                allocated,
                objc.sel(arena.allocateFrom("initWithFrame:")),
                bounds);
        if (effectView.equals(MemorySegment.NULL)) {
            return;
        }

        objc.msgSendVoidLong.invokeExact(effectView, objc.sel(arena.allocateFrom("setAutoresizingMask:")), NS_VIEW_RESIZABLE);
        objc.msgSendVoidLong.invokeExact(effectView, objc.sel(arena.allocateFrom("setMaterial:")), NS_VISUAL_EFFECT_MATERIAL_MENU);
        objc.msgSendVoidLong.invokeExact(effectView, objc.sel(arena.allocateFrom("setBlendingMode:")), NS_VISUAL_EFFECT_BLENDING_BEHIND_WINDOW);
        objc.msgSendVoidLong.invokeExact(effectView, objc.sel(arena.allocateFrom("setState:")), NS_VISUAL_EFFECT_STATE_ACTIVE);
        objc.msgSendVoidBool.invokeExact(effectView, objc.sel(arena.allocateFrom("setWantsLayer:")), true);

        MemorySegment darkAppearance = createDarkAquaAppearance(objc, arena);
        if (!darkAppearance.equals(MemorySegment.NULL)) {
            objc.msgSendVoidObj.invokeExact(effectView, objc.sel(arena.allocateFrom("setAppearance:")), darkAppearance);
        }

        MemorySegment layer = objc.msgSendObj(effectView, arena.allocateFrom("layer"));
        if (!layer.equals(MemorySegment.NULL)) {
            objc.msgSendVoidDouble.invokeExact(layer, objc.sel(arena.allocateFrom("setCornerRadius:")), CORNER_RADIUS);
            objc.msgSendVoidBool.invokeExact(layer, objc.sel(arena.allocateFrom("setMasksToBounds:")), true);
        }

        objc.msgSendVoidObjLongObj.invokeExact(
                contentView,
                objc.sel(arena.allocateFrom("addSubview:positioned:relativeTo:")),
                effectView,
                NS_WINDOW_BELOW,
                MemorySegment.NULL);
    }

    private static MemorySegment createDarkAquaAppearance(ObjC objc, Arena arena) throws Throwable {
        MemorySegment nsStringClass = objc.getClass(arena.allocateFrom("NSString"));
        MemorySegment nameStr = (MemorySegment) objc.msgSendObjObj.invokeExact(
                nsStringClass,
                objc.sel(arena.allocateFrom("stringWithUTF8String:")),
                arena.allocateFrom("NSAppearanceNameDarkAqua"));
        if (nameStr.equals(MemorySegment.NULL)) {
            return MemorySegment.NULL;
        }
        MemorySegment appearanceClass = objc.getClass(arena.allocateFrom("NSAppearance"));
        return (MemorySegment) objc.msgSendObjObj.invokeExact(
                appearanceClass,
                objc.sel(arena.allocateFrom("appearanceNamed:")),
                nameStr);
    }

    private static void requestAwtForeground() {
        try {
            if (!Desktop.isDesktopSupported()) {
                return;
            }
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                desktop.requestForeground(true);
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Best-effort foreground activation.
        }
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private record ObjC(
            MethodHandle objcGetClass,
            MethodHandle objectGetClass,
            MethodHandle classReplaceMethod,
            MethodHandle selRegisterName,
            MethodHandle msgSendObj,
            MethodHandle msgSendInt,
            MethodHandle msgSendLong,
            MethodHandle msgSendObjLong,
            MethodHandle msgSendObjObj,
            MethodHandle msgSendBoolObj,
            MethodHandle msgSendVoid,
            MethodHandle msgSendVoidObj,
            MethodHandle msgSendVoidBool,
            MethodHandle msgSendVoidLong,
            MethodHandle msgSendVoidDouble,
            MethodHandle msgSendBoolLong,
            MethodHandle msgSendVoidObjLongObj,
            MethodHandle msgSendRect,
            MethodHandle msgSendObjRect,
            MethodHandle msgSendObj4Doubles) {

        static ObjC load() {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
            MemorySegment msgSend = lookup.find("objc_msgSend").orElseThrow();
            return new ObjC(
                    linker.downcallHandle(lookup.find("objc_getClass").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(lookup.find("object_getClass").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(lookup.find("class_replaceMethod").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(lookup.find("sel_registerName").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(NS_RECT_LAYOUT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, NS_RECT_LAYOUT)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE)));
        }

        MemorySegment getClass(MemorySegment name) throws Throwable {
            return (MemorySegment) objcGetClass.invokeExact(name);
        }

        MemorySegment sel(MemorySegment name) throws Throwable {
            return (MemorySegment) selRegisterName.invokeExact(name);
        }

        MemorySegment sharedApplication(Arena arena) throws Throwable {
            MemorySegment nsAppClass = getClass(arena.allocateFrom("NSApplication"));
            return msgSendObj(nsAppClass, arena.allocateFrom("sharedApplication"));
        }

        MemorySegment msgSendObj(MemorySegment target, MemorySegment selName) throws Throwable {
            return (MemorySegment) msgSendObj.invokeExact(target, sel(selName));
        }

        long msgSendLong(MemorySegment target, MemorySegment selName) throws Throwable {
            return (long) msgSendLong.invokeExact(target, sel(selName));
        }

        void msgSendVoidBool(MemorySegment target, MemorySegment selName, boolean arg) throws Throwable {
            msgSendVoidBool.invokeExact(target, sel(selName), arg);
        }

        void msgSendBoolLong(MemorySegment target, MemorySegment selName, long arg) throws Throwable {
            boolean ignored = (boolean) msgSendBoolLong.invokeExact(target, sel(selName), arg);
        }
    }
}
