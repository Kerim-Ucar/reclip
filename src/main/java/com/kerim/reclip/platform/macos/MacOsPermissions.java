package com.kerim.reclip.platform.macos;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * macOS permission checks and actionable user guidance for Accessibility and Input Monitoring.
 */
public final class MacOsPermissions {

    private static final String APP_SERVICES_FRAMEWORK =
            "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices";
    private static final String ACCESSIBILITY_SETTINGS_URL =
            "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility";
    private static final String INPUT_MONITORING_SETTINGS_URL =
            "x-apple.systempreferences:com.apple.preference.security?Privacy_ListenEvent";

    private MacOsPermissions() {
    }

    /**
     * Returns whether the current JVM is running on macOS.
     *
     * @return {@code true} on macOS
     */
    public static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * Returns whether the current process is trusted for macOS Accessibility input events.
     *
     * @return {@code true} on macOS when Accessibility permission is granted
     */
    public static boolean isAccessibilityTrusted() {
        if (!isMacOs()) {
            return false;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup appServices = SymbolLookup.libraryLookup(APP_SERVICES_FRAMEWORK, Arena.global());
            MethodHandle axIsProcessTrusted = linker.downcallHandle(
                    appServices.find("AXIsProcessTrusted").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN));
            return (boolean) axIsProcessTrusted.invokeExact();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Opens macOS System Settings directly to the Privacy &amp; Security Accessibility pane.
     *
     * @return {@code true} if the open command was launched
     */
    public static boolean openAccessibilitySettings() {
        return openSettingsUrl(ACCESSIBILITY_SETTINGS_URL);
    }

    /**
     * Opens macOS System Settings directly to the Privacy &amp; Security Input Monitoring pane.
     *
     * @return {@code true} if the open command was launched
     */
    public static boolean openInputMonitoringSettings() {
        return openSettingsUrl(INPUT_MONITORING_SETTINGS_URL);
    }

    private static boolean openSettingsUrl(String url) {
        if (!isMacOs()) {
            return false;
        }
        try {
            new ProcessBuilder("open", url).start();
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /**
     * Returns user-facing guidance for granting macOS Accessibility and Input Monitoring permissions.
     *
     * @return actionable guidance text
     */
    public static String accessibilityGuidance() {
        return "Add Reclip to both permissions in System Settings:\n"
                + "• System Settings → Privacy & Security → Accessibility\n"
                + "• System Settings → Privacy & Security → Input Monitoring\n"
                + "If you updated Reclip, remove the old entry (−), add it back (+), and restart Reclip.";
    }
}
