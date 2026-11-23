package com.kerim.reclip.platform.macos;

import com.kerim.reclip.app.ReclipApp;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Native macOS {@code NSStatusBar} menu-bar item using AppKit template images
 * so the icon background stays transparent and matches system menu-bar icons.
 */
public final class MacOsStatusBarTray implements AutoCloseable {

    private static final double NS_VARIABLE_STATUS_ITEM_LENGTH = -1.0;
    private static final double ICON_POINT_SIZE = 18.0;
    private static final MemoryLayout NS_SIZE_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE);

    private final Runnable onShowRequested;
    private final Runnable onSettingsRequested;
    private final Consumer<Boolean> onPauseToggled;
    private final Runnable onClearHistory;
    private final Runnable onQuitRequested;

    private Arena handlerArena;
    private MemorySegment statusItem = MemorySegment.NULL;
    private MemorySegment pauseMenuItem = MemorySegment.NULL;
    private boolean paused;

    public MacOsStatusBarTray(
            Runnable onShowRequested,
            Runnable onSettingsRequested,
            Consumer<Boolean> onPauseToggled,
            Runnable onClearHistory,
            Runnable onQuitRequested) {
        this.onShowRequested = Objects.requireNonNull(onShowRequested, "onShowRequested must not be null");
        this.onSettingsRequested = Objects.requireNonNull(onSettingsRequested, "onSettingsRequested must not be null");
        this.onPauseToggled = Objects.requireNonNull(onPauseToggled, "onPauseToggled must not be null");
        this.onClearHistory = Objects.requireNonNull(onClearHistory, "onClearHistory must not be null");
        this.onQuitRequested = Objects.requireNonNull(onQuitRequested, "onQuitRequested must not be null");
    }

    /**
     * Installs the native {@code NSStatusItem} in the macOS menu bar.
     * Must be called on the JavaFX Application Thread (macOS main thread).
     *
     * @return {@code true} if installed
     */
    public boolean install() {
        try {
            ObjC objc = ObjC.load();
            handlerArena = Arena.ofShared();

            MemorySegment statusBarClass = objc.getClass(handlerArena.allocateFrom("NSStatusBar"));
            MemorySegment statusBar = objc.msgSendObj(statusBarClass, handlerArena.allocateFrom("systemStatusBar"));
            if (statusBar.equals(MemorySegment.NULL)) {
                return false;
            }

            MemorySegment item = (MemorySegment) objc.msgSendObjDouble.invokeExact(
                    statusBar,
                    objc.sel(handlerArena.allocateFrom("statusItemWithLength:")),
                    NS_VARIABLE_STATUS_ITEM_LENGTH);
            if (item.equals(MemorySegment.NULL)) {
                return false;
            }
            objc.msgSendObj(item, handlerArena.allocateFrom("retain"));
            this.statusItem = item;

            MemorySegment button = objc.msgSendObj(item, handlerArena.allocateFrom("button"));
            if (!button.equals(MemorySegment.NULL)) {
                MemorySegment image = createTemplateNsImage(objc, handlerArena);
                if (!image.equals(MemorySegment.NULL)) {
                    objc.msgSendVoidObj.invokeExact(button, objc.sel(handlerArena.allocateFrom("setImage:")), image);
                }
                MemorySegment tooltip = nsString(objc, handlerArena, ReclipApp.APP_NAME);
                objc.msgSendVoidObj.invokeExact(button, objc.sel(handlerArena.allocateFrom("setToolTip:")), tooltip);
            }

            MemorySegment handler = createTargetHandler(objc, handlerArena);
            MemorySegment menu = buildMenu(objc, handlerArena, handler);
            objc.msgSendVoidObj.invokeExact(item, objc.sel(handlerArena.allocateFrom("setMenu:")), menu);
            return true;
        } catch (Throwable e) {
            close();
            return false;
        }
    }

    @Override
    public void close() {
        MemorySegment item = this.statusItem;
        this.statusItem = MemorySegment.NULL;
        this.pauseMenuItem = MemorySegment.NULL;

        if (!item.equals(MemorySegment.NULL)) {
            try (Arena arena = Arena.ofConfined()) {
                ObjC objc = ObjC.load();
                MemorySegment statusBarClass = objc.getClass(arena.allocateFrom("NSStatusBar"));
                MemorySegment statusBar = objc.msgSendObj(statusBarClass, arena.allocateFrom("systemStatusBar"));
                if (!statusBar.equals(MemorySegment.NULL)) {
                    objc.msgSendVoidObjBoolObj.invokeExact(
                            statusBar,
                            objc.sel(arena.allocateFrom("performSelectorOnMainThread:withObject:waitUntilDone:")),
                            objc.sel(arena.allocateFrom("removeStatusItem:")),
                            item,
                            false);
                }
            } catch (Throwable ignored) {
                // Best-effort cleanup.
            }
        }

        if (handlerArena != null) {
            handlerArena.close();
            handlerArena = null;
        }
    }

    private MemorySegment buildMenu(ObjC objc, Arena arena, MemorySegment target) throws Throwable {
        MemorySegment menuClass = objc.getClass(arena.allocateFrom("NSMenu"));
        MemorySegment menu = objc.msgSendObj(objc.msgSendObj(menuClass, arena.allocateFrom("alloc")),
                arena.allocateFrom("init"));

        addMenuItem(objc, arena, menu, "Show Clipboard", "onShow:", target);
        addMenuItem(objc, arena, menu, "Settings...", "onSettings:", target);
        addSeparator(objc, arena, menu);
        this.pauseMenuItem = addMenuItem(objc, arena, menu, "Pause Monitoring", "onTogglePause:", target);
        addMenuItem(objc, arena, menu, "Clear History", "onClear:", target);
        addSeparator(objc, arena, menu);
        addMenuItem(objc, arena, menu, "Quit " + ReclipApp.APP_NAME, "onQuit:", target);

        return menu;
    }

    private MemorySegment addMenuItem(
            ObjC objc,
            Arena arena,
            MemorySegment menu,
            String title,
            String actionName,
            MemorySegment target) throws Throwable {
        MemorySegment itemClass = objc.getClass(arena.allocateFrom("NSMenuItem"));
        MemorySegment alloc = objc.msgSendObj(itemClass, arena.allocateFrom("alloc"));
        MemorySegment item = (MemorySegment) objc.msgSendObjObjSelObj.invokeExact(
                alloc,
                objc.sel(arena.allocateFrom("initWithTitle:action:keyEquivalent:")),
                nsString(objc, arena, title),
                objc.sel(arena.allocateFrom(actionName)),
                nsString(objc, arena, ""));
        objc.msgSendVoidObj.invokeExact(item, objc.sel(arena.allocateFrom("setTarget:")), target);
        objc.msgSendVoidObj.invokeExact(menu, objc.sel(arena.allocateFrom("addItem:")), item);
        return item;
    }

    private static void addSeparator(ObjC objc, Arena arena, MemorySegment menu) throws Throwable {
        MemorySegment itemClass = objc.getClass(arena.allocateFrom("NSMenuItem"));
        MemorySegment sep = objc.msgSendObj(itemClass, arena.allocateFrom("separatorItem"));
        objc.msgSendVoidObj.invokeExact(menu, objc.sel(arena.allocateFrom("addItem:")), sep);
    }

    private MemorySegment createTargetHandler(ObjC objc, Arena arena) throws Throwable {
        String className = "ReclipStatusMenuHandler_" + System.identityHashCode(this);
        MemorySegment nsObject = objc.getClass(arena.allocateFrom("NSObject"));
        MemorySegment cls = (MemorySegment) objc.objcAllocateClassPair.invokeExact(
                nsObject, arena.allocateFrom(className), 0L);

        FunctionDescriptor callbackDesc = FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Linker linker = Linker.nativeLinker();
        MemorySegment typeEncoding = arena.allocateFrom("v@:@");

        registerAction(objc, arena, cls, "onShow:", lookup, linker, callbackDesc, typeEncoding, "handleShow");
        registerAction(objc, arena, cls, "onSettings:", lookup, linker, callbackDesc, typeEncoding, "handleSettings");
        registerAction(objc, arena, cls, "onTogglePause:", lookup, linker, callbackDesc, typeEncoding, "handleTogglePause");
        registerAction(objc, arena, cls, "onClear:", lookup, linker, callbackDesc, typeEncoding, "handleClear");
        registerAction(objc, arena, cls, "onQuit:", lookup, linker, callbackDesc, typeEncoding, "handleQuit");

        objc.objcRegisterClassPair.invokeExact(cls);
        return objc.msgSendObj(objc.msgSendObj(cls, arena.allocateFrom("alloc")), arena.allocateFrom("init"));
    }

    private void registerAction(
            ObjC objc,
            Arena arena,
            MemorySegment cls,
            String selectorName,
            MethodHandles.Lookup lookup,
            Linker linker,
            FunctionDescriptor desc,
            MemorySegment typeEncoding,
            String javaMethodName) throws Throwable {
        MethodHandle target = lookup.findVirtual(
                MacOsStatusBarTray.class,
                javaMethodName,
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
                .bindTo(this);
        MemorySegment stub = linker.upcallStub(target, desc, arena);
        boolean ignored = (boolean) objc.classAddMethod.invokeExact(
                cls, objc.sel(arena.allocateFrom(selectorName)), stub, typeEncoding);
    }

    @SuppressWarnings("unused")
    private void handleShow(MemorySegment self, MemorySegment sel, MemorySegment sender) {
        onShowRequested.run();
    }

    @SuppressWarnings("unused")
    private void handleSettings(MemorySegment self, MemorySegment sel, MemorySegment sender) {
        onSettingsRequested.run();
    }

    @SuppressWarnings("unused")
    private void handleTogglePause(MemorySegment self, MemorySegment sel, MemorySegment sender) {
        paused = !paused;
        onPauseToggled.accept(paused);
        if (!pauseMenuItem.equals(MemorySegment.NULL)) {
            try (Arena arena = Arena.ofConfined()) {
                ObjC objc = ObjC.load();
                objc.msgSendVoidLong.invokeExact(
                        pauseMenuItem,
                        objc.sel(arena.allocateFrom("setState:")),
                        paused ? 1L : 0L);
            } catch (Throwable ignored) {
                // Best-effort checkmark update.
            }
        }
    }

    @SuppressWarnings("unused")
    private void handleClear(MemorySegment self, MemorySegment sel, MemorySegment sender) {
        onClearHistory.run();
    }

    @SuppressWarnings("unused")
    private void handleQuit(MemorySegment self, MemorySegment sel, MemorySegment sender) {
        onQuitRequested.run();
    }

    private static MemorySegment createTemplateNsImage(ObjC objc, Arena arena) throws Throwable {
        byte[] pngBytes = renderIconPng();
        MemorySegment nativeBytes = arena.allocateFrom(ValueLayout.JAVA_BYTE, pngBytes);
        MemorySegment nsDataClass = objc.getClass(arena.allocateFrom("NSData"));
        MemorySegment nsData = (MemorySegment) objc.msgSendObjObjLong.invokeExact(
                nsDataClass,
                objc.sel(arena.allocateFrom("dataWithBytes:length:")),
                nativeBytes,
                (long) pngBytes.length);
        if (nsData.equals(MemorySegment.NULL)) {
            return MemorySegment.NULL;
        }

        MemorySegment nsImageClass = objc.getClass(arena.allocateFrom("NSImage"));
        MemorySegment alloc = objc.msgSendObj(nsImageClass, arena.allocateFrom("alloc"));
        MemorySegment image = (MemorySegment) objc.msgSendObjObj.invokeExact(
                alloc,
                objc.sel(arena.allocateFrom("initWithData:")),
                nsData);
        if (image.equals(MemorySegment.NULL)) {
            return MemorySegment.NULL;
        }

        MemorySegment sizeStruct = arena.allocate(NS_SIZE_LAYOUT);
        sizeStruct.set(ValueLayout.JAVA_DOUBLE, 0, ICON_POINT_SIZE);
        sizeStruct.set(ValueLayout.JAVA_DOUBLE, 8, ICON_POINT_SIZE);
        objc.msgSendVoidSize.invokeExact(image, objc.sel(arena.allocateFrom("setSize:")), sizeStruct);
        objc.msgSendVoidBool.invokeExact(image, objc.sel(arena.allocateFrom("setTemplate:")), false);
        return image;
    }

    private static byte[] renderIconPng() throws IOException {
        try (InputStream stream = MacOsStatusBarTray.class.getResourceAsStream("/icons/tray-icon.png")) {
            if (stream != null) {
                return stream.readAllBytes();
            }
        }
        int size = 36;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, size, size);
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawRoundRect(7, 8, 22, 23, 5, 5);
            g.fillRoundRect(12, 4, 12, 6, 3, 3);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static MemorySegment nsString(ObjC objc, Arena arena, String value) throws Throwable {
        MemorySegment nsStringClass = objc.getClass(arena.allocateFrom("NSString"));
        return (MemorySegment) objc.msgSendObjObj.invokeExact(
                nsStringClass,
                objc.sel(arena.allocateFrom("stringWithUTF8String:")),
                arena.allocateFrom(value));
    }

    private record ObjC(
            MethodHandle objcGetClass,
            MethodHandle selRegisterName,
            MethodHandle objcAllocateClassPair,
            MethodHandle objcRegisterClassPair,
            MethodHandle classAddMethod,
            MethodHandle msgSendObj,
            MethodHandle msgSendObjDouble,
            MethodHandle msgSendObjObj,
            MethodHandle msgSendObjObjLong,
            MethodHandle msgSendObjObjSelObj,
            MethodHandle msgSendVoidObj,
            MethodHandle msgSendVoidBool,
            MethodHandle msgSendVoidLong,
            MethodHandle msgSendVoidSize,
            MethodHandle msgSendVoidObjBoolObj) {

        static ObjC load() {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
            MemorySegment msgSend = lookup.find("objc_msgSend").orElseThrow();
            return new ObjC(
                    linker.downcallHandle(lookup.find("objc_getClass").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(lookup.find("sel_registerName").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(lookup.find("objc_allocateClassPair").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
                    linker.downcallHandle(lookup.find("objc_registerClassPair").orElseThrow(),
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),
                    linker.downcallHandle(lookup.find("class_addMethod").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, NS_SIZE_LAYOUT)),
                    linker.downcallHandle(msgSend,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)));
        }

        MemorySegment getClass(MemorySegment name) throws Throwable {
            return (MemorySegment) objcGetClass.invokeExact(name);
        }

        MemorySegment sel(MemorySegment name) throws Throwable {
            return (MemorySegment) selRegisterName.invokeExact(name);
        }

        MemorySegment msgSendObj(MemorySegment target, MemorySegment selName) throws Throwable {
            return (MemorySegment) msgSendObj.invokeExact(target, sel(selName));
        }
    }
}
