package com.kerim.reclip.platform;

import java.awt.Desktop;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * Configures OS-level window Space behavior, native macOS vibrancy blur, and
 * foreground focus when triggered from a global shortcut.
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

    private WindowFocusHelper() {
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
     * Marks all app windows to join all Spaces, applies native macOS frosted-glass
     * vibrancy, and brings the app to the foreground on the current Space.
     */
    public static void requestAppForeground() {
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
        long count = objc.msgSendLong(windows, arena.allocateFrom("count"));
        MemorySegment objectAtIndexSel = objc.sel(arena.allocateFrom("objectAtIndex:"));
        MemorySegment setBehaviorSel = objc.sel(arena.allocateFrom("setCollectionBehavior:"));
        MemorySegment setHasShadowSel = objc.sel(arena.allocateFrom("setHasShadow:"));

        for (long i = 0; i < count; i++) {
            MemorySegment window = (MemorySegment) objc.msgSendObjLong.invokeExact(windows, objectAtIndexSel, i);
            if (!window.equals(MemorySegment.NULL)) {
                objc.msgSendVoidLong.invokeExact(window, setBehaviorSel, ALL_SPACES_BEHAVIOR);
                objc.msgSendVoidBool.invokeExact(window, setHasShadowSel, true);
                ensureVibrancyBackdrop(objc, window, arena);
            }
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
            MethodHandle selRegisterName,
            MethodHandle msgSendObj,
            MethodHandle msgSendLong,
            MethodHandle msgSendObjLong,
            MethodHandle msgSendObjObj,
            MethodHandle msgSendBoolObj,
            MethodHandle msgSendVoidObj,
            MethodHandle msgSendVoidBool,
            MethodHandle msgSendVoidLong,
            MethodHandle msgSendVoidDouble,
            MethodHandle msgSendBoolLong,
            MethodHandle msgSendVoidObjLongObj,
            MethodHandle msgSendRect,
            MethodHandle msgSendObjRect) {

        static ObjC load() {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
            MemorySegment msgSend = lookup.find("objc_msgSend").orElseThrow();
            return new ObjC(
                    linker.downcallHandle(lookup.find("objc_getClass").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(lookup.find("sel_registerName").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
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
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, NS_RECT_LAYOUT)));
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
